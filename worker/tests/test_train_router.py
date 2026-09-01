import json
import subprocess
import sys
from pathlib import Path


def test_training_cli_exports_java_properties(tmp_path):
    worker_root = Path(__file__).parents[1]
    dataset = Path(__file__).parent / "fixtures" / "router_training.jsonl"
    model_output = tmp_path / "router.properties"
    report_output = tmp_path / "training-report.json"

    completed = subprocess.run(
        [
            sys.executable,
            str(worker_root / "scripts" / "train_router.py"),
            "--input",
            str(dataset),
            "--model-output",
            str(model_output),
            "--report-output",
            str(report_output),
        ],
        cwd=worker_root,
        check=False,
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 0, completed.stderr
    properties = model_output.read_text(encoding="utf-8")
    report = json.loads(report_output.read_text(encoding="utf-8"))
    assert "intercept=" in properties
    assert "weight.word_count=" in properties
    assert report["samples"] == 10
    assert report["requires_large_samples"] == 5
