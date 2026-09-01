from cascadeserve_worker.benchmark import (
    RequestResult,
    nearest_rank,
    run_load_on_stub,
    summarize,
    summarize_trials,
)
from cascadeserve.v1 import inference_pb2


def test_nearest_rank_percentiles():
    values = [1.0, 2.0, 3.0, 4.0, 100.0]

    assert nearest_rank(values, 50) == 3.0
    assert nearest_rank(values, 95) == 100.0


def test_summary_separates_failures_and_workers():
    results = [
        RequestResult(2.0, "OK", "worker-1", 1),
        RequestResult(4.0, "OK", "worker-2", 2),
        RequestResult(6.0, "UNAVAILABLE"),
    ]

    summary = summarize(results, duration_seconds=0.5)

    assert summary["successful"] == 2
    assert summary["failed"] == 1
    assert summary["throughput_requests_per_second"] == 4.0
    assert summary["worker_counts"] == {"worker-1": 1, "worker-2": 1}
    assert summary["attempt_counts"] == {"1": 1, "2": 1}


def test_trial_summary_reports_variability():
    aggregate = summarize_trials(
        [
            {
                "successful": 100,
                "failed": 0,
                "throughput_requests_per_second": 100.0,
                "latency_ms": {"p50": 10.0, "p95": 20.0, "p99": 30.0},
            },
            {
                "successful": 99,
                "failed": 1,
                "throughput_requests_per_second": 120.0,
                "latency_ms": {"p50": 12.0, "p95": 24.0, "p99": 36.0},
            },
        ]
    )

    assert aggregate["trials"] == 2
    assert aggregate["successful_requests"] == 199
    assert aggregate["failed_requests"] == 1
    assert aggregate["throughput_requests_per_second"]["mean"] == 110.0
    assert aggregate["latency_ms"]["p95"]["median"] == 22.0


def test_load_forwards_hybrid_request_constraints():
    requests = []

    class Stub:
        def Generate(self, request, timeout):
            requests.append((request, timeout))
            return inference_pb2.GenerateResponse(
                worker_id="gemma-e2b",
                selected_tier="fast",
                routing_policy="hybrid",
            )

    report, _ = run_load_on_stub(
        Stub(),
        requests=1,
        concurrency=1,
        prompt="private request",
        max_new_tokens=32,
        timeout_seconds=2.0,
        request_prefix="test",
        latency_budget_ms=500,
        max_cost_usd=0.01,
        require_local=True,
        preferred_region="europe-west1",
    )

    request, timeout = requests[0]
    assert report["successful"] == 1
    assert timeout == 2.0
    assert request.latency_budget_ms == 500
    assert request.max_cost_usd == 0.01
    assert request.require_local is True
    assert request.preferred_region == "europe-west1"
