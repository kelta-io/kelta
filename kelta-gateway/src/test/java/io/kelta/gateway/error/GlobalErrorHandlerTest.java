package io.kelta.gateway.error;

import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.jsonapi.JsonApiParser;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.reactive.server.HttpHandlerConnector;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GlobalErrorHandler.
 * Validates JSON:API error response format: {"errors":[{status, code, title, detail, meta}]}
 */
class GlobalErrorHandlerTest {

    private GlobalErrorHandler errorHandler;
    private ObjectMapper objectMapper;
    private GatewayMetrics metrics;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        metrics = org.mockito.Mockito.mock(GatewayMetrics.class);
        errorHandler = new GlobalErrorHandler(objectMapper, metrics);
    }

    @Test
    void testHandleAuthenticationException() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/users").build()
        );

        GatewayAuthenticationException exception = new GatewayAuthenticationException("Invalid JWT token");

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"errors\""));
        assertTrue(responseBody.contains("\"status\":\"401\""));
        assertTrue(responseBody.contains("\"code\":\"UNAUTHORIZED\""));
        assertTrue(responseBody.contains("\"detail\":\"Invalid JWT token\""));
        assertTrue(responseBody.contains("\"path\":\"/api/users\""));
    }

    @Test
    void testHandleJwtException() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/posts").build()
        );

        JwtException exception = new JwtException("Token expired");

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"status\":\"401\""));
        assertTrue(responseBody.contains("\"code\":\"UNAUTHORIZED\""));
        assertTrue(responseBody.contains("\"detail\":\"Token expired\""));
    }

    @Test
    void testHandleAuthorizationException() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/admin").build()
        );

        GatewayAuthorizationException exception = new GatewayAuthorizationException("Insufficient permissions");

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"status\":\"403\""));
        assertTrue(responseBody.contains("\"code\":\"FORBIDDEN\""));
        assertTrue(responseBody.contains("\"detail\":\"Insufficient permissions\""));
        assertTrue(responseBody.contains("\"path\":\"/api/admin\""));
    }

    @Test
    void testHandleRateLimitExceededException() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/data").build()
        );

        RateLimitExceededException exception = new RateLimitExceededException(
            "Rate limit exceeded",
            Duration.ofSeconds(60),
            100
        );

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("60", exchange.getResponse().getHeaders().getFirst("Retry-After"));

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"status\":\"429\""));
        assertTrue(responseBody.contains("\"code\":\"RATE_LIMIT_EXCEEDED\""));
        assertTrue(responseBody.contains("\"detail\":\"Rate limit exceeded\""));
    }

    @Test
    void testHandleRouteNotFoundException() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/unknown").build()
        );

        RouteNotFoundException exception = new RouteNotFoundException("No route found for path: /api/unknown");

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"status\":\"404\""));
        assertTrue(responseBody.contains("\"code\":\"NOT_FOUND\""));
        assertTrue(responseBody.contains("No route found"));
    }

    @Test
    void testHandleResponseStatusException() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/service").build()
        );

        ResponseStatusException exception = new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Invalid request parameters"
        );

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"status\":\"400\""));
        assertTrue(responseBody.contains("\"detail\":\"Invalid request parameters\""));
    }

    @Test
    void testHandleJsonApiParseException() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/resources").build()
        );

        JsonApiParser.JsonApiParseException exception = new JsonApiParser.JsonApiParseException(
            "Failed to parse JSON:API response"
        );

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"status\":\"500\""));
        assertTrue(responseBody.contains("\"code\":\"JSONAPI_PARSE_ERROR\""));
        assertTrue(responseBody.contains("\"detail\":\"Failed to process JSON:API response\""));
    }

    @Test
    void testHandleGenericException() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/error").build()
        );

        RuntimeException exception = new RuntimeException("Unexpected error");

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"status\":\"500\""));
        assertTrue(responseBody.contains("\"code\":\"INTERNAL_ERROR\""));
        assertTrue(responseBody.contains("\"detail\":\"An unexpected error occurred\""));
        // Should NOT contain the actual exception message for security
        assertFalse(responseBody.contains("Unexpected error"));
    }

    @Test
    void testCorrelationIdFromHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/test")
                .header("X-Correlation-ID", "test-correlation-123")
                .build()
        );

        GatewayAuthenticationException exception = new GatewayAuthenticationException("Test error");

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"correlationId\":\"test-correlation-123\""));
    }

    @Test
    void testCorrelationIdGenerated() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/test").build()
        );

        GatewayAuthenticationException exception = new GatewayAuthenticationException("Test error");

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"correlationId\":"));
        // Should contain a UUID-like string
        assertTrue(responseBody.matches(".*\"correlationId\":\"[a-f0-9-]{36}\".*"));
    }

    @Test
    void testAuthenticationExceptionWithNullMessage() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/users").build()
        );

        GatewayAuthenticationException exception = new GatewayAuthenticationException(null);

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"detail\":\"Authentication failed\""));
    }

    @Test
    void testAuthorizationExceptionWithNullMessage() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/admin").build()
        );

        GatewayAuthorizationException exception = new GatewayAuthorizationException(null);

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"detail\":\"Access denied\""));
    }

    @Test
    void testRateLimitExceptionWithNullMessage() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/data").build()
        );

        RateLimitExceededException exception = new RateLimitExceededException(
            null,
            Duration.ofSeconds(30),
            50
        );

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"detail\":\"Rate limit exceeded\""));
    }

    @Test
    void testRouteNotFoundExceptionWithNullMessage() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/unknown").build()
        );

        RouteNotFoundException exception = new RouteNotFoundException(null);

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        String responseBody = getResponseBody(exchange);
        assertTrue(responseBody.contains("\"detail\":\"Route not found\""));
    }

    @Test
    void testContentTypeIsJson() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/test").build()
        );

        GatewayAuthenticationException exception = new GatewayAuthenticationException("Test");

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
    }

    @Test
    void testErrorResponseStructure() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/users").build()
        );

        GatewayAuthenticationException exception = new GatewayAuthenticationException("Invalid token");

        Mono<Void> result = errorHandler.handle(exchange, exception);

        StepVerifier.create(result)
            .verifyComplete();

        String responseBody = getResponseBody(exchange);

        // Verify JSON:API error structure
        assertTrue(responseBody.contains("\"errors\""));
        assertTrue(responseBody.contains("\"status\":"));
        assertTrue(responseBody.contains("\"code\":"));
        assertTrue(responseBody.contains("\"title\":"));
        assertTrue(responseBody.contains("\"detail\":"));
        assertTrue(responseBody.contains("\"meta\""));
        assertTrue(responseBody.contains("\"timestamp\":"));
        assertTrue(responseBody.contains("\"path\":"));
        assertTrue(responseBody.contains("\"correlationId\":"));
    }

    /**
     * A mapper that sees no bean properties, as on the native image (no reflection
     * metadata for JsonApiError). Bean serialization there wrote {"errors":[{}]}.
     */
    private static ObjectMapper nativeLikeMapper() {
        return JsonMapper.builder()
            .changeDefaultVisibility(vc -> vc.withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE))
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();
    }

    /**
     * Routes not loaded (bootstrap fetch failed): the gateway dispatcher matches no
     * route for a collection API and raises a reason-less 404.
     */
    @Test
    void testUnroutedCollectionApiReturnsFullErrorObject() {
        GlobalErrorHandler handler = new GlobalErrorHandler(nativeLikeMapper(), metrics);
        HttpHandler httpHandler = WebHttpHandlerBuilder.webHandler(new DispatcherHandler())
            .exceptionHandler(handler)
            .build();
        WebTestClient client = WebTestClient.bindToServer(new HttpHandlerConnector(httpHandler))
            .baseUrl("http://localhost")
            .build();

        byte[] body = client.get().uri("/api/fleet-state?filter[name]=default")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody().returnResult().getResponseBody();

        assertFullNotFoundError(new String(body, StandardCharsets.UTF_8), "/api/fleet-state");
    }

    /**
     * The resource handler's "No static resource api/x" 404 — what the gateway raised
     * during the 2026-10-07 route-bootstrap outage.
     */
    @Test
    void testNoResourceFoundExceptionReturnsFullErrorObject() {
        GlobalErrorHandler handler = new GlobalErrorHandler(nativeLikeMapper(), metrics);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/titles").build()
        );

        StepVerifier.create(handler.handle(exchange,
                new NoResourceFoundException(URI.create("/api/titles"), "api/titles")))
            .verifyComplete();

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        assertFullNotFoundError(getResponseBody(exchange), "/api/titles");
    }

    private void assertFullNotFoundError(String responseBody, String path) {
        JsonNode errors = objectMapper.readTree(responseBody).get("errors");
        assertNotNull(errors, responseBody);
        assertEquals(1, errors.size(), responseBody);
        JsonNode error = errors.get(0);
        assertEquals("404", error.path("status").asString(), responseBody);
        assertEquals("NOT_FOUND", error.path("code").asString(), responseBody);
        assertEquals("No route found for path: " + path, error.path("detail").asString(), responseBody);
        assertEquals(path, error.path("meta").path("path").asString(), responseBody);
    }

    /**
     * Helper method to extract response body as string.
     */
    private String getResponseBody(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block();
    }
}
