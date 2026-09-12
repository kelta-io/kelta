package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.worker.repository.EnvironmentPromotionRepository;
import io.kelta.worker.repository.EnvironmentRepository;
import io.kelta.worker.repository.PackageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Metadata promotion: applies a sandbox's configuration to its production
 * tenant (local, in-process import) or to a remote cluster (package push).
 *
 * <p>Flow: create (source must be a local tenant-backed sandbox) → approve
 * (four-eyes: approver ≠ creator) → execute (strictly APPROVED). Execute
 * snapshots the TARGET first (local targets only — the restore point for
 * rollback), exports the sandbox's full package under the sandbox tenant
 * context, filters it (SELECTIVE natural keys + dependency pull-in; security
 * types always excluded), and imports into the target.
 *
 * <p>Security types (ROLE/POLICY/ROUTE_POLICY/FIELD_POLICY) are never promoted
 * — a compromised sandbox must not be able to rewrite production authz. They
 * remain movable only via the explicit {@code /api/packages} surface.
 *
 * <p>Tenant-context discipline: all cross-tenant hops are explicit
 * {@code callWithTenant} with concrete tenant ids — the only shape that works.
 * A sentinel-binding {@code runAsPlatform} helper used to exist and matched no
 * RLS policy (reads returned zero rows silently); it was removed 2026-09-09.
 */
@Service
public class MetadataPromotionService {

    private static final Logger log = LoggerFactory.getLogger(MetadataPromotionService.class);
    private static final String SUBJECT_PROMOTION = "kelta.config.promotion.executed";

    /** Authz-defining package types — excluded from promotion (risk H4). */
    static final Set<String> SECURITY_TYPES = Set.of("ROLE", "POLICY", "ROUTE_POLICY", "FIELD_POLICY");

    private final EnvironmentPromotionRepository promotionRepository;
    private final EnvironmentRepository environmentRepository;
    private final SandboxEnvironmentService sandboxEnvironmentService;
    private final SandboxProvisioningService provisioningService;
    private final PackageService packageService;
    private final PackageImportService packageImportService;
    private final PackageRepository packageRepository;
    private final RemotePromotionClient remotePromotionClient;
    private final SetupAuditService setupAuditService;
    private final ObjectMapper objectMapper;
    private final PlatformEventPublisher eventPublisher;
    private final io.kelta.runtime.router.UserIdResolver userIdResolver;

    public MetadataPromotionService(EnvironmentPromotionRepository promotionRepository,
                                    EnvironmentRepository environmentRepository,
                                    SandboxEnvironmentService sandboxEnvironmentService,
                                    SandboxProvisioningService provisioningService,
                                    PackageService packageService,
                                    PackageImportService packageImportService,
                                    PackageRepository packageRepository,
                                    RemotePromotionClient remotePromotionClient,
                                    SetupAuditService setupAuditService,
                                    ObjectMapper objectMapper,
                                    PlatformEventPublisher eventPublisher,
                                    io.kelta.runtime.router.UserIdResolver userIdResolver) {
        this.promotionRepository = promotionRepository;
        this.environmentRepository = environmentRepository;
        this.sandboxEnvironmentService = sandboxEnvironmentService;
        this.provisioningService = provisioningService;
        this.packageService = packageService;
        this.packageImportService = packageImportService;
        this.packageRepository = packageRepository;
        this.remotePromotionClient = remotePromotionClient;
        this.setupAuditService = setupAuditService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.userIdResolver = userIdResolver;
    }

    /**
     * @param items SELECTIVE promotions: [{itemType, itemName}] natural keys
     */
    public Map<String, Object> createPromotion(String tenantId, String sourceEnvId, String targetEnvId,
                                               String promotionType, String conflictMode,
                                               List<Map<String, Object>> items, String promotedBy) {
        var source = environmentRepository.findByIdAndTenant(sourceEnvId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Source environment not found: " + sourceEnvId));
        var target = environmentRepository.findByIdAndTenant(targetEnvId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Target environment not found: " + targetEnvId));

        if (source.get("sandbox_tenant_id") == null) {
            throw new IllegalArgumentException(
                    "Promotion source must be a local tenant-backed sandbox environment");
        }
        if (!"ACTIVE".equals(source.get("status"))) {
            throw new IllegalArgumentException("Source environment must be ACTIVE");
        }
        boolean remoteTarget = target.get("remote_base_url") != null;
        if (!remoteTarget && !"PRODUCTION".equals(target.get("type"))) {
            throw new IllegalArgumentException(
                    "Local promotion target must be the production environment");
        }
        if (targetEnvId.equals(sourceEnvId)) {
            throw new IllegalArgumentException("Source and target must differ");
        }

        String mode = conflictMode == null || conflictMode.isBlank()
                ? "SKIP" : conflictMode.toUpperCase();
        if (!"SKIP".equals(mode) && !"OVERWRITE".equals(mode)) {
            throw new IllegalArgumentException("conflictMode must be SKIP or OVERWRITE");
        }

        if ("SELECTIVE".equals(promotionType) && (items == null || items.isEmpty())) {
            throw new IllegalArgumentException("SELECTIVE promotions require items [{itemType, itemName}]");
        }

        String promotionId = promotionRepository.create(tenantId, sourceEnvId, targetEnvId,
                promotionType, mode, promotedBy);

        if ("SELECTIVE".equals(promotionType)) {
            for (var item : items) {
                String itemType = String.valueOf(item.get("itemType"));
                String itemName = String.valueOf(item.get("itemName"));
                promotionRepository.createItem(tenantId, promotionId, itemType, null, itemName, "UPDATE");
            }
        }

        log.info("Created promotion {} from env {} to env {} (tenant={}, type={}, conflictMode={})",
                promotionId, sourceEnvId, targetEnvId, tenantId, promotionType, mode);

        return promotionRepository.findByIdAndTenant(promotionId, tenantId).orElseThrow();
    }

    /** Cross-tenant diff of a local sandbox against its parent (this tenant). */
    public Map<String, Object> previewPromotion(String tenantId, String sourceEnvId) {
        return provisioningService.compareWithParent(sourceEnvId, tenantId);
    }

    public Map<String, Object> approvePromotion(String promotionId, String tenantId, String approvedBy) {
        var promo = promotionRepository.findByIdAndTenant(promotionId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Promotion not found: " + promotionId));

        if (!"PENDING".equals(promo.get("status"))) {
            throw new IllegalStateException("Promotion is not in PENDING status");
        }
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new IllegalArgumentException("Approver identity is required");
        }
        if (approvedBy.equals(promo.get("promoted_by"))) {
            throw new IllegalStateException("A promotion cannot be approved by its creator");
        }

        promotionRepository.approve(promotionId, approvedBy);
        log.info("Approved promotion {} by {} (tenant={})", promotionId, approvedBy, tenantId);

        return promotionRepository.findByIdAndTenant(promotionId, tenantId).orElseThrow();
    }

    @Async("applicationTaskExecutor")
    public void executePromotion(String promotionId, String tenantId, String executedBy) {
        var promotion = TenantContext.callWithTenant(tenantId, () ->
                promotionRepository.findByIdAndTenant(promotionId, tenantId));
        if (promotion.isEmpty()) {
            log.error("Promotion not found for execution: {}", promotionId);
            return;
        }

        var promo = promotion.get();
        if (!"APPROVED".equals(promo.get("status"))) {
            log.warn("Promotion {} is in status {}, cannot execute (approval required)",
                    promotionId, promo.get("status"));
            return;
        }

        TenantContext.runWithTenant(tenantId, () -> promotionRepository.markStarted(promotionId));
        String sourceEnvId = (String) promo.get("source_env_id");
        String targetEnvId = (String) promo.get("target_env_id");
        String conflictMode = String.valueOf(promo.getOrDefault("conflict_mode", "SKIP"));

        try {
            var sourceEnv = TenantContext.callWithTenant(tenantId, () ->
                    environmentRepository.findByIdAndTenant(sourceEnvId, tenantId).orElseThrow());
            var targetEnv = TenantContext.callWithTenant(tenantId, () ->
                    environmentRepository.findByIdAndTenant(targetEnvId, tenantId).orElseThrow());
            String sandboxTenantId = (String) sourceEnv.get("sandbox_tenant_id");
            boolean remoteTarget = targetEnv.get("remote_base_url") != null;
            // Slug binds the schema-per-tenant context (user-collection physical
            // tables live in the tenant's slug schema). @Async loses the request
            // slug, so resolve both explicitly.
            String sandboxSlug = provisioningService.tenantSlug(sandboxTenantId);
            String targetSlug = remoteTarget ? null : provisioningService.tenantSlug(tenantId);

            // Export the sandbox's full package under the sandbox tenant context
            Map<String, Object> pkg = TenantContext.callWithTenant(sandboxTenantId, sandboxSlug, () ->
                    packageService.exportPackage(sandboxTenantId,
                            packageService.exportAllOptions(sandboxTenantId,
                                    "promotion-" + promotionId, "1.0.0"), false));

            // Filter: security types always excluded; SELECTIVE keys + dependencies
            Set<String> selectedKeys = null;
            if ("SELECTIVE".equals(promo.get("promotion_type"))) {
                var itemRows = TenantContext.callWithTenant(tenantId, () ->
                        promotionRepository.findItemsByPromotion(promotionId));
                selectedKeys = expandSelection(pkg, itemRows);
            }
            Map<String, Object> filteredPkg = filterPackage(pkg, selectedKeys);

            Map<String, Object> summary;
            int promoted, skipped, failed;
            List<Map<String, Object>> reportItems;

            if (remoteTarget) {
                // Push to the remote cluster's import API. The remote side owns
                // its own snapshot/rollback and enforces its own authz on the
                // supplied PAT.
                Map<String, Object> remoteReport = remotePromotionClient.pushPackage(
                        targetEnv, filteredPkg, conflictMode, false);
                promoted = intOf(remoteReport.get("created")) + intOf(remoteReport.get("updated"));
                skipped = intOf(remoteReport.get("skipped"));
                failed = intOf(remoteReport.get("failed"));
                reportItems = itemList(remoteReport.get("items"));
                summary = Map.of(
                        "created", intOf(remoteReport.get("created")),
                        "updated", intOf(remoteReport.get("updated")),
                        "skipped", skipped,
                        "failed", failed,
                        "remote", true);
            } else {
                // Universal guard: never import a package into the tenant it
                // came from (same installation + same tenant).
                assertNotSelfImport(tenantId, filteredPkg);

                // Restore point: snapshot the TARGET before touching it
                String snapshotId = String.valueOf(TenantContext.callWithTenant(tenantId, targetSlug, () ->
                        sandboxEnvironmentService.createSnapshot(tenantId, targetEnvId,
                                "Pre-promotion target snapshot (" + promotionId + ")", executedBy))
                        .get("id"));
                TenantContext.runWithTenant(tenantId, () ->
                        promotionRepository.setTargetSnapshot(promotionId, snapshotId));

                Set<String> keyFilter = selectedKeys;
                var report = TenantContext.callWithTenant(tenantId, targetSlug, () ->
                        packageImportService.importPackage(tenantId, filteredPkg,
                                new PackageImportService.ImportOptions(
                                        PackageImportService.ConflictMode.valueOf(conflictMode),
                                        false, null, keyFilter, executedBy)));
                promoted = report.created() + report.updated();
                skipped = report.skipped();
                failed = report.failed();
                reportItems = new ArrayList<>();
                for (var item : report.items()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", item.type());
                    m.put("naturalKey", item.naturalKey());
                    m.put("action", item.action());
                    if (item.error() != null) {
                        m.put("error", item.error());
                    }
                    reportItems.add(m);
                }
                summary = Map.of(
                        "created", report.created(),
                        "updated", report.updated(),
                        "skipped", report.skipped(),
                        "failed", report.failed(),
                        "remote", false);
            }

            // Reflect per-item outcomes on SELECTIVE promotion rows
            if (selectedKeys != null) {
                for (var item : reportItems) {
                    TenantContext.runWithTenant(tenantId, () ->
                            promotionRepository.updateItemStatusByKey(promotionId,
                                    String.valueOf(item.get("type")),
                                    String.valueOf(item.get("naturalKey")),
                                    "FAILED".equals(item.get("action")) ? "FAILED"
                                            : "SKIPPED".equals(item.get("action")) ? "SKIPPED" : "COMPLETED",
                                    item.get("error") != null ? String.valueOf(item.get("error")) : null));
                }
            }

            Map<String, Object> changesSummary = new LinkedHashMap<>(summary);
            changesSummary.put("items", reportItems);
            String changesJson = objectMapper.writeValueAsString(changesSummary);
            int promotedFinal = promoted, skippedFinal = skipped, failedFinal = failed;
            TenantContext.runWithTenant(tenantId, () ->
                    promotionRepository.markCompleted(promotionId, promotedFinal, skippedFinal,
                            failedFinal, changesJson));

            TenantContext.runWithTenant(tenantId, () ->
                    audit(tenantId, executedBy, promotionId, targetEnv,
                            "Promotion executed: " + promotedFinal + " promoted, " + skippedFinal
                                    + " skipped, " + failedFinal + " failed (" + conflictMode + ")"));
            publishPromotionEvent(tenantId, promotionId, sourceEnvId, targetEnvId,
                    failed > 0 ? "FAILED" : "COMPLETED");
            log.info("Completed promotion {} (tenant={}, promoted={}, skipped={}, failed={})",
                    promotionId, tenantId, promoted, skipped, failed);

        } catch (Exception e) {
            TenantContext.runWithTenant(tenantId, () ->
                    promotionRepository.markFailed(promotionId, e.getMessage()));
            publishPromotionEvent(tenantId, promotionId, sourceEnvId, targetEnvId, "FAILED");
            log.error("Promotion {} failed (tenant={})", promotionId, tenantId, e);
        }
    }

    /**
     * Restores the target to its pre-promotion snapshot by re-importing the
     * snapshot package with OVERWRITE. Items created by the promotion are NOT
     * deleted (documented limitation); modified items revert.
     */
    public Map<String, Object> rollbackPromotion(String promotionId, String tenantId, String executedBy) {
        var promo = promotionRepository.findByIdAndTenant(promotionId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Promotion not found: " + promotionId));

        String status = (String) promo.get("status");
        if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
            throw new IllegalStateException("Only completed or failed promotions can be rolled back");
        }

        var targetEnv = environmentRepository.findByIdAndTenant(
                (String) promo.get("target_env_id"), tenantId).orElseThrow();
        if (targetEnv.get("remote_base_url") != null) {
            throw new IllegalStateException(
                    "Remote promotions cannot be rolled back from this cluster — restore from a "
                            + "snapshot on the remote installation");
        }

        String snapshotId = (String) promo.get("target_snapshot_id");
        if (snapshotId == null) {
            throw new IllegalStateException("No pre-promotion target snapshot available for rollback");
        }

        Map<String, Object> snapshotPkg = sandboxEnvironmentService.snapshotPackage(snapshotId, tenantId);
        var report = packageImportService.importPackage(tenantId, snapshotPkg,
                new PackageImportService.ImportOptions(
                        PackageImportService.ConflictMode.OVERWRITE, false, null, null, executedBy));
        if (report.failed() > 0) {
            log.warn("Rollback of promotion {} completed with {} failed items", promotionId, report.failed());
        }

        promotionRepository.markRolledBack(promotionId);
        audit(tenantId, executedBy, promotionId, targetEnv,
                "Promotion rolled back to snapshot " + snapshotId
                        + " (restored=" + (report.created() + report.updated())
                        + ", failed=" + report.failed() + ")");
        publishPromotionEvent(tenantId, promotionId,
                (String) promo.get("source_env_id"), (String) promo.get("target_env_id"), "ROLLED_BACK");

        log.info("Rolled back promotion {} using snapshot {} (tenant={})", promotionId, snapshotId, tenantId);
        return promotionRepository.findByIdAndTenant(promotionId, tenantId).orElseThrow();
    }

    public Optional<Map<String, Object>> getPromotion(String promotionId, String tenantId) {
        return promotionRepository.findByIdAndTenant(promotionId, tenantId);
    }

    public List<Map<String, Object>> listPromotions(String tenantId, int limit, int offset) {
        return promotionRepository.findByTenant(tenantId, limit, offset);
    }

    public List<Map<String, Object>> listPromotionsByEnvironment(String envId, String tenantId) {
        return promotionRepository.findByEnvironment(envId, tenantId);
    }

    public List<Map<String, Object>> getPromotionItems(String promotionId) {
        return promotionRepository.findItemsByPromotion(promotionId);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The one identity check valid across unknown topologies: a package must
     * not be promoted into the exact tenant it was exported from on the same
     * installation.
     */
    void assertNotSelfImport(String targetTenantId, Map<String, Object> pkg) {
        Object sourceObj = pkg.get("source");
        if (!(sourceObj instanceof Map<?, ?> source)) {
            return;
        }
        String localInstanceId = packageRepository.findInstanceId().orElse(null);
        if (localInstanceId != null
                && localInstanceId.equals(source.get("instanceId"))
                && targetTenantId.equals(source.get("tenantId"))) {
            throw new IllegalStateException(
                    "Promotion rejected: the package was exported from this exact tenant "
                            + "(source == target)");
        }
    }

    /** Removes security types and, when a selection is given, unselected items. */
    @SuppressWarnings("unchecked")
    Map<String, Object> filterPackage(Map<String, Object> pkg, Set<String> selectedKeys) {
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) pkg.getOrDefault("items", List.of());
        List<Map<String, Object>> kept = new ArrayList<>();
        for (var item : items) {
            String type = (String) item.get("type");
            if (SECURITY_TYPES.contains(type)) {
                continue;
            }
            if (selectedKeys != null) {
                Map<String, Object> data = (Map<String, Object>) item.get("data");
                String key = type + ":" + PackageImportService.naturalKeyFor(type, data);
                if (!selectedKeys.contains(key)) {
                    continue;
                }
            }
            kept.add(item);
        }
        Map<String, Object> filtered = new LinkedHashMap<>(pkg);
        filtered.put("items", kept);
        return filtered;
    }

    /**
     * Expands a SELECTIVE selection to include hard dependencies present in the
     * package (a selected field pulls its collection, a layout field pulls its
     * section/layout/field, …) via fixpoint iteration over parent keys.
     */
    @SuppressWarnings("unchecked")
    Set<String> expandSelection(Map<String, Object> pkg, List<Map<String, Object>> itemRows) {
        Set<String> selected = new LinkedHashSet<>();
        for (var row : itemRows) {
            selected.add(row.get("item_type") + ":" + row.get("item_name"));
        }

        Map<String, Map<String, Object>> packageByKey = new LinkedHashMap<>();
        for (var item : (List<Map<String, Object>>) pkg.getOrDefault("items", List.of())) {
            String type = (String) item.get("type");
            Map<String, Object> data = (Map<String, Object>) item.get("data");
            packageByKey.put(type + ":" + PackageImportService.naturalKeyFor(type, data), item);
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (String key : List.copyOf(selected)) {
                Map<String, Object> item = packageByKey.get(key);
                if (item == null) {
                    continue;
                }
                for (String parentKey : parentKeys((String) item.get("type"),
                        (Map<String, Object>) item.get("data"))) {
                    if (packageByKey.containsKey(parentKey) && selected.add(parentKey)) {
                        changed = true;
                    }
                }
            }
        }
        return selected;
    }

    /** Natural keys of the items this item cannot import without. */
    private List<String> parentKeys(String type, Map<String, Object> data) {
        return switch (type) {
            case "FIELD" -> {
                List<String> parents = new ArrayList<>();
                parents.add("COLLECTION:" + data.get("collection_name"));
                if (data.get("reference_collection_name") != null) {
                    parents.add("COLLECTION:" + data.get("reference_collection_name"));
                }
                yield parents;
            }
            case "VALIDATION_RULE", "PAGE_LAYOUT" ->
                    List.of("COLLECTION:" + data.get("collection_name"));
            case "LAYOUT_SECTION" -> List.of(
                    "PAGE_LAYOUT:" + data.get("collection_name") + ":" + data.get("layout_name"));
            case "LAYOUT_FIELD" -> List.of(
                    "LAYOUT_SECTION:" + data.get("collection_name") + ":" + data.get("layout_name")
                            + ":" + data.get("section_sort_order"),
                    "FIELD:" + data.get("field_collection_name") + "." + data.get("field_name"));
            case "PICKLIST_VALUE" -> "GLOBAL".equals(data.get("picklist_source_type"))
                    ? List.of("GLOBAL_PICKLIST:" + data.get("picklist_name"))
                    : List.of("FIELD:" + data.get("field_collection_name") + "." + data.get("field_name"));
            case "UI_MENU_ITEM" -> List.of("UI_MENU:" + data.get("menu_name"));
            default -> List.of();
        };
    }

    private void audit(String tenantId, String userId, String promotionId,
                       Map<String, Object> targetEnv, String detail) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        // The controller hands us X-User-Id — an email. setup_audit_trail.user_id is a foreign
        // key to platform_user(id), so the insert below violated it on every promotion, the
        // catch swallowed it at WARN, and the audit trail held zero PROMOTION rows in
        // production. approved_by / promoted_by on the promotion row stay as the email: they
        // are display text, not keys.
        String actorId = userIdResolver.resolve(userId, tenantId);
        if (actorId == null || actorId.isBlank()) {
            log.warn("Promotion {} not audited: actor '{}' does not resolve to a platform user",
                    promotionId, userId);
            return;
        }
        try {
            setupAuditService.log(tenantId, actorId, "UPDATED", "environments", "PROMOTION",
                    promotionId, String.valueOf(targetEnv.get("name")),
                    null, objectMapper.writeValueAsString(Map.of("detail", detail)));
        } catch (Exception e) {
            log.warn("Failed to audit promotion {}: {}", promotionId, e.getMessage());
        }
    }

    private static int intOf(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemList(Object value) {
        return value instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : new ArrayList<>();
    }

    private void publishPromotionEvent(String tenantId, String promotionId,
                                        String sourceEnvId, String targetEnvId, String status) {
        try {
            Map<String, Object> payload = Map.of(
                    "promotionId", promotionId,
                    "sourceEnvironmentId", sourceEnvId,
                    "targetEnvironmentId", targetEnvId,
                    "status", status
            );
            PlatformEvent<Map<String, Object>> event = EventFactory.createEvent("promotion.executed", payload);
            event.setTenantId(tenantId);
            String subject = SUBJECT_PROMOTION + "." + tenantId + "." + promotionId;
            eventPublisher.publish(subject, event);
        } catch (Exception e) {
            log.error("Failed to publish promotion event for {} (tenant={})", promotionId, tenantId, e);
        }
    }
}
