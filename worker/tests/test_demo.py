from concurrent import futures

import grpc
import pytest

from cascadeserve.v1 import inference_pb2, inference_pb2_grpc
from cascadeserve_worker.demo import generate, parse_request


def test_parse_request_validates_contract():
    request = parse_request(
        {
            "prompt": "  solve this  ",
            "max_new_tokens": 64,
            "latency_budget_ms": 4000,
            "require_local": True,
        }
    )

    assert request.prompt == "solve this"
    assert request.latency_budget_ms == 4000
    assert request.require_local

    with pytest.raises(ValueError, match="Prompt is required"):
        parse_request({"prompt": ""})


def test_generate_returns_routing_trace():
    class ControlService(inference_pb2_grpc.CascadeServiceServicer):
        def Generate(self, request, context):
            assert request.latency_budget_ms == 4000
            return inference_pb2.GenerateResponse(
                request_id="demo-1",
                output="42",
                worker_id="gemma-e2b-local",
                model_id="google/gemma-4-E2B-it",
                worker_latency_ms=90,
                total_latency_ms=95,
                attempts=1,
                selected_tier="fast",
                routing_policy="hybrid",
                routing_score=0.21,
                input_tokens=12,
                output_tokens=3,
            )

    server = grpc.server(futures.ThreadPoolExecutor(max_workers=1))
    inference_pb2_grpc.add_CascadeServiceServicer_to_server(ControlService(), server)
    port = server.add_insecure_port("localhost:0")
    server.start()
    try:
        result = generate(
            f"localhost:{port}",
            parse_request(
                {
                    "prompt": "What is six times seven?",
                    "latency_budget_ms": 4000,
                }
            ),
            1.0,
        )
    finally:
        server.stop(grace=None).wait()

    assert result["output"] == "42"
    assert result["worker_id"] == "gemma-e2b-local"
    assert result["input_tokens"] == 12
    assert result["output_tokens"] == 3
