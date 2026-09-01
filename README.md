# CascadeServe

CascadeServe is a distributed model-serving gateway for experimenting with scheduling, routing, and reliability under explicit latency, quality, cost, privacy, and availability targets.

The control plane is written in Java. Python workers expose model inference over gRPC. The first vertical slice uses a deterministic worker so that transport and scheduling can be tested without GPU or model-download dependencies.

## Hybrid architecture

```text
                                  ┌─> Python gRPC worker -> local Gemma via llama.cpp
client -> Java control plane -> router -> scheduler
                                  └─> Google GenAI API or compatible hosted endpoint
```

The Java control plane owns routing, deadlines, retries, circuit breaking, metrics, and endpoint health. The Python worker remains a narrow gRPC adapter around local inference. A remote endpoint is a second adapter in the control plane, not a second serving stack.

`ROUTING_POLICY=hybrid` ranks eligible endpoints using explicitly configured quality, expected latency, estimated output cost, locality, and region. Each request can also impose a latency budget, a maximum output cost, `require_local`, and a preferred region. The router returns an ordered endpoint plan; an unavailable remote endpoint falls back to the next eligible endpoint within `MAX_ATTEMPTS`.

The shared API lives in `proto/cascadeserve/v1/inference.proto`. Generated sources are build artifacts and are not committed.

For the concise project story, evidence, and claims that are safe to use on a CV, see [docs/cv-narrative.md](docs/cv-narrative.md).

## Requirements

- Java 17 or newer
- Maven 3.9 or newer
- Python 3.12

## Build the worker

```powershell
cd worker
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[dev]"
python scripts/generate_proto.py
python -m pytest
```

## Build the control plane

```powershell
cd control-plane
mvn test
```

## Run locally

Start the worker:

```powershell
cd worker
.\.venv\Scripts\Activate.ps1
python -m cascadeserve_worker.server
```

Then start the control plane in another terminal:

```powershell
cd control-plane
$env:WORKER_TARGETS="localhost:50052"
mvn exec:java
```

The public gRPC endpoint listens on port `50051`. The worker listens on `50052` by default.

The control plane accepts `MAX_IN_FLIGHT`, `REQUEST_TIMEOUT_MS`, `MAX_ATTEMPTS`, `SCHEDULER_POLICY`, `ROUTING_POLICY`, `PROMPT_WORD_THRESHOLD`, `ROUTER_MODEL_PATH`, `HEALTH_CHECK_INTERVAL_MS`, `HEALTH_CHECK_TIMEOUT_MS`, `METRICS_PORT`, `CIRCUIT_BREAKER_FAILURES`, and `CIRCUIT_BREAKER_COOLDOWN_MS`. `SCHEDULER_POLICY` supports `round_robin` and `least_in_flight`. `ROUTING_POLICY` supports `none`, `always_small`, `always_large`, `prompt_length`, `learned`, and `hybrid`.

Prometheus-format metrics are exposed on `http://localhost:8080/metrics`. Liveness and worker-aware readiness checks are available at `/healthz` and `/readyz`.

Worker targets may declare a model tier:

```powershell
$env:WORKER_TARGETS="small@localhost:50052,large@localhost:50053"
```

## Run in hybrid mode

Local-only remains the default, so CI and all existing experiments need neither a cloud account nor an API key. To add a remote model, set `ENDPOINTS` and select the hybrid router. The format is deliberately one flat configuration value:

```text
id|transport|tier|model|target|quality|latency_ms|usd_per_1k|local|region|key_env|health_url
```

`transport` is `grpc` for a local Python worker, `google` for the native Google AI Studio REST API, or `openai` for a compatible chat-completions API such as vLLM. `key_env` and `health_url` may be empty. When `key_env` is configured but missing, that remote endpoint starts as not ready; local inference is unaffected.

Use the copy-ready local/remote template in [config/endpoints.env.example](config/endpoints.env.example). Its E2B and Gemma 4 31B metadata is calibrated from the committed 50-row GSM8K run; replace it when the model revision, region, API tier, token cap, or workload changes.

The Google adapter calls `generateContent` directly with `x-goog-api-key`, `maxOutputTokens`, and Gemma's `thinkingLevel`; it defaults to `minimal` for latency-sensitive serving and supports `GOOGLE_THINKING_LEVEL=high` for a quality-oriented experiment. It also returns provider input/output token counts through the shared gRPC response. The OpenAI-compatible adapter remains available for a self-hosted vLLM endpoint. Both keep credentials outside source control.

At the time of the 2026-08-31 experiment, [Google's Gemini API pricing page](https://ai.google.dev/gemini-api/docs/pricing) listed Gemma 4 API use as free tier only, so the measured endpoint configuration uses `0.0` estimated cost. This is time-sensitive configuration, not a permanent cost claim.

Circuit breaking is endpoint-local: after `CIRCUIT_BREAKER_FAILURES` request or health failures, an endpoint is skipped for `CIRCUIT_BREAKER_COOLDOWN_MS`; the next health check or successful probe closes it. Request retries are only attempted for unavailable, deadline, or capacity failures, and never exceed both the overall request deadline and `MAX_ATTEMPTS`.

## Compare local, remote, and hybrid paths

Use the same prompt set, token ceiling, concurrency, request count, and warm-up for all three runs. The load tool records selected worker, tier, attempt count, routing policy, routing score, raw client latency, and failures. Save each configuration separately; it does not fabricate cloud cost or quality metrics.

```powershell
cd worker
.\.venv\Scripts\Activate.ps1

# Local-only: validates the reproducible path and privacy constraint.
python scripts/load_test.py `
  --workload gemma_local `
  --require-local `
  --requests 100 `
  --concurrency 4 `
  --warmup-requests 20 `
  --trials 3 `
  --output ../benchmarks/results/hybrid-local.json

# Hybrid: uses the route selected from the endpoint metadata and availability.
python scripts/load_test.py `
  --workload gemma_hybrid `
  --requests 100 `
  --concurrency 4 `
  --warmup-requests 20 `
  --trials 3 `
  --output ../benchmarks/results/hybrid-auto.json
```

For a strict remote-only comparison, run with an endpoint configuration containing only the remote endpoint. Keep provider usage beside the artifact and use observed cost, rather than the routing estimate, in any CV statement. Run the same quality dataset against every candidate model before filling its `quality` field.

## Measured hybrid snapshot

On the same seeded 50-row GSM8K sample, local Gemma 4 E2B Q4 scored 22/50 (44%) at 3.077 s p50, while Gemma 4 31B through Google AI Studio scored 47/50 (94%) at 6.042 s p50. An offline replay of an explicit request policy sent 23 latency-sensitive prompts local and 27 quality-oriented prompts remote, reaching 35/50 (70%) at 3.793 s p50. This replay combines previously captured raw outputs; it is not presented as a live end-to-end hybrid run. Full artifacts, tail latencies, protocol, and limitations are in [benchmarks/README.md](benchmarks/README.md).

Learned routing uses a logistic model exported as Java properties. Training input is JSON Lines with `prompt`, `small_correct`, and `large_correct` fields. A sample is labelled for escalation only when the large model is correct and the small model is not.

Generate those labels from real model outputs by running one small and one large worker on separate ports. The evaluator calls workers directly so routing cannot bias the labels. Dataset rows require `id`, `prompt`, `reference`, and optionally `metric`: `exact_match`, `contains`, or `last_number`.

```powershell
cd worker
python scripts/prepare_gsm8k.py
python scripts/evaluate_models.py `
  --dataset ../benchmarks/datasets/gsm8k-test-50.jsonl `
  --small-target localhost:50052 `
  --large-target localhost:50053 `
  --report-output ../benchmarks/results/model-quality.json `
  --results-output ../benchmarks/results/model-quality-raw.jsonl `
  --training-output ../benchmarks/router-training.jsonl
```

Only examples completed successfully by both models enter the training file. RPC failures still count against end-to-end accuracy in the evaluation report.

On a single 8 GB GPU, collect the tiers separately so only one Gemma 4 model occupies VRAM. Start E2B, collect its results, stop it, then repeat with E4B:

```powershell
python scripts/evaluate_tier.py `
  --dataset ../benchmarks/datasets/gsm8k-test-50.jsonl `
  --target localhost:50052 `
  --tier small `
  --output ../benchmarks/results/gemma-4-e2b.jsonl

python scripts/evaluate_tier.py `
  --dataset ../benchmarks/datasets/gsm8k-test-50.jsonl `
  --target localhost:50052 `
  --tier large `
  --output ../benchmarks/results/gemma-4-e4b.jsonl
```

The comparison step validates that both result files contain exactly the same dataset IDs before producing labels:

```powershell
python scripts/compare_tiers.py `
  --dataset ../benchmarks/datasets/gsm8k-test-50.jsonl `
  --small-results ../benchmarks/results/gemma-4-e2b.jsonl `
  --large-results ../benchmarks/results/gemma-4-e4b.jsonl `
  --report-output ../benchmarks/results/gemma-4-quality.json `
  --results-output ../benchmarks/results/gemma-4-quality-raw.jsonl `
  --training-output ../benchmarks/router-training.jsonl
```

```powershell
cd worker
python -m pip install -e ".[evaluation]"
python scripts/train_router.py `
  --input ../benchmarks/router-training.jsonl `
  --model-output ../benchmarks/models/router.properties `
  --report-output ../benchmarks/results/router-training.json
```

Verify the complete request path from a third terminal:

```powershell
cd worker
.\.venv\Scripts\Activate.ps1
python scripts/smoke_test.py
```

## Run the demo

With the local worker and control plane running, start the dependency-free demo bridge:

```powershell
cd worker
.\.venv\Scripts\Activate.ps1
python scripts/demo.py
```

Open `http://127.0.0.1:8000`. The three request contracts demonstrate quality-first API routing, a 4-second latency budget, and strict local privacy. The response view shows the selected endpoint, model tier, attempts, routing score, latency, and provider token counts. The bridge binds to loopback by default and never receives the Google API key; only the Java control plane reads it.

## Run with Docker

Build and start both services from the repository root:

```powershell
docker compose up --build
```

Run `python scripts/smoke_test.py` from the worker virtual environment while the containers are running.

Workers accept `FAIL_FIRST_N` and `SIMULATED_DELAY_MS` for deterministic fault-injection and heterogeneous-capacity experiments. Both are disabled by default.

## Transport baseline

With both services running, collect a concurrency and RPC-overhead baseline:

```powershell
cd worker
.\.venv\Scripts\Activate.ps1
python scripts/load_test.py `
  --requests 1000 `
  --concurrency 32 `
  --warmup-requests 100 `
  --trials 5 `
  --output ../benchmarks/results/echo-baseline.json
```

The warm-up is discarded. Repeated trials share one gRPC channel and report mean, median, standard deviation, minimum, and maximum while preserving each raw request. This workload uses `echo-v1`; it validates the benchmark pipeline but is not a model-performance result.

Preliminary results and their limitations are documented in `benchmarks/README.md`.

## Run Gemma

Gemma 4 is CascadeServe's primary model family. The two routing tiers are E2B and E4B. Local serving uses Google's QAT Q4_0 GGUF checkpoints rather than quantizing the BF16 checkpoints at load time:

- `google/gemma-4-E2B-it-qat-q4_0-gguf` for the fast tier
- `google/gemma-4-E4B-it-qat-q4_0-gguf` for the quality tier

The Python worker remains the gRPC boundary and delegates token generation to `llama.cpp` through its OpenAI-compatible API. This keeps scheduling and model execution independent while using an inference engine with GPU offload, continuous batching, prompt caching, and native GGUF support.

On Windows, install `llama.cpp` and start E2B on port `8081`:

```powershell
winget install --id ggml.llamacpp
llama-server `
  -hf google/gemma-4-E2B-it-qat-q4_0-gguf:Q4_0 `
  --host 127.0.0.1 `
  --port 8081 `
  -ngl 99 `
  -c 4096 `
  --jinja `
  --metrics
```

Verify the model server directly from the worker environment:

```powershell
cd worker
.\.venv\Scripts\Activate.ps1
python scripts/model_smoke_test.py `
  --backend llama_cpp `
  --server-url http://127.0.0.1:8081
```

Then expose the same model through CascadeServe's gRPC worker:

```powershell
$env:WORKER_ID="gemma-4-e2b"
$env:WORKER_PORT="50052"
$env:MODEL_BACKEND="llama_cpp"
$env:MODEL_ID="google/gemma-4-E2B-it"
$env:MODEL_SERVER_URL="http://127.0.0.1:8081"
$env:MODEL_ENABLE_THINKING="false"
python -m cascadeserve_worker.server
```

The worker health RPC checks the upstream `llama.cpp` `/health` endpoint. A stopped or unavailable model server therefore removes the worker from the control plane's healthy set.

To evaluate E4B on the same GPU, stop both E2B processes and repeat with `google/gemma-4-E4B-it-qat-q4_0-gguf:Q4_0`, `WORKER_ID=gemma-4-e4b`, and `MODEL_ID=google/gemma-4-E4B-it`. The worker and model-server ports can be reused because tier outputs are collected sequentially.

The Docker override runs both QAT checkpoints through the official CUDA `llama.cpp` server image and connects each to one worker:

```powershell
docker compose -f compose.yaml -f compose.gemma.yaml up --build
```

E2B's GGUF model is 3.35 GB and E4B's is 5.15 GB, before multimodal projection, KV cache, and runtime memory. They are practical one at a time on an 8 GB laptop GPU, but the two-tier Compose deployment needs a larger GPU or separate GPU workers. The sequential evaluation commands above are the supported path for honest quality and latency comparison on this machine.

## Project principles

- Benchmark behavior instead of claiming scale without evidence.
- Keep scheduling policy separate from transport and model execution.
- Test failure paths as first-class behavior.
- Add infrastructure only when it supports a measured experiment.

GitHub Actions runs worker tests, control-plane tests, proto generation, and Compose validation on every push and pull request. Model downloads and GPU benchmarks stay outside CI so the pipeline remains deterministic and inexpensive.
