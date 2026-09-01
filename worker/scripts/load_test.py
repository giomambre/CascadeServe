import argparse
import json
import os
import platform
import sys
from datetime import UTC, datetime
from pathlib import Path

from cascadeserve_worker.benchmark import (
    run_repeated_load,
    serializable_results,
    summarize_trials,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", default="localhost:50051")
    parser.add_argument("--requests", type=int, default=1_000)
    parser.add_argument("--concurrency", type=int, default=32)
    parser.add_argument("--prompt", default="CascadeServe benchmark request")
    parser.add_argument("--max-new-tokens", type=int, default=8)
    parser.add_argument("--timeout-seconds", type=float, default=5.0)
    parser.add_argument("--trials", type=int, default=1)
    parser.add_argument("--warmup-requests", type=int, default=0)
    parser.add_argument("--workload", default="echo_transport_baseline")
    parser.add_argument("--scheduler-policy", default="unspecified")
    parser.add_argument("--worker-profile", default="unspecified")
    parser.add_argument("--latency-budget-ms", type=int, default=0)
    parser.add_argument("--max-cost-usd", type=float, default=0.0)
    parser.add_argument("--require-local", action="store_true")
    parser.add_argument("--preferred-region", default="")
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    trials = run_repeated_load(
        args.target,
        args.requests,
        args.concurrency,
        args.prompt,
        args.max_new_tokens,
        args.timeout_seconds,
        args.trials,
        args.warmup_requests,
        args.latency_budget_ms,
        args.max_cost_usd,
        args.require_local,
        args.preferred_region,
    )
    summaries = [summary for summary, _ in trials]
    report = {
        "created_at": datetime.now(UTC).isoformat(),
        "workload": args.workload,
        "environment": {
            "platform": platform.platform(),
            "machine": platform.machine(),
            "processor": platform.processor(),
            "logical_cpus": os.cpu_count(),
            "python": sys.version.split()[0],
        },
        "configuration": {
            "target": args.target,
            "requests": args.requests,
            "concurrency": args.concurrency,
            "prompt": args.prompt,
            "max_new_tokens": args.max_new_tokens,
            "timeout_seconds": args.timeout_seconds,
            "trials": args.trials,
            "warmup_requests": args.warmup_requests,
            "scheduler_policy": args.scheduler_policy,
            "worker_profile": args.worker_profile,
            "latency_budget_ms": args.latency_budget_ms,
            "max_cost_usd": args.max_cost_usd,
            "require_local": args.require_local,
            "preferred_region": args.preferred_region,
        },
        "aggregate": summarize_trials(summaries),
        "trials": [
            {
                "index": index,
                "summary": summary,
                "results": serializable_results(results),
            }
            for index, (summary, results) in enumerate(trials, start=1)
        ],
    }

    rendered = json.dumps(report, indent=2)
    print(json.dumps(report["aggregate"], indent=2))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
