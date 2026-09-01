from pathlib import Path

from grpc_tools import protoc


worker_root = Path(__file__).resolve().parents[1]
repository_root = worker_root.parent
proto_root = repository_root / "proto"
source_root = worker_root / "src"
proto_file = proto_root / "cascadeserve" / "v1" / "inference.proto"

result = protoc.main(
    [
        "grpc_tools.protoc",
        f"-I{proto_root}",
        f"--python_out={source_root}",
        f"--pyi_out={source_root}",
        f"--grpc_python_out={source_root}",
        str(proto_file),
    ]
)

if result != 0:
    raise SystemExit(result)
