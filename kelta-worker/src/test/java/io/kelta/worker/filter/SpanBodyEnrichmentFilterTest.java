package io.kelta.worker.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The redaction rules themselves are covered by {@code SensitiveBodyRedactorTest} in
 * runtime-jsonapi; these tests pin how the worker filter applies them.
 */
@DisplayName("SpanBodyEnrichmentFilter redaction")
class SpanBodyEnrichmentFilterTest {

    private static final int LIMIT = 16384;

    private static String extract(String body, String contentType, int limit) {
        return SpanBodyEnrichmentFilter.extractBody(body.getBytes(StandardCharsets.UTF_8), contentType, limit);
    }

    @Test
    @DisplayName("redacts keys that contain a secret word, not just the bare words")
    void redactsCompoundKeys() {
        String body = """
                {"data":{"attributes":{"name":"Provider","clientSecret":"s3cr3t-value",
                "newPassword":"pw-value","accessToken":"tok-value","x-api_key":"key-value",
                "secret":"bare-value","ssn":"123-45-6789","issuer":"https://idp.example.com"}}}""";

        String sanitized = extract(body, "application/vnd.api+json", LIMIT);

        assertThat(sanitized).doesNotContain("s3cr3t-value", "pw-value", "tok-value", "key-value",
                "bare-value", "123-45-6789");
        assertThat(sanitized).contains("\"clientSecret\":\"[REDACTED]\"", "\"name\":\"Provider\"",
                "\"issuer\":\"https://idp.example.com\"");
    }

    @Test
    @DisplayName("a value with escaped quotes is redacted whole")
    void escapedQuotes() {
        assertThat(extract("{\"clientSecret\":\"a\\\"b\\\"c\",\"name\":\"n\"}", "application/json", LIMIT))
                .isEqualTo("{\"clientSecret\":\"[REDACTED]\",\"name\":\"n\"}");
    }

    @Test
    @DisplayName("redacts before truncating and marks the cut")
    void redactsBeforeTruncating() {
        String body = "{\"pad\":\"" + "x".repeat(20) + "\",\"password\":\"" + "SECRET".repeat(20) + "\"}";

        String sanitized = extract(body, "application/json", 40);

        assertThat(sanitized).doesNotContain("SECRET").endsWith("...[truncated]");
    }

    @Test
    @DisplayName("non-JSON and invalid JSON bodies are not captured")
    void nonJsonNotCaptured() {
        assertThat(extract("password=hunter2", "application/x-www-form-urlencoded", LIMIT)).isNull();
        assertThat(extract("password: hunter2", "text/plain", LIMIT)).isNull();
        assertThat(extract("{\"password\":\"hunter2\"", "application/json", LIMIT)).isNull();
        assertThat(extract("{\"a\":1}", null, LIMIT)).isNull();
    }
}
