from cascadeserve.v1 import inference_pb2
from cascadeserve_worker.generator import EchoGenerator
from cascadeserve_worker.generator import ModelUnavailableError
from cascadeserve_worker.service import WorkerService


class InjectedAbort(Exception):
    def __init__(self, code, details):
        self.code = code
        self.details = details


class AbortContext:
    def abort(self, code, details):
        raise InjectedAbort(code, details)


def test_generate_returns_worker_and_model_metadata():
    service = WorkerService("worker-test", EchoGenerator())
    request = inference_pb2.GenerateRequest(
        request_id="request-1",
        prompt="one two three",
        max_new_tokens=2,
    )

    response = service.Generate(request, None)

    assert response.request_id == "request-1"
    assert response.output == "one two"
    assert response.worker_id == "worker-test"
    assert response.model_id == "echo-v1"
    assert response.worker_latency_ms >= 0


def test_health_reports_worker_as_ready():
    service = WorkerService("worker-test", EchoGenerator())

    response = service.Health(inference_pb2.HealthRequest(), None)

    assert response.worker_id == "worker-test"
    assert response.model_id == "echo-v1"
    assert response.ready


def test_injected_failure_is_consumed_once():
    service = WorkerService("worker-test", EchoGenerator(), fail_first_n=1)
    request = inference_pb2.GenerateRequest(request_id="request-1", prompt="retry")

    try:
        service.Generate(request, AbortContext())
    except InjectedAbort as error:
        assert error.code.name == "UNAVAILABLE"
    else:
        raise AssertionError("expected an injected failure")

    response = service.Generate(request, AbortContext())
    assert response.output == "retry"


class UnavailableGenerator(EchoGenerator):
    def generate(self, prompt: str, max_new_tokens: int) -> str:
        raise ModelUnavailableError("model server offline")


def test_model_server_failure_is_reported_as_unavailable():
    service = WorkerService("worker-test", UnavailableGenerator())
    request = inference_pb2.GenerateRequest(request_id="request-1", prompt="retry")

    try:
        service.Generate(request, AbortContext())
    except InjectedAbort as error:
        assert error.code.name == "UNAVAILABLE"
        assert error.details == "model server offline"
    else:
        raise AssertionError("expected an unavailable model server")
