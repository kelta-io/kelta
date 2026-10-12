package io.kelta.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SecurityConfig CORS")
class SecurityConfigTest {

    private SecurityConfig withOriginPattern(String pattern) {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "corsAllowedOriginPattern", pattern);
        return config;
    }

    private CorsConfiguration corsFor(SecurityConfig config) {
        var source = config.corsConfigurationSource();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/watches"));
        return source.getCorsConfiguration(exchange);
    }

    @Test
    @DisplayName("a single origin is honored")
    void singleOrigin() {
        assertThat(corsFor(withOriginPattern("https://app.kelta.io")).getAllowedOriginPatterns())
                .containsExactly("https://app.kelta.io");
    }

    @Test
    @DisplayName("a comma-separated list allows several frontends (trimmed, blanks dropped)")
    void multipleOrigins() {
        List<String> patterns = corsFor(withOriginPattern(
                "https://app.kelta.io, https://app.example.com , ")).getAllowedOriginPatterns();
        assertThat(patterns).containsExactly("https://app.kelta.io", "https://app.example.com");
    }

    @Test
    @DisplayName("credentials are allowed and the origin is a pattern (never a wildcard '*')")
    void credentialsAllowed() {
        CorsConfiguration cfg = corsFor(withOriginPattern("https://app.example.com"));
        assertThat(cfg.getAllowCredentials()).isTrue();
        assertThat(cfg.getAllowedOrigins()).isNull(); // uses allowedOriginPatterns, never allowedOrigins
    }

    @Test
    @DisplayName("a browser may read a record's ETag and send it back as If-Match (optimistic locking)")
    void optimisticLockingHeaders() {
        CorsConfiguration cfg = corsFor(withOriginPattern("https://app.kelta.io"));
        assertThat(cfg.getExposedHeaders()).contains("ETag");
        assertThat(cfg.getAllowedHeaders()).contains("If-Match");
        assertThat(cfg.checkHeaders(List.of("content-type", "if-match"))).contains("if-match");
    }

    @Test
    @DisplayName("blank config fails fast (misconfiguration must not silently allow nothing)")
    void blankThrows() {
        assertThatThrownBy(() -> withOriginPattern("  ").corsConfigurationSource())
                .isInstanceOf(IllegalStateException.class);
    }
}
