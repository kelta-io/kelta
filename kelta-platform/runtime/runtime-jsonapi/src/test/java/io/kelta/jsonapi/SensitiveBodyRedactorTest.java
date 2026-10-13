package io.kelta.jsonapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SensitiveBodyRedactor")
class SensitiveBodyRedactorTest {

    private static final int LIMIT = 32 * 1024;

    // Token-shaped fixtures built at runtime so the source never holds a literal token.
    private static final String PAT = "klt_" + "AbCdEf0123456789xyz";
    private static final String JWT = "eyJ" + "hbGciOiJIUzI1NiJ9" + ".eyJ" + "zdWIiOiJ1c2VyIn0" + ".c2lnbmF0dXJlLXZhbHVl";

    private static String redact(String json) {
        return SensitiveBodyRedactor.redactJson(json, LIMIT).orElseThrow().body();
    }

    private static void assertNotContains(String haystack, String... needles) {
        for (String needle : needles) {
            assertFalse(haystack.contains(needle), () -> "expected no '" + needle + "' in " + haystack);
        }
    }

    @Test
    @DisplayName("redacts keys that contain a secret word, not just the bare words")
    void compoundKeys() {
        String out = redact("""
                {"data":{"attributes":{"name":"Provider","clientSecret":"v1","client_secret":"v2",
                "newPassword":"v3","refreshToken":"v4","x-api-key":"v5","api_key":"v6","secret":"v7",
                "ssn":"v8","Authorization":"v9","credit-card":"v10","privateKey":"v11",
                "issuer":"https://idp.example.com"}}}""");

        assertNotContains(out, "\"v1\"", "\"v2\"", "\"v3\"", "\"v4\"", "\"v5\"", "\"v6\"", "\"v7\"",
                "\"v8\"", "\"v9\"", "\"v10\"", "\"v11\"");
        assertTrue(out.contains("\"clientSecret\":\"[REDACTED]\""), out);
        assertTrue(out.contains("\"name\":\"Provider\""), out);
        assertTrue(out.contains("\"issuer\":\"https://idp.example.com\""), out);
    }

    @Test
    @DisplayName("redacts at any depth and through arrays")
    void nestedAndArrays() {
        String out = redact("""
                {"items":[{"password":"p1"},{"list":[{"deep":{"accessToken":"p2"}}]}],
                "tokens":["p3","p4"],"clientSecret":{"current":"p5","previous":["p6"]},"keep":"ok"}""");

        assertNotContains(out, "p1", "p2", "p3", "p4", "p5", "p6");
        assertTrue(out.contains("\"keep\":\"ok\""), out);
        assertTrue(out.contains("\"tokens\":[\"[REDACTED]\",\"[REDACTED]\"]"), out);
    }

    @Test
    @DisplayName("keeps numbers and booleans under a sensitive key")
    void keepsNonStringScalars() {
        String out = redact("{\"tokenUsage\":{\"inputTokens\":12,\"cached\":true,\"model\":\"m\"},\"secret\":null}");

        assertTrue(out.contains("\"inputTokens\":12"), out);
        assertTrue(out.contains("\"cached\":true"), out);
        assertTrue(out.contains("\"model\":\"[REDACTED]\""), out);
        assertTrue(out.contains("\"secret\":null"), out);
    }

    @Test
    @DisplayName("redacts token-shaped values regardless of key")
    void tokenShapedValues() {
        String out = redact("{\"note\":\"use Bearer " + JWT + " here\",\"value\":\"" + PAT
                + "\",\"list\":[\"" + JWT + "\"],\"plain\":\"eyJ is fine alone\"}");

        assertNotContains(out, PAT, JWT, "c2lnbmF0dXJlLXZhbHVl");
        assertTrue(out.contains("\"note\":\"use Bearer [REDACTED] here\""), out);
        assertTrue(out.contains("\"value\":\"[REDACTED]\""), out);
        assertTrue(out.contains("\"plain\":\"eyJ is fine alone\""), out);
    }

    @Test
    @DisplayName("keys that merely contain the letters s-s-n are not redacted")
    void ssnIsExact() {
        assertTrue(redact("{\"classname\":\"keep\"}").contains("keep"));
    }

    @Test
    @DisplayName("a value with escaped quotes is redacted whole")
    void escapedQuotes() {
        assertEquals("{\"clientSecret\":\"[REDACTED]\",\"name\":\"n\"}",
                redact("{\"clientSecret\":\"a\\\"b\\\"c\",\"name\":\"n\"}"));
    }

    @Test
    @DisplayName("redacts before truncating: a secret straddling the limit never leaks")
    void redactsBeforeTruncating() {
        String prefix = "{\"pad\":\"" + "x".repeat(40) + "\",\"password\":\"";
        String secret = "SECRETVALUE-" + "y".repeat(100);
        String json = prefix + secret + "\"}";
        // Cut point falls inside the raw secret value.
        int limit = prefix.length() + 5;

        SensitiveBodyRedactor.Result result = SensitiveBodyRedactor.redactJson(json, limit).orElseThrow();

        assertTrue(result.truncated());
        assertEquals(limit, result.body().length());
        assertNotContains(result.body(), "SECRETVALUE", "yyyyy");
    }

    @Test
    @DisplayName("a body exactly at the limit is not truncated")
    void exactLimit() {
        String json = "{\"a\":\"b\"}";
        SensitiveBodyRedactor.Result result = SensitiveBodyRedactor.redactJson(json, json.length()).orElseThrow();

        assertFalse(result.truncated());
        assertEquals(json, result.body());
    }

    @Test
    @DisplayName("invalid or non-JSON bodies yield no content")
    void invalidJson() {
        assertEquals(Optional.empty(), SensitiveBodyRedactor.redactJson("password=hunter2&user=a", LIMIT));
        assertEquals(Optional.empty(), SensitiveBodyRedactor.redactJson("{\"password\":\"x\"", LIMIT));
        assertEquals(Optional.empty(), SensitiveBodyRedactor.redactJson("{\"a\":1} trailing", LIMIT));
        assertEquals(Optional.empty(), SensitiveBodyRedactor.redactJson("", LIMIT));
        assertEquals(Optional.empty(), SensitiveBodyRedactor.redactJson((String) null, LIMIT));
    }

    @Test
    @DisplayName("a root scalar is handled")
    void rootScalar() {
        assertEquals("\"[REDACTED]\"", redact("\"" + PAT + "\""));
        assertEquals("42", redact("42"));
    }

    @Test
    @DisplayName("sensitive paths are matched by whole segment")
    void sensitivePaths() {
        assertTrue(SensitiveBodyRedactor.isSensitivePath("/api/credentials"));
        assertTrue(SensitiveBodyRedactor.isSensitivePath("/acme/api/Credentials/123/test"));
        assertFalse(SensitiveBodyRedactor.isSensitivePath("/api/credentials-report"));
        assertFalse(SensitiveBodyRedactor.isSensitivePath("/api/users"));
        assertFalse(SensitiveBodyRedactor.isSensitivePath(null));
    }
}
