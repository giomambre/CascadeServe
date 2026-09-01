package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;
import com.giomambretti.cascadeserve.v1.GenerateResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoundRobinSchedulerTest {
    @Test
    void cyclesThroughWorkersInOrder() {
        WorkerClient first = new FakeWorker("first");
        WorkerClient second = new FakeWorker("second");
        RoundRobinScheduler scheduler = new RoundRobinScheduler(List.of(first, second));

        assertEquals("first", scheduler.next(Set.of(), "").orElseThrow().target());
        assertEquals("second", scheduler.next(Set.of(), "").orElseThrow().target());
        assertEquals("first", scheduler.next(Set.of(), "").orElseThrow().target());
    }

    @Test
    void skipsUnavailableAndExcludedWorkers() {
        WorkerClient unavailable = new FakeWorker("unavailable", false);
        WorkerClient excluded = new FakeWorker("excluded");
        WorkerClient ready = new FakeWorker("ready");
        RoundRobinScheduler scheduler = new RoundRobinScheduler(
                List.of(unavailable, excluded, ready));

        assertEquals("ready", scheduler.next(Set.of("excluded"), "").orElseThrow().target());
    }

    @Test
    void rejectsAnEmptyWorkerList() {
        assertThrows(IllegalArgumentException.class, () -> new RoundRobinScheduler(List.of()));
    }

    private record FakeWorker(String target, boolean isReady) implements WorkerClient {
        private FakeWorker(String target) {
            this(target, true);
        }

        @Override
        public String tier() {
            return "default";
        }

        @Override
        public int inFlight() {
            return 0;
        }

        @Override
        public void generate(
                GenerateRequest request, long timeoutMs, StreamObserver<GenerateResponse> observer) {
            throw new UnsupportedOperationException();
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
