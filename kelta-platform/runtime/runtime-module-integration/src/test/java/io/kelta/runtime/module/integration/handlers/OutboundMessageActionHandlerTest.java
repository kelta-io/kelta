package io.kelta.runtime.module.integration.handlers;

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
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the handler against a real HTTP server on loopback. The guard allows
 * {@code 127.0.0.0/8} for most tests; {@link #loopbackRefusedByDefault()} shows the default
 * refuses it.
 */
@DisplayName("OutboundMessageActionHandler")
class OutboundMessageActionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LoopbackServer server;
    private OutboundMessageActionHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        server = new LoopbackServer();
        server.responseBody = "ok";
        server.contentType = "text/plain";
        handler = new OutboundMessageActionHandler(objectMapper, new OutboundUrlGuard("127.0.0.0/8"));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("Should have correct action type key")
    void shouldHaveCorrectKey() {
        assertEquals("OUTBOUND_MESSAGE", handler.getActionTypeKey());
    }

    @Test
    @DisplayName("Should send POST request with body template")
    void shouldSendPostWithBodyTemplate() {
        ActionResult result = handler.execute(makeContext("""
            {"url": "%s/hook", "bodyTemplate": "{\\"event\\": \\"test\\"}"}
            """.formatted(server.base())));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals(200, result.outputData().get("statusCode"));
        assertEquals("ok", result.outputData().get("responseBody"));
        assertEquals("POST", server.lastMethod);
        assertEquals("{\"event\": \"test\"}", server.lastBody);
        assertEquals("application/json", server.lastHeaders.getFirst("Content-Type"));
    }

    @Test
    @DisplayName("Should default to POST method")
    void shouldDefaultToPost() {
        ActionResult result = handler.execute(makeContext("""
            {"url": "%s/hook"}
            """.formatted(server.base())));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals("POST", result.outputData().get("method"));
        assertEquals("POST", server.lastMethod);
    }

    @Test
    @DisplayName("Should send record data as default body when no template")
    void shouldSendRecordDataAsDefault() {
        ActionResult result = handler.execute(makeContext("""
            {"url": "%s/hook", "headers": {"X-Source": "kelta"}}
            """.formatted(server.base())));

        assertTrue(result.successful(), result.errorMessage());
        assertTrue(server.lastBody.contains("\"recordId\":\"r1\""), server.lastBody);
        assertTrue(server.lastBody.contains("\"status\":\"Active\""), server.lastBody);
        assertEquals("kelta", server.lastHeaders.getFirst("X-Source"));
    }

    @Test
    @DisplayName("Should fail when URL is missing")
    void shouldFailWhenUrlMissing() {
        ActionResult result = handler.execute(makeContext("{}"));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("URL"));
    }

    @Test
    @DisplayName("Should fail on a 4xx/5xx status")
    void shouldFailOnErrorStatus() {
        server.status = 500;

        ActionResult result = handler.execute(makeContext("""
            {"url": "%s/hook"}
            """.formatted(server.base())));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("500"), result.errorMessage());
    }

    @Test
    @DisplayName("With the default guard, a loopback URL is refused and nothing is sent")
    void loopbackRefusedByDefault() {
        OutboundMessageActionHandler guarded =
            new OutboundMessageActionHandler(objectMapper, new OutboundUrlGuard(""));

        ActionResult result = guarded.execute(makeContext("""
            {"url": "%s/hook"}
            """.formatted(server.base())));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("refused"), result.errorMessage());
        assertEquals(0, server.requests.get());
    }

    @Test
    @DisplayName("A POST is not redirected; the 3xx is returned as the status")
    void postRedirectNotFollowed() {
        server.status = 307;
        server.responseBody = null;
        server.location = "http://10.0.0.1/internal";

        ActionResult result = handler.execute(makeContext("""
            {"url": "%s/hook"}
            """.formatted(server.base())));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals(307, result.outputData().get("statusCode"));
        assertEquals(1, server.requests.get());
    }

    @Test
    @DisplayName("A GET redirect to a non-public address is refused")
    void getRedirectToNonPublicRefused() {
        server.status = 302;
        server.responseBody = null;
        server.location = "http://10.0.0.1/internal";

        ActionResult result = handler.execute(makeContext("""
            {"url": "%s/hook", "method": "GET"}
            """.formatted(server.base())));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("non-public"), result.errorMessage());
    }

    @Test
    @DisplayName("A large response is not returned as responseBody")
    void largeResponseNotReturned() {
        server.responseBody = "x".repeat(OutboundMessageActionHandler.MAX_RESPONSE_BYTES * 2);

        ActionResult result = handler.execute(makeContext("""
            {"url": "%s/hook"}
            """.formatted(server.base())));

        assertTrue(result.successful(), result.errorMessage());
        assertFalse(result.outputData().containsKey("responseBody"));
    }

    @Test
    @DisplayName("The request deadline is honoured")
    void deadlineHonoured() {
        server.delayMillis = 2_000;
        OutboundMessageActionHandler quick = new OutboundMessageActionHandler(objectMapper,
            new OutboundHttpClient(new OutboundUrlGuard("127.0.0.0/8"),
                new OutboundHttpClient.Limits(Duration.ofSeconds(1), Duration.ofMillis(300), 1024, 0)));

        ActionResult result = quick.execute(makeContext("""
            {"url": "%s/slow"}
            """.formatted(server.base())));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("timed out"), result.errorMessage());
    }

    private ActionContext makeContext(String config) {
        return ActionContext.builder()
            .tenantId("t1").collectionId("c1").collectionName("orders").recordId("r1")
            .data(Map.of("status", "Active")).userId("user-1")
            .actionConfigJson(config).workflowRuleId("wf1").executionLogId("log1").build();
    }
}
