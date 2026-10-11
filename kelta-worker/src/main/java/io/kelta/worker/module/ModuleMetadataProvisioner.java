package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.ModuleManifest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Provisions the metadata a module's manifest declares, in the installing tenant, through
 * {@link PackageImportService} — the importer sandbox clone, metadata promotion and package import
 * already use. Writes therefore go through {@code QueryEngine}, so the collection/field NATS
 * broadcast and the physical-table DDL fire on every pod exactly as for an admin.
 *
 * <p>The manifest is translated into a package and applied in two passes. A dry run first finds
 * which collections already exist; the apply pass then writes every collection plus the fields of
 * only those collections that did not. <b>An existing collection is adopted and left completely
 * alone</b>, fields included: a reinstall must never clobber a tenant's live schema, and admins
 * pre-create a module's collections relying on this.
 *
 * <p>Ownership is read from the apply pass, not the dry run, so a collection someone else created
 * between the two is still reported {@code ADOPTED}.
 */
@Service
public class ModuleMetadataProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ModuleMetadataProvisioner.class);

    static final String COLLECTION = "COLLECTION";
    static final String FIELD = "FIELD";

    /** Collection names must be a safe identifier (also the route segment). */
    private static final Pattern COLLECTION_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** Field names must be a safe identifier. */
    private static final Pattern FIELD_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    /** Reserved system field names (case-insensitive) — cannot be module fields. */
    private static final Set<String> RESERVED_FIELD_NAMES =
            Set.of("id", "createdat", "updatedat", "createdby", "updatedby", "tenantid");

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

    private final PackageImportService packageImportService;
    private final TenantSlugResolver tenantSlugResolver;
    private final ObjectMapper objectMapper;

    public ModuleMetadataProvisioner(PackageImportService packageImportService,
                                     TenantSlugResolver tenantSlugResolver,
                                     ObjectMapper objectMapper) {
        this.packageImportService = packageImportService;
        this.tenantSlugResolver = tenantSlugResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates the manifest's collections and their fields, adopting any collection that exists.
     *
     * @throws IllegalArgumentException if a declaration is malformed — rejected before anything is
     *         imported, so a bad manifest cannot half-provision a tenant
     */
    public ProvisionResult provision(String tenantId, ModuleManifest manifest, String executingUserId) {
        List<ModuleManifest.CollectionManifest> collections = manifest.collections();
        if (collections == null || collections.isEmpty()) {
            return ProvisionResult.EMPTY;
        }
        for (ModuleManifest.CollectionManifest collection : collections) {
            validate(collection);
        }

        Map<String, Object> pkg = toPackage(collections);
        String slug = resolveSlug(tenantId);
        return TenantContext.callWithTenant(tenantId, slug,
                () -> apply(tenantId, manifest.id(), pkg, executingUserId));
    }

    @SuppressWarnings("unchecked")
    private ProvisionResult apply(String tenantId, String moduleId, Map<String, Object> pkg,
                                  String executingUserId) {
        ImportReport dryRun = packageImportService.importPackage(tenantId, pkg,
                new ImportOptions(ConflictMode.SKIP, true, null, null, executingUserId));
        Set<String> existing = new HashSet<>();
        for (ItemResult item : dryRun.items()) {
            if (COLLECTION.equals(item.type()) && "SKIPPED".equals(item.action())) {
                existing.add(item.naturalKey());
            }
        }
        if (!existing.isEmpty()) {
            log.info("Module '{}' adopts existing collection(s) {} in tenant {}; their fields are "
                    + "left alone", moduleId, existing, tenantId);
        }

        Map<String, Map<String, Object>> dataByKey = new HashMap<>();
        Set<String> itemKeyFilter = new HashSet<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) pkg.get("items")) {
            String type = (String) item.get("type");
            Map<String, Object> data = (Map<String, Object>) item.get("data");
            String key = PackageImportService.naturalKeyFor(type, data);
            dataByKey.put(type + ":" + key, data);
            if (COLLECTION.equals(type) || !existing.contains((String) data.get("collection_name"))) {
                itemKeyFilter.add(type + ":" + key);
            }
        }

        ImportReport report = packageImportService.importPackage(tenantId, pkg,
                new ImportOptions(ConflictMode.SKIP, false, null, itemKeyFilter, executingUserId));

        Set<String> createdCollections = new HashSet<>();
        for (ItemResult item : report.items()) {
            if (COLLECTION.equals(item.type()) && "CREATED".equals(item.action())) {
                createdCollections.add(item.naturalKey());
            }
        }

        List<ProvisionedResource> resources = new ArrayList<>();
        List<ItemResult> failures = new ArrayList<>();
        for (ItemResult item : report.items()) {
            if ("FAILED".equals(item.action())) {
                failures.add(item);
                continue;
            }
            if (FIELD.equals(item.type())
                    && !createdCollections.contains(item.naturalKey().split("\\.", 2)[0])) {
                // Only reachable when the collection appeared between the two passes: the module
                // does not own that collection, so it claims none of its fields either.
                log.warn("Module '{}' wrote field '{}' on a collection it adopted (created between "
                        + "the dry run and the apply pass) — not recording it", moduleId, item.naturalKey());
                continue;
            }
            boolean created = "CREATED".equals(item.action());
            resources.add(new ProvisionedResource(item.type(), item.naturalKey(), item.resourceId(),
                    created ? ModuleProvenanceStore.OWNERSHIP_CREATED
                            : ModuleProvenanceStore.OWNERSHIP_ADOPTED,
                    created ? contentHash(dataByKey.get(item.type() + ":" + item.naturalKey())) : null));
        }
        return new ProvisionResult(List.copyOf(resources), List.copyOf(failures));
    }

    /**
     * Translates manifest collections into a package. Keys are the system tables' column names,
     * which is what {@code PackageImportService} maps rows by.
     */
    static Map<String, Object> toPackage(List<ModuleManifest.CollectionManifest> collections) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ModuleManifest.CollectionManifest collection : collections) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", collection.name());
            data.put("display_name", displayName(collection));
            data.put("path", "/api/" + collection.name());
            data.put("active", true);
            data.put("current_version", 1);
            items.add(item(COLLECTION, data));
        }
        for (ModuleManifest.CollectionManifest collection : collections) {
            int order = 0;
            for (ModuleManifest.CollectionManifest.FieldManifest field : collection.fields()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("collection_name", collection.name());
                data.put("name", field.name());
                data.put("display_name",
                        field.displayName() != null && !field.displayName().isBlank()
                                ? field.displayName()
                                : field.name());
                data.put("type", field.type());
                data.put("required", field.required());
                data.put("field_order", order++);
                data.put("active", true);
                items.add(item(FIELD, data));
            }
        }
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("items", items);
        return pkg;
    }

    /** SHA-256 of the item's data as canonical JSON (keys sorted at every level), UTF-8. */
    String contentHash(Map<String, Object> data) {
        return ModuleJarService.sha256(objectMapper.writeValueAsBytes(sorted(data)));
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

    private static Map<String, Object> item(String type, Map<String, Object> data) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("data", data);
        return item;
    }

    private static void validate(ModuleManifest.CollectionManifest collection) {
        if (collection.name() == null || !COLLECTION_NAME.matcher(collection.name()).matches()) {
            throw new IllegalArgumentException(
                    "Module collection name must match ^[a-z][a-z0-9_]*$: " + collection.name());
        }
        for (ModuleManifest.CollectionManifest.FieldManifest field : collection.fields()) {
            if (field.name() == null || !FIELD_NAME.matcher(field.name()).matches()) {
                throw new IllegalArgumentException(
                        "Module field name must match ^[a-zA-Z][a-zA-Z0-9_]*$: " + field.name());
            }
            if (RESERVED_FIELD_NAMES.contains(field.name().toLowerCase())) {
                throw new IllegalArgumentException(
                        "Module field name is reserved by the platform: " + field.name());
            }
            if (field.type() == null || field.type().isBlank()) {
                throw new IllegalArgumentException(
                        "Module field '" + field.name() + "' has no type");
            }
        }
    }

    private static String displayName(ModuleManifest.CollectionManifest collection) {
        if (collection.displayName() != null && !collection.displayName().isBlank()) {
            return collection.displayName();
        }
        String name = collection.name();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
