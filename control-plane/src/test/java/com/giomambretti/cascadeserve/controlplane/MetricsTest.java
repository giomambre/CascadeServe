package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;
import com.giomambretti.cascadeserve.v1.GenerateResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsTest {
    @Test
    void rendersRequestAndWorkerMetrics() {
        MetricsRegistry metrics = new MetricsRegistry();
        metrics.requestReceived();
        metrics.requestAccepted();
        metrics.requestCompleted(
                Status.Code.OK,
                "small",
                "worker-1:50052",
                2,
                TimeUnit.MILLISECONDS.toNanos(12));

        String output = metrics.render(List.of(new TestWorker(true)));

        assertTrue(output.contains("cascadeserve_requests_total 1"));
        assertTrue(output.contains("cascadeserve_in_flight_requests 0"));
        assertTrue(output.contains("cascadeserve_responses_total{code=\"OK\"} 1"));
        assertTrue(output.contains("cascadeserve_routed_requests_total{tier=\"small\"} 1"));
        assertTrue(output.contains("cascadeserve_retries_total 1"));
        assertTrue(output.contains("cascadeserve_workers_ready 1"));
    }

    @Test
    void exposesHealthReadinessAndMetrics() throws Exception {
        MetricsRegistry metrics = new MetricsRegistry();
        try (MetricsHttpServer server = new MetricsHttpServer(
                0, metrics, List.of(new TestWorker(false)))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://localhost:" + server.boundPort();

            HttpResponse<String> health = get(client, baseUrl + "/healthz");
            HttpResponse<String> readiness = get(client, baseUrl + "/readyz");
            HttpResponse<String> exportedMetrics = get(client, baseUrl + "/metrics");

            assertEquals(200, health.statusCode());
            assertEquals(503, readiness.statusCode());
            assertEquals(200, exportedMetrics.statusCode());
            assertTrue(exportedMetrics.body().contains("cascadeserve_workers_ready 0"));
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final class TestWorker implements WorkerClient {
        private final boolean ready;

        private TestWorker(boolean ready) {
            this.ready = ready;
        }

        @Override
        public String target() {
            return "worker-1:50052";
        }

        @Override
        public String tier() {
            return "small";
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public int inFlight() {
            return 0;
        }

        @Override
        public void generate(
                GenerateRequest request,
                long timeoutMs,
                StreamObserver<GenerateResponse> observer) {
        }

        @Override
        public void checkHealth(long timeoutMs) {
        }

        @Override
        public void markUnavailable() {
        }

        @Override
        public void close() {
        }
    }
}
