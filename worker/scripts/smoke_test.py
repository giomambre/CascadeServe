import argparse
import json

import grpc

from cascadeserve.v1 import inference_pb2, inference_pb2_grpc


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", default="localhost:50051")
    parser.add_argument("--prompt", default="CascadeServe is running")
    parser.add_argument("--max-new-tokens", type=int, default=8)
    parser.add_argument("--latency-budget-ms", type=int, default=0)
    parser.add_argument("--max-cost-usd", type=float, default=0.0)
    parser.add_argument("--require-local", action="store_true")
    parser.add_argument("--preferred-region", default="")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    with grpc.insecure_channel(args.target) as channel:
        stub = inference_pb2_grpc.CascadeServiceStub(channel)
        response = stub.Generate(
            inference_pb2.GenerateRequest(
                prompt=args.prompt,
                max_new_tokens=args.max_new_tokens,
                latency_budget_ms=args.latency_budget_ms,
                max_cost_usd=args.max_cost_usd,
                require_local=args.require_local,
                preferred_region=args.preferred_region,
            ),
            timeout=5,
        )

    if not response.request_id:
        raise RuntimeError("control plane did not assign a request id")
    if not response.worker_id:
        raise RuntimeError("response does not identify its worker")

    print(
        json.dumps(
            {
                "request_id": response.request_id,
                "output": response.output,
                "worker_id": response.worker_id,
                "model_id": response.model_id,
                "worker_latency_ms": response.worker_latency_ms,
                "total_latency_ms": response.total_latency_ms,
                "attempts": response.attempts,
                "selected_tier": response.selected_tier,
                "routing_policy": response.routing_policy,
                "routing_score": response.routing_score,
                "input_tokens": response.input_tokens,
                "output_tokens": response.output_tokens,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
