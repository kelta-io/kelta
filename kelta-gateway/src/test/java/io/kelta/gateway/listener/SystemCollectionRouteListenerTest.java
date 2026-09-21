package io.kelta.gateway.listener;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.route.RouteRefresher;
import io.kelta.gateway.route.RouteRegistry;
import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SystemCollectionRouteListener}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SystemCollectionRouteListener Tests")
class SystemCollectionRouteListenerTest {

    @Mock
    private RouteRegistry routeRegistry;

    @Mock
    private RouteRefresher routeRefresher;

    @Mock
    private GatewayCacheManager cacheManager;

    private ObjectMapper objectMapper;
    private SystemCollectionRouteListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new SystemCollectionRouteListener(
                routeRegistry, routeRefresher, objectMapper, cacheManager
        );
    }

    private String createEventMessage(String collectionName, String recordId, ChangeType changeType) throws Exception {
        RecordChangedPayload payload = new RecordChangedPayload(
                collectionName, recordId, changeType,
                Map.of("name", "test"), null, java.util.List.of());
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record." + changeType.name().toLowerCase(), "tenant-1", "user-1", payload);
        return objectMapper.writeValueAsString(event);
    }

    @Nested
    @DisplayName("Route Refresh Tests")
    class RouteRefreshTests {

        @Test
        @DisplayName("Should refresh routes when collections record changes")
        void shouldRefreshRoutesOnCollectionChange() throws Exception {
            String message = createEventMessage("collections", "col-1", ChangeType.CREATED);

            listener.onRecordChanged(message);

            verify(routeRefresher).refresh();
        }

        @Test
        @DisplayName("Should not refresh routes for non-collections system collections")
        void shouldNotRefreshRoutesForNonCollections() throws Exception {
            String message = createEventMessage("users", "user-1", ChangeType.UPDATED);

            listener.onRecordChanged(message);

            verify(routeRefresher, never()).refresh();
        }

        @Test
        @DisplayName("Should not refresh routes for user-defined collections")
        void shouldNotRefreshRoutesForUserCollections() throws Exception {
            String message = createEventMessage("products", "prod-1", ChangeType.CREATED);

            listener.onRecordChanged(message);

            verify(routeRefresher, never()).refresh();
        }
    }

    @Nested
    @DisplayName("Governor Limit Cache Refresh Tests")
    class GovernorLimitCacheTests {

        @Test
        @DisplayName("Should refresh governor limits when tenants collection changes")
        void shouldRefreshGovernorLimitsOnTenantChange() throws Exception {
            String message = createEventMessage("tenants", "tenant-1", ChangeType.UPDATED);

            listener.onRecordChanged(message);

            verify(cacheManager).refreshGovernorLimitsFromWorker();
        }

        @Test
        @DisplayName("Should NOT refresh governor limits for non-tenant collections")
        void shouldNotRefreshGovernorLimitsForNonTenantCollections() throws Exception {
            String message = createEventMessage("products", "prod-1", ChangeType.UPDATED);

            listener.onRecordChanged(message);

            verify(cacheManager, never()).refreshGovernorLimitsFromWorker();
        }
    }

    @Nested
    @DisplayName("Custom Domain Cache Invalidation Tests")
    class CustomDomainCacheTests {

        @Test
        @DisplayName("Should evict specific domain from cache when domain record changes")
        void shouldEvictDomainOnChange() throws Exception {
            RecordChangedPayload payload = new RecordChangedPayload(
                    "tenant_custom_domains", "domain-1", ChangeType.CREATED,
                    Map.of("domain", "app.acme.com"), null, java.util.List.of());
            PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                    "record.created", "tenant-1", "user-1", payload);
            String message = objectMapper.writeValueAsString(event);

            listener.onRecordChanged(message);

            verify(cacheManager).removeCustomDomain("app.acme.com");
        }

        @Test
        @DisplayName("Should evict all domains when domain field is missing from event data")
        void shouldEvictAllDomainsWhenDomainMissing() throws Exception {
            RecordChangedPayload payload = new RecordChangedPayload(
                    "tenant_custom_domains", "domain-1", ChangeType.DELETED,
                    Map.of(), null, java.util.List.of());
            PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                    "record.deleted", "tenant-1", "user-1", payload);
            String message = objectMapper.writeValueAsString(event);

            listener.onRecordChanged(message);

            verify(cacheManager).evictAllCustomDomains();
        }

        @Test
        @DisplayName("Should NOT evict domains for non-domain collections")
        void shouldNotEvictDomainsForOtherCollections() throws Exception {
            String message = createEventMessage("products", "prod-1", ChangeType.CREATED);

            listener.onRecordChanged(message);

            verify(cacheManager, never()).removeCustomDomain(anyString());
            verify(cacheManager, never()).evictAllCustomDomains();
        }
    }

    @Nested
    @DisplayName("System Collection Response Cache Invalidation Tests")
    class SystemCollectionResponseCacheTests {

        @Test
        @DisplayName("Should evict response cache when cacheable collection changes")
        void shouldEvictResponseCacheOnCacheableCollectionChange() throws Exception {
            String message = createEventMessage("collections", "col-1", ChangeType.UPDATED);

            listener.onRecordChanged(message);

            verify(cacheManager).evictSystemCollectionResponses("collections");
        }

        @Test
        @DisplayName("Should evict response cache for ui-pages changes")
        void shouldEvictResponseCacheForUiPages() throws Exception {
            String message = createEventMessage("ui-pages", "page-1", ChangeType.CREATED);

            listener.onRecordChanged(message);

            verify(cacheManager).evictSystemCollectionResponses("ui-pages");
        }

        @Test
        @DisplayName("Should NOT evict response cache for non-cacheable collections")
        void shouldNotEvictResponseCacheForNonCacheableCollections() throws Exception {
            String message = createEventMessage("users", "user-1", ChangeType.UPDATED);

            listener.onRecordChanged(message);

            verify(cacheManager, never()).evictSystemCollectionResponses(anyString());
        }

        @Test
        @DisplayName("Should NOT evict response cache for user-defined collections")
        void shouldNotEvictResponseCacheForUserCollections() throws Exception {
            String message = createEventMessage("products", "prod-1", ChangeType.CREATED);

            listener.onRecordChanged(message);

            verify(cacheManager, never()).evictSystemCollectionResponses(anyString());
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle malformed JSON gracefully")
        void shouldHandleMalformedJsonGracefully() {
            assertDoesNotThrow(() -> listener.onRecordChanged("not valid json"));
        }

        @Test
        @DisplayName("Should handle null collection name gracefully")
        void shouldHandleNullCollectionNameGracefully() throws Exception {
            RecordChangedPayload payload = new RecordChangedPayload();
            payload.setCollectionName(null);
            PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                    "record.created", "tenant-1", "user-1", payload);
            String message = objectMapper.writeValueAsString(event);

            assertDoesNotThrow(() -> listener.onRecordChanged(message));
            verifyNoInteractions(routeRefresher);
        }
    }
}
