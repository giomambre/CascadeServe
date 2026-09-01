package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.CascadeServiceGrpc;
import com.giomambretti.cascadeserve.v1.GenerateRequest;
import com.giomambretti.cascadeserve.v1.GenerateResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CascadeGrpcService extends CascadeServiceGrpc.CascadeServiceImplBase {
    private final Scheduler scheduler;
    private final ModelRouter router;
    private final Semaphore inFlightRequests;
    private final long requestTimeoutMs;
    private final int maxAttempts;
    private final MetricsRegistry metrics;

    public CascadeGrpcService(
            Scheduler scheduler, int maxInFlight, long requestTimeoutMs, int maxAttempts) {
        this(
                scheduler,
                Routers.create("none", 1),
                maxInFlight,
                requestTimeoutMs,
                maxAttempts,
                new MetricsRegistry());
    }

    public CascadeGrpcService(
            Scheduler scheduler,
            ModelRouter router,
            int maxInFlight,
            long requestTimeoutMs,
            int maxAttempts) {
        this(
                scheduler,
                router,
                maxInFlight,
                requestTimeoutMs,
                maxAttempts,
                new MetricsRegistry());
    }

    public CascadeGrpcService(
            Scheduler scheduler,
            ModelRouter router,
            int maxInFlight,
            long requestTimeoutMs,
            int maxAttempts,
            MetricsRegistry metrics) {
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        if (requestTimeoutMs <= 0) {
            throw new IllegalArgumentException("requestTimeoutMs must be positive");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.scheduler = scheduler;
        this.router = router;
        this.inFlightRequests = new Semaphore(maxInFlight);
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.metrics = metrics;
    }

    @Override
    public void generate(GenerateRequest request, StreamObserver<GenerateResponse> responseObserver) {
        metrics.requestReceived();
        if (request.getPrompt().isBlank()) {
            metrics.requestRejected(Status.Code.INVALID_ARGUMENT);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("prompt must not be blank")
                    .asRuntimeException());
            return;
        }
        if (!inFlightRequests.tryAcquire()) {
            metrics.requestRejected(Status.Code.RESOURCE_EXHAUSTED);
            responseObserver.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription("control plane is at its in-flight request limit")
                    .asRuntimeException());
            return;
        }
        metrics.requestAccepted();

        long startedAt = System.nanoTime();
        long deadlineAt = startedAt + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMs);
        AtomicBoolean released = new AtomicBoolean();
        GenerateRequest routedRequest = request.getRequestId().isBlank()
                ? request.toBuilder().setRequestId(UUID.randomUUID().toString()).build()
                : request;
        RoutingDecision routingDecision = router.route(routedRequest);

        dispatch(
                routedRequest,
                responseObserver,
                startedAt,
                deadlineAt,
                released,
                new HashSet<>(),
                routingDecision,
                1);
    }

    private void dispatch(
            GenerateRequest request,
            StreamObserver<GenerateResponse> responseObserver,
            long startedAt,
            long deadlineAt,
            AtomicBoolean released,
            Set<String> attemptedTargets,
            RoutingDecision routingDecision,
            int attempt) {
        long remainingNanos = deadlineAt - System.nanoTime();
        if (remainingNanos <= 0) {
            completeRequest(
                    released,
                    Status.Code.DEADLINE_EXCEEDED,
                    routingDecision.tier(),
                    "",
                    attempt,
                    startedAt);
            responseObserver.onError(Status.DEADLINE_EXCEEDED
                    .withDescription("request deadline exceeded before dispatch")
                    .asRuntimeException());
            return;
        }

        String requiredEndpoint = routingDecision.endpointForAttempt(attempt);
        WorkerClient worker = scheduler.next(
                        attemptedTargets,
                        requiredEndpoint.isBlank() ? routingDecision.tier() : "",
                        requiredEndpoint)
                .orElse(null);
        if (worker == null) {
            if (!requiredEndpoint.isBlank() && attempt < maxAttempts
                    && attempt < routingDecision.endpointIds().size()) {
                dispatch(
                        request,
                        responseObserver,
                        startedAt,
                        deadlineAt,
                        released,
                        attemptedTargets,
                        routingDecision,
                        attempt + 1);
                return;
            }
            completeRequest(
                    released,
                    Status.Code.UNAVAILABLE,
                    routingDecision.tier(),
                    "",
                    attempt,
                    startedAt);
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("no ready worker is available")
                    .asRuntimeException());
            return;
        }
        attemptedTargets.add(worker.target());
        long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        AtomicBoolean responseReceived = new AtomicBoolean();

        worker.generate(request, remainingMs, new StreamObserver<>() {
            @Override
            public void onNext(GenerateResponse response) {
                responseReceived.set(true);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                responseObserver.onNext(response.toBuilder()
                        .setTotalLatencyMs(elapsedMs)
                        .setAttempts(attempt)
                        .setSelectedTier(worker.tier())
                        .setRoutingPolicy(router.policy())
                        .setRoutingScore(routingDecision.score())
                        .build());
            }

            @Override
            public void onError(Throwable error) {
                Status.Code code = Status.fromThrowable(error).getCode();
                if (isRetryable(code)) {
                    worker.recordFailure();
                }
                if (!responseReceived.get() && isRetryable(code) && attempt < maxAttempts) {
                    dispatch(
                            request,
                            responseObserver,
                            startedAt,
                            deadlineAt,
                            released,
                            attemptedTargets,
                            routingDecision,
                            attempt + 1);
                    return;
                }
                completeRequest(
                        released,
                        code,
                        routingDecision.tier(),
                        worker.target(),
                        attempt,
                        startedAt);
                responseObserver.onError(error);
            }

            @Override
            public void onCompleted() {
                completeRequest(
                        released,
                        Status.Code.OK,
                        worker.tier(),
                        worker.id(),
                        attempt,
                        startedAt);
                responseObserver.onCompleted();
            }
        });
    }

    private boolean isRetryable(Status.Code code) {
        return code == Status.Code.UNAVAILABLE
                || code == Status.Code.DEADLINE_EXCEEDED
                || code == Status.Code.RESOURCE_EXHAUSTED;
    }

    private void completeRequest(
            AtomicBoolean released,
            Status.Code code,
            String tier,
            String worker,
            int attempts,
            long startedAt) {
        if (released.compareAndSet(false, true)) {
            inFlightRequests.release();
            metrics.requestCompleted(
                    code, tier, worker, attempts, System.nanoTime() - startedAt);
        }
    }
}
