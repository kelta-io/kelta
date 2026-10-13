package io.kelta.runtime.module.integration.handlers;

import com.sun.net.httpserver.HttpServer;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the handler against a real HTTP server on loopback. The guard allows
 * {@code 127.0.0.0/8} for most tests; {@link #loopbackRefusedByDefault()} shows the default
 * refuses it.
 */
@DisplayName("HttpCalloutActionHandler")
class HttpCalloutActionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private String base;
    private HttpCalloutActionHandler handler;

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{\"status\":\"ok\"}";
    private volatile String location;
    private volatile long delayMillis;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastMethod.set(exchange.getRequestMethod());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastHeader.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            byte[] bytes = responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        handler = new HttpCalloutActionHandler(objectMapper, new OutboundUrlGuard("127.0.0.0/8"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("Should have correct action type key")
    void shouldHaveCorrectKey() {
        assertEquals("HTTP_CALLOUT", handler.getActionTypeKey());
    }

    @Test
    @DisplayName("Should execute GET request and capture response")
    void shouldExecuteGetRequest() {
        ActionResult result = handler.execute(makeContext("""
            {"url": "%s/data", "method": "GET"}
            """.formatted(base)));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals(200, result.outputData().get("statusCode"));
        assertEquals(base + "/data", result.outputData().get("url"));
        assertEquals("{\"status\":\"ok\"}", result.outputData().get("responseBody"));
        assertEquals(Map.of("status", "ok"), result.outputData().get("responseData"));
    }

    @Test
    @DisplayName("Should default method to GET")
    void shouldDefaultToGet() {
        ActionResult result = handler.execute(makeContext("""
            {"url": "%s"}
            """.formatted(base)));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals("GET", lastMethod.get());
    }

    @Test
    @DisplayName("Should send method, headers and body")
    void shouldSendMethodHeadersAndBody() {
        ActionResult result = handler.execute(makeContext("""
            {"url": "%s/orders", "method": "POST", "headers": {"X-Api-Key": "k1"}, "body": {"a": 1}}
            """.formatted(base)));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals("POST", lastMethod.get());
        assertEquals("k1", lastHeader.get());
        assertEquals("{\"a\":1}", lastBody.get());
    }

    @Test
    @DisplayName("Should fail when URL is missing")
    void shouldFailWhenUrlMissing() {
        ActionResult result = handler.execute(makeContext("{}"));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("URL"));
    }

    @Test
    @DisplayName("Should fail on invalid HTTP method")
    void shouldFailOnInvalidMethod() {
        ActionResult result = handler.execute(makeContext("""
            {"url": "%s", "method": "INVALID"}
            """.formatted(base)));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("Invalid HTTP method"));
    }

    @Test
    @DisplayName("Should capture response variable")
    void shouldCaptureResponseVariable() {
        ActionResult result = handler.execute(makeContext("""
            {"url": "%s", "responseVariable": "apiResult"}
            """.formatted(base)));
        assertTrue(result.successful(), result.errorMessage());
        assertEquals("apiResult", result.outputData().get("responseVariable"));
    }

    @Test
    @DisplayName("Should fail the step on a 4xx/5xx status")
    void shouldFailOnErrorStatus() {
        status = 503;

        ActionResult result = handler.execute(makeContext("""
            {"url": "%s"}
            """.formatted(base)));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("503"));
    }

    @Test
    @DisplayName("With the default guard, a loopback URL is refused and nothing is sent")
    void loopbackRefusedByDefault() {
        HttpCalloutActionHandler guarded = new HttpCalloutActionHandler(objectMapper, new OutboundUrlGuard(""));

        ActionResult result = guarded.execute(makeContext("""
            {"url": "%s/data"}
            """.formatted(base)));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("refused"));
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("A link-local metadata address is refused even when loopback is allowed")
    void metadataAddressRefused() {
        ActionResult result = handler.execute(makeContext("""
            {"url": "http://169.254.169.254/latest/meta-data/"}
            """));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("non-public"));
    }

    @Test
    @DisplayName("A redirect to a non-public address is refused")
    void redirectToNonPublicRefused() {
        status = 302;
        responseBody = null;
        location = "http://10.0.0.1/internal";

        ActionResult result = handler.execute(makeContext("""
            {"url": "%s/start"}
            """.formatted(base)));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("non-public"));
        assertEquals(1, requests.get());
    }

    @Test
    @DisplayName("An oversized response is captured truncated and not parsed")
    void oversizedResponseCapped() {
        responseBody = "[\"" + "x".repeat(HttpCalloutActionHandler.MAX_RESPONSE_BYTES) + "\"]";

        ActionResult result = handler.execute(makeContext("""
            {"url": "%s/big"}
            """.formatted(base)));

        assertTrue(result.successful(), result.errorMessage());
        String captured = (String) result.outputData().get("responseBody");
        assertTrue(captured.endsWith("... [truncated]"));
        assertEquals(HttpCalloutActionHandler.MAX_RESPONSE_SIZE + "... [truncated]".length(), captured.length());
        assertFalse(result.outputData().containsKey("responseData"));
    }

    @Test
    @DisplayName("The request deadline is honoured")
    void deadlineHonoured() {
        delayMillis = 2_000;
        HttpCalloutActionHandler quick = new HttpCalloutActionHandler(objectMapper, new OutboundHttpClient(
                new OutboundUrlGuard("127.0.0.0/8"),
                new OutboundHttpClient.Limits(Duration.ofSeconds(1), Duration.ofMillis(300), 1024, 0)));

        ActionResult result = quick.execute(makeContext("""
            {"url": "%s/slow"}
            """.formatted(base)));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("timed out"));
    }

    private ActionContext makeContext(String config) {
        return ActionContext.builder()
            .tenantId("t1").collectionId("c1").collectionName("orders").recordId("r1")
            .data(Map.of()).userId("user-1")
            .actionConfigJson(config).workflowRuleId("wf1").executionLogId("log1").build();
    }
}
