package io.kelta.worker.filter;

import io.kelta.jsonapi.SensitiveBodyRedactor;
import io.kelta.worker.interceptor.SpanIdCaptureInterceptor;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Set;

/**
 * Captures request/response bodies and enriches OTEL span attributes.
 * Request/response payloads are logged at DEBUG level for troubleshooting.
 *
 * <p>Only JSON bodies are captured, and only after {@link SensitiveBodyRedactor} has redacted
 * sensitive values (redaction runs before truncation). Non-JSON bodies, invalid JSON, and bodies on
 * {@link SensitiveBodyRedactor#isSensitivePath sensitive paths} are not captured.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class SpanBodyEnrichmentFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SpanBodyEnrichmentFilter.class);

    private static final Set<String> EXCLUDE_PREFIXES = Set.of(
            "/actuator", "/health"
    );

    @Value("${kelta.observability.request-logging.max-body-size:16384}")
    private int maxBodySize;

    @Value("${kelta.observability.request-logging.enabled:true}")
    private boolean enabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!enabled || shouldExclude(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, maxBodySize);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            captureData(wrappedRequest, wrappedResponse);
            wrappedResponse.copyBodyToResponse();
        }
    }

    private void captureData(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response) {
        // Extract bodies (never on paths that carry secrets under generic keys)
        boolean sensitivePath = SensitiveBodyRedactor.isSensitivePath(request.getRequestURI());
        String requestBody = sensitivePath ? null
                : extractBody(request.getContentAsByteArray(), request.getContentType(), maxBodySize);
        String responseBody = sensitivePath ? null
                : extractBody(response.getContentAsByteArray(), response.getContentType(), maxBodySize);

        // Extract context from headers
        String tenantId = request.getHeader("X-Tenant-ID");
        String userId = request.getHeader("X-User-Id");
        String userEmail = request.getHeader("X-Forwarded-User");
        String correlationId = request.getHeader("X-Correlation-ID");

        // Enrich OTEL span attributes (stored by Tempo)
        enrichSpan(requestBody, responseBody, tenantId, userId, userEmail, correlationId);

        // Log at DEBUG level for troubleshooting
        if (log.isDebugEnabled()) {
            String traceId = (String) request.getAttribute(SpanIdCaptureInterceptor.SERVER_TRACE_ID_ATTR);
            String spanId = (String) request.getAttribute(SpanIdCaptureInterceptor.SERVER_SPAN_ID_ATTR);

            if (traceId == null || spanId == null) {
                Span span = Span.current();
                if (span.getSpanContext().isValid()) {
                    traceId = span.getSpanContext().getTraceId();
                    spanId = span.getSpanContext().getSpanId();
                }
            }

            log.debug("HTTP {} {} status={} traceId={} spanId={} tenant={} user={} reqBody={} respBody={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    traceId, spanId, tenantId, userId,
                    requestBody, responseBody);
        }
    }

    /**
     * Returns the redacted, truncated JSON body, or {@code null} when the body is empty, not JSON,
     * or not valid JSON. Redaction runs before truncation, so a cut never exposes part of a secret.
     */
    static String extractBody(byte[] content, String contentType, int maxBodySize) {
        if (content == null || content.length == 0 || contentType == null || !contentType.contains("json")) {
            return null;
        }
        return SensitiveBodyRedactor.redactJson(content, maxBodySize)
                .map(r -> r.truncated() ? r.body() + "...[truncated]" : r.body())
                .orElse(null);
    }

    private void enrichSpan(String requestBody, String responseBody,
                            String tenantId, String userId, String userEmail, String correlationId) {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) {
            return;
        }

        if (requestBody != null) {
            span.setAttribute("http.request.body", requestBody);
        }
        if (responseBody != null) {
            span.setAttribute("http.response.body", responseBody);
        }
        if (tenantId != null) span.setAttribute("kelta.tenant.id", tenantId);
        if (userId != null) span.setAttribute("kelta.user.id", userId);
        if (userEmail != null) span.setAttribute("kelta.user.email", userEmail);
        if (correlationId != null) span.setAttribute("kelta.correlation.id", correlationId);
    }

    private boolean shouldExclude(String path) {
        return EXCLUDE_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
