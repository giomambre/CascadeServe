import json
from concurrent import futures

import grpc
import pytest

from cascadeserve.v1 import inference_pb2, inference_pb2_grpc
from cascadeserve_worker.evaluation import (
    EvaluationExample,
    EvaluationResult,
    build_training_records,
    comparison_summary,
    evaluate_target,
    load_dataset,
    load_evaluation_results,
    replay_hybrid_policy,
    score_output,
    summarize_results,
)
from cascadeserve_worker.generator import EchoGenerator
from cascadeserve_worker.service import WorkerService


def test_quality_metrics():
    assert score_output(" Paris\n", "paris", "exact_match")
    assert score_output("The answer is blue.", "answer is blue", "contains")
    assert score_output("First 10, finally 1,024.5", "1024.5", "last_number")
    assert not score_output("The result is 7", "8", "last_number")


def test_dataset_validation(tmp_path):
    dataset = tmp_path / "dataset.jsonl"
    dataset.write_text(
        json.dumps(
            {
                "id": "math-1",
                "prompt": "What is 2 + 2?",
                "reference": 4,
                "metric": "last_number",
            }
        )
        + "\n",
        encoding="utf-8",
    )

    examples = load_dataset(dataset)

    assert examples == [
        EvaluationExample("math-1", "What is 2 + 2?", "4", "last_number")
    ]


def test_dataset_rejects_duplicate_ids(tmp_path):
    dataset = tmp_path / "dataset.jsonl"
    row = json.dumps({"id": "same", "prompt": "hello", "reference": "world"})
    dataset.write_text(f"{row}\n{row}\n", encoding="utf-8")

    with pytest.raises(ValueError, match="ids must be unique"):
        load_dataset(dataset)


def result(example_id, status, correct, latency_ms):
    return EvaluationResult(
        example_id,
        status,
        "output",
        correct,
        latency_ms,
        1,
        "worker",
        "model",
    )


def test_summary_counts_failures_as_incorrect():
    summary = summarize_results(
        [result("1", "OK", True, 5.0), result("2", "DEADLINE_EXCEEDED", False, 8.0)]
    )

    assert summary["accuracy"] == 0.5
    assert summary["accuracy_on_success"] == 1.0
    assert summary["failed"] == 1


def test_training_records_exclude_rpc_failures():
    examples = [
        EvaluationExample("1", "easy", "yes", "exact_match"),
        EvaluationExample("2", "hard", "42", "last_number"),
    ]
    small = [result("1", "OK", True, 1.0), result("2", "UNAVAILABLE", False, 1.0)]
    large = [result("1", "OK", True, 2.0), result("2", "OK", True, 2.0)]

    records = build_training_records(examples, small, large)

    assert records == [
        {
            "id": "1",
            "prompt": "easy",
            "small_correct": True,
            "large_correct": True,
        }
    ]


def test_comparison_reports_paired_oracle_accuracy():
    examples = [
        EvaluationExample(str(index), f"prompt {index}", "x", "exact_match")
        for index in range(4)
    ]
    small = [
        result("0", "OK", True, 1.0),
        result("1", "OK", True, 1.0),
        result("2", "OK", False, 1.0),
        result("3", "OK", False, 1.0),
    ]
    large = [
        result("0", "OK", True, 2.0),
        result("1", "OK", False, 2.0),
        result("2", "OK", True, 2.0),
        result("3", "OK", False, 2.0),
    ]

    summary, _ = comparison_summary(examples, small, large)

    assert summary["paired_outcomes"] == {
        "both_correct": 1,
        "small_only_correct": 1,
        "large_only_correct": 1,
        "neither_correct": 1,
        "oracle_correct": 3,
        "oracle_accuracy": 0.75,
    }


def test_evaluate_target_uses_worker_rpc():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=1))
    inference_pb2_grpc.add_InferenceWorkerServicer_to_server(
        WorkerService("evaluation-worker", EchoGenerator()), server
    )
    port = server.add_insecure_port("localhost:0")
    server.start()
    try:
        results = evaluate_target(
            f"localhost:{port}",
            [EvaluationExample("1", "hello world", "hello world", "exact_match")],
            max_new_tokens=8,
            timeout_seconds=1.0,
            warmup_requests=1,
        )
    finally:
        server.stop(grace=None).wait()

    assert len(results) == 1
    assert results[0].status == "OK"
    assert results[0].correct
    assert results[0].worker_id == "evaluation-worker"
    assert results[0].model_id == "echo-v1"


def test_evaluate_target_uses_control_rpc_and_forwards_region():
    requests = []

    class ControlService(inference_pb2_grpc.CascadeServiceServicer):
        def Generate(self, request, context):
            requests.append(request)
            return inference_pb2.GenerateResponse(
                request_id=request.request_id,
                output="4",
                worker_id="remote-gemma",
                model_id="gemma-4-31b-it",
                input_tokens=11,
                output_tokens=7,
            )

    server = grpc.server(futures.ThreadPoolExecutor(max_workers=1))
    inference_pb2_grpc.add_CascadeServiceServicer_to_server(ControlService(), server)
    port = server.add_insecure_port("localhost:0")
    server.start()
    try:
        results = evaluate_target(
            f"localhost:{port}",
            [EvaluationExample("1", "2 + 2", "4", "last_number")],
            max_new_tokens=8,
            timeout_seconds=1.0,
            warmup_requests=0,
            service="control",
            preferred_region="global",
        )
    finally:
        server.stop(grace=None).wait()

    assert results[0].correct
    assert requests[0].preferred_region == "global"
    assert results[0].input_tokens == 11
    assert results[0].output_tokens == 7


def test_load_results_aligns_records_to_dataset_order(tmp_path):
    examples = [
        EvaluationExample("first", "one", "one", "exact_match"),
        EvaluationExample("second", "two", "two", "exact_match"),
    ]
    output = tmp_path / "tier.jsonl"
    records = [
        result("second", "OK", True, 2.0),
        result("first", "OK", True, 1.0),
    ]
    output.write_text(
        "".join(json.dumps(record.__dict__) + "\n" for record in records),
        encoding="utf-8",
    )

    loaded = load_evaluation_results(output, examples)

    assert [item.example_id for item in loaded] == ["first", "second"]


def test_load_results_rejects_incomplete_tier(tmp_path):
    examples = [
        EvaluationExample("first", "one", "one", "exact_match"),
        EvaluationExample("second", "two", "two", "exact_match"),
    ]
    output = tmp_path / "tier.jsonl"
    output.write_text(
        json.dumps(result("first", "OK", True, 1.0).__dict__) + "\n",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="do not match dataset"):
        load_evaluation_results(output, examples)


def test_hybrid_replay_routes_latency_and_quality_requests():
    examples = [
        EvaluationExample(str(index), f"prompt {index}", "x", "exact_match")
        for index in range(20)
    ]
    local = [result(str(index), "OK", index % 2 == 0, 100.0) for index in range(20)]
    remote = [result(str(index), "OK", True, 1000.0) for index in range(20)]

    summary, decisions = replay_hybrid_policy(
        examples,
        local,
        remote,
        latency_budget_ms=400,
        remote_expected_latency_ms=1000,
        latency_request_share=0.5,
    )

    assert summary["route_counts"]["local"] > 0
    assert summary["route_counts"]["remote"] > 0
    assert len(decisions) == 20
