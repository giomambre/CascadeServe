package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;
import com.giomambretti.cascadeserve.v1.GenerateResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeastInFlightSchedulerTest {
    @Test
    void selectsTheReadyWorkerWithTheLowestLoad() {
        WorkerClient busy = new LoadedWorker("busy", 4, true);
        WorkerClient idle = new LoadedWorker("idle", 0, true);
        WorkerClient unavailable = new LoadedWorker("unavailable", 0, false);
        LeastInFlightScheduler scheduler = new LeastInFlightScheduler(
                List.of(busy, idle, unavailable));

        assertEquals("idle", scheduler.next(Set.of(), "").orElseThrow().target());
    }

    @Test
    void rotatesTieBreakingAcrossEquallyLoadedWorkers() {
        WorkerClient first = new LoadedWorker("first", 0, true);
        WorkerClient second = new LoadedWorker("second", 0, true);
        LeastInFlightScheduler scheduler = new LeastInFlightScheduler(List.of(first, second));

        assertEquals("first", scheduler.next(Set.of(), "").orElseThrow().target());
        assertEquals("second", scheduler.next(Set.of(), "").orElseThrow().target());
    }

    private record LoadedWorker(String target, int inFlight, boolean isReady)
            implements WorkerClient {
        @Override
        public String tier() {
            return "default";
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
}
