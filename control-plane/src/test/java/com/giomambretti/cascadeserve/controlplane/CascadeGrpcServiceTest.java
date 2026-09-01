package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;
import com.giomambretti.cascadeserve.v1.GenerateResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CascadeGrpcServiceTest {
    @Test
    void assignsAnIdAndForwardsTheWorkerResponse() {
        ReplyingWorker worker = new ReplyingWorker();
        CascadeGrpcService service = new CascadeGrpcService(
                new RoundRobinScheduler(List.of(worker)), 4, 2_000, 2);
        RecordingObserver observer = new RecordingObserver();

        service.generate(GenerateRequest.newBuilder().setPrompt("hello").build(), observer);

        assertNotNull(observer.response);
        assertFalse(observer.response.getRequestId().isBlank());
        assertEquals("worker-test", observer.response.getWorkerId());
        assertEquals("hello", observer.response.getOutput());
        assertTrue(worker.lastTimeoutMs > 0 && worker.lastTimeoutMs <= 2_000);
        assertEquals(1, observer.response.getAttempts());
        assertEquals("default", observer.response.getSelectedTier());
        assertEquals("none", observer.response.getRoutingPolicy());
        assertNotNull(observer.completed);
    }

    @Test
    void rejectsBlankPromptsBeforeSelectingAWorker() {
        CascadeGrpcService service = new CascadeGrpcService(
                new RoundRobinScheduler(List.of(new ReplyingWorker())), 4, 2_000, 2);
        RecordingObserver observer = new RecordingObserver();

        service.generate(GenerateRequest.newBuilder().setPrompt("  ").build(), observer);

        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(observer.error).getCode());
    }

    @Test
    void rejectsRequestsAboveTheInFlightLimit() {
        CascadeGrpcService service = new CascadeGrpcService(
                new RoundRobinScheduler(List.of(new HoldingWorker())), 1, 2_000, 2);

        service.generate(GenerateRequest.newBuilder().setPrompt("first").build(), new RecordingObserver());
        RecordingObserver rejected = new RecordingObserver();
        service.generate(GenerateRequest.newBuilder().setPrompt("second").build(), rejected);

        assertEquals(Status.Code.RESOURCE_EXHAUSTED, Status.fromThrowable(rejected.error).getCode());
    }

    @Test
    void releasesCapacityWhenARequestCompletes() {
        CascadeGrpcService service = new CascadeGrpcService(
                new RoundRobinScheduler(List.of(new ReplyingWorker())), 1, 2_000, 2);
        RecordingObserver first = new RecordingObserver();
        RecordingObserver second = new RecordingObserver();

        service.generate(GenerateRequest.newBuilder().setPrompt("first").build(), first);
        service.generate(GenerateRequest.newBuilder().setPrompt("second").build(), second);

        assertNotNull(first.response);
        assertNotNull(second.response);
    }

    @Test
    void retriesTransientFailuresOnAnotherWorker() {
        FailingWorker failingWorker = new FailingWorker();
        CascadeGrpcService service = new CascadeGrpcService(
                new RoundRobinScheduler(List.of(failingWorker, new ReplyingWorker())),
                4,
                2_000,
                2);
        RecordingObserver observer = new RecordingObserver();

        service.generate(GenerateRequest.newBuilder().setPrompt("retry me").build(), observer);

        assertNotNull(observer.response);
        assertEquals("worker-test", observer.response.getWorkerId());
        assertEquals(2, observer.response.getAttempts());
        assertFalse(failingWorker.isReady());
    }

    @Test
    void fallsBackToTheNextHybridEndpointAfterARemoteFailure() {
        FailingWorker remote = new FailingWorker();
        CascadeGrpcService service = new CascadeGrpcService(
                new RoundRobinScheduler(List.of(remote, new ReplyingWorker())),
                new ModelRouter() {
                    @Override
                    public String policy() {
                        return "hybrid";
                    }

                    @Override
                    public RoutingDecision route(GenerateRequest request) {
                        return new RoutingDecision(
                                "premium", 0.8, List.of("failing-worker", "worker-test"));
                    }
                },
                4,
                2_000,
                2);
        RecordingObserver observer = new RecordingObserver();

        service.generate(GenerateRequest.newBuilder().setPrompt("fallback please").build(), observer);

        assertNotNull(observer.response);
        assertEquals("worker-test", observer.response.getWorkerId());
        assertEquals(2, observer.response.getAttempts());
        assertEquals("hybrid", observer.response.getRoutingPolicy());
    }

    private static final class ReplyingWorker implements WorkerClient {
        private long lastTimeoutMs;

        @Override
        public String target() {
            return "worker-test";
        }

        @Override
        public String tier() {
            return "default";
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public int inFlight() {
            return 0;
        }

        @Override
        public void generate(
                GenerateRequest request, long timeoutMs, StreamObserver<GenerateResponse> observer) {
            lastTimeoutMs = timeoutMs;
            observer.onNext(GenerateResponse.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setOutput(request.getPrompt())
                    .setWorkerId("worker-test")
                    .setModelId("fake-model")
                    .build());
            observer.onCompleted();
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

    private static final class HoldingWorker implements WorkerClient {
        @Override
        public String target() {
            return "holding-worker";
        }

        @Override
        public String tier() {
            return "default";
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public int inFlight() {
            return 0;
        }

        @Override
        public void generate(
                GenerateRequest request, long timeoutMs, StreamObserver<GenerateResponse> observer) {
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

    private static final class FailingWorker implements WorkerClient {
        private boolean ready = true;

        @Override
        public String target() {
            return "failing-worker";
        }

        @Override
        public String tier() {
            return "default";
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
                GenerateRequest request, long timeoutMs, StreamObserver<GenerateResponse> observer) {
            observer.onError(Status.UNAVAILABLE.asRuntimeException());
        }

        @Override
        public void checkHealth(long timeoutMs) {
        }

        @Override
        public void markUnavailable() {
            ready = false;
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingObserver implements StreamObserver<GenerateResponse> {
        private GenerateResponse response;
        private Throwable error;
        private Boolean completed;

        @Override
        public void onNext(GenerateResponse response) {
            this.response = response;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }
    }
}
