from __future__ import annotations

import json
import hashlib
import re
from collections import Counter
from dataclasses import asdict, dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from time import perf_counter_ns

import grpc

from cascadeserve.v1 import inference_pb2, inference_pb2_grpc
from cascadeserve_worker.benchmark import nearest_rank


SUPPORTED_METRICS = {"exact_match", "contains", "last_number"}
NUMBER_PATTERN = re.compile(r"[-+]?(?:\d[\d,]*\.?\d*|\.\d+)")


@dataclass(frozen=True)
class EvaluationExample:
    id: str
    prompt: str
    reference: str
    metric: str


@dataclass(frozen=True)
class EvaluationResult:
    example_id: str
    status: str
    output: str
    correct: bool
    client_latency_ms: float
    worker_latency_ms: int
    worker_id: str
    model_id: str
    input_tokens: int = 0
    output_tokens: int = 0


def load_dataset(path: Path) -> list[EvaluationExample]:
    examples = []
    with path.open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            record = json.loads(line)
            try:
                example_id = str(record["id"])
                prompt = record["prompt"]
                reference = str(record["reference"])
            except KeyError as error:
                raise ValueError(
                    f"line {line_number} is missing {error.args[0]}"
                ) from error
            metric = record.get("metric", "exact_match")
            if not isinstance(prompt, str) or not prompt.strip():
                raise ValueError(f"line {line_number} prompt must not be blank")
            if not reference.strip():
                raise ValueError(f"line {line_number} reference must not be blank")
            if metric not in SUPPORTED_METRICS:
                raise ValueError(f"line {line_number} has unsupported metric {metric}")
            examples.append(EvaluationExample(example_id, prompt, reference, metric))
    if not examples:
        raise ValueError("dataset must contain at least one example")
    if len({example.id for example in examples}) != len(examples):
        raise ValueError("dataset ids must be unique")
    return examples


def normalize(text: str) -> str:
    return " ".join(text.casefold().strip().split())


def score_output(output: str, reference: str, metric: str) -> bool:
    if metric == "exact_match":
        return normalize(output) == normalize(reference)
    if metric == "contains":
        return normalize(reference) in normalize(output)
    if metric == "last_number":
        output_numbers = NUMBER_PATTERN.findall(output)
        reference_numbers = NUMBER_PATTERN.findall(reference)
        if not output_numbers or not reference_numbers:
            return False
        try:
            output_value = Decimal(output_numbers[-1].replace(",", ""))
            reference_value = Decimal(reference_numbers[-1].replace(",", ""))
        except InvalidOperation:
            return False
        return output_value == reference_value
    raise ValueError(f"unsupported metric {metric}")


def evaluate_target(
    target: str,
    examples: list[EvaluationExample],
    max_new_tokens: int,
    timeout_seconds: float,
    warmup_requests: int,
    service: str = "worker",
    preferred_region: str = "",
) -> list[EvaluationResult]:
    if service not in {"worker", "control"}:
        raise ValueError("service must be worker or control")
    with grpc.insecure_channel(target) as channel:
        stub_type = (
            inference_pb2_grpc.InferenceWorkerStub
            if service == "worker"
            else inference_pb2_grpc.CascadeServiceStub
        )
        stub = stub_type(channel)
        for index in range(warmup_requests):
            try:
                stub.Generate(
                    inference_pb2.GenerateRequest(
                        request_id=f"warmup-{index}",
                        prompt=examples[index % len(examples)].prompt,
                        max_new_tokens=max_new_tokens,
                        preferred_region=preferred_region,
                    ),
                    timeout=timeout_seconds,
                )
            except grpc.RpcError:
                pass

        results = []
        for index, example in enumerate(examples):
            started_at = perf_counter_ns()
            try:
                response = stub.Generate(
                    inference_pb2.GenerateRequest(
                        request_id=f"evaluation-{index}",
                        prompt=example.prompt,
                        max_new_tokens=max_new_tokens,
                        preferred_region=preferred_region,
                    ),
                    timeout=timeout_seconds,
                )
                status = "OK"
                output = response.output
                correct = score_output(output, example.reference, example.metric)
                worker_latency_ms = response.worker_latency_ms
                worker_id = response.worker_id
                model_id = response.model_id
                input_tokens = response.input_tokens
                output_tokens = response.output_tokens
            except grpc.RpcError as error:
                status = error.code().name
                output = ""
                correct = False
                worker_latency_ms = 0
                worker_id = ""
                model_id = ""
                input_tokens = 0
                output_tokens = 0
            client_latency_ms = (perf_counter_ns() - started_at) / 1_000_000
            results.append(
                EvaluationResult(
                    example.id,
                    status,
                    output,
                    correct,
                    client_latency_ms,
                    worker_latency_ms,
                    worker_id,
                    model_id,
                    input_tokens,
                    output_tokens,
                )
            )
    return results


def summarize_results(results: list[EvaluationResult]) -> dict:
    successful = [result for result in results if result.status == "OK"]
    correct = sum(result.correct for result in results)
    successful_correct = sum(result.correct for result in successful)
    latencies = [result.client_latency_ms for result in successful]
    return {
        "examples": len(results),
        "successful": len(successful),
        "failed": len(results) - len(successful),
        "correct": correct,
        "accuracy": round(correct / len(results), 6) if results else 0.0,
        "accuracy_on_success": round(successful_correct / len(successful), 6)
        if successful
        else 0.0,
        "client_latency_ms": {
            "p50": round(nearest_rank(latencies, 50), 3),
            "p95": round(nearest_rank(latencies, 95), 3),
            "p99": round(nearest_rank(latencies, 99), 3),
        },
        "models": sorted({result.model_id for result in successful}),
        "tokens": {
            "input": sum(result.input_tokens for result in successful),
            "output": sum(result.output_tokens for result in successful),
        },
    }


def load_evaluation_results(
    path: Path, examples: list[EvaluationExample]
) -> list[EvaluationResult]:
    results = []
    with path.open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            try:
                results.append(EvaluationResult(**json.loads(line)))
            except (TypeError, json.JSONDecodeError) as error:
                raise ValueError(f"invalid result on line {line_number}") from error

    by_id = {result.example_id: result for result in results}
    if len(by_id) != len(results):
        raise ValueError("evaluation result ids must be unique")
    expected_ids = {example.id for example in examples}
    if set(by_id) != expected_ids:
        missing = sorted(expected_ids - set(by_id))
        unexpected = sorted(set(by_id) - expected_ids)
        raise ValueError(
            f"evaluation results do not match dataset; missing={missing}, unexpected={unexpected}"
        )
    return [by_id[example.id] for example in examples]


def serializable_evaluation_results(
    results: list[EvaluationResult],
) -> list[dict]:
    return [asdict(result) for result in results]


def replay_hybrid_policy(
    examples: list[EvaluationExample],
    local_results: list[EvaluationResult],
    remote_results: list[EvaluationResult],
    latency_budget_ms: int,
    remote_expected_latency_ms: int,
    latency_request_share: float,
) -> tuple[dict, list[dict]]:
    if latency_budget_ms <= 0 or remote_expected_latency_ms <= 0:
        raise ValueError("latency values must be positive")
    if not 0.0 <= latency_request_share <= 1.0:
        raise ValueError("latency request share must be between zero and one")
    if not (len(examples) == len(local_results) == len(remote_results)):
        raise ValueError("examples and result lists must have the same length")

    decisions = []
    for example, local, remote in zip(
        examples, local_results, remote_results, strict=True
    ):
        sample = int.from_bytes(
            hashlib.sha256(example.id.encode("utf-8")).digest()[:8], "big"
        ) / 2**64
        latency_sensitive = sample < latency_request_share
        use_local = latency_sensitive and remote_expected_latency_ms > latency_budget_ms
        selected = local if use_local else remote
        decisions.append(
            {
                "example_id": example.id,
                "request_class": "latency" if latency_sensitive else "quality",
                "selected_endpoint": "local" if use_local else "remote",
                "model_id": selected.model_id,
                "status": selected.status,
                "correct": selected.correct,
                "client_latency_ms": selected.client_latency_ms,
            }
        )

    successful = [decision for decision in decisions if decision["status"] == "OK"]
    latencies = [decision["client_latency_ms"] for decision in successful]
    summary = {
        "examples": len(decisions),
        "successful": len(successful),
        "failed": len(decisions) - len(successful),
        "correct": sum(decision["correct"] for decision in decisions),
        "accuracy": round(
            sum(decision["correct"] for decision in decisions) / len(decisions), 6
        )
        if decisions
        else 0.0,
        "route_counts": dict(
            Counter(decision["selected_endpoint"] for decision in decisions)
        ),
        "request_class_counts": dict(
            Counter(decision["request_class"] for decision in decisions)
        ),
        "client_latency_ms": {
            "p50": round(nearest_rank(latencies, 50), 3),
            "p95": round(nearest_rank(latencies, 95), 3),
            "p99": round(nearest_rank(latencies, 99), 3),
        },
    }
    return summary, decisions


def build_training_records(
    examples: list[EvaluationExample],
    small_results: list[EvaluationResult],
    large_results: list[EvaluationResult],
) -> list[dict]:
    records = []
    for example, small, large in zip(
        examples, small_results, large_results, strict=True
    ):
        if small.status != "OK" or large.status != "OK":
            continue
        records.append(
            {
                "id": example.id,
                "prompt": example.prompt,
                "small_correct": small.correct,
                "large_correct": large.correct,
            }
        )
    return records


def paired_results(
    examples: list[EvaluationExample],
    small_results: list[EvaluationResult],
    large_results: list[EvaluationResult],
) -> list[dict]:
    return [
        {
            "example": asdict(example),
            "small": asdict(small),
            "large": asdict(large),
        }
        for example, small, large in zip(
            examples, small_results, large_results, strict=True
        )
    ]


def comparison_summary(
    examples: list[EvaluationExample],
    small_results: list[EvaluationResult],
    large_results: list[EvaluationResult],
) -> tuple[dict, list[dict]]:
    training_records = build_training_records(examples, small_results, large_results)
    escalation_samples = sum(
        record["large_correct"] and not record["small_correct"]
        for record in training_records
    )
    both_correct = sum(
        record["small_correct"] and record["large_correct"]
        for record in training_records
    )
    small_only = sum(
        record["small_correct"] and not record["large_correct"]
        for record in training_records
    )
    neither_correct = sum(
        not record["small_correct"] and not record["large_correct"]
        for record in training_records
    )
    oracle_correct = both_correct + small_only + escalation_samples
    summary = {
        "small": summarize_results(small_results),
        "large": summarize_results(large_results),
        "paired_outcomes": {
            "both_correct": both_correct,
            "small_only_correct": small_only,
            "large_only_correct": escalation_samples,
            "neither_correct": neither_correct,
            "oracle_correct": oracle_correct,
            "oracle_accuracy": round(oracle_correct / len(training_records), 6)
            if training_records
            else 0.0,
        },
        "router_training": {
            "eligible_examples": len(training_records),
            "requires_large_examples": escalation_samples,
            "oracle_escalation_rate": round(
                escalation_samples / len(training_records), 6
            )
            if training_records
            else 0.0,
        },
    }
    return summary, training_records
