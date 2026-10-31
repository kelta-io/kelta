package io.kelta.runtime.module.schema.hooks;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lifecycle hook for the "collections" system collection.
 *
 * <p>Enforces business rules for collection CRUD operations:
 * <ul>
 *   <li>Before create: Refuse {@code systemCollection: true}, validate name format, set
 *       version=1, generate API path</li>
 *   <li>Before update: Reject a change of {@code systemCollection} or of the name</li>
 *   <li>After create/delete: Log for audit trail</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class CollectionLifecycleHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(CollectionLifecycleHook.class);

    private static final String SYSTEM_COLLECTION = "systemCollection";

    /** The field-constraint code for a write to a value only the platform sets. */
    private static final String SYSTEM_MAINTAINED = "systemMaintained";

    @Override
    public String getCollectionName() {
        return "collections";
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        // systemCollection is the platform's: system collections are declared in code and seeded
        // by SystemCollectionSeeder over JDBC, never through a record write.
        if (isTrue(record.get(SYSTEM_COLLECTION))) {
            return systemCollectionRefused();
        }

        // Validate collection name format
        Object nameObj = record.get("name");
        if (nameObj == null || nameObj.toString().isBlank()) {
            return BeforeSaveResult.error("name", "Collection name is required");
        }

        String name = nameObj.toString().trim();

        // Validate name format: lowercase alphanumeric with hyphens and underscores
        if (!name.matches("^[a-z][a-z0-9_-]*$")) {
            return BeforeSaveResult.error("name",
                    "Collection name must start with a lowercase letter and contain only lowercase letters, numbers, underscores, and hyphens");
        }

        // Set defaults
        Map<String, Object> updates = new HashMap<>();

        // Set version to 1 for new collections
        if (!record.containsKey("currentVersion")) {
            updates.put("currentVersion", 1L);
        }

        // Set active to true
        if (!record.containsKey("active")) {
            updates.put("active", true);
        }

        // Generate API path if not provided
        if (!record.containsKey("path") || record.get("path") == null) {
            updates.put("path", "/api/" + name);
        }

        if (updates.isEmpty()) {
            return BeforeSaveResult.ok();
        }

        log.debug("Setting defaults for new collection '{}': {}", name, updates.keySet());
        return BeforeSaveResult.withFieldUpdates(updates);
    }

    /**
     * A collection's name is fixed once it is created. The physical table, the API path, the
     * gateway route and every name-keyed reference (lookup targets, menu paths, search index)
     * are derived from it, and none of them follow a rename — a changed name would leave the
     * stored records behind in a table nothing reads. Sending the unchanged name is fine.
     */
    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                         Map<String, Object> previous, String tenantId) {
        Object nextSystem = record.get(SYSTEM_COLLECTION);
        if (nextSystem != null
                && isTrue(nextSystem) != isTrue(previous != null ? previous.get(SYSTEM_COLLECTION) : null)) {
            return systemCollectionRefused();
        }
        if (!record.containsKey("name") || previous == null) {
            return BeforeSaveResult.ok();
        }
        Object next = record.get("name");
        Object current = previous.get("name");
        String nextName = next == null ? null : next.toString().trim();
        String currentName = current == null ? null : current.toString().trim();
        if (currentName == null || currentName.equals(nextName)) {
            return BeforeSaveResult.ok();
        }
        return BeforeSaveResult.error("name",
                "Collection name cannot be changed after creation (it is '" + currentName
                        + "'); change the display name instead");
    }

    /**
     * The refusal of a write that sets {@code systemCollection} to {@code true} on create, or
     * changes it on update. Whether a collection is platform metadata decides how the runtime
     * loads it (system-collection storage, validation-rule scope, shared-row visibility), so only
     * the platform's seeder sets it. {@code false} on create and a re-sent stored value on update
     * (a full-record round-trip) pass.
     */
    private static BeforeSaveResult systemCollectionRefused() {
        return BeforeSaveResult.errors(List.of(new BeforeSaveResult.ValidationError(SYSTEM_COLLECTION,
                "systemCollection is set by the platform and cannot be written through the API",
                SYSTEM_MAINTAINED)));
    }

    /** A boolean flag's value, accepting the string form an uncoerced payload may carry. */
    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value)
                || (value instanceof String text && "true".equalsIgnoreCase(text.trim()));
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        log.info("Schema lifecycle: collection '{}' created for tenant '{}'",
                record.get("name"), tenantId);
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        log.info("Schema lifecycle: collection '{}' deleted for tenant '{}'", id, tenantId);
    }
}
