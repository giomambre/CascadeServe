import json
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from cascadeserve_worker.generator import ModelUnavailableError


class LlamaCppGenerator:
    def __init__(
        self,
        model_id: str,
        base_url: str = "http://127.0.0.1:8081",
        timeout_seconds: float = 120,
        health_timeout_seconds: float = 1,
        enable_thinking: bool = False,
    ):
        if timeout_seconds <= 0 or health_timeout_seconds <= 0:
            raise ValueError("timeouts must be positive")
        self._model_id = model_id
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._health_timeout_seconds = health_timeout_seconds
        self._enable_thinking = enable_thinking

    @property
    def model_id(self) -> str:
        return self._model_id

    @property
    def ready(self) -> bool:
        request = Request(f"{self._base_url}/health", method="GET")
        try:
            with urlopen(request, timeout=self._health_timeout_seconds) as response:
                return response.status == 200
        except (HTTPError, URLError, TimeoutError):
            return False

    def generate(self, prompt: str, max_new_tokens: int) -> str:
        body = {
            "model": self._model_id,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": max_new_tokens if max_new_tokens > 0 else 64,
            "temperature": 0,
            "seed": 42,
            "stream": False,
            "chat_template_kwargs": {"enable_thinking": self._enable_thinking},
        }
        request = Request(
            f"{self._base_url}/v1/chat/completions",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:
                result = json.load(response)
        except (HTTPError, URLError, TimeoutError) as error:
            raise ModelUnavailableError(f"llama.cpp request failed: {error}") from error

        try:
            return result["choices"][0]["message"]["content"].strip()
        except (KeyError, IndexError, TypeError, AttributeError) as error:
            raise ModelUnavailableError("llama.cpp returned an invalid response") from error
