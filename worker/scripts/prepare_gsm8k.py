import argparse
import json
import random
from pathlib import Path
from urllib.request import urlopen


DEFAULT_SOURCE = (
    "https://raw.githubusercontent.com/openai/grade-school-math/"
    "master/grade_school_math/data/test.jsonl"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", default=DEFAULT_SOURCE)
    parser.add_argument("--sample-size", type=int, default=50)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("../benchmarks/datasets/gsm8k-test-50.jsonl"),
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.sample_size <= 0:
        raise ValueError("sample size must be positive")

    with urlopen(args.source, timeout=30) as response:
        examples = [json.loads(line) for line in response if line.strip()]
    if args.sample_size > len(examples):
        raise ValueError("sample size exceeds the source dataset")

    sampled = random.Random(args.seed).sample(list(enumerate(examples)), args.sample_size)
    sampled.sort()
    records = []
    for index, example in sampled:
        reference = example["answer"].rsplit("####", maxsplit=1)[-1].strip()
        records.append(
            {
                "id": f"gsm8k-test-{index}",
                "prompt": (
                    "Solve the following math problem. Show concise reasoning and "
                    "finish with 'Final answer: <number>'.\n\n" + example["question"]
                ),
                "reference": reference,
                "metric": "last_number",
            }
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        "".join(json.dumps(record) + "\n" for record in records),
        encoding="utf-8",
    )
    print(json.dumps({"source_rows": len(examples), "sampled_rows": len(records)}))


if __name__ == "__main__":
    main()
