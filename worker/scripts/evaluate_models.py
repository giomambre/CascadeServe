import argparse
import json
import platform
import sys
from datetime import UTC, datetime
from pathlib import Path

from cascadeserve_worker.evaluation import (
    comparison_summary,
    evaluate_target,
    load_dataset,
    paired_results,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--small-target", default="localhost:50052")
    parser.add_argument("--large-target", default="localhost:50053")
    parser.add_argument("--max-new-tokens", type=int, default=64)
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument("--warmup-requests", type=int, default=3)
    parser.add_argument("--report-output", type=Path, required=True)
    parser.add_argument("--results-output", type=Path, required=True)
    parser.add_argument("--training-output", type=Path, required=True)
    return parser.parse_args()


def write_json_lines(path: Path, records: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    content = "".join(json.dumps(record) + "\n" for record in records)
    path.write_text(content, encoding="utf-8")


def main() -> None:
    args = parse_args()
    if args.max_new_tokens <= 0 or args.timeout_seconds <= 0:
        raise ValueError("token and timeout values must be positive")
    if args.warmup_requests < 0:
        raise ValueError("warmup requests must not be negative")

    examples = load_dataset(args.dataset)
    small_results = evaluate_target(
        args.small_target,
        examples,
        args.max_new_tokens,
        args.timeout_seconds,
        args.warmup_requests,
    )
    large_results = evaluate_target(
        args.large_target,
        examples,
        args.max_new_tokens,
        args.timeout_seconds,
        args.warmup_requests,
    )
    comparison, training_records = comparison_summary(
        examples, small_results, large_results
    )
    report = {
        "created_at": datetime.now(UTC).isoformat(),
        "dataset": str(args.dataset),
        "environment": {
            "platform": platform.platform(),
            "python": sys.version.split()[0],
        },
        "configuration": {
            "small_target": args.small_target,
            "large_target": args.large_target,
            "max_new_tokens": args.max_new_tokens,
            "timeout_seconds": args.timeout_seconds,
            "warmup_requests": args.warmup_requests,
        },
        **comparison,
    }

    write_json_lines(
        args.results_output, paired_results(examples, small_results, large_results)
    )
    write_json_lines(args.training_output, training_records)
    args.report_output.parent.mkdir(parents=True, exist_ok=True)
    args.report_output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
