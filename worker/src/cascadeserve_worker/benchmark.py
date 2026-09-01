from __future__ import annotations

from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass
from math import ceil
from statistics import mean, median, stdev
from time import perf_counter_ns

import grpc

from cascadeserve.v1 import inference_pb2, inference_pb2_grpc


@dataclass(frozen=True)
class RequestResult:
    latency_ms: float
    status: str
    worker_id: str = ""
    attempts: int = 0
    selected_tier: str = ""
    routing_policy: str = ""
    routing_score: float = 0.0
    input_tokens: int = 0
    output_tokens: int = 0


def nearest_rank(values: list[float], percentile: int) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, ceil(percentile / 100 * len(ordered)) - 1)
    return ordered[index]


def summarize(results: list[RequestResult], duration_seconds: float) -> dict:
    successful = [result for result in results if result.status == "OK"]
    latencies = [result.latency_ms for result in successful]
    return {
        "requests": len(results),
        "successful": len(successful),
        "failed": len(results) - len(successful),
        "throughput_requests_per_second": round(len(successful) / duration_seconds, 2)
        if duration_seconds > 0
        else 0.0,
        "latency_ms": {
            "p50": round(nearest_rank(latencies, 50), 3),
            "p95": round(nearest_rank(latencies, 95), 3),
            "p99": round(nearest_rank(latencies, 99), 3),
        },
        "status_counts": dict(Counter(result.status for result in results)),
        "worker_counts": dict(Counter(result.worker_id for result in successful)),
        "attempt_counts": {
            str(attempts): count
            for attempts, count in sorted(Counter(result.attempts for result in successful).items())
        },
        "tier_counts": dict(Counter(result.selected_tier for result in successful)),
        "routing_policy_counts": dict(
            Counter(result.routing_policy for result in successful)
        ),
        "mean_routing_score": round(
            sum(result.routing_score for result in successful) / len(successful), 6
        )
        if successful
        else 0.0,
        "token_usage": {
            "input": sum(result.input_tokens for result in successful),
            "output": sum(result.output_tokens for result in successful),
        },
    }


def run_load(
    target: str,
    requests: int,
    concurrency: int,
    prompt: str,
    max_new_tokens: int,
    timeout_seconds: float,
    latency_budget_ms: int = 0,
    max_cost_usd: float = 0.0,
    require_local: bool = False,
    preferred_region: str = "",
) -> tuple[dict, list[RequestResult]]:
    if requests <= 0 or concurrency <= 0:
        raise ValueError("requests and concurrency must be positive")

    with grpc.insecure_channel(target) as channel:
        stub = inference_pb2_grpc.CascadeServiceStub(channel)
        return run_load_on_stub(
            stub,
            requests,
            concurrency,
            prompt,
            max_new_tokens,
            timeout_seconds,
            "load",
            latency_budget_ms,
            max_cost_usd,
            require_local,
            preferred_region,
        )


def run_repeated_load(
    target: str,
    requests: int,
    concurrency: int,
    prompt: str,
    max_new_tokens: int,
    timeout_seconds: float,
    trials: int,
    warmup_requests: int,
    latency_budget_ms: int = 0,
    max_cost_usd: float = 0.0,
    require_local: bool = False,
    preferred_region: str = "",
) -> list[tuple[dict, list[RequestResult]]]:
    if trials <= 0 or warmup_requests < 0:
        raise ValueError("trials must be positive and warmup requests must not be negative")
    with grpc.insecure_channel(target) as channel:
        stub = inference_pb2_grpc.CascadeServiceStub(channel)
        if warmup_requests:
            run_load_on_stub(
                stub,
                warmup_requests,
                min(concurrency, warmup_requests),
                prompt,
                max_new_tokens,
                timeout_seconds,
                "warmup",
                latency_budget_ms,
                max_cost_usd,
                require_local,
                preferred_region,
            )
        return [
            run_load_on_stub(
                stub,
                requests,
                concurrency,
                prompt,
                max_new_tokens,
                timeout_seconds,
                f"trial-{trial}",
                latency_budget_ms,
                max_cost_usd,
                require_local,
                preferred_region,
            )
            for trial in range(1, trials + 1)
        ]


def run_load_on_stub(
    stub,
    requests: int,
    concurrency: int,
    prompt: str,
    max_new_tokens: int,
    timeout_seconds: float,
    request_prefix: str,
    latency_budget_ms: int = 0,
    max_cost_usd: float = 0.0,
    require_local: bool = False,
    preferred_region: str = "",
) -> tuple[dict, list[RequestResult]]:
    if requests <= 0 or concurrency <= 0:
        raise ValueError("requests and concurrency must be positive")

    def send(index: int) -> RequestResult:
        started_at = perf_counter_ns()
        try:
            response = stub.Generate(
                inference_pb2.GenerateRequest(
                    request_id=f"{request_prefix}-{index}",
                    prompt=prompt,
                    max_new_tokens=max_new_tokens,
                    latency_budget_ms=latency_budget_ms,
                    max_cost_usd=max_cost_usd,
                    require_local=require_local,
                    preferred_region=preferred_region,
                ),
                timeout=timeout_seconds,
            )
            status = "OK"
            worker_id = response.worker_id
            attempts = response.attempts
            selected_tier = response.selected_tier
            routing_policy = response.routing_policy
            routing_score = response.routing_score
            input_tokens = response.input_tokens
            output_tokens = response.output_tokens
        except grpc.RpcError as error:
            status = error.code().name
            worker_id = ""
            attempts = 0
            selected_tier = ""
            routing_policy = ""
            routing_score = 0.0
            input_tokens = 0
            output_tokens = 0
        latency_ms = (perf_counter_ns() - started_at) / 1_000_000
        return RequestResult(
            latency_ms,
            status,
            worker_id,
            attempts,
            selected_tier,
            routing_policy,
            routing_score,
            input_tokens,
            output_tokens,
        )

    started_at = perf_counter_ns()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        results = list(executor.map(send, range(requests)))
    duration_seconds = (perf_counter_ns() - started_at) / 1_000_000_000

    report = summarize(results, duration_seconds)
    report["duration_seconds"] = round(duration_seconds, 3)
    return report, results


def summarize_trials(summaries: list[dict]) -> dict:
    if not summaries:
        raise ValueError("at least one trial summary is required")

    def distribution(values: list[float]) -> dict:
        return {
            "mean": round(mean(values), 3),
            "median": round(median(values), 3),
            "stdev": round(stdev(values), 3) if len(values) > 1 else 0.0,
            "min": round(min(values), 3),
            "max": round(max(values), 3),
        }

    return {
        "trials": len(summaries),
        "successful_requests": sum(summary["successful"] for summary in summaries),
        "failed_requests": sum(summary["failed"] for summary in summaries),
        "throughput_requests_per_second": distribution(
            [summary["throughput_requests_per_second"] for summary in summaries]
        ),
        "latency_ms": {
            percentile: distribution(
                [summary["latency_ms"][percentile] for summary in summaries]
            )
            for percentile in ("p50", "p95", "p99")
        },
    }


def serializable_results(results: list[RequestResult]) -> list[dict]:
    return [asdict(result) for result in results]
