package com.giomambretti.cascadeserve.controlplane;

import io.grpc.Status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class MetricsRegistry {
    private static final long[] LATENCY_BUCKETS_MS = {
        5, 10, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000
    };

    private final LongAdder requests = new LongAdder();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Map<String, LongAdder> responses = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> routedTiers = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> selectedWorkers = new ConcurrentHashMap<>();
    private final LongAdder retries = new LongAdder();
    private final LongAdder[] latencyBuckets = new LongAdder[LATENCY_BUCKETS_MS.length];
    private final LongAdder latencyCount = new LongAdder();
    private final LongAdder latencyNanos = new LongAdder();

    public MetricsRegistry() {
        for (int index = 0; index < latencyBuckets.length; index++) {
            latencyBuckets[index] = new LongAdder();
        }
    }

    public void requestReceived() {
        requests.increment();
    }

    public void requestAccepted() {
        inFlight.incrementAndGet();
    }

    public void requestRejected(Status.Code code) {
        increment(responses, code.name());
    }

    public void requestCompleted(
            Status.Code code, String tier, String worker, int attempts, long elapsedNanos) {
        inFlight.decrementAndGet();
        increment(responses, code.name());
        if (!tier.isBlank()) {
            increment(routedTiers, tier);
        }
        if (!worker.isBlank()) {
            increment(selectedWorkers, worker);
        }
        if (attempts > 1) {
            retries.add(attempts - 1L);
        }
        latencyCount.increment();
        latencyNanos.add(elapsedNanos);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        for (int index = 0; index < LATENCY_BUCKETS_MS.length; index++) {
            if (elapsedMs <= LATENCY_BUCKETS_MS[index]) {
                latencyBuckets[index].increment();
            }
        }
    }

    public String render(List<WorkerClient> workers) {
        StringBuilder output = new StringBuilder();
        metric(output, "cascadeserve_requests_total", requests.sum());
        metric(output, "cascadeserve_in_flight_requests", inFlight.get());
        labelledMetrics(output, "cascadeserve_responses_total", "code", responses);
        labelledMetrics(output, "cascadeserve_routed_requests_total", "tier", routedTiers);
        labelledMetrics(output, "cascadeserve_worker_requests_total", "worker", selectedWorkers);
        metric(output, "cascadeserve_retries_total", retries.sum());
        for (int index = 0; index < LATENCY_BUCKETS_MS.length; index++) {
            double boundarySeconds = LATENCY_BUCKETS_MS[index] / 1_000.0;
            output.append("cascadeserve_request_latency_seconds_bucket{le=\"")
                    .append(boundarySeconds)
                    .append("\"} ")
                    .append(latencyBuckets[index].sum())
                    .append('\n');
        }
        output.append("cascadeserve_request_latency_seconds_bucket{le=\"+Inf\"} ")
                .append(latencyCount.sum())
                .append('\n');
        output.append("cascadeserve_request_latency_seconds_sum ")
                .append(latencyNanos.sum() / 1_000_000_000.0)
                .append('\n');
        metric(output, "cascadeserve_request_latency_seconds_count", latencyCount.sum());
        metric(output, "cascadeserve_workers_ready", workers.stream()
                .filter(WorkerClient::isReady)
                .count());
        metric(output, "cascadeserve_workers_total", workers.size());
        return output.toString();
    }

    private static void increment(Map<String, LongAdder> metrics, String label) {
        metrics.computeIfAbsent(label, ignored -> new LongAdder()).increment();
    }

    private static void metric(StringBuilder output, String name, long value) {
        output.append(name).append(' ').append(value).append('\n');
    }

    private static void labelledMetrics(
            StringBuilder output, String name, String labelName, Map<String, LongAdder> values) {
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> output.append(name)
                        .append('{')
                        .append(labelName)
                        .append("=\"")
                        .append(escapeLabel(entry.getKey()))
                        .append("\"} ")
                        .append(entry.getValue().sum())
                        .append('\n'));
    }

    private static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
