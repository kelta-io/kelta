package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.groups.Tuple.tuple;
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
        provisioner = new ModuleMetadataProvisioner(importService, slugResolver, new ObjectMapper(),
                mock(io.kelta.runtime.query.QueryEngine.class),
                mock(io.kelta.runtime.registry.CollectionRegistry.class));
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

    /** A collection item, keyed by column name as {@code PackageImportService} maps rows. */
    private static Map<String, Object> collection(String name) {
        return item("COLLECTION", Map.of("name", name, "display_name", name, "path", "/api/" + name,
                "active", true));
    }

    private static Map<String, Object> field(String collection, String name) {
        return item("FIELD", Map.of("collection_name", collection, "name", name, "display_name", name,
                "type", "STRING", "active", true));
    }

    private static Map<String, Object> item(String type, Map<String, Object> data) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("data", data);
        return item;
    }

    @SafeVarargs
    private static Map<String, Object> pkg(Map<String, Object>... items) {
        return Map.of("items", List.of(items));
    }

    private static Map<String, Object> invoices() {
        return pkg(collection("invoices"), field("invoices", "reference"), field("invoices", "amount"));
    }

    private ModuleMetadataProvisioner.ProvisionResult provision(Map<String, Object> pkg) {
        return provisioner.provisionPackage(TENANT, "test-module", pkg, Set.of(), "user-1");
    }

    @Test
    @DisplayName("a new collection is created with its fields: a SKIP-mode dry run, then the apply pass")
    void newCollectionIsCreated() {
        var result = provision(invoices());

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).dryRun()).isTrue();
        assertThat(calls.get(0).conflictMode()).isEqualTo(PackageImportService.ConflictMode.SKIP);
        assertThat(calls.get(1).dryRun()).isFalse();
        assertThat(calls.get(1).conflictMode()).isEqualTo(PackageImportService.ConflictMode.SKIP);
        assertThat(calls.get(1).itemKeyFilter()).containsExactlyInAnyOrder(
                "COLLECTION:invoices", "FIELD:invoices.reference", "FIELD:invoices.amount");
        assertThat(calls.get(1).executingUserId()).isEqualTo("user-1");

        assertThat(result.failures()).isEmpty();
        assertThat(result.resources()).extracting(
                        ModuleMetadataProvisioner.ProvisionedResource::type,
                        ModuleMetadataProvisioner.ProvisionedResource::naturalKey,
                        ModuleMetadataProvisioner.ProvisionedResource::resourceId,
                        ModuleMetadataProvisioner.ProvisionedResource::ownership)
                .containsExactly(
                        tuple("COLLECTION", "invoices", "id-invoices", "CREATED"),
                        tuple("FIELD", "invoices.reference", "id-invoices.reference", "CREATED"),
                        tuple("FIELD", "invoices.amount", "id-invoices.amount", "CREATED"));
    }

    @Test
    @DisplayName("an existing collection is adopted: one ADOPTED resource, no hash, its fields never written")
    void existingCollectionIsAdopted() {
        existing.add("invoices");

        var result = provision(invoices());

        assertThat(calls.get(1).itemKeyFilter()).containsExactly("COLLECTION:invoices");
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

        var result = provision(invoices());

        // The dry run said "new", so its fields were in the filter — but none is claimed.
        assertThat(calls.get(1).itemKeyFilter()).contains("FIELD:invoices.reference");
        assertThat(result.resources()).singleElement()
                .satisfies(r -> assertThat(r.ownership()).isEqualTo("ADOPTED"));
    }

    @Test
    @DisplayName("creates new collections and adopts existing ones in the same run")
    void mixedCreatedAndAdopted() {
        existing.add("existing");

        var result = provision(pkg(collection("brand_new"), collection("existing"),
                field("brand_new", "reference"), field("existing", "reference")));

        assertThat(calls.get(1).itemKeyFilter()).containsExactlyInAnyOrder(
                "COLLECTION:brand_new", "COLLECTION:existing", "FIELD:brand_new.reference");
        assertThat(result.resources()).extracting(
                        ModuleMetadataProvisioner.ProvisionedResource::naturalKey,
                        ModuleMetadataProvisioner.ProvisionedResource::ownership)
                .containsExactly(
                        tuple("brand_new", "CREATED"),
                        tuple("existing", "ADOPTED"),
                        tuple("brand_new.reference", "CREATED"));
    }

    @Test
    @DisplayName("the plan classifies without writing: CREATE, SKIP_ADOPTED under an adopted parent, KEEP for its own")
    void planClassifiesWithoutWriting() {
        existing.add("existing");

        var planned = provisioner.planPackage(TENANT, pkg(collection("brand_new"), collection("existing"),
                        field("brand_new", "reference"), field("existing", "reference")),
                Set.of("COLLECTION:brand_new"), "user-1");

        assertThat(calls).singleElement().satisfies(c -> assertThat(c.dryRun()).isTrue());
        assertThat(planned).extracting(ModuleMetadataProvisioner.PlannedItem::naturalKey,
                        ModuleMetadataProvisioner.PlannedItem::action)
                .containsExactlyInAnyOrder(
                        tuple("brand_new", ModuleMetadataProvisioner.ACTION_CREATE),
                        tuple("existing", ModuleMetadataProvisioner.ACTION_ADOPT),
                        tuple("brand_new.reference", ModuleMetadataProvisioner.ACTION_CREATE),
                        tuple("existing.reference", ModuleMetadataProvisioner.ACTION_SKIP_ADOPTED));
    }

    @Test
    @DisplayName("does nothing when the package has no items")
    void emptyPackageIsANoOp() {
        assertThat(provision(Map.of("items", List.of())).resources()).isEmpty();
        assertThat(provisioner.planPackage(TENANT, Map.of("items", List.of()), Set.of(), "user-1")).isEmpty();
        verify(importService, never()).importPackage(anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("a failed item lands in failures while the items that succeeded are still returned")
    void failedItemIsReported() {
        failing.add("FIELD:invoices.amount");

        var result = provision(invoices());

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

        provision(invoices());

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
