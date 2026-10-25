package io.kelta.ai.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Stand-in for {@code POST /v1/messages} on a loopback port, so tests drive the real anthropic-java
 * client (request serialization, JSON and SSE response parsing) without a live API call. Responses
 * are served in the order they were queued; every request body is recorded.
 */
final class MockAnthropicApi implements AutoCloseable {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private record Reply(String contentType, String body) {}

    private final HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<JsonNode> requests = new ArrayList<>();

    MockAnthropicApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            Reply reply;
            synchronized (this) {
                requests.add(JSON.readTree(requestBody));
                reply = replies.poll();
            }
            if (reply == null) {
                reply = new Reply("application/json",
                        "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"no reply queued\"}}");
                exchange.getResponseHeaders().add("Content-Type", reply.contentType());
                exchange.sendResponseHeaders(500, 0);
            } else {
                exchange.getResponseHeaders().add("Content-Type", reply.contentType());
                exchange.sendResponseHeaders(200, 0);
            }
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply.body().getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
    }

    AnthropicClient client() {
        return AnthropicOkHttpClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("test-key")
                .maxRetries(0)
                .build();
    }

    synchronized void replyJson(String body) {
        replies.add(new Reply("application/json", body));
    }

    /** Queues a streamed reply; each element is one {@code event: <type>} with its JSON data. */
    synchronized void replyStream(String... eventJson) {
        StringBuilder sse = new StringBuilder();
        for (String json : eventJson) {
            String type = JSON.readTree(json).get("type").asString();
            sse.append("event: ").append(type).append('\n')
                    .append("data: ").append(json).append("\n\n");
        }
        replies.add(new Reply("text/event-stream", sse.toString()));
    }

    synchronized List<JsonNode> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
