# CascadeServe — CV narrative

## One-line description

Built a hybrid Gemma serving gateway that routes each inference request across local and API-hosted endpoints under explicit quality, latency, cost, privacy, and availability constraints.

## CV bullets

- Built a Java gRPC control plane and Python inference workers for hybrid Gemma 4 serving, with deadline-aware scheduling, backpressure, health checks, retry, circuit breaking, fallback, Prometheus metrics, and Docker-based local execution.
- Benchmarked local Gemma 4 E2B Q4 against API-hosted Gemma 4 31B on the same 50 GSM8K prompts: 44% versus 94% accuracy, with p50 latency of 3.08 s versus 6.04 s; retained raw outputs and tail-latency artifacts for auditability.
- Implemented constraint-aware routing over measured quality and latency, estimated cost, locality, region, and endpoint health; an offline mixed-policy replay reached 70% accuracy at 3.79 s p50 by routing 23/50 requests locally and 27/50 remotely.

The third bullet must retain the words “offline” or “replay”: it is not a live mixed-traffic result.

## What the project demonstrates

The main engineering question is not “which model is largest?” It is how to deliver the right model under a request contract. A privacy-sensitive or latency-bounded request can stay on local E2B; a quality-oriented request can use hosted 31B when the measured gain justifies its added latency. If the API is unavailable, the control plane tries the next eligible endpoint within the request deadline.

The implementation stays deliberately small:

- Java owns endpoint metadata, routing, scheduling, deadlines, health, and reliability.
- Python is the local gRPC model boundary and delegates generation to `llama.cpp`.
- A native Google `generateContent` adapter serves Gemma 4 through AI Studio; a separate OpenAI-compatible adapter supports self-hosted services such as vLLM.
- Endpoint metadata is explicit configuration derived from committed benchmark artifacts.
- The shared response exposes the selected endpoint, policy, score, attempts, latency, and provider token usage.

## Evidence currently available

| Area | Evidence |
|---|---|
| Local performance | Repeated end-to-end E2B trials up to concurrency 8; raw requests in `benchmarks/results/` |
| Local/API quality | Same 50-row GSM8K sample: E2B 44%, Google AI Studio Gemma 4 31B 94% |
| Hybrid trade-off | Offline policy replay: 70% accuracy, 3.79 s p50, 23 local and 27 remote routes |
| API reliability | Short pilot includes automatic attempt-two fallback from the API to local E2B |
| Reliability logic | Tests cover retry eligibility, ordered fallback, dynamic health, timeout, and circuit-breaker cooldown |
| Reproducibility | 25 Java and 34 Python tests pass without model downloads or cloud credentials; remote servers are simulated in tests |
| Live demo | Quality-first selected API-hosted 31B; a 4-second request budget selected local E2B; both returned the complete route trace |

## Limits to state in an interview

- Fifty seeded GSM8K rows are enough for a portfolio experiment, not a statistically complete model evaluation.
- Hybrid numbers are an offline replay of captured outputs. They exclude mixed-load queueing and changing network conditions.
- The 2026-08-31 Google AI Studio Gemma 4 tier was free, so the configured routing cost is zero. Pricing is time-sensitive and must be recalibrated.
- The prompt-only learned router was a useful negative result: its 50-label development split was too small and prompt length did not predict E2B errors reliably. The production path therefore uses explicit, inspectable request contracts and measured endpoint metadata.

## Interview walkthrough

Start with the local system boundary, then show why adding a larger API model creates a real quality/latency decision. Walk through one unrestricted request, one 4-second latency-budget request, and one `require_local` request. Finish with the fallback trace and the distinction between measured baselines, offline policy replay, and claims still requiring a live mixed-load experiment.
