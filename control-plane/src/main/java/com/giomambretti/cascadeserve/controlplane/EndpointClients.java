package com.giomambretti.cascadeserve.controlplane;

public final class EndpointClients {
    private EndpointClients() {
    }

    public static WorkerClient create(
            EndpointDefinition definition, int circuitBreakerFailures, long circuitBreakerCooldownMs) {
        return switch (definition.transport()) {
            case "grpc" -> new GrpcWorkerClient(
                    definition, circuitBreakerFailures, circuitBreakerCooldownMs);
            case "openai" -> new OpenAiCompatibleClient(
                    definition, circuitBreakerFailures, circuitBreakerCooldownMs);
            case "google" -> new GoogleGenAiClient(
                    definition, circuitBreakerFailures, circuitBreakerCooldownMs);
            default -> throw new IllegalArgumentException("unsupported endpoint transport: " + definition.transport());
        };
    }
}
