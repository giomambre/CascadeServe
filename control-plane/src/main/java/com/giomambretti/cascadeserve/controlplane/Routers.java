package com.giomambretti.cascadeserve.controlplane;

import java.nio.file.Path;
import java.util.List;

public final class Routers {
    private Routers() {
    }

    public static ModelRouter create(String policy, int promptWordThreshold) {
        return create(policy, promptWordThreshold, null);
    }

    public static ModelRouter create(
            String policy, int promptWordThreshold, String learnedModelPath) {
        return switch (policy) {
            case "none" -> new StaticModelRouter("none", "");
            case "always_small" -> new StaticModelRouter("always_small", "small");
            case "always_large" -> new StaticModelRouter("always_large", "large");
            case "prompt_length" -> new PromptLengthRouter(promptWordThreshold);
            case "learned" -> {
                if (learnedModelPath == null || learnedModelPath.isBlank()) {
                    throw new IllegalArgumentException("ROUTER_MODEL_PATH is required for learned routing");
                }
                yield new LearnedModelRouter(Path.of(learnedModelPath));
            }
            default -> throw new IllegalArgumentException("unsupported routing policy: " + policy);
        };
    }

    public static ModelRouter create(
            String policy,
            int promptWordThreshold,
            String learnedModelPath,
            List<EndpointProfile> endpoints) {
        if (policy.equals("hybrid")) {
            return new HybridModelRouter(endpoints);
        }
        return create(policy, promptWordThreshold, learnedModelPath);
    }

    public static ModelRouter createForWorkers(
            String policy,
            int promptWordThreshold,
            String learnedModelPath,
            List<WorkerClient> workers) {
        if (policy.equals("hybrid")) {
            return HybridModelRouter.fromWorkers(workers);
        }
        return create(policy, promptWordThreshold, learnedModelPath);
    }
}
