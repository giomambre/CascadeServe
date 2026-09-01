package com.giomambretti.cascadeserve.controlplane;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class RoundRobinScheduler implements Scheduler {
    private final List<WorkerClient> workers;
    private final AtomicLong cursor = new AtomicLong();

    public RoundRobinScheduler(List<WorkerClient> workers) {
        if (workers.isEmpty()) {
            throw new IllegalArgumentException("at least one worker is required");
        }
        this.workers = List.copyOf(workers);
        this.workers.forEach(Objects::requireNonNull);
    }

    @Override
    public String policy() {
        return "round_robin";
    }

    @Override
    public Optional<WorkerClient> next(Set<String> excludedTargets, String requiredTier) {
        return next(excludedTargets, requiredTier, "");
    }

    @Override
    public Optional<WorkerClient> next(
            Set<String> excludedTargets, String requiredTier, String requiredEndpoint) {
        for (int attempt = 0; attempt < workers.size(); attempt++) {
            int index = (int) Math.floorMod(cursor.getAndIncrement(), workers.size());
            WorkerClient worker = workers.get(index);
            if (worker.isReady()
                    && !excludedTargets.contains(worker.target())
                    && matchesTier(worker, requiredTier)
                    && matchesEndpoint(worker, requiredEndpoint)) {
                return Optional.of(worker);
            }
        }
        return Optional.empty();
    }

    private boolean matchesTier(WorkerClient worker, String requiredTier) {
        return requiredTier.isEmpty() || worker.tier().equals(requiredTier);
    }

    private boolean matchesEndpoint(WorkerClient worker, String requiredEndpoint) {
        return requiredEndpoint.isEmpty() || worker.id().equals(requiredEndpoint);
    }
}
