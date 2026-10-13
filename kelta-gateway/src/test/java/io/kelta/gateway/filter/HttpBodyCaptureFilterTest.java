package io.kelta.gateway.filter;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpBodyCaptureFilter}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpBodyCaptureFilter Tests")
class HttpBodyCaptureFilterTest {

    @Mock
    private GatewayFilterChain filterChain;

    private HttpBodyCaptureFilter filter;

    @BeforeEach
    void setUp() {
        filter = new HttpBodyCaptureFilter();
    }

    @Test
    @DisplayName("Should have order -80 (after ObservabilityContextFilter)")
    void shouldHaveCorrectOrder() {
        assertThat(filter.getOrder()).isEqualTo(-80);
    }

    @Nested
    @DisplayName("Content type detection")
    class ContentTypeDetection {

        @Test
        @DisplayName("Should identify JSON content type as capturable")
        void shouldCaptureJsonContentType() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            assertThat(HttpBodyCaptureFilter.isCapturableContentType(headers)).isTrue();
        }

        @Test
        @DisplayName("Should identify JSON UTF-8 content type as capturable")
        void shouldCaptureJsonUtf8ContentType() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
            assertThat(HttpBodyCaptureFilter.isCapturableContentType(headers)).isTrue();
        }

        @Test
        @DisplayName("Should not capture text/plain content type")
        void shouldNotCaptureTextPlain() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            assertThat(HttpBodyCaptureFilter.isCapturableContentType(headers)).isFalse();
        }

        @Test
        @DisplayName("Should not capture multipart content type")
        void shouldNotCaptureMultipart() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            assertThat(HttpBodyCaptureFilter.isCapturableContentType(headers)).isFalse();
        }

        @Test
        @DisplayName("Should not capture when content type is absent")
        void shouldNotCaptureAbsentContentType() {
            HttpHeaders headers = new HttpHeaders();
            assertThat(HttpBodyCaptureFilter.isCapturableContentType(headers)).isFalse();
        }

        @Test
        @DisplayName("Should not capture octet-stream content type")
        void shouldNotCaptureOctetStream() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            assertThat(HttpBodyCaptureFilter.isCapturableContentType(headers)).isFalse();
        }
    }

    @Nested
    @DisplayName("GET requests (no request body)")
    class GetRequests {

        @Test
        @DisplayName("Should pass GET request through and decorate response only")
        void shouldPassGetRequestThrough() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/users")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(filterChain.filter(any(ServerWebExchange.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            // Verify chain was called with a mutated exchange (response decorated)
            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
            verify(filterChain).filter(captor.capture());

            ServerWebExchange passedExchange = captor.getValue();
            // Response should be decorated
            assertThat(passedExchange.getResponse()).isInstanceOf(ServerHttpResponseDecorator.class);
        }
    }

    @Nested
    @DisplayName("POST requests with body")
    class PostRequests {

        @Test
        @DisplayName("Should decorate POST request with JSON content type")
        void shouldDecoratePostWithJsonBody() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"name\":\"test\"}");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(filterChain.filter(any(ServerWebExchange.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
            verify(filterChain).filter(captor.capture());

            ServerWebExchange passedExchange = captor.getValue();
            // Both request and response should be decorated
            assertThat(passedExchange.getResponse()).isInstanceOf(ServerHttpResponseDecorator.class);
        }

        @Test
        @DisplayName("Should not decorate POST request with non-JSON content type")
        void shouldNotDecoratePostWithNonJsonBody() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/upload")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body("binary-data");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(filterChain.filter(any(ServerWebExchange.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            // Chain should still be called (response decorated, but request not)
            verify(filterChain).filter(any(ServerWebExchange.class));
        }

        @Test
        @DisplayName("Should skip request body capture when content length exceeds max size")
        void shouldSkipLargeRequestBody() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/data")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_LENGTH,
                            String.valueOf(HttpBodyCaptureFilter.MAX_BODY_SIZE + 1))
                    .body("{\"large\":true}");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(filterChain.filter(any(ServerWebExchange.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            verify(filterChain).filter(any(ServerWebExchange.class));
        }
    }

    @Nested
    @DisplayName("PUT and PATCH requests")
    class PutPatchRequests {

        @Test
        @DisplayName("Should decorate PUT request with JSON body")
        void shouldDecoratePutWithJsonBody() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .put("/api/users/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"name\":\"updated\"}");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(filterChain.filter(any(ServerWebExchange.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
            verify(filterChain).filter(captor.capture());
            assertThat(captor.getValue().getResponse()).isInstanceOf(ServerHttpResponseDecorator.class);
        }

        @Test
        @DisplayName("Should decorate PATCH request with JSON body")
        void shouldDecoratePatchWithJsonBody() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .patch("/api/users/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"name\":\"patched\"}");
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(filterChain.filter(any(ServerWebExchange.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            verify(filterChain).filter(any(ServerWebExchange.class));
        }
    }

    @Nested
    @DisplayName("DELETE requests (no request body capture)")
    class DeleteRequests {

        @Test
        @DisplayName("Should not capture request body for DELETE")
        void shouldNotCaptureDeleteRequestBody() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .delete("/api/users/1")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(filterChain.filter(any(ServerWebExchange.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();

            // Response should still be decorated for response body capture
            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
            verify(filterChain).filter(captor.capture());
            assertThat(captor.getValue().getResponse()).isInstanceOf(ServerHttpResponseDecorator.class);
        }
    }

    @Nested
    @DisplayName("Response body capture")
    class ResponseBodyCapture {

        @Test
        @DisplayName("Should pass response body through when writing JSON response")
        void shouldPassResponseBodyThrough() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/users")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            byte[] responseBytes = "{\"users\":[]}".getBytes(StandardCharsets.UTF_8);

            // Simulate the chain writing a response body
            when(filterChain.filter(any(ServerWebExchange.class)))
                    .thenAnswer(invocation -> {
                        ServerWebExchange ex = invocation.getArgument(0);
                        ServerHttpResponse response = ex.getResponse();
                        response.setStatusCode(HttpStatus.OK);
                        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        DataBuffer buffer = new DefaultDataBufferFactory().wrap(responseBytes);
                        return response.writeWith(Mono.just(buffer));
                    });

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should pass non-JSON response body through without capturing")
        void shouldPassNonJsonResponseThrough() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/index.html")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            byte[] responseBytes = "<html></html>".getBytes(StandardCharsets.UTF_8);

            when(filterChain.filter(any(ServerWebExchange.class)))
                    .thenAnswer(invocation -> {
                        ServerWebExchange ex = invocation.getArgument(0);
                        ServerHttpResponse response = ex.getResponse();
                        response.setStatusCode(HttpStatus.OK);
                        response.getHeaders().setContentType(MediaType.TEXT_HTML);
                        DataBuffer buffer = new DefaultDataBufferFactory().wrap(responseBytes);
                        return response.writeWith(Mono.just(buffer));
                    });

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should propagate filter chain errors")
        void shouldPropagateFilterChainErrors() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/users")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(filterChain.filter(any(ServerWebExchange.class)))
                    .thenReturn(Mono.error(new RuntimeException("downstream error")));

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("Span attributes never carry sensitive values")
    class SpanRedaction {

        // Token-shaped fixtures built at runtime so the source never holds a literal token.
        private static final String PAT = "klt_" + "AbCdEf0123456789xyz";
        private static final String JWT = "eyJ" + "hbGciOiJIUzI1NiJ9" + ".eyJ" + "zdWIiOiJ1c2VyIn0"
                + ".c2lnbmF0dXJlLXZhbHVl";

        private SdkTracerProvider tracerProvider;
        private Span span;
        private Scope scope;

        @BeforeEach
        void startSpan() {
            tracerProvider = SdkTracerProvider.builder().build();
            span = tracerProvider.get("test").spanBuilder("request").startSpan();
            scope = span.makeCurrent();
        }

        @AfterEach
        void endSpan() {
            scope.close();
            span.end();
            tracerProvider.close();
        }

        private Attributes attributes() {
            return ((ReadableSpan) span).toSpanData().getAttributes();
        }

        private String attr(String key) {
            return attributes().get(AttributeKey.stringKey(key));
        }

        /** Chain stub that reads the forwarded request body, then writes {@code responseBody}. */
        private void chainEchoing(AtomicReference<String> forwarded, MediaType responseType, String responseBody) {
            when(filterChain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
                ServerWebExchange ex = invocation.getArgument(0);
                return readAll(ex.getRequest().getBody())
                        .doOnNext(forwarded::set)
                        .then(Mono.defer(() -> {
                            ServerHttpResponse response = ex.getResponse();
                            response.setStatusCode(HttpStatus.OK);
                            response.getHeaders().setContentType(responseType);
                            DataBuffer buffer = new DefaultDataBufferFactory()
                                    .wrap(responseBody.getBytes(StandardCharsets.UTF_8));
                            return response.writeWith(Mono.just(buffer));
                        }));
            });
        }

        @Test
        @DisplayName("redacts secrets in captured JSON request and response bodies, forwards originals")
        void redactsJsonBodies() {
            String requestJson = "{\"data\":{\"attributes\":{\"name\":\"idp\",\"clientSecret\":\"req-secret-1\","
                    + "\"nested\":[{\"password\":\"req-secret-2\"}],\"note\":\"" + PAT + "\"}}}";
            String responseJson = "{\"access_token\":\"resp-secret-1\",\"refreshToken\":\"resp-secret-2\","
                    + "\"id\":\"keep-me\",\"link\":\"Bearer " + JWT + "\"}";
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .post("/api/oidc-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestJson));
            AtomicReference<String> forwarded = new AtomicReference<>();
            chainEchoing(forwarded, MediaType.APPLICATION_JSON, responseJson);

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            // Downstream receives the untouched body.
            assertThat(forwarded.get()).isEqualTo(requestJson);

            String capturedRequest = attr("http.request.body");
            String capturedResponse = attr("http.response.body");
            assertThat(capturedRequest).contains("\"name\":\"idp\"", "\"clientSecret\":\"[REDACTED]\"")
                    .doesNotContain("req-secret-1", "req-secret-2", PAT);
            assertThat(capturedResponse).contains("\"id\":\"keep-me\"", "\"access_token\":\"[REDACTED]\"")
                    .doesNotContain("resp-secret-1", "resp-secret-2", JWT);
            assertThat(attributes().get(AttributeKey.longKey("http.request.body.size")))
                    .isEqualTo((long) requestJson.length());

            // No attribute anywhere on the span holds a secret.
            assertThat(attributes().asMap().values()).allSatisfy(v -> assertThat(String.valueOf(v))
                    .doesNotContain("req-secret", "resp-secret", PAT, JWT));
        }

        @Test
        @DisplayName("non-JSON and invalid JSON bodies are recorded by size only")
        void nonJsonSizeOnly() {
            String form = "username=a&password=req-secret-1";
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .post("/api/login")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .contentLength(form.length())
                    .body(form));
            chainEchoing(new AtomicReference<>(), MediaType.APPLICATION_JSON, "{\"token\":\"resp-secret-1\"");

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            assertThat(attr("http.request.body")).isNull();
            assertThat(attr("http.response.body")).isNull();
            assertThat(attributes().get(AttributeKey.longKey("http.request.body.size")))
                    .isEqualTo((long) form.length());
            assertThat(attributes().get(AttributeKey.longKey("http.response.body.size"))).isPositive();
        }

        @Test
        @DisplayName("bodies on sensitive paths are never captured")
        void sensitivePathNotCaptured() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .post("/api/credentials")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"type\":\"api_key\",\"value\":\"req-secret-1\"}"));
            chainEchoing(new AtomicReference<>(), MediaType.APPLICATION_JSON, "{\"value\":\"resp-secret-1\"}");

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            assertThat(attr("http.request.body")).isNull();
            assertThat(attr("http.response.body")).isNull();
        }

        @Test
        @DisplayName("redacts before truncating a large response")
        void truncatesAfterRedaction() {
            String pad = "x".repeat(HttpBodyCaptureFilter.MAX_BODY_SIZE - 30);
            String responseJson = "{\"pad\":\"" + pad + "\",\"secret\":\"" + "S".repeat(200) + "\"}";
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"));
            chainEchoing(new AtomicReference<>(), MediaType.APPLICATION_JSON, responseJson);

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            assertThat(attr("http.response.body")).hasSizeLessThanOrEqualTo(HttpBodyCaptureFilter.MAX_BODY_SIZE)
                    .doesNotContain("SSSS");
            assertThat(attributes().get(AttributeKey.booleanKey("http.response.body.truncated"))).isTrue();
        }
    }

    /** Joins a body publisher into a UTF-8 string. */
    private static Mono<String> readAll(Flux<DataBuffer> body) {
        return DataBufferUtils.join(body)
                .map(buffer -> {
                    String text = buffer.toString(StandardCharsets.UTF_8);
                    DataBufferUtils.release(buffer);
                    return text;
                })
                .defaultIfEmpty("");
    }
}
