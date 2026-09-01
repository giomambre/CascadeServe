import argparse
import json

import grpc

from cascadeserve.v1 import inference_pb2, inference_pb2_grpc


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", default="localhost:50052")
    parser.add_argument("--prompt", default="Reply with exactly: CascadeServe ready")
    parser.add_argument("--max-new-tokens", type=int, default=16)
    parser.add_argument("--timeout-seconds", type=float, default=120)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    with grpc.insecure_channel(args.target) as channel:
        stub = inference_pb2_grpc.InferenceWorkerStub(channel)
        health = stub.Health(
            inference_pb2.HealthRequest(), timeout=args.timeout_seconds
        )
        response = stub.Generate(
            inference_pb2.GenerateRequest(
                request_id="worker-smoke-test",
                prompt=args.prompt,
                max_new_tokens=args.max_new_tokens,
            ),
            timeout=args.timeout_seconds,
        )

    if not health.ready:
        raise RuntimeError("worker is not ready")
    print(
        json.dumps(
            {
                "ready": health.ready,
                "worker_id": response.worker_id,
                "model_id": response.model_id,
                "worker_latency_ms": response.worker_latency_ms,
                "output": response.output,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
