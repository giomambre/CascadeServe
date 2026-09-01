package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;

import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class HybridModelRouter implements ModelRouter {
    private final List<EndpointCandidate> endpoints;

    public HybridModelRouter(List<EndpointProfile> endpoints) {
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one endpoint profile is required");
        }
        this.endpoints = endpoints.stream()
                .map(endpoint -> new EndpointCandidate(endpoint, () -> true))
                .toList();
    }

    public static HybridModelRouter fromWorkers(List<WorkerClient> workers) {
        if (workers.isEmpty()) {
            throw new IllegalArgumentException("at least one worker is required");
        }
        return new HybridModelRouter(workers.stream()
                .map(worker -> new EndpointCandidate(worker.profile(), worker::isReady))
                .toList(), true);
    }

    private HybridModelRouter(List<EndpointCandidate> endpoints, boolean liveEndpoints) {
        this.endpoints = List.copyOf(endpoints);
    }

    @Override
    public String policy() {
        return "hybrid";
    }

    @Override
    public RoutingDecision route(GenerateRequest request) {
        List<ScoredEndpoint> candidates = endpoints.stream()
                .filter(endpoint -> endpoint.ready().getAsBoolean())
                .map(EndpointCandidate::profile)
                .filter(endpoint -> !request.getRequireLocal() || endpoint.local())
                .filter(endpoint -> withinLatencyBudget(endpoint, request))
                .filter(endpoint -> withinCostBudget(endpoint, request))
                .map(endpoint -> new ScoredEndpoint(endpoint, score(endpoint, request)))
                .sorted(Comparator.comparingDouble(ScoredEndpoint::score).reversed())
                .toList();
        if (candidates.isEmpty()) {
            return new RoutingDecision("unavailable", 0.0, List.of("__no_eligible_endpoint__"));
        }
        ScoredEndpoint selected = candidates.get(0);
        return new RoutingDecision(
                selected.endpoint().tier(),
                selected.score(),
                candidates.stream().map(candidate -> candidate.endpoint().id()).toList());
    }

    private boolean withinLatencyBudget(EndpointProfile endpoint, GenerateRequest request) {
        return request.getLatencyBudgetMs() <= 0
                || endpoint.expectedLatencyMs() <= request.getLatencyBudgetMs();
    }

    private boolean withinCostBudget(EndpointProfile endpoint, GenerateRequest request) {
        return request.getMaxCostUsd() <= 0.0
                || estimatedCost(endpoint, request) <= request.getMaxCostUsd();
    }

    private double score(EndpointProfile endpoint, GenerateRequest request) {
        double latencyPenalty = endpoint.expectedLatencyMs() / 10_000.0;
        double costPenalty = estimatedCost(endpoint, request) * 10.0;
        double regionBonus = request.getPreferredRegion().isBlank()
                || endpoint.region().equalsIgnoreCase(request.getPreferredRegion()) ? 0.05 : 0.0;
        double localBonus = endpoint.local() ? 0.02 : 0.0;
        return endpoint.qualityScore() - latencyPenalty - costPenalty + regionBonus + localBonus;
    }

    private double estimatedCost(EndpointProfile endpoint, GenerateRequest request) {
        int tokens = request.getMaxNewTokens() > 0 ? request.getMaxNewTokens() : 256;
        return endpoint.costPerThousandTokensUsd() * tokens / 1_000.0;
    }

    private record ScoredEndpoint(EndpointProfile endpoint, double score) {
    }

    private record EndpointCandidate(EndpointProfile profile, BooleanSupplier ready) {
    }
}
