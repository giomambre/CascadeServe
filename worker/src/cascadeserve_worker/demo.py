from __future__ import annotations

from dataclasses import dataclass

import grpc

from cascadeserve.v1 import inference_pb2, inference_pb2_grpc


@dataclass(frozen=True)
class DemoRequest:
    prompt: str
    max_new_tokens: int
    latency_budget_ms: int
    max_cost_usd: float
    require_local: bool
    preferred_region: str


def parse_request(payload: dict) -> DemoRequest:
    prompt = payload.get("prompt", "")
    max_new_tokens = payload.get("max_new_tokens", 128)
    latency_budget_ms = payload.get("latency_budget_ms", 0)
    max_cost_usd = payload.get("max_cost_usd", 0.0)
    require_local = payload.get("require_local", False)
    preferred_region = payload.get("preferred_region", "")

    if not isinstance(prompt, str) or not prompt.strip():
        raise ValueError("Prompt is required")
    if len(prompt) > 20_000:
        raise ValueError("Prompt is too long")
    if not isinstance(max_new_tokens, int) or not 1 <= max_new_tokens <= 2048:
        raise ValueError("max_new_tokens must be between 1 and 2048")
    if not isinstance(latency_budget_ms, int) or latency_budget_ms < 0:
        raise ValueError("latency_budget_ms must be zero or greater")
    if not isinstance(max_cost_usd, (int, float)) or max_cost_usd < 0:
        raise ValueError("max_cost_usd must be zero or greater")
    if not isinstance(require_local, bool):
        raise ValueError("require_local must be a boolean")
    if not isinstance(preferred_region, str):
        raise ValueError("preferred_region must be a string")

    return DemoRequest(
        prompt.strip(),
        max_new_tokens,
        latency_budget_ms,
        float(max_cost_usd),
        require_local,
        preferred_region.strip(),
    )


def generate(target: str, request: DemoRequest, timeout_seconds: float) -> dict:
    with grpc.insecure_channel(target) as channel:
        stub = inference_pb2_grpc.CascadeServiceStub(channel)
        response = stub.Generate(
            inference_pb2.GenerateRequest(
                prompt=request.prompt,
                max_new_tokens=request.max_new_tokens,
                latency_budget_ms=request.latency_budget_ms,
                max_cost_usd=request.max_cost_usd,
                require_local=request.require_local,
                preferred_region=request.preferred_region,
            ),
            timeout=timeout_seconds,
        )

    return {
        "request_id": response.request_id,
        "output": response.output,
        "worker_id": response.worker_id,
        "model_id": response.model_id,
        "worker_latency_ms": response.worker_latency_ms,
        "total_latency_ms": response.total_latency_ms,
        "attempts": response.attempts,
        "selected_tier": response.selected_tier,
        "routing_policy": response.routing_policy,
        "routing_score": round(response.routing_score, 4),
        "input_tokens": response.input_tokens,
        "output_tokens": response.output_tokens,
    }
