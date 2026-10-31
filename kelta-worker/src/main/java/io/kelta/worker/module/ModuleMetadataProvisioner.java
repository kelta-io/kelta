package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.service.PackageImportService;
import io.kelta.worker.service.PackageImportService.ConflictMode;
import io.kelta.worker.service.PackageImportService.ImportOptions;
import io.kelta.worker.service.PackageImportService.ImportReport;
import io.kelta.worker.service.PackageImportService.ItemResult;
import io.kelta.worker.service.TenantSlugResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Provisions the metadata package a module ships (part 9c), in the installing tenant, through
 * {@link PackageImportService} — the importer sandbox clone, metadata promotion and package import
 * already use. Writes therefore go through {@code QueryEngine}, so the collection/field NATS
 * broadcast and the physical-table DDL fire on every pod exactly as for an admin.
 *
 * <p>{@link #planPackage} classifies every item without writing, and {@link #provisionPackage}
 * applies the same classification in two passes: a dry run, then the apply. <b>An existing parent
 * is adopted and left completely alone</b>: an item whose parent (a field's collection, a picklist
 * value's collection or global picklist, a menu item's menu) already existed and is not the
 * module's own is {@value #ACTION_SKIP_ADOPTED} and never written. A reinstall must never clobber a
 * tenant's live schema, and admins pre-create a module's collections relying on this.
 *
 * <p>Ownership is read from the apply pass, not the dry run, so a parent someone else created
 * between the two is still reported {@code ADOPTED}. Content hashes are taken over the stored row
 * after the apply ({@link #currentContentHash}), which is what uninstall compares against.
 */
@Service
public class ModuleMetadataProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ModuleMetadataProvisioner.class);

    static final String COLLECTION = "COLLECTION";
    static final String FIELD = "FIELD";

    /** Plan actions for a package item. */
    public static final String ACTION_CREATE = "CREATE";
    /** Exists already and is not the module's: reused, never written, never removable. */
    public static final String ACTION_ADOPT = "ADOPT";
    /** Exists already because this module created it (a reinstall or upgrade). */
    public static final String ACTION_KEEP = "KEEP";
    /** Its parent was adopted, so it is left alone (a module never edits a tenant's own schema). */
    public static final String ACTION_SKIP_ADOPTED = "SKIP_ADOPTED";
    /** The dry run rejected it; the plan carries a blocker. */
    public static final String ACTION_FAILED = "FAILED";

    /** Attributes not part of a stored row's content hash: identity and audit, which change on their own. */
    private static final Set<String> UNHASHED_ATTRIBUTES =
            Set.of("id", "tenantId", "createdAt", "updatedAt", "createdBy", "updatedBy");

    /** One resource a provisioning run created or adopted. {@code contentHash} is null when adopted. */
    public record ProvisionedResource(String type, String naturalKey, String resourceId,
                                      String ownership, String contentHash) {}

    /**
     * What a provisioning run did. {@code resources} holds every item that succeeded, even when
     * others failed, so their provenance is still recorded.
     */
    public record ProvisionResult(List<ProvisionedResource> resources, List<ItemResult> failures) {

        static final ProvisionResult EMPTY = new ProvisionResult(List.of(), List.of());

        public boolean hasFailures() {
            return !failures.isEmpty();
        }
    }

    /**
     * One package item as a plan sees it.
     *
     * @param action {@link #ACTION_CREATE}, {@link #ACTION_ADOPT}, {@link #ACTION_KEEP},
     *               {@link #ACTION_SKIP_ADOPTED} or {@link #ACTION_FAILED}
     * @param error  the dry run's message for a {@link #ACTION_FAILED} item, else {@code null}
     */
    public record PlannedItem(String type, String naturalKey, String action, String error) {}

    private final PackageImportService packageImportService;
    private final TenantSlugResolver tenantSlugResolver;
    private final ObjectMapper objectMapper;
    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;

    public ModuleMetadataProvisioner(PackageImportService packageImportService,
                                     TenantSlugResolver tenantSlugResolver,
                                     ObjectMapper objectMapper,
                                     QueryEngine queryEngine,
                                     CollectionRegistry collectionRegistry) {
        this.packageImportService = packageImportService;
        this.tenantSlugResolver = tenantSlugResolver;
        this.objectMapper = objectMapper;
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
    }

    /**
     * Classifies every item of an external module's metadata package for the install plan.
     * Writes nothing.
     *
     * @param ownedKeys {@code TYPE:naturalKey} of what this module already created in the tenant
     *                  (its {@code CREATED} provenance rows); empty on a first install
     */
    public List<PlannedItem> planPackage(String tenantId, Map<String, Object> pkg, Set<String> ownedKeys,
                                         String executingUserId) {
        if (packageItems(pkg).isEmpty()) {
            return List.of();
        }
        return TenantContext.callWithTenant(tenantId, resolveSlug(tenantId), () -> {
            ImportReport dryRun = packageImportService.importPackage(tenantId, pkg,
                    new ImportOptions(ConflictMode.SKIP, true, null, null, executingUserId));
            return classify(pkg, dryRun, ownedKeys);
        });
    }

    /**
     * Applies an external module's metadata package: creates what the plan calls
     * {@link #ACTION_CREATE}, reports what it adopts or keeps, and writes nothing under an adopted
     * parent. Idempotent: re-running converges, since every type upserts on its natural key.
     */
    public ProvisionResult provisionPackage(String tenantId, String moduleId, Map<String, Object> pkg,
                                            Set<String> ownedKeys, String executingUserId) {
        if (packageItems(pkg).isEmpty()) {
            return ProvisionResult.EMPTY;
        }
        return TenantContext.callWithTenant(tenantId, resolveSlug(tenantId),
                () -> applyPackage(tenantId, moduleId, pkg, ownedKeys, executingUserId));
    }

    /**
     * The content hash of a stored resource as it is now: the canonical-JSON SHA-256 of the row
     * read back through its system collection, minus identity and audit attributes. Empty when
     * the resource no longer exists. Must run with the tenant bound.
     */
    public Optional<String> currentContentHash(String type, String resourceId) {
        String systemCollection = PackageImportService.systemCollectionFor(type);
        CollectionDefinition definition = systemCollection == null ? null : collectionRegistry.get(systemCollection);
        if (definition == null || resourceId == null) {
            return Optional.empty();
        }
        return queryEngine.getById(definition, resourceId).map(row -> {
            Map<String, Object> content = new TreeMap<>();
            row.forEach((key, value) -> {
                if (!UNHASHED_ATTRIBUTES.contains(key)) {
                    content.put(key, value);
                }
            });
            return contentHash(content);
        });
    }

    private ProvisionResult applyPackage(String tenantId, String moduleId, Map<String, Object> pkg,
                                         Set<String> ownedKeys, String executingUserId) {
        ImportReport dryRun = packageImportService.importPackage(tenantId, pkg,
                new ImportOptions(ConflictMode.SKIP, true, null, null, executingUserId));
        Map<String, String> planned = new HashMap<>();
        for (PlannedItem item : classify(pkg, dryRun, ownedKeys)) {
            planned.put(item.type() + ":" + item.naturalKey(), item.action());
        }
        Set<String> itemKeyFilter = new HashSet<>();
        planned.forEach((key, action) -> {
            if (!ACTION_SKIP_ADOPTED.equals(action) && !ACTION_FAILED.equals(action)) {
                itemKeyFilter.add(key);
            }
        });

        ImportReport report = packageImportService.importPackage(tenantId, pkg,
                new ImportOptions(ConflictMode.SKIP, false, null, itemKeyFilter, executingUserId));

        Set<String> createdNow = new HashSet<>();
        for (ItemResult item : report.items()) {
            if ("CREATED".equals(item.action())) {
                createdNow.add(item.type() + ":" + item.naturalKey());
            }
        }
        Map<String, Map<String, Object>> dataByKey = dataByKey(pkg);
        List<ProvisionedResource> resources = new ArrayList<>();
        List<ItemResult> failures = new ArrayList<>();
        for (ItemResult item : report.items()) {
            String key = item.type() + ":" + item.naturalKey();
            if ("FAILED".equals(item.action())) {
                failures.add(item);
                continue;
            }
            String parent = parentKey(item.type(), dataByKey.getOrDefault(key, Map.of()));
            boolean created = "CREATED".equals(item.action());
            if (created && parent != null && !createdNow.contains(parent) && !ownedKeys.contains(parent)) {
                // Only reachable when the parent appeared between the two passes: the module does
                // not own it, so it claims nothing written under it either.
                log.warn("Module '{}' wrote {} '{}' under {} it does not own (created between the dry "
                        + "run and the apply pass) — not recording it", moduleId, item.type(),
                        item.naturalKey(), parent);
                continue;
            }
            resources.add(new ProvisionedResource(item.type(), item.naturalKey(), item.resourceId(),
                    created ? ModuleProvenanceStore.OWNERSHIP_CREATED : ModuleProvenanceStore.OWNERSHIP_ADOPTED,
                    created ? currentContentHash(item.type(), item.resourceId()).orElse(null) : null));
        }
        return new ProvisionResult(List.copyOf(resources), List.copyOf(failures));
    }

    private List<PlannedItem> classify(Map<String, Object> pkg, ImportReport dryRun, Set<String> ownedKeys) {
        Map<String, Map<String, Object>> dataByKey = dataByKey(pkg);
        Set<String> createdInRun = new HashSet<>();
        Set<String> skippedInRun = new HashSet<>();
        for (ItemResult item : dryRun.items()) {
            String key = item.type() + ":" + item.naturalKey();
            if ("CREATED".equals(item.action())) {
                createdInRun.add(key);
            } else if ("SKIPPED".equals(item.action())) {
                skippedInRun.add(key);
            }
        }
        List<PlannedItem> planned = new ArrayList<>();
        for (ItemResult item : dryRun.items()) {
            String key = item.type() + ":" + item.naturalKey();
            String parent = parentKey(item.type(), dataByKey.getOrDefault(key, Map.of()));
            boolean parentAdopted = parent != null && !createdInRun.contains(parent) && !ownedKeys.contains(parent);
            String action;
            if ("FAILED".equals(item.action())) {
                // Under a parent the package itself adopts the item is never written, so its dry-run
                // failure does not matter. Anywhere else (a parent that exists nowhere) it does.
                action = parentAdopted && skippedInRun.contains(parent) ? ACTION_SKIP_ADOPTED : ACTION_FAILED;
            } else if (parentAdopted) {
                action = ACTION_SKIP_ADOPTED;
            } else if ("CREATED".equals(item.action())) {
                action = ACTION_CREATE;
            } else {
                action = ownedKeys.contains(key) ? ACTION_KEEP : ACTION_ADOPT;
            }
            planned.add(new PlannedItem(item.type(), item.naturalKey(), action,
                    ACTION_FAILED.equals(action) ? item.error() : null));
        }
        return planned;
    }

    /**
     * {@code TYPE:naturalKey} of the item this one lives under, or {@code null} for a top-level
     * item (collection, global picklist, page, menu).
     */
    static String parentKey(String type, Map<String, Object> data) {
        return switch (type) {
            case FIELD, "VALIDATION_RULE" -> COLLECTION + ":" + data.get("collection_name");
            case "PICKLIST_VALUE" -> "GLOBAL".equals(data.get("picklist_source_type"))
                    ? "GLOBAL_PICKLIST:" + data.get("picklist_name")
                    : COLLECTION + ":" + data.get("field_collection_name");
            case "UI_MENU_ITEM" -> "UI_MENU:" + data.get("menu_name");
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> packageItems(Map<String, Object> pkg) {
        return pkg == null || !(pkg.get("items") instanceof List<?> items)
                ? List.of() : (List<Map<String, Object>>) items;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> dataByKey(Map<String, Object> pkg) {
        Map<String, Map<String, Object>> byKey = new HashMap<>();
        for (Map<String, Object> item : packageItems(pkg)) {
            String type = (String) item.get("type");
            Map<String, Object> data = (Map<String, Object>) item.get("data");
            byKey.put(type + ":" + PackageImportService.naturalKeyFor(type, data), data);
        }
        return byKey;
    }

    /** SHA-256 of the item's data as canonical JSON (keys sorted at every level), UTF-8. */
    String contentHash(Map<String, Object> data) {
        return ModuleHashes.sha256(objectMapper.writeValueAsBytes(sorted(data)));
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new TreeMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), sorted(v)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ModuleMetadataProvisioner::sorted).toList();
        }
        return value;
    }

    private String resolveSlug(String tenantId) {
        return tenantSlugResolver.resolveSlug(tenantId).orElseGet(() -> {
            if (tenantId.equals(TenantContext.get()) && TenantContext.getSlug() != null) {
                return TenantContext.getSlug();
            }
            // Without a slug the storage adapter resolves the PUBLIC schema, so a module's tables
            // would land outside the tenant. Refuse rather than provision into the wrong place.
            throw new IllegalStateException("Cannot resolve the slug for tenant " + tenantId);
        });
    }
}
