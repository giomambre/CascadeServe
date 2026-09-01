package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;

public final class PromptLengthRouter implements ModelRouter {
    private final int wordThreshold;

    public PromptLengthRouter(int wordThreshold) {
        if (wordThreshold <= 0) {
            throw new IllegalArgumentException("wordThreshold must be positive");
        }
        this.wordThreshold = wordThreshold;
    }

    @Override
    public String policy() {
        return "prompt_length";
    }

    @Override
    public RoutingDecision route(GenerateRequest request) {
        int words = request.getPrompt().trim().split("\\s+").length;
        boolean useLarge = words > wordThreshold;
        return new RoutingDecision(useLarge ? "large" : "small", useLarge ? 1.0 : 0.0);
    }
}
