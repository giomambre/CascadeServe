import argparse
import json
from time import perf_counter_ns

from cascadeserve_worker.models import GEMMA_SMALL_MODEL_ID
def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--backend", choices=("llama_cpp", "transformers"), default="llama_cpp"
    )
    parser.add_argument("--model-id", default=GEMMA_SMALL_MODEL_ID)
    parser.add_argument("--server-url", default="http://127.0.0.1:8081")
    parser.add_argument("--timeout-seconds", type=float, default=120)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--quantization", choices=("none", "4bit"), default="none")
    parser.add_argument("--enable-thinking", action="store_true")
    parser.add_argument("--cpu-offload", action="store_true")
    parser.add_argument("--multimodal", action="store_true")
    parser.add_argument("--prompt", default="Reply with the single word ready.")
    parser.add_argument("--max-new-tokens", type=int, default=12)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    started_at = perf_counter_ns()
    if args.backend == "llama_cpp":
        from cascadeserve_worker.llama_cpp_generator import LlamaCppGenerator

        generator = LlamaCppGenerator(
            args.model_id,
            args.server_url,
            args.timeout_seconds,
            enable_thinking=args.enable_thinking,
        )
        if not generator.ready:
            raise RuntimeError(f"model server is not ready at {args.server_url}")
    else:
        from cascadeserve_worker.transformers_generator import TransformersGenerator

        generator = TransformersGenerator(
            args.model_id,
            args.device,
            args.quantization,
            args.enable_thinking,
            args.cpu_offload,
            args.multimodal,
        )
    setup_ms = (perf_counter_ns() - started_at) / 1_000_000

    started_at = perf_counter_ns()
    output = generator.generate(args.prompt, args.max_new_tokens)
    generation_ms = (perf_counter_ns() - started_at) / 1_000_000

    print(
        json.dumps(
            {
                "model_id": generator.model_id,
                "backend": args.backend,
                "device": "model_server" if args.backend == "llama_cpp" else args.device,
                "quantization": (
                    "qat_q4_0" if args.backend == "llama_cpp" else args.quantization
                ),
                "thinking": args.enable_thinking,
                "cpu_offload": args.cpu_offload,
                "multimodal": args.multimodal,
                "execution_devices": getattr(generator, "execution_devices", []),
                "setup_ms": round(setup_ms, 2),
                "generation_ms": round(generation_ms, 2),
                "output": output,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
