package com.giomambretti.cascadeserve.controlplane;

import java.util.List;

public final class Schedulers {
    private Schedulers() {
    }

    public static Scheduler create(String policy, List<WorkerClient> workers) {
        return switch (policy) {
            case "round_robin" -> new RoundRobinScheduler(workers);
            case "least_in_flight" -> new LeastInFlightScheduler(workers);
            default -> throw new IllegalArgumentException("unsupported scheduler policy: " + policy);
        };
    }
}
