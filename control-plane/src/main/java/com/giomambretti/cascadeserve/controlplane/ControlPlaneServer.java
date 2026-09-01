package com.giomambretti.cascadeserve.controlplane;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ControlPlaneServer implements AutoCloseable {
    private final Server server;
    private final List<WorkerClient> workers;
    private final WorkerHealthMonitor healthMonitor;
    private final MetricsHttpServer metricsServer;

    public ControlPlaneServer(
            int port,
            List<String> workerTargets,
            int maxInFlight,
            long requestTimeoutMs,
            int maxAttempts,
            String schedulerPolicy,
            String routingPolicy,
            int promptWordThreshold,
            String routerModelPath,
            long healthCheckIntervalMs,
            long healthCheckTimeoutMs,
            int metricsPort,
            int circuitBreakerFailures,
            long circuitBreakerCooldownMs) {
        this(
                port,
                workerTargets.stream()
                        .map(EndpointDefinition::legacyWorker)
                        .toArray(EndpointDefinition[]::new),
                maxInFlight,
                requestTimeoutMs,
                maxAttempts,
                schedulerPolicy,
                routingPolicy,
                promptWordThreshold,
                routerModelPath,
                healthCheckIntervalMs,
                healthCheckTimeoutMs,
                metricsPort,
                circuitBreakerFailures,
                circuitBreakerCooldownMs);
    }

    public void start() throws IOException {
        server.start();
        healthMonitor.start();
        metricsServer.start();
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    @Override
    public void close() {
        server.shutdown();
        metricsServer.close();
        healthMonitor.close();
        workers.forEach(WorkerClient::close);
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException exception) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("CONTROL_PLANE_PORT", "50051"));
        int maxInFlight = Integer.parseInt(System.getenv().getOrDefault("MAX_IN_FLIGHT", "64"));
        long requestTimeoutMs = Long.parseLong(System.getenv().getOrDefault("REQUEST_TIMEOUT_MS", "5000"));
        int maxAttempts = Integer.parseInt(System.getenv().getOrDefault("MAX_ATTEMPTS", "2"));
        String schedulerPolicy = System.getenv().getOrDefault("SCHEDULER_POLICY", "round_robin");
        String routingPolicy = System.getenv().getOrDefault("ROUTING_POLICY", "none");
        int promptWordThreshold = Integer.parseInt(
                System.getenv().getOrDefault("PROMPT_WORD_THRESHOLD", "24"));
        String routerModelPath = System.getenv().getOrDefault("ROUTER_MODEL_PATH", "");
        long healthCheckIntervalMs = Long.parseLong(
                System.getenv().getOrDefault("HEALTH_CHECK_INTERVAL_MS", "1000"));
        long healthCheckTimeoutMs = Long.parseLong(
                System.getenv().getOrDefault("HEALTH_CHECK_TIMEOUT_MS", "500"));
        int metricsPort = Integer.parseInt(
                System.getenv().getOrDefault("METRICS_PORT", "8080"));
        int circuitBreakerFailures = Integer.parseInt(
                System.getenv().getOrDefault("CIRCUIT_BREAKER_FAILURES", "3"));
        long circuitBreakerCooldownMs = Long.parseLong(
                System.getenv().getOrDefault("CIRCUIT_BREAKER_COOLDOWN_MS", "10000"));
        List<String> targets = Arrays.stream(System.getenv()
                        .getOrDefault("WORKER_TARGETS", "localhost:50052")
                        .split(","))
                .map(String::trim)
                .filter(target -> !target.isEmpty())
                .toList();

        String configuredEndpoints = System.getenv().getOrDefault("ENDPOINTS", "");
        if (!configuredEndpoints.isBlank()) {
            List<EndpointDefinition> endpoints = EndpointDefinition.parseAll(configuredEndpoints);
            ControlPlaneServer controlPlane = new ControlPlaneServer(
                    port,
                    endpoints.toArray(EndpointDefinition[]::new),
                    maxInFlight,
                    requestTimeoutMs,
                    maxAttempts,
                    schedulerPolicy,
                    routingPolicy,
                    promptWordThreshold,
                    routerModelPath,
                    healthCheckIntervalMs,
                    healthCheckTimeoutMs,
                    metricsPort,
                    circuitBreakerFailures,
                    circuitBreakerCooldownMs);
            Runtime.getRuntime().addShutdownHook(new Thread(controlPlane::close));
            controlPlane.start();
            controlPlane.awaitTermination();
            return;
        }

        ControlPlaneServer controlPlane = new ControlPlaneServer(
                port,
                targets,
                maxInFlight,
                requestTimeoutMs,
                maxAttempts,
                schedulerPolicy,
                routingPolicy,
                promptWordThreshold,
                routerModelPath,
                healthCheckIntervalMs,
                healthCheckTimeoutMs,
                metricsPort,
                circuitBreakerFailures,
                circuitBreakerCooldownMs);
        Runtime.getRuntime().addShutdownHook(new Thread(controlPlane::close));
        controlPlane.start();
        controlPlane.awaitTermination();
    }

    private ControlPlaneServer(
            int port,
            EndpointDefinition[] endpointDefinitions,
            int maxInFlight,
            long requestTimeoutMs,
            int maxAttempts,
            String schedulerPolicy,
            String routingPolicy,
            int promptWordThreshold,
            String routerModelPath,
            long healthCheckIntervalMs,
            long healthCheckTimeoutMs,
            int metricsPort,
            int circuitBreakerFailures,
            long circuitBreakerCooldownMs) {
        List<EndpointDefinition> endpoints = List.of(endpointDefinitions);
        this.workers = endpoints.stream()
                .map(endpoint -> EndpointClients.create(
                        endpoint, circuitBreakerFailures, circuitBreakerCooldownMs))
                .toList();
        this.healthMonitor = new WorkerHealthMonitor(
                workers, healthCheckIntervalMs, healthCheckTimeoutMs);
        MetricsRegistry metrics = new MetricsRegistry();
        this.metricsServer = new MetricsHttpServer(metricsPort, metrics, workers);
        this.server = ServerBuilder.forPort(port)
                .addService(new CascadeGrpcService(
                        Schedulers.create(schedulerPolicy, workers),
                        Routers.createForWorkers(
                                routingPolicy,
                                promptWordThreshold,
                                routerModelPath,
                                workers),
                        maxInFlight,
                        requestTimeoutMs,
                        maxAttempts,
                        metrics))
                .build();
    }
}
