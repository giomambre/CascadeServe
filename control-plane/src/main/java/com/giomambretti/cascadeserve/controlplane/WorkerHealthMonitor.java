package com.giomambretti.cascadeserve.controlplane;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WorkerHealthMonitor implements AutoCloseable {
    private final List<WorkerClient> workers;
    private final long intervalMs;
    private final long timeoutMs;
    private final ScheduledExecutorService executor;

    public WorkerHealthMonitor(List<WorkerClient> workers, long intervalMs, long timeoutMs) {
        if (intervalMs <= 0 || timeoutMs <= 0) {
            throw new IllegalArgumentException("health check timings must be positive");
        }
        this.workers = List.copyOf(workers);
        this.intervalMs = intervalMs;
        this.timeoutMs = timeoutMs;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "worker-health-monitor");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::checkWorkers, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void checkWorkers() {
        workers.forEach(worker -> worker.checkHealth(timeoutMs));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
