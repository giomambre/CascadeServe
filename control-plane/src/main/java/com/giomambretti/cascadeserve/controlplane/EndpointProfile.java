package com.giomambretti.cascadeserve.controlplane;

public record EndpointProfile(
        String id,
        String tier,
        String modelId,
        double qualityScore,
        long expectedLatencyMs,
        double costPerThousandTokensUsd,
        boolean local,
        String region) {
    public EndpointProfile {
        if (id.isBlank() || tier.isBlank() || modelId.isBlank() || region.isBlank()) {
            throw new IllegalArgumentException("endpoint profile fields must not be blank");
        }
        if (qualityScore < 0.0 || qualityScore > 1.0
                || expectedLatencyMs < 0 || costPerThousandTokensUsd < 0.0) {
            throw new IllegalArgumentException("endpoint profile metrics must be non-negative");
        }
    }
}
