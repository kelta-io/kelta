package io.kelta.worker.service;

import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.worker.repository.EnvironmentPromotionRepository;
import io.kelta.worker.repository.EnvironmentRepository;
import io.kelta.worker.repository.PackageRepository;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Promotion workflow: create validations, four-eyes approval, security-type
 * exclusion, SELECTIVE dependency expansion, self-import provenance guard, and
 * rollback guards. {@code executePromotion} is exercised end-to-end in the
 * test harness; the package-private helpers it composes are unit-tested here.
 */
@DisplayName("MetadataPromotionService")
class MetadataPromotionServiceTest {

    private static final String TENANT = "t1";

    private EnvironmentPromotionRepository promotionRepository;
    private EnvironmentRepository environmentRepository;
    private SandboxEnvironmentService sandboxEnvironmentService;
    private SandboxProvisioningService provisioningService;
    private PackageService packageService;
    private PackageImportService packageImportService;
    private PackageRepository packageRepository;
    private RemotePromotionClient remotePromotionClient;
    private SetupAuditService setupAuditService;
    private io.kelta.runtime.router.UserIdResolver userIdResolver;
    private PlatformEventPublisher eventPublisher;
    private MetadataPromotionService service;

    @BeforeEach
    void setUp() {
        promotionRepository = mock(EnvironmentPromotionRepository.class);
        environmentRepository = mock(EnvironmentRepository.class);
        sandboxEnvironmentService = mock(SandboxEnvironmentService.class);
        provisioningService = mock(SandboxProvisioningService.class);
        packageService = mock(PackageService.class);
        packageImportService = mock(PackageImportService.class);
        packageRepository = mock(PackageRepository.class);
        remotePromotionClient = mock(RemotePromotionClient.class);
        setupAuditService = mock(SetupAuditService.class);
        userIdResolver = mock(io.kelta.runtime.router.UserIdResolver.class);
        // Existing tests pass ids; only the audit case below exercises email resolution.
        org.mockito.Mockito.lenient().when(userIdResolver.resolve(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        eventPublisher = mock(PlatformEventPublisher.class);

        service = new MetadataPromotionService(promotionRepository, environmentRepository,
                sandboxEnvironmentService, provisioningService, packageService, packageImportService,
                packageRepository, remotePromotionClient, setupAuditService,
                new ObjectMapper(), eventPublisher, userIdResolver);
    }

    private Map<String, Object> sandboxSource() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("id", "env-s");
        env.put("type", "SANDBOX");
        env.put("status", "ACTIVE");
        env.put("sandbox_tenant_id", "sbx-tenant");
        return env;
    }

    private Map<String, Object> productionTarget() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("id", "env-t");
        env.put("type", "PRODUCTION");
        env.put("status", "ACTIVE");
        return env;
    }

    private static Map<String, Object> item(String type, Map<String, Object> data) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("data", data);
        return item;
    }

    // ------------------------------------------------------------------
    // createPromotion
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("createPromotion")
    class CreatePromotion {

        @Test
        @DisplayName("rejects a source that is not a tenant-backed sandbox")
        void rejectsNonSandboxSource() {
            Map<String, Object> source = productionTarget(); // no sandbox_tenant_id
            source.put("id", "env-s");
            when(environmentRepository.findByIdAndTenant("env-s", TENANT)).thenReturn(Optional.of(source));
            when(environmentRepository.findByIdAndTenant("env-t", TENANT)).thenReturn(Optional.of(productionTarget()));

            assertThatThrownBy(() -> service.createPromotion(TENANT, "env-s", "env-t",
                    "FULL", "SKIP", null, "creator"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("local tenant-backed sandbox");
        }

        @Test
        @DisplayName("rejects a source that is not ACTIVE")
        void rejectsInactiveSource() {
            Map<String, Object> source = sandboxSource();
            source.put("status", "CREATING");
            when(environmentRepository.findByIdAndTenant("env-s", TENANT)).thenReturn(Optional.of(source));
            when(environmentRepository.findByIdAndTenant("env-t", TENANT)).thenReturn(Optional.of(productionTarget()));

            assertThatThrownBy(() -> service.createPromotion(TENANT, "env-s", "env-t",
                    "FULL", "SKIP", null, "creator"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be ACTIVE");
        }

        @Test
        @DisplayName("rejects a local target that is not the production environment")
        void rejectsNonProductionLocalTarget() {
            when(environmentRepository.findByIdAndTenant("env-s", TENANT)).thenReturn(Optional.of(sandboxSource()));
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("id", "env-t");
            target.put("type", "SANDBOX");
            when(environmentRepository.findByIdAndTenant("env-t", TENANT)).thenReturn(Optional.of(target));

            assertThatThrownBy(() -> service.createPromotion(TENANT, "env-s", "env-t",
                    "FULL", "SKIP", null, "creator"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("production environment");
        }

        @Test
        @DisplayName("rejects an invalid conflict mode")
        void rejectsBadConflictMode() {
            when(environmentRepository.findByIdAndTenant("env-s", TENANT)).thenReturn(Optional.of(sandboxSource()));
            when(environmentRepository.findByIdAndTenant("env-t", TENANT)).thenReturn(Optional.of(productionTarget()));

            assertThatThrownBy(() -> service.createPromotion(TENANT, "env-s", "env-t",
                    "FULL", "MERGE", null, "creator"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SKIP or OVERWRITE");
        }

        @Test
        @DisplayName("rejects SELECTIVE promotions without items")
        void rejectsSelectiveWithoutItems() {
            when(environmentRepository.findByIdAndTenant("env-s", TENANT)).thenReturn(Optional.of(sandboxSource()));
            when(environmentRepository.findByIdAndTenant("env-t", TENANT)).thenReturn(Optional.of(productionTarget()));

            assertThatThrownBy(() -> service.createPromotion(TENANT, "env-s", "env-t",
                    "SELECTIVE", "SKIP", List.of(), "creator"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SELECTIVE promotions require items");
        }

        @Test
        @DisplayName("rejects identical source and target")
        void rejectsSameSourceAndTarget() {
            Map<String, Object> env = sandboxSource();
            env.put("type", "PRODUCTION");
            when(environmentRepository.findByIdAndTenant("env-s", TENANT)).thenReturn(Optional.of(env));

            assertThatThrownBy(() -> service.createPromotion(TENANT, "env-s", "env-s",
                    "FULL", "SKIP", null, "creator"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must differ");
        }

        @Test
        @DisplayName("creates a FULL promotion with the normalized conflict mode")
        void createsFullPromotion() {
            when(environmentRepository.findByIdAndTenant("env-s", TENANT)).thenReturn(Optional.of(sandboxSource()));
            when(environmentRepository.findByIdAndTenant("env-t", TENANT)).thenReturn(Optional.of(productionTarget()));
            when(promotionRepository.create(TENANT, "env-s", "env-t", "FULL", "OVERWRITE", "creator"))
                    .thenReturn("promo-1");
            when(promotionRepository.findByIdAndTenant("promo-1", TENANT))
                    .thenReturn(Optional.of(Map.of("id", "promo-1", "status", "PENDING")));

            var result = service.createPromotion(TENANT, "env-s", "env-t",
                    "FULL", "overwrite", null, "creator");

            assertThat(result.get("id")).isEqualTo("promo-1");
            verify(promotionRepository).create(TENANT, "env-s", "env-t", "FULL", "OVERWRITE", "creator");
            verify(promotionRepository, never()).createItem(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("persists natural-key items for SELECTIVE promotions")
        void persistsSelectiveItems() {
            when(environmentRepository.findByIdAndTenant("env-s", TENANT)).thenReturn(Optional.of(sandboxSource()));
            when(environmentRepository.findByIdAndTenant("env-t", TENANT)).thenReturn(Optional.of(productionTarget()));
            when(promotionRepository.create(TENANT, "env-s", "env-t", "SELECTIVE", "SKIP", "creator"))
                    .thenReturn("promo-1");
            when(promotionRepository.findByIdAndTenant("promo-1", TENANT))
                    .thenReturn(Optional.of(Map.of("id", "promo-1")));

            service.createPromotion(TENANT, "env-s", "env-t", "SELECTIVE", "SKIP",
                    List.of(Map.of("itemType", "COLLECTION", "itemName", "orders")), "creator");

            verify(promotionRepository).createItem(TENANT, "promo-1", "COLLECTION", null, "orders", "UPDATE");
        }

        @Test
        @DisplayName("allows a remote target of any type")
        void allowsRemoteTarget() {
            when(environmentRepository.findByIdAndTenant("env-s", TENANT)).thenReturn(Optional.of(sandboxSource()));
            Map<String, Object> remote = new LinkedHashMap<>();
            remote.put("id", "env-t");
            remote.put("type", "STAGING");
            remote.put("remote_base_url", "https://remote.example.com");
            when(environmentRepository.findByIdAndTenant("env-t", TENANT)).thenReturn(Optional.of(remote));
            when(promotionRepository.create(TENANT, "env-s", "env-t", "FULL", "SKIP", "creator"))
                    .thenReturn("promo-1");
            when(promotionRepository.findByIdAndTenant("promo-1", TENANT))
                    .thenReturn(Optional.of(Map.of("id", "promo-1")));

            assertThatCode(() -> service.createPromotion(TENANT, "env-s", "env-t",
                    "FULL", null, null, "creator")).doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------
    // approvePromotion (four-eyes)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("approvePromotion")
    class ApprovePromotion {

        @Test
        @DisplayName("rejects a promotion that is not PENDING")
        void rejectsNonPending() {
            when(promotionRepository.findByIdAndTenant("promo-1", TENANT))
                    .thenReturn(Optional.of(Map.of("id", "promo-1", "status", "COMPLETED")));

            assertThatThrownBy(() -> service.approvePromotion("promo-1", TENANT, "approver"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not in PENDING status");
            verify(promotionRepository, never()).approve(any(), any());
        }

        @Test
        @DisplayName("rejects approval by the promotion's creator")
        void rejectsSelfApproval() {
            when(promotionRepository.findByIdAndTenant("promo-1", TENANT))
                    .thenReturn(Optional.of(Map.of("id", "promo-1", "status", "PENDING",
                            "promoted_by", "creator")));

            assertThatThrownBy(() -> service.approvePromotion("promo-1", TENANT, "creator"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be approved by its creator");
            verify(promotionRepository, never()).approve(any(), any());
        }

        @Test
        @DisplayName("rejects a blank approver identity")
        void rejectsBlankApprover() {
            when(promotionRepository.findByIdAndTenant("promo-1", TENANT))
                    .thenReturn(Optional.of(Map.of("id", "promo-1", "status", "PENDING",
                            "promoted_by", "creator")));

            assertThatThrownBy(() -> service.approvePromotion("promo-1", TENANT, " "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Approver identity is required");
        }

        @Test
        @DisplayName("approves a pending promotion by a different user")
        void approvesPending() {
            when(promotionRepository.findByIdAndTenant("promo-1", TENANT))
                    .thenReturn(Optional.of(Map.of("id", "promo-1", "status", "PENDING",
                            "promoted_by", "creator")));

            service.approvePromotion("promo-1", TENANT, "approver");

            verify(promotionRepository).approve("promo-1", "approver");
        }
    }

    // ------------------------------------------------------------------
    // filterPackage — security types never promoted
    // ------------------------------------------------------------------

    @Test
    @DisplayName("filterPackage strips ROLE/POLICY/ROUTE_POLICY/FIELD_POLICY unconditionally")
    @SuppressWarnings("unchecked")
    void filterPackageStripsSecurityTypes() {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("items", List.of(
                item("ROLE", Map.of("name", "admin")),
                item("POLICY", Map.of("name", "read-all")),
                item("ROUTE_POLICY", Map.of("collection_id", "c1", "operation", "READ")),
                item("FIELD_POLICY", Map.of("field_id", "f1", "operation", "READ")),
                item("COLLECTION", Map.of("name", "orders")),
                item("FLOW", Map.of("name", "myflow"))));

        var filtered = service.filterPackage(pkg, null);

        var kept = (List<Map<String, Object>>) filtered.get("items");
        assertThat(kept.stream().map(i -> i.get("type")).toList())
                .containsExactly("COLLECTION", "FLOW");
    }

    @Test
    @DisplayName("filterPackage keeps only selected keys — and still never security types")
    @SuppressWarnings("unchecked")
    void filterPackageAppliesSelection() {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("items", List.of(
                item("ROLE", Map.of("name", "admin")),
                item("COLLECTION", Map.of("name", "orders")),
                item("COLLECTION", Map.of("name", "customers"))));

        var filtered = service.filterPackage(pkg, Set.of("COLLECTION:orders", "ROLE:admin"));

        var kept = (List<Map<String, Object>>) filtered.get("items");
        assertThat(kept).hasSize(1);
        assertThat(((Map<String, Object>) kept.get(0).get("data")).get("name")).isEqualTo("orders");
    }

    // ------------------------------------------------------------------
    // expandSelection — hard dependency pull-in
    // ------------------------------------------------------------------

    @Test
    @DisplayName("expandSelection pulls a selected FIELD's owning and referenced collections")
    void expandSelectionPullsFieldParents() {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("items", List.of(
                item("COLLECTION", Map.of("name", "orders")),
                item("COLLECTION", Map.of("name", "customers")),
                item("FIELD", Map.of("collection_name", "orders", "name", "customer",
                        "reference_collection_name", "customers"))));

        var selected = service.expandSelection(pkg,
                List.of(Map.of("item_type", "FIELD", "item_name", "orders.customer")));

        assertThat(selected).containsExactlyInAnyOrder(
                "FIELD:orders.customer", "COLLECTION:orders", "COLLECTION:customers");
    }

    @Test
    @DisplayName("expandSelection walks LAYOUT_FIELD → section → layout → collection transitively")
    void expandSelectionWalksLayoutChain() {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("items", List.of(
                item("COLLECTION", Map.of("name", "orders")),
                item("FIELD", Map.of("collection_name", "orders", "name", "customer")),
                item("PAGE_LAYOUT", Map.of("collection_name", "orders", "name", "Default")),
                item("LAYOUT_SECTION", Map.of("collection_name", "orders", "layout_name", "Default",
                        "sort_order", 0)),
                item("LAYOUT_FIELD", Map.of("collection_name", "orders", "layout_name", "Default",
                        "section_sort_order", 0, "field_name", "customer",
                        "field_collection_name", "orders"))));

        var selected = service.expandSelection(pkg,
                List.of(Map.of("item_type", "LAYOUT_FIELD", "item_name", "orders:Default:0:customer")));

        assertThat(selected).containsExactlyInAnyOrder(
                "LAYOUT_FIELD:orders:Default:0:customer",
                "LAYOUT_SECTION:orders:Default:0",
                "PAGE_LAYOUT:orders:Default",
                "FIELD:orders.customer",
                "COLLECTION:orders");
    }

    // ------------------------------------------------------------------
    // assertNotSelfImport — provenance guard
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("assertNotSelfImport")
    class AssertNotSelfImport {

        private Map<String, Object> pkgWithSource(String instanceId, String tenantId) {
            Map<String, Object> pkg = new LinkedHashMap<>();
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("instanceId", instanceId);
            source.put("tenantId", tenantId);
            pkg.put("source", source);
            return pkg;
        }

        @Test
        @DisplayName("throws only when instance AND tenant both match")
        void throwsOnExactMatch() {
            when(packageRepository.findInstanceId()).thenReturn(Optional.of("inst-1"));

            assertThatThrownBy(() -> service.assertNotSelfImport(TENANT, pkgWithSource("inst-1", TENANT)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("source == target");
        }

        @Test
        @DisplayName("allows the same installation, different tenant (local sandbox → production)")
        void allowsDifferentTenantSameInstance() {
            when(packageRepository.findInstanceId()).thenReturn(Optional.of("inst-1"));

            assertThatCode(() -> service.assertNotSelfImport(TENANT, pkgWithSource("inst-1", "sbx-tenant")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("allows the same tenant id on a different installation")
        void allowsDifferentInstance() {
            when(packageRepository.findInstanceId()).thenReturn(Optional.of("inst-1"));

            assertThatCode(() -> service.assertNotSelfImport(TENANT, pkgWithSource("inst-other", TENANT)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("tolerates packages without provenance and unknown local instance")
        void toleratesMissingProvenance() {
            when(packageRepository.findInstanceId()).thenReturn(Optional.empty());

            assertThatCode(() -> service.assertNotSelfImport(TENANT, new LinkedHashMap<>()))
                    .doesNotThrowAnyException();
            assertThatCode(() -> service.assertNotSelfImport(TENANT, pkgWithSource("inst-1", TENANT)))
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------
    // rollbackPromotion
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("rollbackPromotion")
    class RollbackPromotion {

        @Test
        @DisplayName("rejects a promotion that is neither COMPLETED nor FAILED")
        void rejectsWrongStatus() {
            when(promotionRepository.findByIdAndTenant("promo-1", TENANT))
                    .thenReturn(Optional.of(Map.of("id", "promo-1", "status", "PENDING")));

            assertThatThrownBy(() -> service.rollbackPromotion("promo-1", TENANT, "user-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completed or failed");
        }

        @Test
        @DisplayName("rejects rollback of a remote-target promotion")
        void rejectsRemoteTarget() {
            Map<String, Object> promo = new LinkedHashMap<>();
            promo.put("id", "promo-1");
            promo.put("status", "COMPLETED");
            promo.put("target_env_id", "env-t");
            when(promotionRepository.findByIdAndTenant("promo-1", TENANT)).thenReturn(Optional.of(promo));

            Map<String, Object> remoteEnv = new LinkedHashMap<>();
            remoteEnv.put("id", "env-t");
            remoteEnv.put("remote_base_url", "https://remote.example.com");
            when(environmentRepository.findByIdAndTenant("env-t", TENANT)).thenReturn(Optional.of(remoteEnv));

            assertThatThrownBy(() -> service.rollbackPromotion("promo-1", TENANT, "user-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be rolled back from this cluster");
        }

        @Test
        @DisplayName("rejects rollback without a pre-promotion target snapshot")
        void rejectsMissingSnapshot() {
            Map<String, Object> promo = new LinkedHashMap<>();
            promo.put("id", "promo-1");
            promo.put("status", "FAILED");
            promo.put("target_env_id", "env-t");
            promo.put("target_snapshot_id", null);
            when(promotionRepository.findByIdAndTenant("promo-1", TENANT)).thenReturn(Optional.of(promo));
            when(environmentRepository.findByIdAndTenant("env-t", TENANT))
                    .thenReturn(Optional.of(productionTarget()));

            assertThatThrownBy(() -> service.rollbackPromotion("promo-1", TENANT, "user-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No pre-promotion target snapshot");
        }

        @Test
        @DisplayName("re-imports the target snapshot with OVERWRITE and marks the promotion rolled back")
        void rollsBackFromSnapshot() {
            Map<String, Object> promo = new LinkedHashMap<>();
            promo.put("id", "promo-1");
            promo.put("status", "COMPLETED");
            promo.put("source_env_id", "env-s");
            promo.put("target_env_id", "env-t");
            promo.put("target_snapshot_id", "snap-1");
            when(promotionRepository.findByIdAndTenant("promo-1", TENANT)).thenReturn(Optional.of(promo));
            when(environmentRepository.findByIdAndTenant("env-t", TENANT))
                    .thenReturn(Optional.of(productionTarget()));

            Map<String, Object> snapshotPkg = Map.of("items", List.of());
            when(sandboxEnvironmentService.snapshotPackage("snap-1", TENANT)).thenReturn(snapshotPkg);
            when(packageImportService.importPackage(eq(TENANT), eq(snapshotPkg), any()))
                    .thenReturn(new PackageImportService.ImportReport(0, 3, 0, 0, List.of()));

            var result = service.rollbackPromotion("promo-1", TENANT, "user-2");

            assertThat(result.get("id")).isEqualTo("promo-1");
            verify(packageImportService).importPackage(eq(TENANT), eq(snapshotPkg), argThat(opts ->
                    opts.conflictMode() == PackageImportService.ConflictMode.OVERWRITE
                            && !opts.dryRun()
                            && "user-2".equals(opts.executingUserId())));
            verify(promotionRepository).markRolledBack("promo-1");
        }

        @Test
        @DisplayName("audits under the resolved platform_user.id, not the X-User-Id email")
        void auditsUnderResolvedId() {
            // setup_audit_trail.user_id is a foreign key to platform_user(id). Passing the header's
            // email violated it on every promotion; the catch logged a WARN and production held
            // zero PROMOTION audit rows. The promotion's own promoted_by stays as the email.
            Map<String, Object> promo = new java.util.HashMap<>();
            promo.put("id", "promo-1");
            promo.put("status", "COMPLETED");
            promo.put("target_env_id", "env-t");
            promo.put("target_snapshot_id", "snap-1");
            when(promotionRepository.findByIdAndTenant("promo-1", TENANT)).thenReturn(Optional.of(promo));
            when(environmentRepository.findByIdAndTenant("env-t", TENANT))
                    .thenReturn(Optional.of(productionTarget()));
            Map<String, Object> snapshotPkg = Map.of("items", List.of());
            when(sandboxEnvironmentService.snapshotPackage("snap-1", TENANT)).thenReturn(snapshotPkg);
            when(packageImportService.importPackage(eq(TENANT), eq(snapshotPkg), any()))
                    .thenReturn(new PackageImportService.ImportReport(0, 0, 0, 0, List.of()));
            when(userIdResolver.resolve("craig@example.com", TENANT)).thenReturn("uuid-craig");

            service.rollbackPromotion("promo-1", TENANT, "craig@example.com");

            verify(setupAuditService).log(eq(TENANT), eq("uuid-craig"), eq("UPDATED"),
                    eq("environments"), eq("PROMOTION"), eq("promo-1"), any(), any(), any());
            verify(setupAuditService, never()).log(any(), eq("craig@example.com"), any(), any(),
                    any(), any(), any(), any(), any());
        }
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listPromotions delegates to the repository")
    void listShouldDelegate() {
        when(promotionRepository.findByTenant(TENANT, 50, 0))
                .thenReturn(List.of(Map.of("id", "promo-1")));

        assertThat(service.listPromotions(TENANT, 50, 0)).hasSize(1);
    }
}
