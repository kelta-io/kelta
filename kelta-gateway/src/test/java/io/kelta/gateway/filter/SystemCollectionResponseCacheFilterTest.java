package io.kelta.gateway.filter;

import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.cache.GatewayCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("SystemCollectionResponseCacheFilter Tests")
class SystemCollectionResponseCacheFilterTest {

    @Mock
    private GatewayCacheManager cacheManager;

    /** {@code RouteAuthorizationFilter.getOrder()}. */
    private static final int ROUTE_AUTHORIZATION_ORDER = 0;

    /** {@code HeaderTransformationFilter.getOrder()}. */
    private static final int HEADER_TRANSFORMATION_ORDER = 50;

    private SystemCollectionResponseCacheFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SystemCollectionResponseCacheFilter(cacheManager);
    }

    @Nested
    @DisplayName("extractCollectionName Tests")
    class ExtractCollectionNameTests {

        @Test
        void extractsCollectionNameFromListPath() {
            assertThat(SystemCollectionResponseCacheFilter.extractCollectionName("/api/collections"))
                    .isEqualTo("collections");
        }

        @Test
        void extractsCollectionNameFromGetByIdPath() {
            assertThat(SystemCollectionResponseCacheFilter.extractCollectionName("/api/collections/abc-123"))
                    .isEqualTo("collections");
        }

        @Test
        void extractsHyphenatedCollectionName() {
            assertThat(SystemCollectionResponseCacheFilter.extractCollectionName("/api/ui-pages"))
                    .isEqualTo("ui-pages");
        }

        @Test
        void extractsFromSubResourcePath() {
            assertThat(SystemCollectionResponseCacheFilter.extractCollectionName("/api/collections/abc/fields"))
                    .isEqualTo("collections");
        }

        @Test
        void returnsNullForNonApiPath() {
            assertThat(SystemCollectionResponseCacheFilter.extractCollectionName("/internal/health"))
                    .isNull();
        }

        @Test
        void returnsNullForRootApiPath() {
            assertThat(SystemCollectionResponseCacheFilter.extractCollectionName("/api"))
                    .isNull();
        }

        @Test
        void returnsNullForEmptyPath() {
            assertThat(SystemCollectionResponseCacheFilter.extractCollectionName(""))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("CACHEABLE_COLLECTIONS Tests")
    class CacheableCollectionsTests {

        @Test
        void containsHighTrafficCollections() {
            assertThat(SystemCollectionResponseCacheFilter.CACHEABLE_COLLECTIONS)
                    .contains("ui-pages", "ui-menus", "ui-menu-items",
                            "collections", "fields", "tenants", "oidc-providers");
        }

        @Test
        void doesNotContainUserDataCollections() {
            assertThat(SystemCollectionResponseCacheFilter.CACHEABLE_COLLECTIONS)
                    .doesNotContain("users", "notes", "attachments", "bulk-jobs");
        }
    }

    @Nested
    @DisplayName("Filter order")
    class FilterOrderTests {

        @Test
        @DisplayName("the lookup runs after route authorization and before the worker headers")
        void lookupRunsAfterRouteAuthorization() {
            assertThat(filter.getOrder())
                    .isGreaterThan(ROUTE_AUTHORIZATION_ORDER)
                    .isLessThan(HEADER_TRANSFORMATION_ORDER);
        }

        @Test
        @DisplayName("the capture runs before the gateway writes the routed response")
        void captureRunsBeforeTheResponseIsWritten() {
            assertThat(new SystemCollectionResponseCaptureFilter(cacheManager).getOrder())
                    .isLessThan(NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER);
        }
    }

    @Nested
    @DisplayName("Cache key")
    class CacheKeyTests {

        private final GatewayPrincipal base = new GatewayPrincipal("a@example.test", List.of("g1", "g2"),
                Map.of("user_type", "INTERNAL"))
                .withProfileId("profile-a")
                .withTenantId("tenant-1")
                .withUserId("user-a");

        @Test
        @DisplayName("is the same for the same caller")
        void stableForTheSameCaller() {
            GatewayPrincipal same = new GatewayPrincipal("a@example.test", List.of("g2", "g1"),
                    Map.of("user_type", "INTERNAL"))
                    .withProfileId("profile-a")
                    .withTenantId("tenant-1")
                    .withUserId("user-a");

            assertThat(SystemCollectionResponseCacheFilter.callerKey(same))
                    .isEqualTo(SystemCollectionResponseCacheFilter.callerKey(base));
        }

        @Test
        @DisplayName("differs for every caller attribute the worker can decide on")
        void differsPerCallerAttribute() {
            String key = SystemCollectionResponseCacheFilter.callerKey(base);
            List<GatewayPrincipal> others = List.of(
                    base.withUserId("user-b"),
                    base.withProfileId("profile-b"),
                    base.withTenantId("tenant-2"),
                    base.withClaim("user_type", "PORTAL"),
                    base.withConnectedAppId("app-1"),
                    base.withAppScopes("read"),
                    base.withGeoCountry("PT"),
                    new GatewayPrincipal("b@example.test", base.getGroups(), base.getClaims())
                            .withProfileId("profile-a").withTenantId("tenant-1").withUserId("user-a"),
                    new GatewayPrincipal("a@example.test", List.of("g1"), base.getClaims())
                            .withProfileId("profile-a").withTenantId("tenant-1").withUserId("user-a"));

            assertThat(others).allSatisfy(other ->
                    assertThat(SystemCollectionResponseCacheFilter.callerKey(other)).isNotEqualTo(key));
            assertThat(SystemCollectionResponseCacheFilter.callerKey(null))
                    .isEqualTo(SystemCollectionResponseCacheFilter.ANONYMOUS_CALLER)
                    .isNotEqualTo(key);
        }

        @Test
        @DisplayName("keeps the path matchable by collection-name eviction")
        void keyKeepsThePathForEviction() {
            String callerKey = SystemCollectionResponseCacheFilter.callerKey(base);
            String key = SystemCollectionResponseCacheFilter.buildCacheKey(
                    "tenant-1", callerKey, "/api/ui-menus", "include=items");

            assertThat(key).isEqualTo("tenant-1:" + callerKey + ":/api/ui-menus?include=items");
            assertThat(callerKey).matches("[0-9a-f]{64}");
        }
    }
}
