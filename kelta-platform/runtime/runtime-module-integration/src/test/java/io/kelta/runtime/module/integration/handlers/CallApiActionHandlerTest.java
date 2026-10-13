package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.flow.StateDataResolver;
import io.kelta.runtime.module.integration.api.ApiOperation;
import io.kelta.runtime.module.integration.api.ApiSpec;
import io.kelta.runtime.module.integration.mapping.PayloadMapperService;
import io.kelta.runtime.module.integration.spi.ApiSpecStore;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.runtime.module.integration.spi.IdempotencyStore;
import io.kelta.runtime.module.integration.spi.IdempotencyStore.CachedResponse;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Drives the handler against a real HTTP server on loopback. The guard allows
 * {@code 127.0.0.0/8} for most tests; {@link #loopbackRefusedByDefault()} shows the default
 * refuses it.
 */
@DisplayName("CallApiActionHandler")
class CallApiActionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PayloadMapperService mapper = new PayloadMapperService(new StateDataResolver(objectMapper));
    private LoopbackServer server;
    private CallApiActionHandler handler;
    private ApiSpecStore specStore;
    private CredentialResolverPort credentialResolver;
    private IdempotencyStore idempotencyStore;

    @BeforeEach
    void setUp() throws IOException {
        server = new LoopbackServer();
        specStore = mock(ApiSpecStore.class);
        credentialResolver = mock(CredentialResolverPort.class);
        idempotencyStore = mock(IdempotencyStore.class);
        handler = handlerWith(new OutboundUrlGuard("127.0.0.0/8"));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private CallApiActionHandler handlerWith(OutboundUrlGuard guard) {
        return new CallApiActionHandler(
            objectMapper, mapper, specStore, credentialResolver, idempotencyStore, guard);
    }

    @Test
    @DisplayName("Action type key is CALL_API")
    void typeKey() {
        assertEquals("CALL_API", handler.getActionTypeKey());
    }

    @Test
    @DisplayName("Raw mode: builds URL from config + state, maps headers and body")
    void rawModeExecutes() throws Exception {
        server.responseBody = "{\"id\":42}";
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", "raw");
        config.put("url", server.base() + "/orders/${$.input.orderId}");
        config.put("method", "POST");
        config.put("headers", Map.of("X-Trace", "${$.input.traceId}"));
        config.put("body", Map.of("status", "submitted"));

        ActionResult result = handler.execute(context(config,
            Map.of("input", Map.of("orderId", "O-99", "traceId", "trc"))));

        assertTrue(result.successful(), "expected success but got: " + result.errorMessage());
        assertEquals("POST", server.lastMethod);
        assertEquals("/orders/O-99", server.lastPath);
        assertEquals("trc", server.lastHeaders.getFirst("X-Trace"));
        assertEquals("application/json", server.lastHeaders.getFirst("Content-Type"));
        assertTrue(server.lastBody.contains("\"status\":\"submitted\""));
        assertEquals(200, result.outputData().get("statusCode"));
        assertEquals(Map.of("id", 42), result.outputData().get("responseData"));
    }

    @Test
    @DisplayName("Operation mode: loads spec + operation, fills path params, builds URL")
    void operationModeBuildsUrl() throws Exception {
        ApiSpec spec = spec("petstore", server.base() + "/v2");
        ApiOperation operation = operation(spec.id(), "getPetById", "GET", "/pet/{petId}");
        when(specStore.findSpec("t-1", "petstore")).thenReturn(Optional.of(spec));
        when(specStore.findOperation("t-1", "petstore", "getPetById"))
            .thenReturn(Optional.of(operation));
        server.responseBody = "{}";

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", "operation");
        config.put("specId", "petstore");
        config.put("operationId", "getPetById");
        config.put("pathParams", Map.of("petId", "${$.queryResult.id}"));

        ActionResult result = handler.execute(context(config, Map.of("queryResult", Map.of("id", "501"))));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals("GET", server.lastMethod);
        assertEquals("/v2/pet/501", server.lastPath);
        assertEquals(server.base() + "/v2/pet/501", result.outputData().get("url"));
    }

    @Test
    @DisplayName("Operation mode: a spec base URL on a non-public address is refused")
    void operationModeBaseUrlChecked() throws Exception {
        ApiSpec spec = spec("internal", "http://10.0.0.5/api");
        ApiOperation operation = operation(spec.id(), "list", "GET", "/items");
        when(specStore.findSpec("t-1", "internal")).thenReturn(Optional.of(spec));
        when(specStore.findOperation("t-1", "internal", "list")).thenReturn(Optional.of(operation));

        ActionResult result = handler.execute(context(
            Map.of("mode", "operation", "specId", "internal", "operationId", "list"), Map.of()));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().startsWith("Api.Refused:"), result.errorMessage());
    }

    @Test
    @DisplayName("Applies api_key credential as a header")
    void appliesApiKeyHeader() throws Exception {
        when(credentialResolver.resolve(eq("t-1"), eq("salesforce"), anyString()))
            .thenReturn(new ResolvedCredential("c-1", "salesforce", "api_key",
                Map.of("value", "sk_test_123"),
                Map.of("headerName", "X-API-Key", "location", "header"),
                Instant.now()));
        server.responseBody = "ok";
        server.contentType = "text/plain";

        ActionResult result = handler.execute(context(Map.of(
            "mode", "raw",
            "url", server.base() + "/x",
            "method", "GET",
            "credentialRef", "salesforce"), Map.of()));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals("sk_test_123", server.lastHeaders.getFirst("X-API-Key"));
        assertEquals("ok", result.outputData().get("responseBody"));
    }

    @Test
    @DisplayName("Applies bearer_token credential as Authorization header")
    void appliesBearer() throws Exception {
        when(credentialResolver.resolve(eq("t-1"), eq("github"), anyString()))
            .thenReturn(new ResolvedCredential("c-2", "github", "bearer_token",
                Map.of("token", "ghp_token"), Map.of(), Instant.now()));
        server.responseBody = "[]";

        ActionResult result = handler.execute(context(Map.of(
            "mode", "raw",
            "url", server.base() + "/user",
            "credentialRef", "github"), Map.of()));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals("Bearer ghp_token", server.lastHeaders.getFirst("Authorization"));
        assertNotNull(server.lastHeaders.getFirst("Accept"));
    }

    @Test
    @DisplayName("Idempotency hit replays cached response without calling upstream")
    void idempotencyHitReplays() throws Exception {
        when(idempotencyStore.lookup(eq("t-1"), anyString()))
            .thenReturn(Optional.of(new CachedResponse(201, "{\"cached\":true}", "hash")));

        ActionResult result = handler.execute(context(Map.of(
            "mode", "raw",
            "url", server.base() + "/orders",
            "method", "POST",
            "body", Map.of("x", 1),
            "idempotency", Map.of("enabled", true, "key", "fixed-key-1")), Map.of()));

        assertTrue(result.successful());
        assertEquals(0, server.requests.get());
        assertEquals(201, result.outputData().get("statusCode"));
    }

    @Test
    @DisplayName("Idempotency miss sends upstream with header and records response on success")
    void idempotencyMissRecords() throws Exception {
        when(idempotencyStore.lookup(eq("t-1"), anyString())).thenReturn(Optional.empty());
        server.status = 201;
        server.responseBody = "{\"ok\":true}";

        handler.execute(context(Map.of(
            "mode", "raw",
            "url", server.base() + "/orders",
            "method", "POST",
            "body", Map.of("x", 1),
            "idempotency", Map.of("enabled", true, "key", "key-7", "ttlSeconds", 60)), Map.of()));

        assertEquals("key-7", server.lastHeaders.getFirst("Idempotency-Key"));
        verify(idempotencyStore).record(eq("t-1"), eq("key-7"), any(), any(),
            eq(201), eq("{\"ok\":true}"), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("4xx maps to Api.HttpClientError with the response body")
    void httpClientErrorMaps() throws Exception {
        server.status = 404;
        server.responseBody = "{\"error\":\"missing\"}";

        ActionResult result = handler.execute(context(
            Map.of("mode", "raw", "url", server.base() + "/x"), Map.of()));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().startsWith("Api.HttpClientError: 404"), result.errorMessage());
        assertTrue(result.errorMessage().contains("missing"), result.errorMessage());
    }

    @Test
    @DisplayName("5xx maps to Api.HttpServerError")
    void httpServerErrorMaps() throws Exception {
        server.status = 503;

        ActionResult result = handler.execute(context(
            Map.of("mode", "raw", "url", server.base() + "/x"), Map.of()));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().startsWith("Api.HttpServerError: 503"), result.errorMessage());
    }

    @Test
    @DisplayName("With the default guard, a loopback URL is refused and nothing is sent")
    void loopbackRefusedByDefault() throws Exception {
        CallApiActionHandler guarded = handlerWith(new OutboundUrlGuard(""));

        ActionResult result = guarded.execute(context(
            Map.of("mode", "raw", "url", server.base() + "/x"), Map.of()));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().startsWith("Api.Refused:"), result.errorMessage());
        assertEquals(0, server.requests.get());
    }

    @Test
    @DisplayName("A raw URL resolved from state is checked after substitution")
    void resolvedRawUrlChecked() throws Exception {
        ActionResult result = handler.execute(context(
            Map.of("mode", "raw", "url", "${$.input.target}"),
            Map.of("input", Map.of("target", "http://169.254.169.254/latest/meta-data/"))));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().startsWith("Api.Refused:"), result.errorMessage());
    }

    @Test
    @DisplayName("A GET redirect to a non-public address is refused")
    void redirectToNonPublicRefused() throws Exception {
        server.status = 302;
        server.responseBody = null;
        server.location = "http://10.0.0.1/internal";

        ActionResult result = handler.execute(context(
            Map.of("mode", "raw", "url", server.base() + "/start"), Map.of()));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().startsWith("Api.Refused:"), result.errorMessage());
        assertEquals(1, server.requests.get());
    }

    @Test
    @DisplayName("An oversized response keeps a truncated responseBody and is not parsed")
    void oversizedResponseCapped() throws Exception {
        server.responseBody = "[\"" + "x".repeat(CallApiActionHandler.MAX_RESPONSE_BYTES) + "\"]";

        ActionResult result = handler.execute(context(
            Map.of("mode", "raw", "url", server.base() + "/big"), Map.of()));

        assertTrue(result.successful(), result.errorMessage());
        assertTrue(((String) result.outputData().get("responseBody")).endsWith("... [truncated]"));
        assertFalse(result.outputData().containsKey("responseData"));
    }

    @Test
    @DisplayName("The request deadline maps to Api.Timeout")
    void deadlineHonoured() throws Exception {
        server.delayMillis = 2_000;
        CallApiActionHandler quick = new CallApiActionHandler(
            objectMapper, mapper, specStore, credentialResolver, idempotencyStore,
            new OutboundHttpClient(new OutboundUrlGuard("127.0.0.0/8"),
                new OutboundHttpClient.Limits(Duration.ofSeconds(1), Duration.ofMillis(300), 1024, 0)));

        ActionResult result = quick.execute(context(
            Map.of("mode", "raw", "url", server.base() + "/slow"), Map.of()));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().startsWith("Api.Timeout:"), result.errorMessage());
    }

    @Test
    @DisplayName("validate() rejects raw mode without url and operation mode without specId")
    void validateRejectsBadConfig() throws Exception {
        String config1 = objectMapper.writeValueAsString(Map.of("mode", "raw"));
        assertThrows(IllegalArgumentException.class, () -> handler.validate(config1));

        String config2 = objectMapper.writeValueAsString(
            Map.of("mode", "operation", "operationId", "x"));
        assertThrows(IllegalArgumentException.class, () -> handler.validate(config2));

        String good = objectMapper.writeValueAsString(
            Map.of("mode", "raw", "url", "https://example.com"));
        assertDoesNotThrow(() -> handler.validate(good));
    }

    // -----------------------------------------------------------------------

    private ActionContext context(Map<String, Object> config, Map<String, Object> state) throws Exception {
        return ActionContext.builder()
            .tenantId("t-1")
            .actionConfigJson(objectMapper.writeValueAsString(config))
            .resolvedData(state)
            .build();
    }

    private static ApiSpec spec(String name, String baseUrl) {
        return new ApiSpec(
            name, "t-1", name, null, "3.0.3", name, "1.0", baseUrl,
            null, null, "URL", null, "{}", "json", null,
            "deadbeef", 1, true, Instant.now());
    }

    private static ApiOperation operation(String specId, String opId, String method, String path) {
        return new ApiOperation(
            opId, "t-1", specId, opId, opId, method, path,
            "summary", null, null, null, null, null, null, false);
    }
}
