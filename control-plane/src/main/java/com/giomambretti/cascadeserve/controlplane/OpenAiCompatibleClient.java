package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;
import com.giomambretti.cascadeserve.v1.GenerateResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OpenAiCompatibleClient implements WorkerClient {
    private static final Pattern CONTENT = Pattern.compile("\\\"content\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static final Pattern PROMPT_TOKENS = Pattern.compile("\\\"prompt_tokens\\\"\\s*:\\s*(\\d+)");
    private static final Pattern OUTPUT_TOKENS = Pattern.compile("\\\"completion_tokens\\\"\\s*:\\s*(\\d+)");

    private final EndpointDefinition definition;
    private final HttpClient httpClient;
    private final CircuitBreaker circuitBreaker;
    private final AtomicBoolean ready;
    private final AtomicInteger inFlight = new AtomicInteger();

    public OpenAiCompatibleClient(
            EndpointDefinition definition, int circuitBreakerFailures, long circuitBreakerCooldownMs) {
        this.definition = definition;
        this.httpClient = HttpClient.newHttpClient();
        this.circuitBreaker = new CircuitBreaker(circuitBreakerFailures, circuitBreakerCooldownMs);
        this.ready = new AtomicBoolean(hasCredentials());
    }

    @Override
    public String id() {
        return definition.profile().id();
    }

    @Override
    public String target() {
        return definition.target();
    }

    @Override
    public String tier() {
        return definition.profile().tier();
    }

    @Override
    public EndpointProfile profile() {
        return definition.profile();
    }

    @Override
    public boolean isReady() {
        return ready.get() && circuitBreaker.allowsRequest();
    }

    @Override
    public int inFlight() {
        return inFlight.get();
    }

    @Override
    public void generate(GenerateRequest request, long timeoutMs, StreamObserver<GenerateResponse> observer) {
        if (!isReady()) {
            observer.onError(Status.UNAVAILABLE.withDescription("remote endpoint is not ready").asRuntimeException());
            return;
        }
        inFlight.incrementAndGet();
        long startedAt = System.nanoTime();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(definition.target()))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request)));
        String apiKey = apiKey();
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    inFlight.decrementAndGet();
                    if (error != null) {
                        ready.set(false);
                        observer.onError(Status.UNAVAILABLE
                                .withDescription("remote API request failed")
                                .withCause(error)
                                .asRuntimeException());
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        observer.onError(statusFor(response.statusCode())
                                .withDescription("remote API returned HTTP " + response.statusCode())
                                .asRuntimeException());
                        return;
                    }
                    String output = parseContent(response.body());
                    if (output == null) {
                        circuitBreaker.recordFailure();
                        observer.onError(Status.INTERNAL
                                .withDescription("remote API response did not contain choices[0].message.content")
                                .asRuntimeException());
                        return;
                    }
                    circuitBreaker.recordSuccess();
                    observer.onNext(GenerateResponse.newBuilder()
                            .setRequestId(request.getRequestId())
                            .setOutput(output)
                            .setWorkerId(id())
                            .setModelId(profile().modelId())
                            .setWorkerLatencyMs((System.nanoTime() - startedAt) / 1_000_000)
                            .setInputTokens(parseInt(PROMPT_TOKENS, response.body()))
                            .setOutputTokens(parseInt(OUTPUT_TOKENS, response.body()))
                            .build());
                    observer.onCompleted();
                });
    }

    @Override
    public void checkHealth(long timeoutMs) {
        if (!hasCredentials()) {
            ready.set(false);
            return;
        }
        if (definition.healthUrl().isBlank()) {
            ready.set(true);
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(definition.healthUrl()))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, error) -> {
                    boolean healthy = error == null && response.statusCode() >= 200 && response.statusCode() < 300;
                    ready.set(healthy);
                    if (healthy) {
                        circuitBreaker.recordSuccess();
                    } else {
                        circuitBreaker.recordFailure();
                    }
                });
    }

    @Override
    public void markUnavailable() {
        circuitBreaker.recordFailure();
    }

    @Override
    public void recordFailure() {
        circuitBreaker.recordFailure();
    }

    @Override
    public void close() {
    }

    private boolean hasCredentials() {
        return definition.apiKeyEnvironmentVariable().isBlank() || !apiKey().isBlank();
    }

    private String apiKey() {
        return definition.apiKeyEnvironmentVariable().isBlank()
                ? ""
                : System.getenv().getOrDefault(definition.apiKeyEnvironmentVariable(), "");
    }

    private String requestBody(GenerateRequest request) {
        int maxTokens = request.getMaxNewTokens() > 0 ? request.getMaxNewTokens() : 256;
        return "{\"model\":\"" + escape(profile().modelId())
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + escape(request.getPrompt()) + "\"}],\"max_tokens\":" + maxTokens + "}";
    }

    private static String parseContent(String response) {
        Matcher matcher = CONTENT.matcher(response);
        return matcher.find() ? unescape(matcher.group(1)) : null;
    }

    private static int parseInt(Pattern pattern, String response) {
        Matcher matcher = pattern.matcher(response);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static Status statusFor(int code) {
        if (code == 401 || code == 403) {
            return Status.UNAUTHENTICATED;
        }
        if (code == 429) {
            return Status.RESOURCE_EXHAUSTED;
        }
        return code >= 500 ? Status.UNAVAILABLE : Status.FAILED_PRECONDITION;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
