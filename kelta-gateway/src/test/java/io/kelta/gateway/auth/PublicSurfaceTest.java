package io.kelta.gateway.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the gateway's unauthenticated attack surface (consumer-alerting slice 7).
 *
 * <p>Reads the <b>shipped</b> {@code application.yml} rather than fixtures, because
 * the risk being controlled is a future change quietly widening the allowlist:
 * a prefix is one line of YAML, it is easy to add for a legitimate reason, and
 * nothing else in the build would notice that a data endpoint became world
 * readable. Adding a public prefix must be a deliberate act that also edits this
 * test.
 *
 * <p>Public traffic reaching a consumer tenant makes this concrete — the parent
 * spec's posture is that there is <b>no public bulk-data API</b>, so a collection
 * or aggregate endpoint appearing here is a defect, not a feature.
 */
@DisplayName("Public surface Tests")
class PublicSurfaceTest {

    /**
     * The complete set of prefixes reachable without authentication. Every entry
     * is here because something outside the platform must reach it, and each
     * carries its own trust anchor (a signature, an HMAC token, or read-only
     * bootstrap data that is not tenant-private).
     */
    private static final List<String> EXPECTED_UNAUTHENTICATED = List.of(
            "/otel",                        // browser OTEL SDK export
            "/api/webhooks",                // inbound flow webhooks
            "/scim",                        // SCIM provisioning (own auth)
            "/api/track",                   // campaign open-pixel / click / unsubscribe (HMAC)
            "/api/telehealth/visits",       // signed visit links
            "/api/telehealth/webhooks",     // LiveKit webhook (signed JWT + body digest)
            "/api/modules/webhooks",        // runtime-module webhook (module owns verification)
            "/api/modules/.well-known/jwks.json"); // public module-signing keys (no secret, no tenant)

    /**
     * GET/HEAD-only bootstrap data the UI needs before anyone has signed in — exactly the
     * requests {@code kelta-ui/app/src/utils/bootstrapCache.ts} issues with no token.
     * All of it is tenant-scoped presentation metadata, none of it is tenant-private
     * business data; writes stay authenticated because this list is GET/HEAD only.
     */
    private static final List<String> EXPECTED_PUBLIC_GET = List.of(
            "/api/ui-pages",                // page structure
            "/api/ui-menus",                // nav structure
            "/api/ui-translations",         // tenant UI label overlay (i18n), rendered pre-login
            "/api/oidc-providers",          // which login buttons to render
            "/api/tenants");                // the tenant record behind the slug

    private static Properties shippedConfig() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        yaml.afterPropertiesSet();
        Properties properties = yaml.getObject();
        assertThat(properties).as("application.yml is readable").isNotNull();
        return properties;
    }

    private static List<String> configured(String key) {
        String value = shippedConfig().getProperty(key);
        assertThat(value).as("%s is set", key).isNotBlank();
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static PublicPathMatcher shippedMatcher() {
        return new PublicPathMatcher(
                configured("kelta.gateway.security.public-paths"),
                configured("kelta.gateway.security.unauthenticated-paths"));
    }

    private static boolean isPublic(String path, HttpMethod method) {
        return shippedMatcher().isPublicRequest(MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path).build()));
    }

    @Nested
    @DisplayName("Declared allowlist")
    class DeclaredAllowlist {

        @Test
        @DisplayName("the shipped unauthenticated prefixes are exactly the reviewed set")
        void unauthenticatedPrefixesAreExpected() {
            // If this fails, a prefix was added or removed. That is allowed —
            // but say why in the list above, and confirm the endpoint carries its
            // own trust anchor, because an unauthenticated path also bypasses
            // TenantIpAllowlistFilter.
            assertThat(configured("kelta.gateway.security.unauthenticated-paths"))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_UNAUTHENTICATED);
        }

        @Test
        @DisplayName("the shipped public GET prefixes are exactly the reviewed set")
        void publicPrefixesAreExpected() {
            assertThat(configured("kelta.gateway.security.public-paths"))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_PUBLIC_GET);
        }
    }

    @Nested
    @DisplayName("Data endpoints stay closed")
    class DataEndpointsStayClosed {

        @Test
        @DisplayName("no collection, record, or aggregate endpoint is reachable anonymously")
        void dataEndpointsRequireAuth() {
            // The launch posture is deliberately no public bulk-data API: the SEO
            // build reads aggregates with a service token, so nothing here needs
            // to be open, and anything open here is scrapeable.
            for (String path : List.of(
                    "/api/watches", "/api/watch-targets", "/api/alerts",
                    "/api/admin/availability/synthetic-events",
                    "/api/modules/kelta-billing/x/plans", "/api/modules/kelta-billing/x/me",
                    "/api/modules/kelta-billing/x/checkout-sessions",
                    "/api/collections", "/api/records", "/api/analytics", "/api/reports",
                    "/api/dashboards", "/api/users", "/api/files", "/api/devices",
                    "/api/operations", "/api/metadata", "/api/packages")) {
                for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST,
                        HttpMethod.PATCH, HttpMethod.DELETE)) {
                    assertThat(isPublic(path, method))
                            .as("%s %s must require authentication", method, path)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("a public prefix does not open its siblings")
        void prefixDoesNotLeakToSiblings() {
            // Billing has no unauthenticated path of its own: the processor webhook goes to the
            // module webhook route below. The retired compiled-in /api/billing prefix must not
            // have come back open.
            assertThat(isPublic("/api/billing/webhooks/stripe/t1", HttpMethod.POST)).isFalse();
            assertThat(isPublic("/api/billing", HttpMethod.GET)).isFalse();
            // Module member routes (billing's checkout, portal, me) stay authenticated.
            assertThat(isPublic("/api/modules/kelta-billing/x/me", HttpMethod.GET)).isFalse();
            // Modules: the webhook dispatch path is open, module administration is not.
            assertThat(isPublic("/api/modules/webhooks/t1/m1", HttpMethod.POST)).isTrue();
            assertThat(isPublic("/api/modules", HttpMethod.GET)).isFalse();
            assertThat(isPublic("/api/modules/install-jar", HttpMethod.POST)).isFalse();
            // External modules: the signing JWKS is open; plan/install by URL and the rest of
            // /api/modules/.well-known are not.
            assertThat(isPublic("/api/modules/.well-known/jwks.json", HttpMethod.GET)).isTrue();
            assertThat(isPublic("/api/modules/.well-known", HttpMethod.GET)).isFalse();
            assertThat(isPublic("/api/modules/.well-known/kelta-module.json", HttpMethod.GET)).isFalse();
            assertThat(isPublic("/api/modules/plan", HttpMethod.POST)).isFalse();
            assertThat(isPublic("/api/modules/install", HttpMethod.POST)).isFalse();
            assertThat(isPublic("/api/modules/acme/upgrade", HttpMethod.POST)).isFalse();
            assertThat(isPublic("/api/modules/acme/uninstall-plan", HttpMethod.GET)).isFalse();
            assertThat(isPublic("/api/telehealth/encounters", HttpMethod.GET)).isFalse();
        }
    }

    @Nested
    @DisplayName("Method scoping")
    class MethodScoping {

        @Test
        @DisplayName("bootstrap prefixes are readable but never writable anonymously")
        void publicPathsAreReadOnly() {
            for (String path : EXPECTED_PUBLIC_GET) {
                assertThat(isPublic(path, HttpMethod.GET)).as("GET %s", path).isTrue();
                assertThat(isPublic(path, HttpMethod.HEAD)).as("HEAD %s", path).isTrue();
                for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT,
                        HttpMethod.PATCH, HttpMethod.DELETE)) {
                    assertThat(isPublic(path, method)).as("%s %s", method, path).isFalse();
                }
            }
        }
    }
}
