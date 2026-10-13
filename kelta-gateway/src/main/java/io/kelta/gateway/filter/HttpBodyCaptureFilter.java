package io.kelta.gateway.filter;

import io.kelta.jsonapi.SensitiveBodyRedactor;
import io.opentelemetry.api.trace.Span;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.Set;

/**
 * Global filter that captures HTTP request and response bodies as OpenTelemetry
 * span attributes for observability.
 *
 * <p>Bodies are stored as {@code http.request.body} and {@code http.response.body}
 * span attributes, which are then visible in Tempo traces and the monitoring UI.
 *
 * <p>Safety guards:
 * <ul>
 *   <li>Only captures JSON content types; any other body, a body that is not valid JSON, and
 *       bodies on paths {@link SensitiveBodyRedactor#isSensitivePath sensitive} by design are
 *       recorded by size only ({@code http.request.body.size} / {@code http.response.body.size})</li>
 *   <li>Redacts sensitive values (secret-named keys, token-shaped strings) with
 *       {@link SensitiveBodyRedactor} <em>before</em> truncating, so a cut never exposes part of a
 *       secret</li>
 *   <li>Truncates the redacted text at {@link #MAX_BODY_SIZE} characters</li>
 *   <li>Skips requests with Content-Length exceeding the limit</li>
 * </ul>
 */
@Component
public class HttpBodyCaptureFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(HttpBodyCaptureFilter.class);

    /** Maximum body size to capture (32 KB); also the cap, in characters, on the redacted text. */
    static final int MAX_BODY_SIZE = 32 * 1024;

    static final String REQUEST_BODY_ATTR = "http.request.body";
    static final String RESPONSE_BODY_ATTR = "http.response.body";

    /** HTTP methods that may carry a request body. */
    private static final Set<HttpMethod> BODY_METHODS = Set.of(
            HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);

    /**
     * Runs after ObservabilityContextFilter (-90) so span context is available,
     * but before most other filters.
     */
    private static final int ORDER = -80;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        boolean sensitivePath = SensitiveBodyRedactor.isSensitivePath(request.getPath().value());

        // Decorate the response to capture the response body
        DataBufferFactory bufferFactory = response.bufferFactory();
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (sensitivePath || !isCapturableContentType(getDelegate().getHeaders())) {
                    recordDeclaredSize(RESPONSE_BODY_ATTR, getDelegate().getHeaders());
                    return super.writeWith(body);
                }

                return DataBufferUtils.join(Flux.from(body))
                        .flatMap(dataBuffer -> {
                            byte[] content = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(content);
                            DataBufferUtils.release(dataBuffer);

                            captureBody(RESPONSE_BODY_ATTR, content);

                            return getDelegate().writeWith(
                                    Mono.just(bufferFactory.wrap(content)));
                        });
            }
        };

        boolean bodyMethod = BODY_METHODS.contains(request.getMethod());

        // For methods with a request body, decorate the request to capture it
        if (bodyMethod
                && !sensitivePath
                && isCapturableContentType(request.getHeaders())
                && !exceedsMaxSize(request.getHeaders())) {

            ServerHttpRequestDecorator decoratedRequest = new ServerHttpRequestDecorator(request) {
                @Override
                public Flux<DataBuffer> getBody() {
                    return DataBufferUtils.join(super.getBody())
                            .flatMapMany(dataBuffer -> {
                                byte[] content = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(content);
                                DataBufferUtils.release(dataBuffer);

                                captureBody(REQUEST_BODY_ATTR, content);

                                // Re-emit the buffered content so downstream can read it
                                return Mono.just(bufferFactory.wrap(content));
                            });
                }
            };

            return chain.filter(exchange.mutate()
                    .request(decoratedRequest)
                    .response(decoratedResponse)
                    .build());
        }

        // No request body to capture — record its declared size only, and decorate the response
        if (bodyMethod) {
            recordDeclaredSize(REQUEST_BODY_ATTR, request.getHeaders());
        }
        return chain.filter(exchange.mutate()
                .response(decoratedResponse)
                .build());
    }

    /**
     * Records a body on the current span: its size always, and its content only after redaction.
     * A body that is not valid JSON is recorded by size only.
     */
    static void captureBody(String attribute, byte[] content) {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) return;

        if (content.length == 0) return;

        span.setAttribute(attribute + ".size", (long) content.length);
        Optional<SensitiveBodyRedactor.Result> redacted =
                SensitiveBodyRedactor.redactJson(content, MAX_BODY_SIZE);
        if (redacted.isEmpty()) {
            return;
        }
        span.setAttribute(attribute, redacted.get().body());
        if (redacted.get().truncated()) {
            span.setAttribute(attribute + ".truncated", true);
        }
    }

    /** Records a body's declared Content-Length without reading it. */
    private static void recordDeclaredSize(String attribute, HttpHeaders headers) {
        long contentLength = headers.getContentLength();
        if (contentLength <= 0) return;
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) return;
        span.setAttribute(attribute + ".size", contentLength);
    }

    /**
     * Checks whether the content type is JSON (the only type we capture).
     */
    static boolean isCapturableContentType(HttpHeaders headers) {
        MediaType contentType = headers.getContentType();
        if (contentType == null) return false;
        return contentType.isCompatibleWith(MediaType.APPLICATION_JSON);
    }

    /**
     * Checks whether the declared Content-Length exceeds the capture limit.
     */
    private boolean exceedsMaxSize(HttpHeaders headers) {
        long contentLength = headers.getContentLength();
        return contentLength > MAX_BODY_SIZE;
    }
}
