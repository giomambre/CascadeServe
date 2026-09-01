import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import grpc

from cascadeserve_worker.demo import generate, parse_request


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the local CascadeServe demo")
    parser.add_argument("--target", default="127.0.0.1:50051")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--timeout-seconds", type=float, default=25.0)
    parser.add_argument(
        "--page",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "demo" / "index.html",
    )
    return parser.parse_args()


def handler_for(target: str, page: Path, timeout_seconds: float):
    class DemoHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path not in {"/", "/index.html"}:
                self.send_error(404)
                return
            content = page.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-store")
            self.send_header(
                "Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; "
                "script-src 'self' 'unsafe-inline'; connect-src 'self'",
            )
            self.end_headers()
            self.wfile.write(content)

        def do_POST(self):
            if self.path != "/api/generate":
                self.send_error(404)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 65_536:
                    raise ValueError("Invalid request size")
                payload = json.loads(self.rfile.read(length))
                if not isinstance(payload, dict):
                    raise ValueError("Request body must be an object")
                result = generate(target, parse_request(payload), timeout_seconds)
                self.send_json(200, result)
            except (ValueError, json.JSONDecodeError) as error:
                self.send_json(400, {"error": str(error)})
            except grpc.RpcError as error:
                status = 504 if error.code() == grpc.StatusCode.DEADLINE_EXCEEDED else 503
                self.send_json(
                    status,
                    {"error": error.details() or "Inference request failed", "code": error.code().name},
                )

        def send_json(self, status: int, payload: dict):
            content = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(content)

        def log_message(self, format, *args):
            return

    return DemoHandler


def main() -> None:
    args = parse_args()
    if not args.page.is_file():
        raise FileNotFoundError(args.page)
    server = ThreadingHTTPServer(
        (args.host, args.port),
        handler_for(args.target, args.page, args.timeout_seconds),
    )
    print(f"CascadeServe demo: http://{args.host}:{args.port}")
    print(f"Control plane: {args.target}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
