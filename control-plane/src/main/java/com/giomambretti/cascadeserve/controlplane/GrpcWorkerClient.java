package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;
import com.giomambretti.cascadeserve.v1.GenerateResponse;
import com.giomambretti.cascadeserve.v1.HealthRequest;
import com.giomambretti.cascadeserve.v1.HealthResponse;
import com.giomambretti.cascadeserve.v1.InferenceWorkerGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class GrpcWorkerClient implements WorkerClient {
    private final String target;
    private final String tier;
    private final EndpointProfile profile;
    private final CircuitBreaker circuitBreaker;
    private final ManagedChannel channel;
    private final InferenceWorkerGrpc.InferenceWorkerStub stub;
    private final AtomicBoolean ready = new AtomicBoolean(true);
    private final AtomicInteger inFlight = new AtomicInteger();

    public GrpcWorkerClient(WorkerDefinition definition) {
        this(EndpointDefinition.legacyWorker(definition.tier() + "@" + definition.target()), 3, 10_000);
    }

    public GrpcWorkerClient(
            EndpointDefinition definition, int circuitBreakerFailures, long circuitBreakerCooldownMs) {
        this.target = definition.target();
        this.tier = definition.profile().tier();
        this.profile = definition.profile();
        this.circuitBreaker = new CircuitBreaker(circuitBreakerFailures, circuitBreakerCooldownMs);
        this.channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        this.stub = InferenceWorkerGrpc.newStub(channel);
    }

    @Override
    public String target() {
        return target;
    }

    @Override
    public String id() {
        return profile.id();
    }

    @Override
    public String tier() {
        return tier;
    }

    @Override
    public EndpointProfile profile() {
        return profile;
    }

    @Override
    public boolean isReady() {
        return ready.get() && circuitBreaker.allowsRequest();
    }

    @Override
    public int inFlight() {
        return inFlight.get();
    }

    @Override
    public void generate(GenerateRequest request, long timeoutMs, StreamObserver<GenerateResponse> observer) {
        inFlight.incrementAndGet();
        AtomicBoolean released = new AtomicBoolean();
        StreamObserver<GenerateResponse> trackingObserver = new StreamObserver<>() {
            @Override
            public void onNext(GenerateResponse response) {
                observer.onNext(response);
            }

            @Override
            public void onError(Throwable error) {
                release(released);
                observer.onError(error);
            }

            @Override
            public void onCompleted() {
                release(released);
                circuitBreaker.recordSuccess();
                observer.onCompleted();
            }
        };
        try {
            stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .generate(request, trackingObserver);
        } catch (RuntimeException error) {
            release(released);
            observer.onError(error);
        }
    }

    @Override
    public void checkHealth(long timeoutMs) {
        stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .health(HealthRequest.getDefaultInstance(), new StreamObserver<>() {
                    @Override
                    public void onNext(HealthResponse response) {
                        ready.set(response.getReady());
                        if (response.getReady()) {
                            circuitBreaker.recordSuccess();
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        ready.set(false);
                        circuitBreaker.recordFailure();
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
    }

    @Override
    public void markUnavailable() {
        ready.set(false);
        circuitBreaker.recordFailure();
    }

    @Override
    public void recordFailure() {
        circuitBreaker.recordFailure();
    }

    private void release(AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            inFlight.decrementAndGet();
        }
    }

    @Override
    public void close() {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException exception) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
