import argparse
import json
from pathlib import Path

from cascadeserve_worker.evaluation import (
    evaluate_target,
    load_dataset,
    serializable_evaluation_results,
    summarize_results,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--tier", choices=("small", "large"), required=True)
    parser.add_argument("--max-new-tokens", type=int, default=64)
    parser.add_argument("--timeout-seconds", type=float, default=60.0)
    parser.add_argument("--warmup-requests", type=int, default=3)
    parser.add_argument("--service", choices=("worker", "control"), default="worker")
    parser.add_argument("--preferred-region", default="")
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.max_new_tokens <= 0 or args.timeout_seconds <= 0:
        raise ValueError("token and timeout values must be positive")
    if args.warmup_requests < 0:
        raise ValueError("warmup requests must not be negative")

    examples = load_dataset(args.dataset)
    results = evaluate_target(
        args.target,
        examples,
        args.max_new_tokens,
        args.timeout_seconds,
        args.warmup_requests,
        args.service,
        args.preferred_region,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    content = "".join(
        json.dumps(record) + "\n"
        for record in serializable_evaluation_results(results)
    )
    args.output.write_text(content, encoding="utf-8")
    print(json.dumps({"tier": args.tier, **summarize_results(results)}, indent=2))


if __name__ == "__main__":
    main()
