package io.kelta.ai.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpExchange;
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
 * Local HTTP stand-in for {@code POST /v1/messages}, so tests drive the real SDK client (request
 * serialization, response parsing, SSE event decoding) without a live API call. Responses are
 * served in the order they were queued; every request body is recorded.
 */
final class AnthropicStubServer implements AutoCloseable {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private record StubResponse(String contentType, String body) {}

    private final HttpServer server;
    private final Deque<StubResponse> responses = new ArrayDeque<>();
    private final List<JsonNode> requests = new ArrayList<>();

    AnthropicStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages", this::handle);
        server.start();
    }

    AnthropicClient client() {
        return AnthropicOkHttpClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("test-key")
                .maxRetries(0)
                .build();
    }

    void enqueueJson(String body) {
        synchronized (responses) {
            responses.add(new StubResponse("application/json", body));
        }
    }

    /** Queues an SSE stream; each element is one raw event's {@code data} JSON. */
    void enqueueStream(List<String> events) {
        StringBuilder body = new StringBuilder();
        for (String data : events) {
            String type = JSON.readTree(data).get("type").asString();
            body.append("event: ").append(type).append('\n')
                    .append("data: ").append(data).append("\n\n");
        }
        synchronized (responses) {
            responses.add(new StubResponse("text/event-stream", body.toString()));
        }
    }

    List<JsonNode> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        synchronized (requests) {
            requests.add(request);
        }
        StubResponse response;
        synchronized (responses) {
            response = responses.poll();
        }
        if (response == null) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
        }
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", response.contentType());
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
