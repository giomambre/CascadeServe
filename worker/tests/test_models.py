from cascadeserve_worker.models import GEMMA_LARGE_MODEL_ID, GEMMA_SMALL_MODEL_ID
from cascadeserve_worker.transformers_generator import TransformersGenerator


def test_gemma_tiers_use_official_instruction_models():
    assert GEMMA_SMALL_MODEL_ID == "google/gemma-4-E2B-it"
    assert GEMMA_LARGE_MODEL_ID == "google/gemma-4-E4B-it"


def test_four_bit_configuration_enables_explicit_cpu_offload():
    import torch

    options = TransformersGenerator._load_options(
        torch, "cuda", "4bit", cpu_offload=True
    )

    config = options["quantization_config"]
    assert options["device_map"] == "auto"
    assert config.load_in_4bit
    assert config.bnb_4bit_quant_type == "nf4"
    assert config.llm_int8_enable_fp32_cpu_offload
