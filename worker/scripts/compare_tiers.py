import argparse
import json
import platform
import sys
from datetime import UTC, datetime
from pathlib import Path

from cascadeserve_worker.evaluation import (
    comparison_summary,
    load_dataset,
    load_evaluation_results,
    paired_results,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--small-results", type=Path, required=True)
    parser.add_argument("--large-results", type=Path, required=True)
    parser.add_argument("--report-output", type=Path, required=True)
    parser.add_argument("--results-output", type=Path, required=True)
    parser.add_argument("--training-output", type=Path, required=True)
    return parser.parse_args()


def write_json_lines(path: Path, records: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(record) + "\n" for record in records),
        encoding="utf-8",
    )


def main() -> None:
    args = parse_args()
    examples = load_dataset(args.dataset)
    small_results = load_evaluation_results(args.small_results, examples)
    large_results = load_evaluation_results(args.large_results, examples)
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
        "inputs": {
            "small_results": str(args.small_results),
            "large_results": str(args.large_results),
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
