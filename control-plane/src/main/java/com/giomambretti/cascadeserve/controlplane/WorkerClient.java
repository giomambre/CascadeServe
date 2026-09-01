package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;
import com.giomambretti.cascadeserve.v1.GenerateResponse;
import io.grpc.stub.StreamObserver;

public interface WorkerClient extends AutoCloseable {
    default String id() {
        return target();
    }

    String target();

    String tier();

    default EndpointProfile profile() {
        return new EndpointProfile(id(), tier(), tier(), 0.5, 0, 0.0, true, "local");
    }

    boolean isReady();

    int inFlight();

    void generate(GenerateRequest request, long timeoutMs, StreamObserver<GenerateResponse> observer);

    void checkHealth(long timeoutMs);

    void markUnavailable();

    default void recordFailure() {
        markUnavailable();
    }

    @Override
    void close();
}
