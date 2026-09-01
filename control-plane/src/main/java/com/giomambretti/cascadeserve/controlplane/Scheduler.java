package com.giomambretti.cascadeserve.controlplane;

import java.util.Optional;
import java.util.Set;

public interface Scheduler {
    String policy();

    Optional<WorkerClient> next(Set<String> excludedTargets, String requiredTier);

    default Optional<WorkerClient> next(
            Set<String> excludedTargets, String requiredTier, String requiredEndpoint) {
        return next(excludedTargets, requiredTier);
    }
}
