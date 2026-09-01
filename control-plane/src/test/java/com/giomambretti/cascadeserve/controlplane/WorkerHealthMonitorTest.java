package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;
import com.giomambretti.cascadeserve.v1.GenerateResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerHealthMonitorTest {
    @Test
    void checksRegisteredWorkers() throws InterruptedException {
        ProbeWorker worker = new ProbeWorker();

        try (WorkerHealthMonitor monitor = new WorkerHealthMonitor(List.of(worker), 20, 10)) {
            monitor.start();
            assertTrue(worker.checked.await(1, TimeUnit.SECONDS));
        }
    }

    private static final class ProbeWorker implements WorkerClient {
        private final CountDownLatch checked = new CountDownLatch(1);

        @Override
        public String target() {
            return "probe";
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
            checked.countDown();
        }

        @Override
        public void markUnavailable() {
        }

        @Override
        public void close() {
        }
    }
}
