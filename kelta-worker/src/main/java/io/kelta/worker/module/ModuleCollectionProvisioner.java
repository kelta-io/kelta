package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.module.ModuleManifest;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Creates the collections a module's manifest declares, in the installing tenant.
 *
 * <p>Creation goes through {@link QueryEngine#create} against the {@code collections} and
 * {@code fields} system collections — the same write path the admin API and MCP tools use — so
 * the collection-config NATS broadcast and lifecycle init (including the physical table) fire
 * normally on every pod. A module therefore gets exactly the schema powers an admin already has
 * through the UI, and no more: no DDL, no migration, no reserved-name overrides.
 *
 * <p>Mirrors {@code ExternalEntityMaterializer}, which materializes an OpenAPI operation the same
 * way.
 *
 * <p><b>Existing collections are left alone.</b> A reinstall or version upgrade that re-declares a
 * collection must never clobber a tenant's live schema or data, so a name that already exists is
 * skipped rather than updated or failed. Uninstall likewise does not drop collections — dropping a
 * tenant's data is an explicit admin act, not a side effect of removing a module.
 */
@Service
public class ModuleCollectionProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ModuleCollectionProvisioner.class);

    /** Collection names must be a safe identifier (also the route segment). */
    private static final Pattern COLLECTION_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** Field names must be a safe identifier. */
    private static final Pattern FIELD_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    /** Reserved system field names (case-insensitive) — cannot be module fields. */
    private static final Set<String> RESERVED_FIELD_NAMES =
            Set.of("id", "createdat", "updatedat", "createdby", "updatedby", "tenantid");

    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;

    public ModuleCollectionProvisioner(QueryEngine queryEngine,
                                       CollectionRegistry collectionRegistry) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
    }

    /**
     * Creates every manifest-declared collection that does not already exist in the tenant.
     *
     * @param tenantId the installing tenant
     * @param collections the manifest's collection declarations
     * @return the names actually created (skipped ones are not included)
     * @throws IllegalArgumentException if a declaration is malformed — the install is rejected
     *         before anything is created, so a bad manifest cannot half-provision a tenant
     */
    /**
     * What a provisioning run did, split by ownership.
     *
     * <p>The distinction is the whole point: uninstall may remove a collection the module
     * <b>created</b>, and must never remove one it merely <b>adopted</b> because the name already
     * existed. Returning only the created names, as this used to, throws that away.
     */
    public record ProvisionResult(List<String> created, List<String> adopted) {
        public int total() {
            return created.size() + adopted.size();
        }
    }

    /**
     * @deprecated use {@link #provisionWithOwnership} — this loses the created/adopted distinction
     *             that uninstall depends on.
     */
    @Deprecated
    public List<String> provision(String tenantId, List<ModuleManifest.CollectionManifest> collections) {
        return provisionWithOwnership(tenantId, collections).created();
    }

    /** Creates the declared collections, reporting which were created and which already existed. */
    public ProvisionResult provisionWithOwnership(
            String tenantId, List<ModuleManifest.CollectionManifest> collections) {
        if (collections == null || collections.isEmpty()) {
            return new ProvisionResult(List.of(), List.of());
        }
        for (ModuleManifest.CollectionManifest collection : collections) {
            validate(collection);
        }

        return TenantContext.callWithTenant(tenantId, () -> {
            CollectionDefinition collectionsDef = collectionRegistry.get("collections");
            CollectionDefinition fieldsDef = collectionRegistry.get("fields");
            if (collectionsDef == null || fieldsDef == null) {
                throw new IllegalStateException("System collections not initialized");
            }

            List<String> created = new java.util.ArrayList<>();
            List<String> adopted = new java.util.ArrayList<>();
            for (ModuleManifest.CollectionManifest collection : collections) {
                if (collectionRegistry.get(collection.name()) != null) {
                    log.info("Module collection '{}' already exists for tenant {} — adopting",
                            collection.name(), tenantId);
                    adopted.add(collection.name());
                    continue;
                }
                createCollection(tenantId, collectionsDef, fieldsDef, collection);
                created.add(collection.name());
            }
            return new ProvisionResult(List.copyOf(created), List.copyOf(adopted));
        });
    }

    private void createCollection(String tenantId,
                                  CollectionDefinition collectionsDef,
                                  CollectionDefinition fieldsDef,
                                  ModuleManifest.CollectionManifest collection) {
        Map<String, Object> collectionData = new LinkedHashMap<>();
        // A direct queryEngine.create must set tenantId itself — the JSON:API layer injects it on
        // the HTTP path, and collection.tenant_id is NOT NULL.
        collectionData.put("tenantId", tenantId);
        collectionData.put("name", collection.name());
        collectionData.put("displayName", displayName(collection));
        collectionData.put("path", "/api/" + collection.name());
        collectionData.put("active", true);
        collectionData.put("systemCollection", false);
        collectionData.put("currentVersion", 1);

        Map<String, Object> createdCollection = queryEngine.create(collectionsDef, collectionData);
        String collectionId = String.valueOf(createdCollection.get("id"));

        int order = 0;
        for (ModuleManifest.CollectionManifest.FieldManifest field : collection.fields()) {
            Map<String, Object> fieldData = new LinkedHashMap<>();
            // fields is tenant-scoped too (KLT-206): same direct-create rule as above.
            fieldData.put("tenantId", tenantId);
            fieldData.put("collectionId", collectionId);
            fieldData.put("name", field.name());
            fieldData.put("displayName",
                    field.displayName() != null && !field.displayName().isBlank()
                            ? field.displayName()
                            : field.name());
            fieldData.put("type", field.type());
            fieldData.put("required", field.required());
            fieldData.put("fieldOrder", order++);
            fieldData.put("active", true);
            queryEngine.create(fieldsDef, fieldData);
        }

        log.info("Created module collection '{}' (id={}) with {} fields for tenant {}",
                collection.name(), collectionId, collection.fields().size(), tenantId);
    }

    private void validate(ModuleManifest.CollectionManifest collection) {
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

    private String displayName(ModuleManifest.CollectionManifest collection) {
        if (collection.displayName() != null && !collection.displayName().isBlank()) {
            return collection.displayName();
        }
        String name = collection.name();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
