from threading import Lock


class TransformersGenerator:
    def __init__(
        self,
        model_id: str,
        device: str = "auto",
        quantization: str = "none",
        enable_thinking: bool = False,
        cpu_offload: bool = False,
        multimodal: bool = False,
    ):
        import torch

        selected_device = (
            "cuda" if device == "auto" and torch.cuda.is_available() else "cpu"
        )
        if device != "auto":
            selected_device = device
        if selected_device == "cuda" and not torch.cuda.is_available():
            raise RuntimeError("CUDA was requested but is not available")
        if quantization not in {"none", "4bit"}:
            raise ValueError("quantization must be none or 4bit")
        if quantization == "4bit" and selected_device != "cuda":
            raise RuntimeError("4-bit model loading requires CUDA")

        self._torch = torch
        self._device = selected_device
        self._model_id = model_id
        self._enable_thinking = enable_thinking
        self._is_gemma4 = model_id.startswith("google/gemma-4-")
        self._multimodal = multimodal
        load_options = self._load_options(
            torch, selected_device, quantization, cpu_offload
        )

        if self._is_gemma4 and multimodal:
            from transformers import AutoModelForMultimodalLM, AutoProcessor

            self._processor = AutoProcessor.from_pretrained(model_id)
            self._model = AutoModelForMultimodalLM.from_pretrained(
                model_id, **load_options
            )
        else:
            from transformers import AutoModelForCausalLM, AutoTokenizer

            self._processor = AutoTokenizer.from_pretrained(model_id)
            self._model = AutoModelForCausalLM.from_pretrained(
                model_id, **load_options
            )

        if quantization == "none":
            self._model.to(selected_device)
        self._model.eval()
        self._lock = Lock()

    @property
    def model_id(self) -> str:
        return self._model_id

    @property
    def ready(self) -> bool:
        return True

    @property
    def execution_devices(self) -> list[str]:
        device_map = getattr(self._model, "hf_device_map", None)
        if not device_map:
            return [self._device]
        return sorted({str(device) for device in device_map.values()})

    def generate(self, prompt: str, max_new_tokens: int) -> str:
        output_limit = max_new_tokens if max_new_tokens > 0 else 64
        messages = [{"role": "user", "content": prompt}]

        with self._lock, self._torch.inference_mode():
            template_options = {}
            if self._is_gemma4:
                template_options["enable_thinking"] = self._enable_thinking
            inputs = self._processor.apply_chat_template(
                messages,
                add_generation_prompt=True,
                tokenize=True,
                return_dict=True,
                return_tensors="pt",
                **template_options,
            )
            inputs = {name: value.to(self._device) for name, value in inputs.items()}
            output = self._model.generate(
                **inputs,
                max_new_tokens=output_limit,
                do_sample=False,
            )

        prompt_length = inputs["input_ids"].shape[-1]
        generated_tokens = output[0, prompt_length:]
        return self._processor.decode(
            generated_tokens, skip_special_tokens=True
        ).strip()

    @staticmethod
    def _load_options(
        torch, device: str, quantization: str, cpu_offload: bool = False
    ) -> dict:
        dtype = torch.bfloat16 if device == "cuda" else torch.float32
        if quantization == "none":
            return {"dtype": dtype}

        from transformers import BitsAndBytesConfig

        return {
            "device_map": "auto",
            "dtype": dtype,
            "quantization_config": BitsAndBytesConfig(
                load_in_4bit=True,
                bnb_4bit_quant_type="nf4",
                bnb_4bit_compute_dtype=dtype,
                llm_int8_enable_fp32_cpu_offload=cpu_offload,
            ),
        }
