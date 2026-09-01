from typing import Protocol


class ModelUnavailableError(RuntimeError):
    pass


class TextGenerator(Protocol):
    @property
    def model_id(self) -> str: ...

    @property
    def ready(self) -> bool: ...

    def generate(self, prompt: str, max_new_tokens: int) -> str: ...


class EchoGenerator:
    @property
    def model_id(self) -> str:
        return "echo-v1"

    @property
    def ready(self) -> bool:
        return True

    def generate(self, prompt: str, max_new_tokens: int) -> str:
        words = prompt.split()
        limit = max_new_tokens if max_new_tokens > 0 else len(words)
        return " ".join(words[:limit])
