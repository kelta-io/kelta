package io.kelta.worker.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FlowWebhookPolicy")
class FlowWebhookPolicyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static FlowWebhookPolicy parse(String json) {
        return FlowWebhookPolicy.parse(json, MAPPER);
    }

    @Test
    @DisplayName("No trigger config or no webhook block is the default POST/JSON/no-secret policy")
    void absentBlockIsDefault() {
        assertThat(parse(null)).isEqualTo(FlowWebhookPolicy.DEFAULT);
        assertThat(parse("{}")).isEqualTo(FlowWebhookPolicy.DEFAULT);
        assertThat(parse("{\"cron\":\"0 * * * *\"}")).isEqualTo(FlowWebhookPolicy.DEFAULT);
        assertThat(FlowWebhookPolicy.DEFAULT.allowsMethod("POST")).isTrue();
        assertThat(FlowWebhookPolicy.DEFAULT.allowsMethod("GET")).isFalse();
        assertThat(FlowWebhookPolicy.DEFAULT.allowsBody(FlowWebhookPolicy.BodyType.FORM)).isFalse();
        assertThat(FlowWebhookPolicy.DEFAULT.authenticates(null, Map.of())).isTrue();
    }

    @Test
    @DisplayName("Reads methods, bodies and secret case-insensitively")
    void readsBlock() {
        FlowWebhookPolicy policy = parse(
                "{\"webhook\":{\"methods\":[\"get\",\"POST\"],\"bodies\":[\"JSON\",\"form\"],\"secret\":\"s3cret\"}}");

        assertThat(policy.methods()).isEqualTo(Set.of("GET", "POST"));
        assertThat(policy.bodies()).isEqualTo(Set.of(FlowWebhookPolicy.BodyType.JSON, FlowWebhookPolicy.BodyType.FORM));
        assertThat(policy.secret()).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("GET or form bodies without a secret are an invalid config")
    void getOrFormRequireSecret() {
        assertThatThrownBy(() -> parse("{\"webhook\":{\"methods\":[\"GET\"]}}"))
                .isInstanceOf(FlowWebhookPolicy.InvalidConfigException.class)
                .hasMessageContaining("require triggerConfig.webhook.secret");
        assertThatThrownBy(() -> parse("{\"webhook\":{\"bodies\":[\"form\"]}}"))
                .isInstanceOf(FlowWebhookPolicy.InvalidConfigException.class)
                .hasMessageContaining("require triggerConfig.webhook.secret");
    }

    @Test
    @DisplayName("Unknown methods or bodies, a blank secret or a non-object block are invalid")
    void rejectsMalformedBlock() {
        assertThatThrownBy(() -> parse("{\"webhook\":{\"methods\":[\"PUT\"],\"secret\":\"s\"}}"))
                .isInstanceOf(FlowWebhookPolicy.InvalidConfigException.class);
        assertThatThrownBy(() -> parse("{\"webhook\":{\"methods\":[]}}"))
                .isInstanceOf(FlowWebhookPolicy.InvalidConfigException.class);
        assertThatThrownBy(() -> parse("{\"webhook\":{\"bodies\":[\"xml\"],\"secret\":\"s\"}}"))
                .isInstanceOf(FlowWebhookPolicy.InvalidConfigException.class);
        assertThatThrownBy(() -> parse("{\"webhook\":{\"secret\":\"  \"}}"))
                .isInstanceOf(FlowWebhookPolicy.InvalidConfigException.class);
        assertThatThrownBy(() -> parse("{\"webhook\":true}"))
                .isInstanceOf(FlowWebhookPolicy.InvalidConfigException.class);
    }

    @Test
    @DisplayName("Secret header authenticates; a wrong or missing one does not")
    void headerSecret() {
        FlowWebhookPolicy policy = parse("{\"webhook\":{\"secret\":\"s3cret\"}}");

        assertThat(policy.authenticates("s3cret", Map.of())).isTrue();
        assertThat(policy.authenticates("s3cret!", Map.of())).isFalse();
        assertThat(policy.authenticates(null, Map.of())).isFalse();
    }

    @Test
    @DisplayName("Canonical query: decoded, sig excluded, sorted by name then value, joined with &")
    void canonicalQuery() {
        Map<String, List<String>> query = FlowWebhookPolicy.decodeParameters("u=42&e=a%40b.com&sig=ff&e=0&x");

        assertThat(FlowWebhookPolicy.canonicalQuery(query)).isEqualTo("e=0&e=a@b.com&u=42&x=");
    }

    @Test
    @DisplayName("sig over the canonical query authenticates, regardless of hex case; a tampered query does not")
    void signature() {
        FlowWebhookPolicy policy = parse("{\"webhook\":{\"secret\":\"s3cret\"}}");
        String sig = FlowWebhookPolicy.sign("s3cret", "email=a@b.com&token=t1");

        assertThat(policy.authenticates(null,
                FlowWebhookPolicy.decodeParameters("token=t1&email=a%40b.com&sig=" + sig))).isTrue();
        assertThat(policy.authenticates(null,
                FlowWebhookPolicy.decodeParameters("token=t1&email=a%40b.com&sig=" + sig.toUpperCase()))).isTrue();
        assertThat(policy.authenticates(null,
                FlowWebhookPolicy.decodeParameters("token=t2&email=a%40b.com&sig=" + sig))).isFalse();
        assertThat(policy.authenticates(null,
                FlowWebhookPolicy.decodeParameters("token=t1&email=a%40b.com&sig=" + sig + "&sig=" + sig))).isFalse();
    }

    @Test
    @DisplayName("sign matches a known HMAC-SHA256 vector (the docs' worked example)")
    void signKnownVector() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?"
        assertThat(FlowWebhookPolicy.sign("Jefe", "what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
        // automation/triggers.md worked example: `printf 'list=news&u=42' | openssl dgst -sha256 -hmac 'example-secret'`
        String canonical = FlowWebhookPolicy.canonicalQuery(FlowWebhookPolicy.decodeParameters("u=42&list=news"));
        assertThat(canonical).isEqualTo("list=news&u=42");
        assertThat(FlowWebhookPolicy.sign("example-secret", canonical))
                .isEqualTo("a9e75d97ecf1257b5afa05ae2fef47ae2a5590cc00f79bca49e0b85f1e075c8f");
    }

    @Test
    @DisplayName("Parameters with one value become strings, repeated ones lists")
    void stateValues() {
        Map<String, Object> values = FlowWebhookPolicy.toStateValues(
                FlowWebhookPolicy.decodeParameters("a=1&tag=x&tag=y&q=hello+world"));

        assertThat(values).containsEntry("a", "1")
                .containsEntry("tag", List.of("x", "y"))
                .containsEntry("q", "hello world");
    }

    @Test
    @DisplayName("Malformed percent-encoding is rejected")
    void malformedQuery() {
        assertThatThrownBy(() -> FlowWebhookPolicy.decodeParameters("a=%zz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Response: status + capped body + content type, or an absolute http(s) redirect")
    void validResponses() {
        FlowWebhookPolicy content = parse("{\"webhook\":{\"response\":"
                + "{\"status\":202,\"body\":\"<p>Done</p>\",\"contentType\":\"text/html\"}}}");
        assertThat(content.response()).isEqualTo(new FlowWebhookPolicy.Content(202, "<p>Done</p>", "text/html"));

        FlowWebhookPolicy defaults = parse("{\"webhook\":{\"response\":{\"body\":\"ok\"}}}");
        assertThat(defaults.response()).isEqualTo(new FlowWebhookPolicy.Content(200, "ok", "text/plain"));

        FlowWebhookPolicy redirect = parse("{\"webhook\":{\"response\":{\"redirect\":\"https://example.com/bye\"}}}");
        assertThat(redirect.response()).isEqualTo(new FlowWebhookPolicy.Redirect(URI.create("https://example.com/bye")));
    }

    @Test
    @DisplayName("An invalid response config falls back to the default response")
    void invalidResponsesFallBack() {
        String big = "x".repeat(FlowWebhookPolicy.MAX_RESPONSE_BODY_BYTES + 1);
        for (String response : List.of(
                "{\"status\":302,\"body\":\"no\"}",
                "{\"status\":\"200\"}",
                "{\"body\":\"" + big + "\"}",
                "{\"body\":\"x\",\"contentType\":\"application/javascript\"}",
                "{\"status\":204,\"body\":\"x\"}",
                "{\"redirect\":\"/relative\"}",
                "{\"redirect\":\"javascript:alert(1)\"}",
                "\"ok\"")) {
            assertThat(parse("{\"webhook\":{\"response\":" + response + "}}").response())
                    .as(response).isNull();
        }
    }
}
