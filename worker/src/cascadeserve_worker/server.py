import os
from concurrent import futures

import grpc

from cascadeserve.v1 import inference_pb2_grpc
from cascadeserve_worker.generator import EchoGenerator
from cascadeserve_worker.generator import TextGenerator
from cascadeserve_worker.models import GEMMA_SMALL_MODEL_ID
from cascadeserve_worker.service import WorkerService


def create_generator(
    backend: str,
    model_id: str,
    device: str,
    quantization: str = "none",
    enable_thinking: bool = False,
    cpu_offload: bool = False,
    multimodal: bool = False,
    model_server_url: str = "http://127.0.0.1:8081",
    model_server_timeout_seconds: float = 120,
) -> TextGenerator:
    if backend == "echo":
        return EchoGenerator()
    if backend == "transformers":
        from cascadeserve_worker.transformers_generator import TransformersGenerator

        return TransformersGenerator(
            model_id,
            device,
            quantization,
            enable_thinking,
            cpu_offload,
            multimodal,
        )
    if backend == "llama_cpp":
        from cascadeserve_worker.llama_cpp_generator import LlamaCppGenerator

        return LlamaCppGenerator(
            model_id,
            model_server_url,
            model_server_timeout_seconds,
            enable_thinking=enable_thinking,
        )
    raise ValueError(f"unsupported model backend: {backend}")


def build_server(
    worker_id: str,
    port: int,
    generator: TextGenerator | None = None,
    fail_first_n: int = 0,
    simulated_delay_ms: int = 0,
) -> grpc.Server:
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=8))
    inference_pb2_grpc.add_InferenceWorkerServicer_to_server(
        WorkerService(
            worker_id,
            generator or EchoGenerator(),
            fail_first_n,
            simulated_delay_ms,
        ),
        server,
    )
    server.add_insecure_port(f"[::]:{port}")
    return server


def main() -> None:
    worker_id = os.getenv("WORKER_ID", "worker-1")
    port = int(os.getenv("WORKER_PORT", "50052"))
    fail_first_n = int(os.getenv("FAIL_FIRST_N", "0"))
    simulated_delay_ms = int(os.getenv("SIMULATED_DELAY_MS", "0"))
    backend = os.getenv("MODEL_BACKEND", "echo")
    model_id = os.getenv("MODEL_ID", GEMMA_SMALL_MODEL_ID)
    device = os.getenv("MODEL_DEVICE", "auto")
    quantization = os.getenv("MODEL_QUANTIZATION", "none")
    enable_thinking = os.getenv("MODEL_ENABLE_THINKING", "false").lower() == "true"
    cpu_offload = os.getenv("MODEL_CPU_OFFLOAD", "false").lower() == "true"
    multimodal = os.getenv("MODEL_MULTIMODAL", "false").lower() == "true"
    model_server_url = os.getenv("MODEL_SERVER_URL", "http://127.0.0.1:8081")
    model_server_timeout_seconds = float(
        os.getenv("MODEL_SERVER_TIMEOUT_SECONDS", "120")
    )
    generator = create_generator(
        backend,
        model_id,
        device,
        quantization,
        enable_thinking,
        cpu_offload,
        multimodal,
        model_server_url,
        model_server_timeout_seconds,
    )
    server = build_server(
        worker_id, port, generator, fail_first_n, simulated_delay_ms
    )
    server.start()
    server.wait_for_termination()


if __name__ == "__main__":
    main()
