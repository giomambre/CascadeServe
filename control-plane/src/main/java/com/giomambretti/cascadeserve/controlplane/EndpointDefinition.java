package com.giomambretti.cascadeserve.controlplane;

import java.util.ArrayList;
import java.util.List;

public record EndpointDefinition(
        EndpointProfile profile,
        String transport,
        String target,
        String apiKeyEnvironmentVariable,
        String healthUrl) {
    public EndpointDefinition {
        if (!transport.equals("grpc") && !transport.equals("openai") && !transport.equals("google")) {
            throw new IllegalArgumentException("endpoint transport must be grpc, openai, or google");
        }
        if (target.isBlank()) {
            throw new IllegalArgumentException("endpoint target must not be blank");
        }
    }

    public static EndpointDefinition legacyWorker(String value) {
        WorkerDefinition worker = WorkerDefinition.parse(value);
        return new EndpointDefinition(
                new EndpointProfile(
                        worker.target(), worker.tier(), worker.tier(), 0.5, 0, 0.0, true, "local"),
                "grpc",
                worker.target(),
                "",
                "");
    }

    public static List<EndpointDefinition> parseAll(String value) {
        List<EndpointDefinition> endpoints = new ArrayList<>();
        for (String raw : value.split(",")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                endpoints.add(parse(trimmed));
            }
        }
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one endpoint is required");
        }
        return List.copyOf(endpoints);
    }

    // id|transport|tier|model|target|quality|latency_ms|usd_per_1k|local|region|key_env|health_url
    public static EndpointDefinition parse(String value) {
        String[] fields = value.split("\\|", -1);
        if (fields.length != 12) {
            throw new IllegalArgumentException("endpoint must contain 12 pipe-separated fields");
        }
        return new EndpointDefinition(
                new EndpointProfile(
                        fields[0].trim(),
                        fields[2].trim(),
                        fields[3].trim(),
                        Double.parseDouble(fields[5].trim()),
                        Long.parseLong(fields[6].trim()),
                        Double.parseDouble(fields[7].trim()),
                        Boolean.parseBoolean(fields[8].trim()),
                        fields[9].trim()),
                fields[1].trim(),
                fields[4].trim(),
                fields[10].trim(),
                fields[11].trim());
    }
}
