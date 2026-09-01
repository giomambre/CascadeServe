package com.giomambretti.cascadeserve.controlplane;

public record WorkerDefinition(String tier, String target) {
    public WorkerDefinition {
        if (tier.isBlank() || target.isBlank()) {
            throw new IllegalArgumentException("worker tier and target must not be blank");
        }
    }

    public static WorkerDefinition parse(String value) {
        String trimmed = value.trim();
        int separator = trimmed.indexOf('@');
        if (separator < 0) {
            return new WorkerDefinition("default", trimmed);
        }
        return new WorkerDefinition(
                trimmed.substring(0, separator).trim(),
                trimmed.substring(separator + 1).trim());
    }
}
