import json
from io import BytesIO
from unittest.mock import patch
from urllib.error import URLError

from cascadeserve_worker.generator import ModelUnavailableError
from cascadeserve_worker.llama_cpp_generator import LlamaCppGenerator


class Response(BytesIO):
    def __init__(self, body: dict, status: int = 200):
        super().__init__(json.dumps(body).encode("utf-8"))
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def test_generate_calls_chat_completions():
    generator = LlamaCppGenerator("google/gemma-4-E2B-it", "http://model:8080")
    response = Response({"choices": [{"message": {"content": "  hello  "}}]})

    with patch(
        "cascadeserve_worker.llama_cpp_generator.urlopen", return_value=response
    ) as request:
        output = generator.generate("say hello", 12)

    assert output == "hello"
    sent_request = request.call_args.args[0]
    assert sent_request.full_url == "http://model:8080/v1/chat/completions"
    body = json.loads(sent_request.data)
    assert body["messages"] == [{"role": "user", "content": "say hello"}]
    assert body["max_tokens"] == 12
    assert body["temperature"] == 0
    assert body["chat_template_kwargs"] == {"enable_thinking": False}


def test_health_uses_llama_cpp_health_endpoint():
    generator = LlamaCppGenerator("google/gemma-4-E2B-it", "http://model:8080")

    with patch(
        "cascadeserve_worker.llama_cpp_generator.urlopen",
        return_value=Response({"status": "ok"}),
    ) as request:
        assert generator.ready

    assert request.call_args.args[0].full_url == "http://model:8080/health"


def test_health_is_false_when_model_server_is_unavailable():
    generator = LlamaCppGenerator("google/gemma-4-E2B-it", "http://model:8080")

    with patch(
        "cascadeserve_worker.llama_cpp_generator.urlopen",
        side_effect=URLError("offline"),
    ):
        assert not generator.ready


def test_generate_maps_connection_failure():
    generator = LlamaCppGenerator("google/gemma-4-E2B-it", "http://model:8080")

    with patch(
        "cascadeserve_worker.llama_cpp_generator.urlopen",
        side_effect=URLError("offline"),
    ):
        try:
            generator.generate("hello", 8)
        except ModelUnavailableError as error:
            assert "request failed" in str(error)
        else:
            raise AssertionError("expected a model server failure")


def test_generator_factory_builds_llama_cpp_backend():
    from cascadeserve_worker.server import create_generator

    generator = create_generator(
        "llama_cpp",
        "google/gemma-4-E2B-it",
        "cuda",
        model_server_url="http://model:8080",
    )

    assert isinstance(generator, LlamaCppGenerator)
    assert generator.model_id == "google/gemma-4-E2B-it"
