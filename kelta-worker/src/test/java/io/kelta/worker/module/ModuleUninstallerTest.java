package io.kelta.worker.module;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.listener.CollectionDeletionGuardHook;
import io.kelta.worker.service.TenantSlugResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ModuleUninstaller")
class ModuleUninstallerTest {

    private static final String TENANT = "tenant-1";
    private static final String MODULE = "acme-billing";

    private final List<Map<String, Object>> provenance = new ArrayList<>();
    private final Map<String, String> currentHashes = new HashMap<>();
    private final Map<String, Long> recordCounts = new HashMap<>();
    private final Map<String, Map<String, Integer>> dependents = new HashMap<>();
    private final List<String> deleted = new ArrayList<>();
    private final List<Boolean> forceSeen = new ArrayList<>();

    private ModuleProvenanceStore provenanceStore;
    private QueryEngine queryEngine;
    private ModuleUninstaller uninstaller;

    @BeforeEach
    void setUp() {
        provenanceStore = mock(ModuleProvenanceStore.class);
        when(provenanceStore.findByModule(TENANT, MODULE)).thenReturn(provenance);
        ModuleMetadataProvisioner provisioner = mock(ModuleMetadataProvisioner.class);
        when(provisioner.currentContentHash(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(currentHashes.get((String) inv.getArgument(1))));
        CollectionDeletionGuardHook guard = mock(CollectionDeletionGuardHook.class);
        when(guard.dependents(anyString(), eq(TENANT)))
                .thenAnswer(inv -> dependents.getOrDefault((String) inv.getArgument(0), Map.of()));

        CollectionRegistry registry = mock(CollectionRegistry.class);
        when(registry.get(anyString())).thenAnswer(inv -> definition(inv.getArgument(0)));
        queryEngine = mock(QueryEngine.class);
        when(queryEngine.aggregate(any(), anyList(), anyList())).thenAnswer(inv -> {
            String name = ((CollectionDefinition) inv.getArgument(0)).name();
            Long count = recordCounts.getOrDefault(name, 0L);
            if (count < 0) {
                throw new IllegalStateException("relation does not exist");
            }
            return Map.of("n", count);
        });
        when(queryEngine.delete(any(), anyString())).thenAnswer(inv -> {
            forceSeen.add(CollectionDeletionGuardHook.FORCE.isBound() && CollectionDeletionGuardHook.FORCE.get());
            deleted.add(((CollectionDefinition) inv.getArgument(0)).name() + ":" + inv.getArgument(1));
            return true;
        });

        TenantSlugResolver slugs = mock(TenantSlugResolver.class);
        when(slugs.resolveSlug(TENANT)).thenReturn(Optional.of("acme"));
        uninstaller = new ModuleUninstaller(provenanceStore, provisioner, guard, queryEngine, registry, slugs,
                new ObjectMapper());
    }

    private static CollectionDefinition definition(String name) {
        CollectionDefinition definition = mock(CollectionDefinition.class);
        when(definition.name()).thenReturn(name);
        return definition;
    }

    /** A provenance row the module CREATED, untouched since (its hash still matches). */
    private void created(String type, String key, String resourceId) {
        row(type, key, resourceId, ModuleProvenanceStore.OWNERSHIP_CREATED, "h-" + resourceId);
        currentHashes.put(resourceId, "h-" + resourceId);
    }

    private void row(String type, String key, String resourceId, String ownership, String hash) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "prov-" + resourceId);
        row.put("resource_type", type);
        row.put("natural_key", key);
        row.put("resource_id", resourceId);
        row.put("ownership", ownership);
        row.put("content_hash", hash);
        provenance.add(row);
    }

    private TenantModuleData module() {
        return new TenantModuleData("install-1", TENANT, MODULE, "Billing", "1.0.0", null,
                "https://billing.example.test", null, null, null, "{}", TenantModuleData.STATUS_ACTIVE, "user-1",
                null, null, null, List.of(), "https://billing.example.test", 1, "app-1", "svc-1");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> decisions(boolean purgeData) {
        Map<String, String> decisions = new HashMap<>();
        ((List<Map<String, Object>>) uninstaller.plan(module(), purgeData).get("entries"))
                .forEach(e -> decisions.put(e.get("resourceType") + ":" + e.get("naturalKey"),
                        (String) e.get("decision")));
        return decisions;
    }

    @Test
    @DisplayName("the decision table: adopted, gone, unverifiable, modified, data, dependents, remove")
    void decisionTable() {
        created("COLLECTION", "billing_plans", "col-plans");
        created("FIELD", "billing_plans.code", "fld-code");
        row("COLLECTION", "accounts", "col-accounts", ModuleProvenanceStore.OWNERSHIP_ADOPTED, null);
        created("FIELD", "accounts.billing_id", "fld-billing-id");
        recordCounts.put("accounts", 5L);
        row("COLLECTION", "billing_gone", "col-gone", ModuleProvenanceStore.OWNERSHIP_CREATED, "h");
        row("UI_MENU", "Billing", "menu-1", ModuleProvenanceStore.OWNERSHIP_CREATED, null);
        currentHashes.put("menu-1", "h-menu");
        row("VALIDATION_RULE", "billing_plans:positive", "rule-1", ModuleProvenanceStore.OWNERSHIP_CREATED, "old");
        currentHashes.put("rule-1", "edited-by-an-admin");
        created("COLLECTION", "billing_invoices", "col-invoices");
        recordCounts.put("billing_invoices", 3L);
        created("COLLECTION", "billing_events", "col-events");
        dependents.put("col-events", Map.of("page_layout", 1));

        assertThat(decisions(false))
                .containsEntry("COLLECTION:billing_plans", ModuleUninstaller.REMOVE)
                .containsEntry("FIELD:billing_plans.code", ModuleUninstaller.REMOVE)
                .containsEntry("COLLECTION:accounts", ModuleUninstaller.KEEP_ADOPTED)
                .containsEntry("FIELD:accounts.billing_id", ModuleUninstaller.KEEP_HAS_DATA)
                .containsEntry("COLLECTION:billing_gone", ModuleUninstaller.GONE)
                .containsEntry("UI_MENU:Billing", ModuleUninstaller.KEEP_UNVERIFIABLE)
                .containsEntry("VALIDATION_RULE:billing_plans:positive", ModuleUninstaller.KEEP_MODIFIED)
                .containsEntry("COLLECTION:billing_invoices", ModuleUninstaller.KEEP_HAS_DATA)
                .containsEntry("COLLECTION:billing_events", ModuleUninstaller.KEEP_HAS_DEPENDENTS);
    }

    @Test
    @DisplayName("purgeData removes collections and fields holding data, never an adopted or modified one")
    void purgeData() {
        created("COLLECTION", "billing_invoices", "col-invoices");
        recordCounts.put("billing_invoices", 3L);
        created("COLLECTION", "billing_events", "col-events");
        dependents.put("col-events", Map.of("page_layout", 1));
        row("COLLECTION", "accounts", "col-accounts", ModuleProvenanceStore.OWNERSHIP_ADOPTED, null);
        created("FIELD", "accounts.billing_id", "fld-billing-id");
        recordCounts.put("accounts", 5L);
        row("VALIDATION_RULE", "billing_plans:positive", "rule-1", ModuleProvenanceStore.OWNERSHIP_CREATED, "old");
        currentHashes.put("rule-1", "edited-by-an-admin");

        assertThat(decisions(true))
                .containsEntry("COLLECTION:billing_invoices", ModuleUninstaller.REMOVE)
                .containsEntry("COLLECTION:billing_events", ModuleUninstaller.REMOVE)
                .containsEntry("FIELD:accounts.billing_id", ModuleUninstaller.REMOVE)
                .containsEntry("COLLECTION:accounts", ModuleUninstaller.KEEP_ADOPTED)
                .containsEntry("VALIDATION_RULE:billing_plans:positive", ModuleUninstaller.KEEP_MODIFIED);
    }

    @Test
    @DisplayName("an uncountable collection is kept, never treated as empty")
    void uncountableIsKept() {
        created("COLLECTION", "billing_invoices", "col-invoices");
        recordCounts.put("billing_invoices", -1L);

        assertThat(decisions(false)).containsEntry("COLLECTION:billing_invoices", ModuleUninstaller.KEEP_HAS_DATA);
    }

    @Test
    @DisplayName("planHash is stable across calls and changes with purgeData")
    void planHash() {
        created("COLLECTION", "billing_plans", "col-plans");

        Object hash = uninstaller.plan(module(), false).get("planHash");
        assertThat(uninstaller.plan(module(), false).get("planHash")).isEqualTo(hash);
        assertThat(uninstaller.plan(module(), true).get("planHash")).isNotEqualTo(hash);
    }

    @Test
    @DisplayName("apply: fields before collections, a removed collection's fields go by cascade, FORCE = purgeData")
    @SuppressWarnings("unchecked")
    void applyOrderAndCascade() {
        created("COLLECTION", "billing_plans", "col-plans");
        created("FIELD", "billing_plans.code", "fld-code");
        row("COLLECTION", "accounts", "col-accounts", ModuleProvenanceStore.OWNERSHIP_ADOPTED, null);
        created("FIELD", "accounts.billing_id", "fld-billing-id");
        row("COLLECTION", "billing_gone", "col-gone", ModuleProvenanceStore.OWNERSHIP_CREATED, "h");

        Map<String, Object> result = uninstaller.apply(module(), false);

        // The adopted collection's own field is deleted on its own, before any collection.
        assertThat(deleted).containsExactly("fields:fld-billing-id", "collections:col-plans");
        assertThat(forceSeen).containsOnly(false);
        assertThat((List<Map<String, Object>>) result.get("removed"))
                .extracting(e -> e.get("naturalKey"))
                .containsExactlyInAnyOrder("accounts.billing_id", "billing_plans", "billing_plans.code");
        assertThat((List<Map<String, Object>>) result.get("retained"))
                .extracting(e -> e.get("decision")).containsExactly(ModuleUninstaller.KEEP_ADOPTED);
        assertThat((List<Map<String, Object>>) result.get("removed"))
                .allSatisfy(e -> assertThat(e).doesNotContainKeys("id", "resourceId", "cascade"));
        // Provenance goes for what was removed and what was already gone; the adopted row stays.
        verify(provenanceStore).deleteRow(TENANT, "prov-col-gone");
        verify(provenanceStore).deleteRow(TENANT, "prov-col-plans");
        verify(provenanceStore).deleteRow(TENANT, "prov-fld-code");
        verify(provenanceStore, never()).deleteRow(TENANT, "prov-col-accounts");
    }

    @Test
    @DisplayName("apply with purgeData binds FORCE so the deletion guard admits the delete")
    void applyBindsForce() {
        created("COLLECTION", "billing_invoices", "col-invoices");
        recordCounts.put("billing_invoices", 3L);

        uninstaller.apply(module(), true);

        assertThat(deleted).containsExactly("collections:col-invoices");
        assertThat(forceSeen).containsExactly(true);
    }

    @Test
    @DisplayName("a refused collection delete is KEEP_HAS_DEPENDENTS without purge; other failures are FAILED")
    @SuppressWarnings("unchecked")
    void failedDeletes() {
        created("COLLECTION", "billing_plans", "col-plans");
        created("UI_MENU", "Billing", "menu-1");
        doThrow(new IllegalStateException("refused")).when(queryEngine).delete(any(), anyString());

        Map<String, Object> result = uninstaller.apply(module(), false);

        assertThat((List<Map<String, Object>>) result.get("retained"))
                .extracting(e -> e.get("naturalKey") + "=" + e.get("decision"))
                .containsExactly("billing_plans=" + ModuleUninstaller.KEEP_HAS_DEPENDENTS);
        assertThat((List<Map<String, Object>>) result.get("failed"))
                .extracting(e -> e.get("naturalKey") + "=" + e.get("decision") + ":" + e.get("error"))
                .containsExactly("Billing=" + ModuleUninstaller.FAILED + ":refused");
        assertThat((List<Map<String, Object>>) result.get("removed")).isEmpty();
        verify(provenanceStore, never()).deleteRow(eq(TENANT), anyString());
    }
}
