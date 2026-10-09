package io.kelta.gateway.error;

import io.kelta.gateway.filter.TenantResolutionFilter;
import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.jsonapi.JsonApiError;
import io.kelta.jsonapi.JsonApiParser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Global error handler for the API Gateway.
 * Implements centralized error handling for all gateway errors with consistent JSON:API responses.
 *
 * This handler processes:
 * - Authentication errors (401) - Missing, invalid, or expired JWT tokens
 * - Authorization errors (403) - Insufficient permissions
 * - Rate limit errors (429) - Request limit exceeded with Retry-After header
 * - Routing errors (404) - No matching route found
 * - Backend errors - Passed through unchanged
 * - Internal errors (500) - Unexpected exceptions with generic message
 *
 * All errors are logged with full details, and sensitive information is excluded
 * from client responses for internal errors.
 *
 * Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5
 */
@Component
@Order(-2) // Run before Spring's default error handler
public class GlobalErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    private final ObjectMapper objectMapper;
    private final GatewayMetrics metrics;

    public GlobalErrorHandler(ObjectMapper objectMapper, GatewayMetrics metrics) {
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        String path = exchange.getRequest().getPath().value();
        String correlationId = getOrCreateCorrelationId(exchange);

        log.debug("Handling error for path: {}, correlationId: {}, exception: {}",
            path, correlationId, ex.getClass().getSimpleName());

        JsonApiError error;
        HttpStatus status;

        // Handle different exception types
        if (ex instanceof GatewayAuthenticationException || ex instanceof JwtException) {
            // Authentication errors (401)
            status = HttpStatus.UNAUTHORIZED;
            error = new JsonApiError(
                String.valueOf(status.value()),
                "UNAUTHORIZED",
                "UNAUTHORIZED",
                ex.getMessage() != null ? ex.getMessage() : "Authentication failed"
            );
            log.warn("Authentication error for path: {}, correlationId: {}, message: {}",
                path, correlationId, ex.getMessage());

        } else if (ex instanceof GatewayAuthorizationException) {
            // Authorization errors (403)
            status = HttpStatus.FORBIDDEN;
            error = new JsonApiError(
                String.valueOf(status.value()),
                "FORBIDDEN",
                "FORBIDDEN",
                ex.getMessage() != null ? ex.getMessage() : "Access denied"
            );
            log.warn("Authorization error for path: {}, correlationId: {}, message: {}",
                path, correlationId, ex.getMessage());

        } else if (ex instanceof RateLimitExceededException) {
            // Rate limit errors (429)
            RateLimitExceededException rateLimitEx = (RateLimitExceededException) ex;
            status = HttpStatus.TOO_MANY_REQUESTS;
            error = new JsonApiError(
                String.valueOf(status.value()),
                "RATE_LIMIT_EXCEEDED",
                "RATE_LIMIT_EXCEEDED",
                ex.getMessage() != null ? ex.getMessage() : "Rate limit exceeded"
            );

            // Add Retry-After header (best-effort — response may already be committed)
            long retryAfterSeconds = rateLimitEx.getRetryAfter().getSeconds();
            ResponseHelpers.applyHeaderIfWritable(exchange.getResponse(),
                () -> exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(retryAfterSeconds)));

            log.warn("Rate limit exceeded for path: {}, correlationId: {}, retryAfter: {}s",
                path, correlationId, retryAfterSeconds);

        } else if (ex instanceof RouteNotFoundException) {
            // Routing errors (404)
            status = HttpStatus.NOT_FOUND;
            error = new JsonApiError(
                String.valueOf(status.value()),
                "NOT_FOUND",
                "NOT_FOUND",
                ex.getMessage() != null ? ex.getMessage() : "Route not found"
            );
            log.warn("Route not found for path: {}, correlationId: {}", path, correlationId);

        } else if (ex instanceof ResponseStatusException) {
            // Spring's ResponseStatusException (includes backend errors)
            ResponseStatusException statusEx = (ResponseStatusException) ex;
            status = HttpStatus.resolve(statusEx.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }

            String code = status.name().replace(" ", "_");
            error = new JsonApiError(
                String.valueOf(status.value()),
                code,
                code,
                responseStatusDetail(statusEx, status, path)
            );
            log.warn("Response status exception for path: {}, correlationId: {}, status: {}, message: {}",
                path, correlationId, status, statusEx.getReason());

        } else if (ex instanceof JsonApiParser.JsonApiParseException) {
            // JSON:API parsing errors (500)
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            error = new JsonApiError(
                String.valueOf(status.value()),
                "JSONAPI_PARSE_ERROR",
                "JSONAPI_PARSE_ERROR",
                "Failed to process JSON:API response"
            );
            log.error("JSON:API parse error for path: {}, correlationId: {}", path, correlationId, ex);

        } else {
            // Internal errors (500) - generic message for security
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            error = new JsonApiError(
                String.valueOf(status.value()),
                "INTERNAL_ERROR",
                "INTERNAL_ERROR",
                "An unexpected error occurred"
            );
            log.error("Internal error for path: {}, correlationId: {}", path, correlationId, ex);
        }

        // Record error metric
        String tenantSlug = TenantResolutionFilter.getTenantSlug(exchange);
        metrics.recordError(tenantSlug, String.valueOf(status.value()), error.getCode());

        // Set meta with timestamp, path, and correlationId
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("timestamp", Instant.now());
        meta.put("path", path);
        meta.put("correlationId", correlationId);
        error.setMeta(meta);

        // Set response status and headers — abandon if the response was already
        // committed upstream so we don't surface a 500 in place of the real status.
        if (!ResponseHelpers.prepareJsonResponse(exchange.getResponse(), status)) {
            log.warn("Response already committed for path: {}, correlationId: {} — dropping error body",
                path, correlationId);
            return Mono.empty();
        }

        // Serialize error response to JSON:API format
        try {
            // Serialize the plain map, not the bean: the native image has no reflection
            // metadata for JsonApiError, so the bean serialized as {"errors":[{}]}.
            Map<String, Object> responseBody = Map.of("errors", List.of(error.toMap()));
            String json = objectMapper.writeValueAsString(responseBody);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);

            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (JacksonException e) {
            log.error("Failed to serialize error response for path: {}, correlationId: {}",
                path, correlationId, e);

            // Fallback to simple JSON:API error format
            String fallbackJson = String.format(
                "{\"errors\":[{\"status\":\"%d\",\"code\":\"%s\",\"detail\":\"%s\",\"meta\":{\"path\":\"%s\",\"correlationId\":\"%s\"}}]}",
                status.value(),
                error.getCode(),
                error.getDetail(),
                path,
                correlationId
            );
            byte[] bytes = fallbackJson.getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);

            return exchange.getResponse().writeWith(Mono.just(buffer));
        }
    }

    /**
     * A request that matched no gateway route surfaces as a reason-less 404 from the
     * dispatcher, or as {@link NoResourceFoundException} ("No static resource ...") from
     * the resource handler. Both mean the same thing, so name the path that missed.
     */
    private static String responseStatusDetail(ResponseStatusException ex, HttpStatus status, String path) {
        if (ex instanceof NoResourceFoundException
                || (status == HttpStatus.NOT_FOUND && ex.getReason() == null)) {
            return "No route found for path: " + path;
        }
        return ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
    }

    /**
     * Gets the correlation ID from the exchange attributes or creates a new one.
     *
     * @param exchange the server web exchange
     * @return the correlation ID
     */
    private String getOrCreateCorrelationId(ServerWebExchange exchange) {
        // Try to get from exchange attributes first
        String correlationId = exchange.getAttribute("correlationId");

        if (correlationId == null) {
            // Try to get from request header
            correlationId = exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
        }

        if (correlationId == null) {
            // Generate new correlation ID
            correlationId = UUID.randomUUID().toString();
        }

        return correlationId;
    }
}
