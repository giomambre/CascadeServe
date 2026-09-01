import argparse
import json
from pathlib import Path

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, precision_score, recall_score, roc_auc_score
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler

from cascadeserve_worker.routing import FEATURE_NAMES, feature_vector


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--model-output", type=Path, required=True)
    parser.add_argument("--report-output", type=Path, required=True)
    parser.add_argument("--threshold", type=float, default=0.5)
    parser.add_argument("--test-size", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=42)
    return parser.parse_args()


def load_examples(path: Path) -> tuple[np.ndarray, np.ndarray]:
    features = []
    labels = []
    with path.open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            record = json.loads(line)
            try:
                prompt = record["prompt"]
                small_correct = record["small_correct"]
                large_correct = record["large_correct"]
            except KeyError as error:
                raise ValueError(f"line {line_number} is missing {error.args[0]}") from error
            if not isinstance(prompt, str):
                raise ValueError(f"line {line_number} prompt must be a string")
            if not isinstance(small_correct, bool) or not isinstance(large_correct, bool):
                raise ValueError(f"line {line_number} correctness values must be boolean")
            features.append(feature_vector(prompt))
            labels.append(int(large_correct and not small_correct))

    if len(set(labels)) != 2:
        raise ValueError("training data must contain both routing classes")
    return np.asarray(features, dtype=float), np.asarray(labels, dtype=int)


def export_model(
    path: Path,
    classifier: LogisticRegression,
    scaler: StandardScaler,
    threshold: float,
) -> None:
    scaled_weights = classifier.coef_[0]
    raw_weights = scaled_weights / scaler.scale_
    raw_intercept = classifier.intercept_[0] - np.sum(
        scaled_weights * scaler.mean_ / scaler.scale_
    )
    lines = [f"intercept={raw_intercept:.17g}", f"threshold={threshold:.17g}"]
    lines.extend(
        f"weight.{name}={weight:.17g}"
        for name, weight in zip(FEATURE_NAMES, raw_weights, strict=True)
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    args = parse_args()
    if not 0 < args.threshold < 1:
        raise ValueError("threshold must be between zero and one")

    features, labels = load_examples(args.input)
    train_features, test_features, train_labels, test_labels = train_test_split(
        features,
        labels,
        test_size=args.test_size,
        random_state=args.seed,
        stratify=labels,
    )
    scaler = StandardScaler().fit(train_features)
    classifier = LogisticRegression(
        class_weight="balanced",
        max_iter=1_000,
        random_state=args.seed,
    ).fit(scaler.transform(train_features), train_labels)

    probabilities = classifier.predict_proba(scaler.transform(test_features))[:, 1]
    predictions = (probabilities >= args.threshold).astype(int)
    metrics = {
        "accuracy": accuracy_score(test_labels, predictions),
        "precision": precision_score(test_labels, predictions, zero_division=0),
        "recall": recall_score(test_labels, predictions, zero_division=0),
        "roc_auc": roc_auc_score(test_labels, probabilities),
    }

    export_model(args.model_output, classifier, scaler, args.threshold)
    report = {
        "input": str(args.input),
        "model_output": str(args.model_output),
        "samples": len(labels),
        "requires_large_samples": int(labels.sum()),
        "test_samples": len(test_labels),
        "threshold": args.threshold,
        "seed": args.seed,
        "features": list(FEATURE_NAMES),
        "metrics": metrics,
    }
    args.report_output.parent.mkdir(parents=True, exist_ok=True)
    args.report_output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
