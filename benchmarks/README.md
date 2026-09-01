# Benchmarks

Benchmark artifacts are committed only when their workload and environment are explicit. Raw request samples are retained so summary statistics can be audited.

## Hybrid routing protocol

The hybrid control plane records `worker_id`, `selected_tier`, `routing_policy`, `routing_score`, and `attempts` for every successful request. Run the same fixed workload three times: `--require-local` for the local baseline, a remote-only endpoint configuration for the API baseline, and both endpoints with `ROUTING_POLICY=hybrid` for the routing result. Use three or more trials and discarded warm-up traffic.

Only populate endpoint quality, expected latency, and cost metadata from a matching benchmark: same model revision, region, prompt distribution, token cap, and API tier. The router's per-token cost field is an estimate used for request-time decisions; provider token usage and billing data are separate observed evidence. The Google adapter now records API token counts for future evaluations. The 2026-08-31 quality run predates that field and is retained rather than rerun merely to manufacture usage data.

## Quality and routing evaluation

`worker/scripts/evaluate_models.py` evaluates small and large workers independently on the same JSON Lines dataset. It records raw outputs, correctness, client latency, worker latency, and failures before exporting eligible router-training labels. This keeps model quality measurement separate from the control-plane policy being evaluated.

For memory-constrained hardware, `evaluate_tier.py` collects one tier at a time and `compare_tiers.py` joins the artifacts only after checking complete, unique dataset coverage. This permits E2B and E4B evaluation on one GPU without running both models concurrently or treating RPC failures as quality labels.

Supported task metrics are normalized exact match, normalized substring containment, and last-number equality. Dataset provenance and task-specific scoring limitations must accompany any committed quality result.

The synthetic file under `worker/tests/fixtures` exists only to test the training pipeline. It is not an evaluation dataset and its classifier metrics must never be reported as project performance.

## Local E2B versus API-hosted Gemma 4 31B

Both endpoints were evaluated through CascadeServe on the same seeded 50-row GSM8K sample with last-number scoring and a 512-token ceiling. All 100 RPCs succeeded.

| Endpoint | Correct | Accuracy | p50 (ms) | p95 (ms) | p99 (ms) |
|---|---:|---:|---:|---:|---:|
| Local Gemma 4 E2B Q4 | 22/50 | 44% | 3,077.019 | 3,132.799 | 9,447.035 |
| Google AI Studio Gemma 4 31B | 47/50 | 94% | 6,041.683 | 10,794.931 | 14,938.673 |

The paired outcomes were 22 solved by both models, 25 only by 31B, none only by E2B, and 3 by neither. This small seeded sample estimates the routing trade-off; it is not a general model benchmark. The clean results are in `results/gemma-4-e2b-gsm8k-50.jsonl`, `results/gemma-4-31b-gsm8k-50.jsonl`, and `results/gemma-4-e2b-vs-31b-gsm8k-50.json`.

Two failed experimental configurations are preserved as diagnostics. A 64-token run truncated final answers and scored 6%; a 5-second control-plane deadline produced 17 failures and mixed local fallbacks. Neither is used as model-quality evidence.

## Hybrid policy replay

`replay_hybrid_policy.py` applies a deterministic request contract to the captured local and remote outputs. Half the request IDs are assigned a 4,000 ms latency budget; because the measured remote expectation is 6,042 ms, those requests use E2B and quality-oriented requests use 31B.

| Policy | Accuracy | Local routes | Remote routes | p50 (ms) | p95 (ms) | p99 (ms) |
|---|---:|---:|---:|---:|---:|---:|
| Local-only | 44% | 50 | 0 | 3,077.019 | 3,132.799 | 9,447.035 |
| Remote-only | 94% | 0 | 50 | 6,041.683 | 10,794.931 | 14,938.673 |
| Hybrid policy replay | 70% | 23 | 27 | 3,792.713 | 8,874.075 | 9,712.093 |

This is an offline policy replay, not a live hybrid load test: it reuses measured per-example latency and output from the two baseline runs. It makes the policy auditable and reproducible but does not measure queueing, concurrent contention, or network changes under mixed live traffic. The full decision trace is in `results/gemma-4-hybrid-policy-replay-gsm8k-50.json`.

## Short-response local/API pilot

For the fixed 16-token prompt `Reply with exactly: CascadeServe benchmark ready`, the local E2B run completed 30/30 requests at 12.58 requests/s with 75.234 ms mean p50. The API-preferred run completed 30/30 at 1.037 requests/s with 961.136 ms mean p50: 29 requests used Gemma 4 31B and one transient API failure fell back to E2B on attempt two. This is useful reliability evidence, but the mixed fallback means it is not labelled a strict remote-only benchmark. Raw trials are in `results/hybrid-local-pilot.json` and `results/hybrid-remote-pilot.json`.

## Calibrated live routing check

After loading the measured endpoint metadata, a live unconstrained request selected API-hosted 31B with score 0.3858. A 4,000 ms latency budget and a separate `require_local` request both selected E2B with score 0.2023. All three completed on their first attempt; the remote response also carried provider token counts through gRPC. `results/hybrid-calibrated-smoke.json` preserves the trace. These single short requests validate policy behavior and telemetry, not model throughput.

## Gemma 4 E2B local inference

The first real-model benchmark runs Google's `gemma-4-E2B-it-qat-q4_0-gguf` through a Vulkan `llama.cpp` server with four slots and 4,096-token contexts. Requests traverse the complete local path: Python load client, Java control plane, health-aware scheduler, Python gRPC worker, and the model server on an RTX 4070 Laptop GPU with 8 GB VRAM.

The fixed workload asks for the exact short response `CascadeServe ready` with a 16-token ceiling. Each configuration discards warm-up traffic and runs three measured trials. All 750 measured requests succeeded.

| Concurrency | Measured requests | Mean throughput ± SD (req/s) | Mean p50 ± SD (ms) | Mean p95 ± SD (ms) | Mean p99 ± SD (ms) |
|---:|---:|---:|---:|---:|---:|
| 1 | 150 | 12.883 ± 0.046 | 77.525 ± 0.395 | 89.296 ± 0.580 | 92.023 ± 1.558 |
| 4 | 300 | 22.290 ± 2.756 | 171.273 ± 23.230 | 200.542 ± 12.209 | 476.353 ± 457.748 |
| 8 | 300 | 24.163 ± 0.855 | 328.130 ± 13.243 | 352.468 ± 21.096 | 356.631 ± 18.613 |

Concurrency 8 increased mean request throughput by 87.6% over serial execution, while mean p50 latency rose from 77.5 ms to 328.1 ms. The concurrency-4 p99 distribution contains a visible one-second outlier and is intentionally reported rather than removed. These numbers describe short deterministic generation on one laptop, not arbitrary-prompt serving capacity or tokens per second.

Environment: Windows 11, Python 3.12.10, Java control plane, `llama.cpp` b10689 Vulkan build, and an NVIDIA RTX 4070 Laptop GPU. Full configuration, per-trial summaries, and raw request latencies are stored in `results/gemma-4-e2b-q4-windows*.json`.

## Gemma 4 E2B/E4B quality cascade

On the seeded 50-row GSM8K sample, both tiers completed every RPC. E2B reached 22/50 (44%) with p50 3.077 s; E4B reached 34/50 (68%) with p50 5.487 s. The paired outcomes were 21 solved by both, 1 only by E2B, 13 only by E4B, and 15 by neither. An oracle cascade that escalates only the 13 E2B misses recovered 35/50 (70%) while escalating 26% of examples.

The first learned prompt-only router fit on these 50 labels is intentionally retained as a development result, not a CV claim: its held-out accuracy was 40%, precision 20%, recall 33%, and ROC-AUC 0.286. This is a useful failure signal: prompt length alone cannot reliably predict which GSM8K examples E2B will miss, so the next routing iteration should use calibrated first-pass uncertainty or a larger training set. Raw tier outputs, paired outcomes, and labels are in `results/gemma-4-*-gsm8k-50*` and `router-training.jsonl`.

## Preliminary scheduler comparison

The current controlled workload uses two `echo-v1` workers:

- `worker-fast`: no artificial delay
- `worker-slow`: 20 ms artificial delay
- 2,000 requests
- concurrency 32
- one local Windows machine

| Policy | Throughput (req/s) | p95 (ms) | p99 (ms) | Fast-worker requests |
|---|---:|---:|---:|---:|
| Round robin | 762.81 | 82.521 | 83.600 | 1,000 / 2,000 |
| Least in flight | 3,234.25 | 40.723 | 43.530 | 1,780 / 2,000 |

These are single-run engineering results. They validate the experimental setup but are not yet stable CV claims. The load tool now supports discarded warm-up requests and repeated trials on one gRPC channel; these preliminary artifacts will be superseded after collecting fixed-revision aggregate statistics.

The SmolLM2 result validates the generic Transformers adapter on CPU. It is not Gemma validation and is not a substitute for the planned Gemma 4 E2B/E4B quantized quality and CUDA benchmarks.

## Repeated scheduler comparison

The repeated workload keeps the same heterogeneous echo workers and concurrency, discards 200 warm-up requests, then runs five trials of 2,000 measured requests per policy over one reused gRPC channel. All 20,000 measured requests completed successfully.

| Policy | Mean throughput ± SD (req/s) | Mean p95 ± SD (ms) | Mean p99 ± SD (ms) | Mean fast-worker share |
|---|---:|---:|---:|---:|
| Round robin | 770.63 ± 2.52 | 82.734 ± 0.387 | 83.973 ± 1.293 | 50.0% |
| Least in flight | 3,232.73 ± 70.51 | 40.968 ± 0.190 | 41.863 ± 0.414 | 88.5% |

On this controlled local workload, least-in-flight delivered 4.19× the mean throughput and reduced mean p95 latency by 50.5% relative to round robin. This measures scheduling behavior with deterministic worker delays; it is not a claim about model inference throughput.

Environment: Windows 11, Python 3.12.10, AMD64 processor with 24 logical CPUs, Java control plane, two Python workers, concurrency 32. Full trial summaries and raw request records are stored in `results/*-repeated.json`.
