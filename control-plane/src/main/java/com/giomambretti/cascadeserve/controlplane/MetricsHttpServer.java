package com.giomambretti.cascadeserve.controlplane;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MetricsHttpServer implements AutoCloseable {
    private final int port;
    private final MetricsRegistry metrics;
    private final List<WorkerClient> workers;
    private HttpServer server;

    public MetricsHttpServer(int port, MetricsRegistry metrics, List<WorkerClient> workers) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("metrics port is outside the valid range");
        }
        this.port = port;
        this.metrics = metrics;
        this.workers = List.copyOf(workers);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", exchange -> respond(
                exchange,
                200,
                metrics.render(workers),
                "text/plain; version=0.0.4; charset=utf-8"));
        server.createContext("/healthz", exchange -> respond(
                exchange, 200, "ok\n", "text/plain; charset=utf-8"));
        server.createContext("/readyz", exchange -> {
            boolean ready = workers.stream().anyMatch(WorkerClient::isReady);
            respond(
                    exchange,
                    ready ? 200 : 503,
                    ready ? "ready\n" : "no ready workers\n",
                    "text/plain; charset=utf-8");
        });
        server.start();
    }

    int boundPort() {
        if (server == null) {
            throw new IllegalStateException("metrics server has not started");
        }
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(
            HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, content.length);
        try (var response = exchange.getResponseBody()) {
            response.write(content);
        }
    }
}
