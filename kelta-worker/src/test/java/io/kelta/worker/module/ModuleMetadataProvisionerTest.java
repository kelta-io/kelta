package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.ModuleManifest;
import io.kelta.runtime.module.ModuleManifest.CollectionManifest;
import io.kelta.runtime.module.ModuleManifest.CollectionManifest.FieldManifest;
import io.kelta.worker.service.PackageImportService;
import io.kelta.worker.service.PackageImportService.ImportOptions;
import io.kelta.worker.service.PackageImportService.ImportReport;
import io.kelta.worker.service.PackageImportService.ItemResult;
import io.kelta.worker.service.TenantSlugResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ModuleMetadataProvisioner")
class ModuleMetadataProvisionerTest {

    private static final String TENANT = "tenant-1";
    private static final String SLUG = "acme";
    private static final String HEX_64 = "^[0-9a-f]{64}$";

    private PackageImportService importService;
    private ModuleMetadataProvisioner provisioner;

    /** Collections the fake importer treats as already present in the tenant. */
    private final Set<String> existing = new HashSet<>();
    /** Item keys ("TYPE:key") the fake importer fails in the apply pass. */
    private final Set<String> failing = new HashSet<>();
    private final List<ImportOptions> calls = new ArrayList<>();
    private final List<Map<String, Object>> packages = new ArrayList<>();
    private final List<String> slugsSeen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        importService = mock(PackageImportService.class);
        TenantSlugResolver slugResolver = mock(TenantSlugResolver.class);
        when(slugResolver.resolveSlug(TENANT)).thenReturn(Optional.of(SLUG));
        when(importService.importPackage(anyString(), anyMap(), any())).thenAnswer(inv -> {
            slugsSeen.add(TenantContext.getSlug());
            Map<String, Object> pkg = inv.getArgument(1);
            ImportOptions options = inv.getArgument(2);
            packages.add(pkg);
            calls.add(options);
            return fakeImport(pkg, options);
        });
        provisioner = new ModuleMetadataProvisioner(importService, slugResolver, new ObjectMapper());
    }

    /** SKIP-mode importer: existing collections skip, the key filter excludes, failing keys fail. */
    @SuppressWarnings("unchecked")
    private ImportReport fakeImport(Map<String, Object> pkg, ImportOptions options) {
        List<ItemResult> results = new ArrayList<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) pkg.get("items")) {
            String type = (String) item.get("type");
            String key = PackageImportService.naturalKeyFor(type, (Map<String, Object>) item.get("data"));
            if (options.itemKeyFilter() != null && !options.itemKeyFilter().contains(type + ":" + key)) {
                continue;
            }
            if (!options.dryRun() && failing.contains(type + ":" + key)) {
                results.add(new ItemResult(type, key, "FAILED", "boom"));
            } else if ("COLLECTION".equals(type) && existing.contains(key)) {
                results.add(new ItemResult(type, key, "SKIPPED", null, "existing-" + key));
            } else {
                results.add(new ItemResult(type, key, "CREATED", null,
                        options.dryRun() ? "dry-run:" + key : "id-" + key));
            }
        }
        return new ImportReport(0, 0, 0, 0, results);
    }

    private static ModuleManifest manifest(List<CollectionManifest> collections) {
        return new ModuleManifest("test-module", "Test", "1.0.0", null, null, "x.Module", null,
                List.of(), List.of(), collections, null, null, List.of(), List.of());
    }

    private static CollectionManifest invoices() {
        return new CollectionManifest("invoices", "Invoices", List.of(
                new FieldManifest("reference", "Reference", "STRING", true),
                new FieldManifest("amount", null, "DECIMAL", false)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> pkg) {
        return (List<Map<String, Object>>) pkg.get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> item) {
        return (Map<String, Object>) item.get("data");
    }

    @Test
    @DisplayName("translates a collection and its fields into package items keyed by column name")
    void translatesManifestToPackage() {
        provisioner.provision(TENANT, manifest(List.of(invoices())), "user-1");

        List<Map<String, Object>> items = items(packages.get(0));
        assertThat(items).extracting(i -> i.get("type")).containsExactly("COLLECTION", "FIELD", "FIELD");
        assertThat(data(items.get(0))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "name", "invoices", "display_name", "Invoices", "path", "/api/invoices",
                "active", true, "current_version", 1));
        assertThat(data(items.get(1))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "collection_name", "invoices", "name", "reference", "display_name", "Reference",
                "type", "STRING", "required", true, "field_order", 0, "active", true));
        // An omitted display name falls back to the field name.
        assertThat(data(items.get(2)))
                .containsEntry("display_name", "amount")
                .containsEntry("required", false)
                .containsEntry("field_order", 1);
        assertThat(calls.get(0).executingUserId()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("defaults the collection display name to the name with a capitalised first letter")
    void defaultsDisplayName() {
        provisioner.provision(TENANT,
                manifest(List.of(new CollectionManifest("invoices", null, List.of()))), "user-1");

        assertThat(data(items(packages.get(0)).get(0))).containsEntry("display_name", "Invoices");
    }

    @Test
    @DisplayName("a new collection is created with its fields: CREATED resources with ids and hashes")
    void newCollectionIsCreated() {
        var result = provisioner.provision(TENANT, manifest(List.of(invoices())), "user-1");

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).dryRun()).isTrue();
        assertThat(calls.get(0).conflictMode()).isEqualTo(PackageImportService.ConflictMode.SKIP);
        assertThat(calls.get(1).dryRun()).isFalse();
        assertThat(calls.get(1).conflictMode()).isEqualTo(PackageImportService.ConflictMode.SKIP);
        assertThat(calls.get(1).itemKeyFilter()).containsExactlyInAnyOrder(
                "COLLECTION:invoices", "FIELD:invoices.reference", "FIELD:invoices.amount");

        assertThat(result.failures()).isEmpty();
        assertThat(result.resources()).extracting(
                        ModuleMetadataProvisioner.ProvisionedResource::type,
                        ModuleMetadataProvisioner.ProvisionedResource::naturalKey,
                        ModuleMetadataProvisioner.ProvisionedResource::resourceId,
                        ModuleMetadataProvisioner.ProvisionedResource::ownership)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("COLLECTION", "invoices", "id-invoices", "CREATED"),
                        org.assertj.core.groups.Tuple.tuple("FIELD", "invoices.reference",
                                "id-invoices.reference", "CREATED"),
                        org.assertj.core.groups.Tuple.tuple("FIELD", "invoices.amount",
                                "id-invoices.amount", "CREATED"));
        assertThat(result.resources()).allSatisfy(r -> assertThat(r.contentHash()).matches(HEX_64));
        // Each hash covers that item's own data.
        assertThat(result.resources()).extracting(
                ModuleMetadataProvisioner.ProvisionedResource::contentHash).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an existing collection is adopted: one ADOPTED resource, no hash, its fields never written")
    void existingCollectionIsAdopted() {
        existing.add("invoices");

        var result = provisioner.provision(TENANT, manifest(List.of(invoices())), "user-1");

        assertThat(calls.get(1).itemKeyFilter()).containsExactly("COLLECTION:invoices");
        assertThat(calls.get(1).itemKeyFilter()).noneMatch(k -> k.startsWith("FIELD:"));
        assertThat(result.resources()).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo("COLLECTION");
            assertThat(r.naturalKey()).isEqualTo("invoices");
            assertThat(r.resourceId()).isEqualTo("existing-invoices");
            assertThat(r.ownership()).isEqualTo("ADOPTED");
            assertThat(r.contentHash()).isNull();
        });
    }

    @Test
    @DisplayName("ownership comes from the apply pass: a collection that appears after the dry run is ADOPTED")
    void ownershipFromApplyPass() {
        doAnswer(inv -> {
            ImportOptions options = inv.getArgument(2);
            calls.add(options);
            if (!options.dryRun()) {
                existing.add("invoices");
            }
            return fakeImport(inv.getArgument(1), options);
        }).when(importService).importPackage(anyString(), anyMap(), any());

        var result = provisioner.provision(TENANT, manifest(List.of(invoices())), "user-1");

        // The dry run said "new", so its fields were in the filter — but none is claimed.
        assertThat(calls.get(1).itemKeyFilter()).contains("FIELD:invoices.reference");
        assertThat(result.resources()).singleElement()
                .satisfies(r -> assertThat(r.ownership()).isEqualTo("ADOPTED"));
    }

    @Test
    @DisplayName("creates new collections and adopts existing ones in the same run")
    void mixedCreatedAndAdopted() {
        existing.add("existing");

        var result = provisioner.provision(TENANT, manifest(List.of(
                new CollectionManifest("brand_new", null,
                        List.of(new FieldManifest("reference", null, "STRING", false))),
                new CollectionManifest("existing", null,
                        List.of(new FieldManifest("reference", null, "STRING", false))))), "user-1");

        assertThat(calls.get(1).itemKeyFilter()).containsExactlyInAnyOrder(
                "COLLECTION:brand_new", "COLLECTION:existing", "FIELD:brand_new.reference");
        assertThat(result.resources()).extracting(
                        ModuleMetadataProvisioner.ProvisionedResource::naturalKey,
                        ModuleMetadataProvisioner.ProvisionedResource::ownership)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("brand_new", "CREATED"),
                        org.assertj.core.groups.Tuple.tuple("existing", "ADOPTED"),
                        org.assertj.core.groups.Tuple.tuple("brand_new.reference", "CREATED"));
    }

    @Test
    @DisplayName("rejects a malformed manifest before importing anything")
    void rejectsMalformedManifestBeforeImporting() {
        var bad = manifest(List.of(invoices(), new CollectionManifest("Not Valid", null, List.of())));

        assertThatThrownBy(() -> provisioner.provision(TENANT, bad, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not Valid");

        // The valid first entry must not have been imported — a bad manifest cannot
        // half-provision a tenant.
        verify(importService, never()).importPackage(anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("rejects a field with a blank type before importing anything")
    void rejectsBlankType() {
        var bad = manifest(List.of(new CollectionManifest(
                "invoices", null, List.of(new FieldManifest("reference", null, " ", false)))));

        assertThatThrownBy(() -> provisioner.provision(TENANT, bad, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no type");
        verify(importService, never()).importPackage(anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("rejects a field name the platform reserves")
    void rejectsReservedFieldName() {
        var bad = manifest(List.of(new CollectionManifest(
                "invoices", null, List.of(new FieldManifest("tenantId", null, "STRING", false)))));

        assertThatThrownBy(() -> provisioner.provision(TENANT, bad, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
        verify(importService, never()).importPackage(anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("does nothing when the manifest declares no collections")
    void noCollectionsIsANoOp() {
        assertThat(provisioner.provision(TENANT, manifest(List.of()), "user-1").resources()).isEmpty();
        assertThat(provisioner.provision(TENANT, manifest(null), "user-1").failures()).isEmpty();
        verify(importService, never()).importPackage(anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("a failed item lands in failures while the items that succeeded are still returned")
    void failedItemIsReported() {
        failing.add("FIELD:invoices.amount");

        var result = provisioner.provision(TENANT, manifest(List.of(invoices())), "user-1");

        assertThat(result.hasFailures()).isTrue();
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.naturalKey()).isEqualTo("invoices.amount");
            assertThat(f.action()).isEqualTo("FAILED");
        });
        assertThat(result.resources()).extracting(
                ModuleMetadataProvisioner.ProvisionedResource::naturalKey)
                .containsExactly("invoices", "invoices.reference");
    }

    @Test
    @DisplayName("binds the tenant slug from TenantSlugResolver around both import passes")
    void bindsSlug() {
        assertThat(TenantContext.getSlug()).isNull();

        provisioner.provision(TENANT, manifest(List.of(invoices())), "user-1");

        assertThat(slugsSeen).containsExactly(SLUG, SLUG);
    }

    @Test
    @DisplayName("the content hash does not depend on map insertion order")
    void hashIsStableAcrossInsertionOrder() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("x", 1);
        config.put("y", 2);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", "invoices");
        a.put("display_name", "Invoices");
        a.put("config", config);

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("y", 2);
        nested.put("x", 1);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("config", nested);
        b.put("display_name", "Invoices");
        b.put("name", "invoices");

        assertThat(provisioner.contentHash(a))
                .matches(HEX_64)
                .isEqualTo(provisioner.contentHash(b));
        b.put("name", "other");
        assertThat(provisioner.contentHash(b)).isNotEqualTo(provisioner.contentHash(a));
    }
}
