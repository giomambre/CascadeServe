package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;
import com.giomambretti.cascadeserve.v1.GenerateResponse;
import com.sun.net.httpserver.HttpServer;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleClientTest {
    @Test
    void sendsOpenAiCompatibleRequestAndConvertsTheResponse() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"choices\":[{\"message\":{\"content\":\"hello from remote\"}}],"
                    + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            EndpointDefinition endpoint = EndpointDefinition.parse(
                    "remote-gemma|openai|premium|google/gemma-4-31b-it|http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/v1/chat/completions|0.82|1800|0.004|false|europe-west1||");
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(endpoint, 2, 1_000);
            RecordingObserver observer = new RecordingObserver();

            client.generate(GenerateRequest.newBuilder()
                    .setRequestId("request-1")
                    .setPrompt("say hello")
                    .setMaxNewTokens(32)
                    .build(), 2_000, observer);

            assertTrue(observer.done.await(2, TimeUnit.SECONDS));
            assertEquals("hello from remote", observer.response.getOutput());
            assertEquals("remote-gemma", observer.response.getWorkerId());
            assertEquals("google/gemma-4-31b-it", observer.response.getModelId());
            assertEquals(9, observer.response.getInputTokens());
            assertEquals(4, observer.response.getOutputTokens());
            assertTrue(requestBody.get().contains("\"model\":\"google/gemma-4-31b-it\""));
            assertTrue(requestBody.get().contains("\"content\":\"say hello\""));
        } finally {
            server.stop(0);
        }
    }

    private static final class RecordingObserver implements StreamObserver<GenerateResponse> {
        private final CountDownLatch done = new CountDownLatch(1);
        private GenerateResponse response;

        @Override
        public void onNext(GenerateResponse response) {
            this.response = response;
        }

        @Override
        public void onError(Throwable error) {
            done.countDown();
        }

        @Override
        public void onCompleted() {
            done.countDown();
        }
    }
}
