package com.giomambretti.cascadeserve.controlplane;

import java.util.List;

public record RoutingDecision(String tier, double score, List<String> endpointIds) {
    public RoutingDecision {
        endpointIds = List.copyOf(endpointIds);
    }

    public RoutingDecision(String tier, double score) {
        this(tier, score, List.of());
    }

    public String endpointForAttempt(int attempt) {
        if (attempt <= 0 || attempt > endpointIds.size()) {
            return "";
        }
        return endpointIds.get(attempt - 1);
    }
}
