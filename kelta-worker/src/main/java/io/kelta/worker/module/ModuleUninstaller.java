package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.query.AggregationSpec;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.listener.CollectionDeletionGuardHook;
import io.kelta.worker.service.PackageImportService;
import io.kelta.worker.service.TenantSlugResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Decides and applies what uninstalling an external module removes from the tenant
 * ({@code specs/module-platform/3-lifecycle.md} §3.5, as part 9c ships it for external modules).
 *
 * <p>One decision per provenance row, the first rule that matches:
 * <ol>
 *   <li>{@code ADOPTED} → {@code KEEP_ADOPTED}, always;</li>
 *   <li>the resource no longer exists → {@code GONE} (its provenance row is dropped);</li>
 *   <li>no content hash → {@code KEEP_UNVERIFIABLE};</li>
 *   <li>the stored row's hash differs from the one taken at install → {@code KEEP_MODIFIED};</li>
 *   <li>a field of a collection that is not itself removed, holding records →
 *       {@code KEEP_HAS_DATA} unless {@code purgeData};</li>
 *   <li>a collection with records → {@code KEEP_HAS_DATA} unless {@code purgeData};</li>
 *   <li>a collection with cascading dependents (layouts, notes, history…) →
 *       {@code KEEP_HAS_DEPENDENTS} unless {@code purgeData};</li>
 *   <li>otherwise {@code REMOVE}.</li>
 * </ol>
 * A field of a removed collection is removed with it by the cascade, not deleted on its own.
 *
 * <p>The apply deletes in the reverse of {@link PackageImportService#supportedTypes()} (menu
 * items before menus, fields before collections), through {@code QueryEngine} so every pod sees
 * it, under the tenant and with {@link CollectionDeletionGuardHook#FORCE} bound to
 * {@code purgeData}. A delete that fails is reported and the rest continue: the module row is
 * already gone, and throwing would strand a half-removed module no one could address. Kept rows
 * stay, so "what did the module I removed leave behind?" still has an answer.
 */
public class ModuleUninstaller {

    private static final Logger log = LoggerFactory.getLogger(ModuleUninstaller.class);

    public static final String KEEP_ADOPTED = "KEEP_ADOPTED";
    public static final String GONE = "GONE";
    public static final String KEEP_UNVERIFIABLE = "KEEP_UNVERIFIABLE";
    public static final String KEEP_MODIFIED = "KEEP_MODIFIED";
    public static final String KEEP_HAS_DATA = "KEEP_HAS_DATA";
    public static final String KEEP_HAS_DEPENDENTS = "KEEP_HAS_DEPENDENTS";
    public static final String REMOVE = "REMOVE";
    public static final String FAILED = "FAILED";

    private static final String COLLECTION = "COLLECTION";
    private static final String FIELD = "FIELD";

    private final ModuleProvenanceStore provenanceStore;
    private final ModuleMetadataProvisioner provisioner;
    private final CollectionDeletionGuardHook deletionGuard;
    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final TenantSlugResolver tenantSlugResolver;
    private final ObjectMapper objectMapper;

    public ModuleUninstaller(ModuleProvenanceStore provenanceStore, ModuleMetadataProvisioner provisioner,
                             CollectionDeletionGuardHook deletionGuard, QueryEngine queryEngine,
                             CollectionRegistry collectionRegistry, TenantSlugResolver tenantSlugResolver,
                             ObjectMapper objectMapper) {
        this.provenanceStore = Objects.requireNonNull(provenanceStore);
        this.provisioner = Objects.requireNonNull(provisioner);
        this.deletionGuard = Objects.requireNonNull(deletionGuard);
        this.queryEngine = Objects.requireNonNull(queryEngine);
        this.collectionRegistry = Objects.requireNonNull(collectionRegistry);
        this.tenantSlugResolver = Objects.requireNonNull(tenantSlugResolver);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /** The {@code module-uninstall-plans} attributes, {@code planHash} included. Writes nothing. */
    public Map<String, Object> plan(TenantModuleData module, boolean purgeData) {
        String tenantId = module.tenantId();
        List<Map<String, Object>> entries = TenantContext.callWithTenant(tenantId, slug(tenantId),
                () -> decide(tenantId, module.moduleId(), purgeData));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("moduleId", module.moduleId());
        attributes.put("installedVersion", module.version());
        attributes.put("purgeData", purgeData);
        attributes.put("entries", entries.stream().map(ModuleUninstaller::publicEntry).toList());
        attributes.put("planHash", hash(attributes));
        return attributes;
    }

    /**
     * Removes the plan's {@code REMOVE} entries and drops the provenance of what was removed or
     * was already gone. Call after the module row is deleted.
     *
     * @return attributes {@code removed}, {@code retained} (with {@code decision}) and {@code failed}
     */
    public Map<String, Object> apply(TenantModuleData module, boolean purgeData) {
        String tenantId = module.tenantId();
        String slug = slug(tenantId);
        return TenantContext.callWithTenant(tenantId, slug, () ->
                ScopedValue.where(CollectionDeletionGuardHook.FORCE, purgeData)
                        .call(() -> remove(tenantId, decide(tenantId, module.moduleId(), purgeData), purgeData)));
    }

    /** Every provenance row of the module as retained, for an uninstall that removes nothing. */
    public List<Map<String, Object>> retainedAll(String tenantId, String moduleId) {
        return provenanceStore.findByModule(tenantId, moduleId).stream()
                .map(row -> entry(row, null))
                .map(ModuleUninstaller::publicEntry)
                .toList();
    }

    private List<Map<String, Object>> decide(String tenantId, String moduleId, boolean purgeData) {
        List<Map<String, Object>> rows = provenanceStore.findByModule(tenantId, moduleId);
        Map<String, String> collectionDecisions = new HashMap<>();
        Map<String, Long> recordCounts = new HashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        // Collections first: a field's decision depends on its collection's.
        List<Map<String, Object>> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(row -> COLLECTION.equals(row.get("resource_type")) ? 0 : 1));
        for (Map<String, Object> row : ordered) {
            String type = (String) row.get("resource_type");
            String key = (String) row.get("natural_key");
            Map<String, Object> entry = entry(row, null);
            String decision = baseDecision(row);
            if (decision == null && COLLECTION.equals(type)) {
                long records = recordCount(key, recordCounts);
                entry.put("records", records);
                if (records != 0 && !purgeData) {
                    decision = KEEP_HAS_DATA;
                } else if (!purgeData && !deletionGuard.dependents((String) row.get("resource_id"), tenantId).isEmpty()) {
                    decision = KEEP_HAS_DEPENDENTS;
                } else {
                    decision = REMOVE;
                }
                collectionDecisions.put(key, decision);
            } else if (decision == null && FIELD.equals(type)) {
                String collection = key.split("\\.", 2)[0];
                if (REMOVE.equals(collectionDecisions.get(collection))) {
                    decision = REMOVE;
                    entry.put("cascade", true);
                } else {
                    long records = recordCount(collection, recordCounts);
                    entry.put("records", records);
                    decision = records != 0 && !purgeData ? KEEP_HAS_DATA : REMOVE;
                }
            } else if (decision == null) {
                decision = REMOVE;
            }
            entry.put("decision", decision);
            entries.add(entry);
        }
        entries.sort(Comparator.comparing((Map<String, Object> e) -> String.valueOf(e.get("resourceType")))
                .thenComparing(e -> String.valueOf(e.get("naturalKey"))));
        return entries;
    }

    /** Rules 1–4, or {@code null} when the resource is the module's own and untouched. */
    private String baseDecision(Map<String, Object> row) {
        if (ModuleProvenanceStore.OWNERSHIP_ADOPTED.equals(row.get("ownership"))) {
            return KEEP_ADOPTED;
        }
        Optional<String> current = provisioner.currentContentHash((String) row.get("resource_type"),
                (String) row.get("resource_id"));
        if (current.isEmpty()) {
            return GONE;
        }
        Object recorded = row.get("content_hash");
        if (recorded == null) {
            return KEEP_UNVERIFIABLE;
        }
        return recorded.equals(current.get()) ? null : KEEP_MODIFIED;
    }

    private long recordCount(String collection, Map<String, Long> cache) {
        return cache.computeIfAbsent(collection, name -> {
            CollectionDefinition definition = collectionRegistry.get(name);
            if (definition == null) {
                return 0L;
            }
            try {
                Object count = queryEngine.aggregate(definition, List.of(),
                        List.of(new AggregationSpec("COUNT", null, "n"))).get("n");
                return count instanceof Number n ? n.longValue() : 0L;
            } catch (RuntimeException e) {
                // -1 = unknown, and unknown means keep: an uncountable collection is never
                // treated as empty.
                log.warn("Could not count records of '{}' for an uninstall plan: {}", name, e.getMessage());
                return -1L;
            }
        });
    }

    private Map<String, Object> remove(String tenantId, List<Map<String, Object>> entries, boolean purgeData) {
        List<String> order = PackageImportService.supportedTypes();
        List<Map<String, Object>> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing((Map<String, Object> e) -> -order.indexOf(e.get("resourceType"))));
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> retained = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Map<String, Object> entry : sorted) {
            String decision = (String) entry.get("decision");
            String type = (String) entry.get("resourceType");
            if (GONE.equals(decision)) {
                provenanceStore.deleteRow(tenantId, (String) entry.get("id"));
                continue;
            }
            if (!REMOVE.equals(decision)) {
                retained.add(publicEntry(entry));
                continue;
            }
            if (!Boolean.TRUE.equals(entry.get("cascade"))) {
                try {
                    CollectionDefinition definition =
                            collectionRegistry.get(PackageImportService.systemCollectionFor(type));
                    queryEngine.delete(definition, (String) entry.get("resourceId"));
                } catch (RuntimeException e) {
                    Map<String, Object> outcome = publicEntry(entry);
                    if (COLLECTION.equals(type) && !purgeData) {
                        // Gained dependents between plan and apply; the guard refused, as it should.
                        outcome.put("decision", KEEP_HAS_DEPENDENTS);
                        retained.add(outcome);
                    } else {
                        outcome.put("decision", FAILED);
                        outcome.put("error", e.getMessage());
                        failed.add(outcome);
                    }
                    log.warn("Module uninstall kept {} '{}' in tenant {}: {}", type, entry.get("naturalKey"),
                            tenantId, e.getMessage());
                    continue;
                }
            }
            provenanceStore.deleteRow(tenantId, (String) entry.get("id"));
            removed.add(publicEntry(entry));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", removed);
        result.put("retained", retained);
        result.put("failed", failed);
        return result;
    }

    private static Map<String, Object> entry(Map<String, Object> row, String decision) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", row.get("id"));
        entry.put("resourceId", row.get("resource_id"));
        entry.put("resourceType", row.get("resource_type"));
        entry.put("naturalKey", row.get("natural_key"));
        entry.put("ownership", row.get("ownership"));
        if (decision != null) {
            entry.put("decision", decision);
        }
        return entry;
    }

    /** The entry as the API shows it: no provenance row id or resource id. */
    private static Map<String, Object> publicEntry(Map<String, Object> entry) {
        Map<String, Object> shown = new LinkedHashMap<>(entry);
        shown.remove("id");
        shown.remove("resourceId");
        shown.remove("cascade");
        return shown;
    }

    private String hash(Map<String, Object> attributes) {
        Map<String, Object> copy = new TreeMap<>(attributes);
        copy.remove("planHash");
        return ModuleJarService.sha256(objectMapper.writeValueAsBytes(canonical(copy)));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ModuleUninstaller::canonical).toList();
        }
        return value;
    }

    private String slug(String tenantId) {
        return tenantSlugResolver.resolveSlug(tenantId).orElseGet(() -> {
            if (tenantId.equals(TenantContext.get()) && TenantContext.getSlug() != null) {
                return TenantContext.getSlug();
            }
            throw new IllegalStateException("Cannot resolve the slug for tenant " + tenantId);
        });
    }
}
