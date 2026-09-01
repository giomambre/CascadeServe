package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;

public record StaticModelRouter(String policy, String tier) implements ModelRouter {
    @Override
    public RoutingDecision route(GenerateRequest request) {
        return new RoutingDecision(tier, tier.equals("large") ? 1.0 : 0.0);
    }
}
