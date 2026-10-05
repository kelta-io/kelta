package io.kelta.gateway.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PublicPathMatcher}.
 */
class PublicPathMatcherTest {

    private static final List<String> BOOTSTRAP_PATHS = List.of(
            "/api/ui-pages", "/api/ui-menus", "/api/oidc-providers", "/api/tenants");

    private static final List<String> UNAUTHENTICATED_PATHS = List.of("/otel");

    @Test
    void shouldReturnTrueForGetOnPublicPath() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, Collections.emptyList());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/ui-pages").build());

        assertThat(matcher.isPublicRequest(exchange)).isTrue();
    }

    @Test
    void shouldReturnTrueForHeadOnPublicPath() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, Collections.emptyList());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.head("/api/ui-menus").build());

        assertThat(matcher.isPublicRequest(exchange)).isTrue();
    }

    @Test
    void shouldReturnFalseForPostOnPublicPath() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, Collections.emptyList());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/ui-pages").build());

        assertThat(matcher.isPublicRequest(exchange)).isFalse();
    }

    @Test
    void shouldReturnFalseForPutOnPublicPath() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, Collections.emptyList());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.put("/api/ui-pages/page-1").build());

        assertThat(matcher.isPublicRequest(exchange)).isFalse();
    }

    @Test
    void shouldReturnFalseForPatchOnPublicPath() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, Collections.emptyList());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.patch("/api/tenants/tenant-1").build());

        assertThat(matcher.isPublicRequest(exchange)).isFalse();
    }

    @Test
    void shouldReturnFalseForDeleteOnPublicPath() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, Collections.emptyList());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.delete("/api/oidc-providers/provider-1").build());

        assertThat(matcher.isPublicRequest(exchange)).isFalse();
    }

    @Test
    void shouldReturnFalseForGetOnNonPublicPath() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, Collections.emptyList());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());

        assertThat(matcher.isPublicRequest(exchange)).isFalse();
    }

    @Test
    void shouldReturnFalseWhenNoPublicPathsConfigured() {
        PublicPathMatcher matcher = new PublicPathMatcher(Collections.emptyList(), Collections.emptyList());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/ui-pages").build());

        assertThat(matcher.isPublicRequest(exchange)).isFalse();
    }

    @Test
    void shouldMatchByPrefix() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, Collections.emptyList());

        // /api/ui-pages/some-id should match the /api/ui-pages prefix
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/ui-pages/some-page-id").build());

        assertThat(matcher.isPublicRequest(exchange)).isTrue();
    }

    @Test
    void shouldMatchAllConfiguredPaths() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, Collections.emptyList());

        for (String path : BOOTSTRAP_PATHS) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(path).build());
            assertThat(matcher.isPublicRequest(exchange))
                    .as("Expected %s to be public", path)
                    .isTrue();
        }
    }

    // --- Unauthenticated paths tests (all HTTP methods allowed) ---

    @Test
    void shouldAllowPostOnUnauthenticatedPath() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, UNAUTHENTICATED_PATHS);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/otel/v1/traces").build());

        assertThat(matcher.isPublicRequest(exchange)).isTrue();
    }

    @Test
    void shouldAllowGetOnUnauthenticatedPath() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, UNAUTHENTICATED_PATHS);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/otel/v1/traces").build());

        assertThat(matcher.isPublicRequest(exchange)).isTrue();
    }

    @Test
    void shouldAllowPutOnUnauthenticatedPath() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, UNAUTHENTICATED_PATHS);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.put("/otel/v1/traces").build());

        assertThat(matcher.isPublicRequest(exchange)).isTrue();
    }

    @Test
    void shouldAllowDeleteOnUnauthenticatedPath() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, UNAUTHENTICATED_PATHS);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.delete("/otel/v1/traces").build());

        assertThat(matcher.isPublicRequest(exchange)).isTrue();
    }

    @Test
    void shouldMatchUnauthenticatedPathByPrefix() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, UNAUTHENTICATED_PATHS);

        // /otel/v1/traces should match the /otel prefix
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/otel/v1/traces").build());

        assertThat(matcher.isPublicRequest(exchange)).isTrue();
    }

    @Test
    void shouldNotMatchNonUnauthenticatedPathForPost() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, UNAUTHENTICATED_PATHS);

        // POST to a non-unauthenticated path should still require auth
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/users").build());

        assertThat(matcher.isPublicRequest(exchange)).isFalse();
    }

    @Test
    void shouldReturnFalseWhenNoUnauthenticatedPathsConfigured() {
        PublicPathMatcher matcher = new PublicPathMatcher(Collections.emptyList(), Collections.emptyList());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/otel/v1/traces").build());

        assertThat(matcher.isPublicRequest(exchange)).isFalse();
    }

    @Test
    void shouldCheckUnauthenticatedPathsBeforePublicPaths() {
        // If a path is in both lists, the unauthenticated-paths check should match first
        // (allowing all methods) without falling through to the public-paths method check
        List<String> bothPaths = List.of("/otel");
        PublicPathMatcher matcher = new PublicPathMatcher(bothPaths, bothPaths);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/otel/v1/traces").build());

        assertThat(matcher.isPublicRequest(exchange)).isTrue();
    }

    @Test
    void anonymousOnlyPathIsPublicWithoutAToken() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, Collections.emptyList(),
                List.of("/api/tenants"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/tenants").build());
        assertThat(matcher.isPublicRequest(exchange)).isTrue();
    }

    @Test
    void anonymousOnlyPathIsNotPublicWhenATokenIsPresent() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, Collections.emptyList(),
                List.of("/api/tenants"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/tenants").header("Authorization", "Bearer x").build());
        assertThat(matcher.isPublicRequest(exchange)).isFalse();
    }

    @Test
    void otherPublicPathsStayPublicWithAToken() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, Collections.emptyList(),
                List.of("/api/tenants"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/ui-pages").header("Authorization", "Bearer x").build());
        assertThat(matcher.isPublicRequest(exchange)).isTrue();
    }

    @Test
    void bootstrapPathsAreTenantScopedButUnauthenticatedPathsAreNot() {
        PublicPathMatcher matcher = new PublicPathMatcher(BOOTSTRAP_PATHS, UNAUTHENTICATED_PATHS);
        assertThat(matcher.isTenantScopedPublicPath("/api/ui-pages")).isTrue();
        assertThat(matcher.isTenantScopedPublicPath("/api/oidc-providers/abc")).isTrue();
        assertThat(matcher.isTenantScopedPublicPath("/otel/v1/traces")).isFalse();
        assertThat(matcher.isTenantScopedPublicPath("/api/users")).isFalse();
    }
}
