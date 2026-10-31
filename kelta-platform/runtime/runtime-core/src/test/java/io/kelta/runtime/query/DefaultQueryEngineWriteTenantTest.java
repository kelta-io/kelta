package io.kelta.runtime.query;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.events.RecordEventPublisher;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.validation.FieldError;
import io.kelta.runtime.validation.ValidationEngine;
import io.kelta.runtime.validation.ValidationException;
import io.kelta.runtime.validation.ValidationResult;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tenant of a write: hooks and the record-changed event always run for the bound
 * {@link TenantContext} tenant, a payload {@code tenantId} naming another tenant is refused before
 * anything sees it, and only an unbound platform path takes the tenant from the data.
 */
@DisplayName("DefaultQueryEngine — the tenant of a write")
class DefaultQueryEngineWriteTenantTest {

    private static final String TENANT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String TENANT_B = "bbbbbbbb-0000-0000-0000-000000000002";

    private StorageAdapter storageAdapter;
    private RecordEventPublisher publisher;
    private BeforeSaveHookRegistry hooks;
    private DefaultQueryEngine engine;
    private CollectionDefinition products;
    private CollectionDefinition menus;

    /** Every hook call, as {@code "<who>:<phase>:<tenantId>"}. */
    private List<String> hookCalls;

    @BeforeEach
    void setUp() {
        storageAdapter = mock(StorageAdapter.class);
        publisher = mock(RecordEventPublisher.class);
        ValidationEngine validationEngine = mock(ValidationEngine.class);
        when(validationEngine.validate(any(), any(), any())).thenReturn(ValidationResult.success());
        when(validationEngine.validate(any(), any(), any(), any())).thenReturn(ValidationResult.success());
        hooks = new BeforeSaveHookRegistry();
        engine = new DefaultQueryEngine(storageAdapter, validationEngine, null, null, null, null, null,
                publisher, hooks);

        products = new CollectionDefinitionBuilder()
                .name("products").displayName("Products")
                .addField(FieldDefinition.string("name"))
                .build();
        menus = new CollectionDefinitionBuilder()
                .name("ui-menus").displayName("Menus")
                .addField(FieldDefinition.string("name"))
                .systemCollection(true)
                .tenantScoped(true)
                .build();

        hookCalls = new ArrayList<>();
        for (String collection : List.of("products", "ui-menus")) {
            hooks.register(recordingHook("global", collection));
            hooks.registerTenantHook(TENANT_A, recordingHook("module-a", collection));
            hooks.registerTenantHook(TENANT_B, recordingHook("module-b", collection));
        }

        when(storageAdapter.create(any(), anyMap())).thenAnswer(inv -> new HashMap<>(inv.<Map<String, Object>>getArgument(1)));
    }

    private BeforeSaveHook recordingHook(String who, String collection) {
        return new BeforeSaveHook() {
            @Override public String getCollectionName() { return collection; }
            @Override public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
                hookCalls.add(who + ":beforeCreate:" + tenantId);
                return BeforeSaveResult.ok();
            }
            @Override public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                                           Map<String, Object> previous, String tenantId) {
                hookCalls.add(who + ":beforeUpdate:" + tenantId);
                return BeforeSaveResult.ok();
            }
            @Override public BeforeSaveResult beforeDelete(String id, String tenantId) {
                hookCalls.add(who + ":beforeDelete:" + tenantId);
                return BeforeSaveResult.ok();
            }
            @Override public void afterCreate(Map<String, Object> record, String tenantId) {
                hookCalls.add(who + ":afterCreate:" + tenantId);
            }
            @Override public void afterUpdate(String id, Map<String, Object> record,
                                              Map<String, Object> previous, String tenantId) {
                hookCalls.add(who + ":afterUpdate:" + tenantId);
            }
            @Override public void afterDelete(String id, String tenantId) {
                hookCalls.add(who + ":afterDelete:" + tenantId);
            }
        };
    }

    private String publishedTenant() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<PlatformEvent<RecordChangedPayload>> captor = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue().getTenantId();
    }

    private static void assertForeignTenantRefusal(ValidationException ex) {
        FieldError error = ex.getValidationResult().errors().getFirst();
        assertThat(error.fieldName()).isEqualTo("tenantId");
        assertThat(error.constraint()).isEqualTo("systemMaintained");
    }

    @Nested
    @DisplayName("a payload tenantId naming another tenant than the bound one")
    class ForeignTenantId {

        @Test
        @DisplayName("is refused on create of a user collection before any hook or storage call")
        void refusedOnUserCollectionCreate() {
            Map<String, Object> data = new HashMap<>(Map.of("name", "Widget", "tenantId", TENANT_B));

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> TenantContext.runWithTenant(TENANT_A, () -> engine.create(products, data)));

            assertForeignTenantRefusal(ex);
            assertThat(hookCalls).isEmpty();
            verify(storageAdapter, never()).create(any(), anyMap());
            verify(publisher, never()).publish(any());
        }

        @Test
        @DisplayName("is refused on create of a tenant-scoped system collection")
        void refusedOnSystemCollectionCreate() {
            Map<String, Object> data = new HashMap<>(Map.of("name", "Main", "tenantId", TENANT_B));

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> TenantContext.runWithTenant(TENANT_A, () -> engine.create(menus, data)));

            assertForeignTenantRefusal(ex);
            assertThat(hookCalls).isEmpty();
            verify(storageAdapter, never()).create(any(), anyMap());
        }

        @Test
        @DisplayName("is refused on update before the record is even read")
        void refusedOnUpdate() {
            Map<String, Object> patch = new HashMap<>(Map.of("name", "Renamed", "tenantId", TENANT_B));

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> TenantContext.runWithTenant(TENANT_A, () -> engine.update(menus, "menu-1", patch)));

            assertForeignTenantRefusal(ex);
            assertThat(hookCalls).isEmpty();
            verify(storageAdapter, never()).getById(any(), anyString());
            verify(storageAdapter, never()).update(any(), anyString(), anyMap());
        }

        @Test
        @DisplayName("is refused on update of a user collection")
        void refusedOnUserCollectionUpdate() {
            Map<String, Object> patch = new HashMap<>(Map.of("name", "Renamed", "tenantId", TENANT_B));

            assertForeignTenantRefusal(assertThrows(ValidationException.class,
                    () -> TenantContext.runWithTenant(TENANT_A, () -> engine.update(products, "p-1", patch))));
            verify(storageAdapter, never()).update(any(), anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("with a tenant bound")
    class BoundTenant {

        @Test
        @DisplayName("a matching payload tenantId is accepted and hooks run for the bound tenant only")
        void matchingTenantIdIsAccepted() {
            Map<String, Object> data = new HashMap<>(Map.of("name", "Main", "tenantId", TENANT_A));

            TenantContext.runWithTenant(TENANT_A, () -> engine.create(menus, data));

            assertThat(hookCalls).containsExactlyInAnyOrder(
                    "global:beforeCreate:" + TENANT_A, "module-a:beforeCreate:" + TENANT_A,
                    "global:afterCreate:" + TENANT_A, "module-a:afterCreate:" + TENANT_A);
            assertThat(publishedTenant()).isEqualTo(TENANT_A);
        }

        @Test
        @DisplayName("create hooks and the event use the bound tenant, not the stored row's")
        void createUsesBoundTenantOverStoredRow() {
            // A storage read-back carrying another tenant's id (e.g. a superuser DB role with no RLS)
            // must not move the after-create hooks or the event to that tenant.
            when(storageAdapter.create(any(), anyMap())).thenAnswer(inv -> {
                Map<String, Object> row = new HashMap<>(inv.<Map<String, Object>>getArgument(1));
                row.put("tenantId", TENANT_B);
                return row;
            });

            TenantContext.runWithTenant(TENANT_A, () -> engine.create(products, new HashMap<>(Map.of("name", "W"))));

            assertThat(hookCalls).noneMatch(call -> call.startsWith("module-b") || call.endsWith(TENANT_B));
            assertThat(hookCalls).contains("module-a:beforeCreate:" + TENANT_A, "module-a:afterCreate:" + TENANT_A);
            assertThat(publishedTenant()).isEqualTo(TENANT_A);
        }

        @Test
        @DisplayName("update hooks and the event use the bound tenant, not the stored row's or the patch's")
        void updateUsesBoundTenantOverStoredRow() {
            Map<String, Object> stored = new HashMap<>(Map.of("id", "menu-1", "name", "Old", "tenantId", TENANT_B));
            when(storageAdapter.getById(menus, "menu-1")).thenReturn(Optional.of(stored));
            when(storageAdapter.update(eq(menus), eq("menu-1"), anyMap())).thenAnswer(inv -> {
                Map<String, Object> row = new HashMap<>(stored);
                row.putAll(inv.<Map<String, Object>>getArgument(2));
                return Optional.of(row);
            });

            TenantContext.runWithTenant(TENANT_A,
                    () -> engine.update(menus, "menu-1", new HashMap<>(Map.of("name", "New"))));

            assertThat(hookCalls).containsExactlyInAnyOrder(
                    "global:beforeUpdate:" + TENANT_A, "module-a:beforeUpdate:" + TENANT_A,
                    "global:afterUpdate:" + TENANT_A, "module-a:afterUpdate:" + TENANT_A);
            assertThat(publishedTenant()).isEqualTo(TENANT_A);
        }

        @Test
        @DisplayName("delete hooks and the event use the bound tenant, not the stored row's")
        void deleteUsesBoundTenantOverStoredRow() {
            when(storageAdapter.getById(menus, "menu-1")).thenReturn(Optional.of(
                    new HashMap<>(Map.of("id", "menu-1", "name", "Old", "tenantId", TENANT_B))));
            when(storageAdapter.delete(menus, "menu-1")).thenReturn(true);

            TenantContext.runWithTenant(TENANT_A, () -> engine.delete(menus, "menu-1"));

            assertThat(hookCalls).containsExactlyInAnyOrder(
                    "global:beforeDelete:" + TENANT_A, "module-a:beforeDelete:" + TENANT_A,
                    "global:afterDelete:" + TENANT_A, "module-a:afterDelete:" + TENANT_A);
            assertThat(publishedTenant()).isEqualTo(TENANT_A);
        }
    }

    @Nested
    @DisplayName("with no tenant bound (a platform path)")
    class UnboundPlatformPath {

        @Test
        @DisplayName("hooks and the event take the tenant from the data")
        void usesDataTenantId() {
            Map<String, Object> data = new HashMap<>(Map.of("name", "Main", "tenantId", TENANT_B));

            engine.create(menus, data);

            assertThat(hookCalls).containsExactlyInAnyOrder(
                    "global:beforeCreate:" + TENANT_B, "module-b:beforeCreate:" + TENANT_B,
                    "global:afterCreate:" + TENANT_B, "module-b:afterCreate:" + TENANT_B);
            assertThat(publishedTenant()).isEqualTo(TENANT_B);
            verify(storageAdapter, atLeastOnce()).create(eq(menus), anyMap());
        }

        @Test
        @DisplayName("no tenant anywhere falls back to \"default\" and runs only global hooks")
        void noTenantFallsBackToDefault() {
            engine.create(products, new HashMap<>(Map.of("name", "W")));

            assertThat(hookCalls).containsExactlyInAnyOrder("global:beforeCreate:default", "global:afterCreate:default");
            assertThat(publishedTenant()).isEqualTo("default");
        }
    }
}
