package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;
import com.giomambretti.cascadeserve.v1.GenerateResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingTest {
    @Test
    void parsesTieredAndLegacyWorkerDefinitions() {
        assertEquals(
                new WorkerDefinition("small", "localhost:50052"),
                WorkerDefinition.parse("small@localhost:50052"));
        assertEquals(
                new WorkerDefinition("default", "localhost:50052"),
                WorkerDefinition.parse("localhost:50052"));
    }

    @Test
    void routesLongPromptsToTheLargeTier() {
        PromptLengthRouter router = new PromptLengthRouter(3);

        assertEquals(
                "small",
                router.route(GenerateRequest.newBuilder().setPrompt("one two").build()).tier());
        assertEquals(
                "large",
                router.route(
                                GenerateRequest.newBuilder()
                                        .setPrompt("one two three four")
                                        .build())
                        .tier());
    }

    @Test
    void learnedRouterLoadsExportedWeights(@TempDir Path directory) throws IOException {
        Path model = directory.resolve("router.properties");
        Files.writeString(model, String.join("\n",
                "intercept=-2.0",
                "threshold=0.5",
                "weight.word_count=1.0",
                "weight.char_count=0.0",
                "weight.digit_count=0.0",
                "weight.symbol_count=0.0",
                "weight.newline_count=0.0"));
        LearnedModelRouter router = new LearnedModelRouter(model);

        RoutingDecision shortDecision = router.route(
                GenerateRequest.newBuilder().setPrompt("one").build());
        RoutingDecision longDecision = router.route(
                GenerateRequest.newBuilder().setPrompt("one two three").build());

        assertEquals("small", shortDecision.tier());
        assertEquals("large", longDecision.tier());
    }

    @Test
    void promptFeaturesMatchTheTrainingContract() {
        assertEquals(
                new PromptFeatures(5, 17, 2, 3, 1),
                PromptFeatures.from("Solve (2 + 3)\nnow"));
    }

    @Test
    void schedulerRestrictsSelectionToTheRequestedTier() {
        RoundRobinScheduler scheduler = new RoundRobinScheduler(List.of(
                new TieredWorker("small-worker", "small"),
                new TieredWorker("large-worker", "large")));

        assertEquals(
                "large-worker",
                scheduler.next(Set.of(), "large").orElseThrow().target());
    }

    @Test
    void parsesAnExplicitRemoteEndpointDefinition() {
        EndpointDefinition endpoint = EndpointDefinition.parse(
                "gemma-31b|openai|premium|google/gemma-4-31b-it|https://api.example/v1/chat/completions"
                        + "|0.82|1800|0.004|false|europe-west1|GOOGLE_API_KEY|");

        assertEquals("gemma-31b", endpoint.profile().id());
        assertEquals("openai", endpoint.transport());
        assertEquals("google/gemma-4-31b-it", endpoint.profile().modelId());
        assertTrue(!endpoint.profile().local());
    }

    @Test
    void hybridRouterHonorsLocalAndCostConstraints() {
        HybridModelRouter router = new HybridModelRouter(List.of(
                new EndpointProfile("gemma-e2b", "fast", "google/gemma-4-e2b-it", 0.44, 350, 0.0, true, "local"),
                new EndpointProfile("gemma-31b", "premium", "google/gemma-4-31b-it", 0.82, 1_800, 0.004, false, "europe-west1")));

        RoutingDecision remotePreferred = router.route(GenerateRequest.newBuilder()
                .setPrompt("Solve this")
                .setMaxNewTokens(128)
                .build());
        RoutingDecision localOnly = router.route(GenerateRequest.newBuilder()
                .setPrompt("Solve this")
                .setMaxNewTokens(128)
                .setRequireLocal(true)
                .build());
        RoutingDecision costCapped = router.route(GenerateRequest.newBuilder()
                .setPrompt("Solve this")
                .setMaxNewTokens(128)
                .setMaxCostUsd(0.0001)
                .build());

        assertEquals("gemma-31b", remotePreferred.endpointForAttempt(1));
        assertEquals("gemma-e2b", localOnly.endpointForAttempt(1));
        assertEquals("gemma-e2b", costCapped.endpointForAttempt(1));
    }

    @Test
    void hybridRouterSkipsUnavailableEndpointsBeforeSelectingAPlan() {
        HybridModelRouter router = HybridModelRouter.fromWorkers(List.of(
                new ProfiledWorker(
                        new EndpointProfile("remote", "premium", "gemma-4-31b-it", 0.9, 500, 0.0, false, "global"),
                        false),
                new ProfiledWorker(
                        new EndpointProfile("local", "fast", "gemma-4-e2b-it", 0.4, 100, 0.0, true, "local"),
                        true)));

        RoutingDecision decision = router.route(GenerateRequest.newBuilder()
                .setPrompt("available endpoint only")
                .build());

        assertEquals("local", decision.endpointForAttempt(1));
        assertEquals("fast", decision.tier());
    }

    private record TieredWorker(String target, String tier) implements WorkerClient {
        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public int inFlight() {
            return 0;
        }

        @Override
        public void generate(
                GenerateRequest request, long timeoutMs, StreamObserver<GenerateResponse> observer) {
        }

        @Override
        public void checkHealth(long timeoutMs) {
        }

        @Override
        public void markUnavailable() {
        }

        @Override
        public void close() {
        }
    }

    private record ProfiledWorker(EndpointProfile profile, boolean ready) implements WorkerClient {
        @Override
        public String id() {
            return profile.id();
        }

        @Override
        public String target() {
            return profile.id();
        }

        @Override
        public String tier() {
            return profile.tier();
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public int inFlight() {
            return 0;
        }

        @Override
        public void generate(
                GenerateRequest request, long timeoutMs, StreamObserver<GenerateResponse> observer) {
        }

        @Override
        public void checkHealth(long timeoutMs) {
        }

        @Override
        public void markUnavailable() {
        }

        @Override
        public void close() {
        }
    }
}
