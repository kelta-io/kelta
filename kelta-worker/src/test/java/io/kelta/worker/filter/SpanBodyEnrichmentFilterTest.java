package io.kelta.worker.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SpanBodyEnrichmentFilter redaction")
class SpanBodyEnrichmentFilterTest {

    @Test
    @DisplayName("redacts keys that contain a secret word, not just the bare words")
    void redactsCompoundKeys() {
        String body = """
                {"data":{"attributes":{"name":"Provider","clientSecret":"s3cr3t-value",
                "newPassword":"pw-value","accessToken":"tok-value","x-api_key":"key-value",
                "secret":"bare-value","ssn":"123-45-6789","issuer":"https://idp.example.com"}}}""";

        String sanitized = SpanBodyEnrichmentFilter.sanitize(body);

        assertThat(sanitized).doesNotContain("s3cr3t-value", "pw-value", "tok-value", "key-value",
                "bare-value", "123-45-6789");
        assertThat(sanitized).contains("\"clientSecret\":\"[REDACTED]\"", "\"name\":\"Provider\"",
                "\"issuer\":\"https://idp.example.com\"");
    }

    @Test
    @DisplayName("a value with escaped quotes is redacted whole")
    void escapedQuotes() {
        String sanitized = SpanBodyEnrichmentFilter.sanitize("{\"clientSecret\":\"a\\\"b\\\"c\",\"name\":\"n\"}");

        assertThat(sanitized).isEqualTo("{\"clientSecret\":\"[REDACTED]\",\"name\":\"n\"}");
    }

    @Test
    @DisplayName("keys that merely contain the letters s-s-n are not redacted")
    void ssnIsExact() {
        assertThat(SpanBodyEnrichmentFilter.sanitize("{\"classname\":\"keep\"}")).contains("keep");
    }
}
