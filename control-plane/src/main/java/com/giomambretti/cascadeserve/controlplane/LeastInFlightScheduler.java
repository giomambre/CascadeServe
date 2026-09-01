package com.giomambretti.cascadeserve.controlplane;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class LeastInFlightScheduler implements Scheduler {
    private final List<WorkerClient> workers;
    private final AtomicLong cursor = new AtomicLong();

    public LeastInFlightScheduler(List<WorkerClient> workers) {
        if (workers.isEmpty()) {
            throw new IllegalArgumentException("at least one worker is required");
        }
        this.workers = List.copyOf(workers);
        this.workers.forEach(Objects::requireNonNull);
    }

    @Override
    public String policy() {
        return "least_in_flight";
    }

    @Override
    public Optional<WorkerClient> next(Set<String> excludedTargets, String requiredTier) {
        return next(excludedTargets, requiredTier, "");
    }

    @Override
    public Optional<WorkerClient> next(
            Set<String> excludedTargets, String requiredTier, String requiredEndpoint) {
        long start = cursor.getAndIncrement();
        WorkerClient selected = null;
        int lowestLoad = Integer.MAX_VALUE;

        for (int offset = 0; offset < workers.size(); offset++) {
            int index = (int) Math.floorMod(start + offset, workers.size());
            WorkerClient worker = workers.get(index);
            if (!worker.isReady()
                    || excludedTargets.contains(worker.target())
                    || (!requiredTier.isEmpty() && !worker.tier().equals(requiredTier))
                    || (!requiredEndpoint.isEmpty() && !worker.id().equals(requiredEndpoint))) {
                continue;
            }
            if (worker.inFlight() < lowestLoad) {
                selected = worker;
                lowestLoad = worker.inFlight();
            }
        }
        return Optional.ofNullable(selected);
    }
}
