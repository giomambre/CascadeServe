from time import perf_counter_ns, sleep
from threading import Lock

import grpc

from cascadeserve.v1 import inference_pb2, inference_pb2_grpc
from cascadeserve_worker.generator import ModelUnavailableError
from cascadeserve_worker.generator import TextGenerator


class WorkerService(inference_pb2_grpc.InferenceWorkerServicer):
    def __init__(
        self,
        worker_id: str,
        generator: TextGenerator,
        fail_first_n: int = 0,
        simulated_delay_ms: int = 0,
    ):
        if fail_first_n < 0 or simulated_delay_ms < 0:
            raise ValueError("fault-injection values must not be negative")
        self._worker_id = worker_id
        self._generator = generator
        self._remaining_failures = fail_first_n
        self._simulated_delay_ms = simulated_delay_ms
        self._failure_lock = Lock()

    def Generate(self, request, context):
        if not request.prompt.strip():
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "prompt must not be blank")
        if self._consume_injected_failure():
            context.abort(grpc.StatusCode.UNAVAILABLE, "injected worker failure")

        started_at = perf_counter_ns()
        if self._simulated_delay_ms:
            sleep(self._simulated_delay_ms / 1_000)
        try:
            output = self._generator.generate(request.prompt, request.max_new_tokens)
        except ModelUnavailableError as error:
            context.abort(grpc.StatusCode.UNAVAILABLE, str(error))
        elapsed_ms = (perf_counter_ns() - started_at) // 1_000_000

        return inference_pb2.GenerateResponse(
            request_id=request.request_id,
            output=output,
            worker_id=self._worker_id,
            model_id=self._generator.model_id,
            worker_latency_ms=elapsed_ms,
        )

    def Health(self, request, context):
        return inference_pb2.HealthResponse(
            worker_id=self._worker_id,
            model_id=self._generator.model_id,
            ready=self._generator.ready,
        )

    def _consume_injected_failure(self) -> bool:
        with self._failure_lock:
            if self._remaining_failures == 0:
                return False
            self._remaining_failures -= 1
            return True
