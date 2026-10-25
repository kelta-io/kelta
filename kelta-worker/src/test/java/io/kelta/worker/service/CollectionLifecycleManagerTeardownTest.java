package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.registry.ConcurrentCollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two tenants may each own a collection with the same name. Tearing one down must leave the
 * other registered: unregistering by bare name removed whichever tenant's entry the registry
 * met first, so deleting tenant A's {@code orders} could make tenant B's {@code /api/orders}
 * 404 on that pod until restart.
 */
@DisplayName("CollectionLifecycleManager.teardownCollection — same-named collections in two tenants")
class CollectionLifecycleManagerTeardownTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";

    private ConcurrentCollectionRegistry registry;
    private CollectionLifecycleManager manager;

    @BeforeEach
    void setUp() {
        registry = new ConcurrentCollectionRegistry();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("FROM collection WHERE id"), eq("col-a")))
                .thenReturn(List.of(collectionRow("col-a", TENANT_A)));
        when(jdbcTemplate.queryForList(contains("FROM collection WHERE id"), eq("col-b")))
                .thenReturn(List.of(collectionRow("col-b", TENANT_B)));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyString())).thenReturn(null);
        manager = new CollectionLifecycleManager(registry, mock(StorageAdapter.class), jdbcTemplate,
                new ObjectMapper());
    }

    private static Map<String, Object> collectionRow(String id, String tenantId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("name", "orders");
        row.put("active", true);
        row.put("system_collection", false);
        row.put("tenant_id", tenantId);
        return row;
    }

    @Test
    @DisplayName("tearing down tenant A's `orders` leaves tenant B's registered, whichever was registered first")
    void teardownRemovesOnlyTheOwningTenantsEntry() {
        manager.initializeCollection("col-b", false);
        manager.initializeCollection("col-a", false);
        assertThat(TenantContext.callWithTenant(TENANT_A, () -> registry.get("orders"))).isNotNull();
        assertThat(TenantContext.callWithTenant(TENANT_B, () -> registry.get("orders"))).isNotNull();

        manager.teardownCollection("col-a");

        assertThat(TenantContext.callWithTenant(TENANT_A, () -> registry.get("orders"))).isNull();
        assertThat(TenantContext.callWithTenant(TENANT_B, () -> registry.get("orders")))
                .as("tenant B's `orders` survives tenant A's teardown")
                .isNotNull()
                .extracting(def -> def.tenantId())
                .isEqualTo(TENANT_B);
        assertThat(manager.getActiveCollections()).containsExactly("col-b");
    }
}
