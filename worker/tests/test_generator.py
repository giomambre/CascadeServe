from cascadeserve_worker.generator import EchoGenerator
from cascadeserve_worker.server import create_generator


def test_echo_generator_respects_output_limit():
    generator = EchoGenerator()

    assert generator.generate("one two three", 2) == "one two"


def test_echo_generator_uses_full_prompt_when_limit_is_unset():
    generator = EchoGenerator()

    assert generator.generate("one two three", 0) == "one two three"


def test_generator_factory_rejects_unknown_backends():
    try:
        create_generator("unknown", "unused", "cpu")
    except ValueError as error:
        assert "unsupported model backend" in str(error)
    else:
        raise AssertionError("expected an unsupported backend error")
