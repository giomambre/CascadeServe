import argparse
import json
from pathlib import Path

from cascadeserve_worker.evaluation import (
    load_dataset,
    load_evaluation_results,
    replay_hybrid_policy,
    summarize_results,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--local-results", type=Path, required=True)
    parser.add_argument("--remote-results", type=Path, required=True)
    parser.add_argument("--latency-budget-ms", type=int, required=True)
    parser.add_argument("--remote-expected-latency-ms", type=int, required=True)
    parser.add_argument("--latency-request-share", type=float, default=0.5)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    examples = load_dataset(args.dataset)
    local = load_evaluation_results(args.local_results, examples)
    remote = load_evaluation_results(args.remote_results, examples)
    hybrid, decisions = replay_hybrid_policy(
        examples,
        local,
        remote,
        args.latency_budget_ms,
        args.remote_expected_latency_ms,
        args.latency_request_share,
    )
    report = {
        "method": "offline_policy_replay",
        "policy": {
            "latency_budget_ms": args.latency_budget_ms,
            "remote_expected_latency_ms": args.remote_expected_latency_ms,
            "latency_request_share": args.latency_request_share,
        },
        "local_baseline": summarize_results(local),
        "remote_baseline": summarize_results(remote),
        "hybrid": hybrid,
        "decisions": decisions,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
