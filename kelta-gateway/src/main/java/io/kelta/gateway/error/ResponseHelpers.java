package io.kelta.gateway.error;

import io.kelta.jsonapi.JsonApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Helpers for safely mutating gateway response status and headers.
 *
 * <p>If an upstream filter has already committed the response, the headers
 * become {@code ReadOnlyHttpHeaders} and any mutation throws
 * {@link UnsupportedOperationException}. That surfaces as a 500 instead of
 * the intended 4xx. These helpers short-circuit when the response is already
 * finalized so the chain completes cleanly.
 */
public final class ResponseHelpers {

    private static final Logger log = LoggerFactory.getLogger(ResponseHelpers.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseHelpers() {}

    /**
     * The request's correlation id: the {@code correlationId} exchange attribute,
     * else the {@code X-Correlation-ID} header (set by {@code ObservabilityContextFilter}),
     * else a fresh UUID.
     */
    public static String correlationId(ServerWebExchange exchange) {
        String correlationId = exchange.getAttribute("correlationId");
        if (correlationId == null) {
            correlationId = exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
        }
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        return correlationId;
    }

    /**
     * Writes {@code {"errors":[error]}} with the given status. Serializes the plain
     * map rather than the bean: the native image has no reflection metadata for
     * {@link JsonApiError}. Returns {@code Mono.empty()} if the response was
     * already finalized upstream.
     */
    public static Mono<Void> writeJsonApiError(ServerWebExchange exchange, HttpStatus status, JsonApiError error) {
        ServerHttpResponse response = exchange.getResponse();
        if (!prepareJsonResponse(response, status)) {
            return Mono.empty();
        }
        byte[] bytes = MAPPER.writeValueAsString(Map.of("errors", List.of(error.toMap())))
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    /**
     * Sets status and {@code application/json} content type on the response.
     * Returns {@code true} if the caller should proceed to write the body,
     * {@code false} if the response was already finalized upstream and the
     * caller should abandon the write (return {@code Mono.empty()}).
     */
    public static boolean prepareJsonResponse(ServerHttpResponse response, HttpStatus status) {
        if (response.isCommitted()) {
            log.debug("Response already committed; skipping JSON error body");
            return false;
        }
        try {
            response.setStatusCode(status);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return true;
        } catch (UnsupportedOperationException e) {
            log.debug("Response headers read-only; skipping JSON error body: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Best-effort header mutation: applies the action only if the response is
     * still writable. Swallows {@link UnsupportedOperationException} so a
     * read-only headers race does not break the chain.
     */
    public static void applyHeaderIfWritable(ServerHttpResponse response, Runnable mutation) {
        if (response.isCommitted()) {
            return;
        }
        try {
            mutation.run();
        } catch (UnsupportedOperationException e) {
            log.debug("Skipped header mutation — response already finalized: {}", e.getMessage());
        }
    }
}
