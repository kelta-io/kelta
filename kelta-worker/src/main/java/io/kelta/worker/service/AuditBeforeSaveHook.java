package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Wildcard before-save hook that records configuration changes to the setup audit trail.
 *
 * <p>Replaces {@code SetupAuditWriteListener} by using the consolidated
 * {@link BeforeSaveHook} pattern with wildcard collection matching. Only writes
 * to configuration collections (schema, profiles, permissions, users, OIDC,
 * flows, etc.) are audited. User data collections are ignored.
 *
 * <p>Runs with order {@code 1000} to ensure it executes after all
 * collection-specific hooks.
 *
 * @since 1.0.0
 */
@Component
public class AuditBeforeSaveHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(AuditBeforeSaveHook.class);

    /**
     * Maps collection names to their setup section for audit purposes.
     */
    private static final Map<String, String> COLLECTION_SECTION_MAP = Map.ofEntries(
            // Schema
            Map.entry("collections", "Schema"),
            Map.entry("fields", "Schema"),

            // Profiles
            Map.entry("profiles", "Profiles"),
            Map.entry("profile-system-permissions", "Profiles"),
            Map.entry("profile-object-permissions", "Profiles"),
            Map.entry("profile-field-permissions", "Profiles"),

            // Users
            Map.entry("users", "Users"),
            Map.entry("groups", "Users"),

            // OIDC
            Map.entry("oidc-providers", "OIDC"),

            // Validation
            Map.entry("validation-rules", "Validation"),
            Map.entry("record-types", "Validation"),

            // UI
            Map.entry("page-layouts", "UI"),
            Map.entry("list-views", "UI"),

            // Flows
            Map.entry("flows", "Flows"),

            // Governor Limits
            Map.entry("governor-limits", "Governor Limits")
    );

    /**
     * Collections whose names should be derived from the entity type rather
     * than the collection name itself (for more readable audit entries).
     */
    private static final Map<String, CollectionDefinition> SYSTEM_DEFINITIONS =
            SystemCollectionDefinitions.byName();

    private static final Set<String> JUNCTION_COLLECTIONS = Set.of(
            "profile-system-permissions", "profile-object-permissions", "profile-field-permissions"
    );

    private final SetupAuditService auditService;
    private final ObjectMapper objectMapper;

    public AuditBeforeSaveHook(SetupAuditService auditService) {
        this.auditService = auditService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getCollectionName() {
        return BeforeSaveHookRegistry.WILDCARD;
    }

    @Override
    public int getOrder() {
        return 1000;
    }

    @Override
    public void afterCreate(String collectionName, Map<String, Object> record, String tenantId) {
        String section = COLLECTION_SECTION_MAP.get(collectionName);
        if (section == null) {
            return; // Not a setup collection - skip
        }

        String entityType = resolveEntityType(collectionName);
        String entityName = extractEntityName(record);
        String recordId = extractRecordId(record);
        String userId = extractUserId(record);

        auditService.log(tenantId, userId, "CREATED", section, entityType,
                recordId, entityName, null, toJson(visible(collectionName, record)));
    }

    @Override
    public void afterUpdate(String collectionName, String id, Map<String, Object> record,
                            Map<String, Object> previous, String tenantId) {
        String section = COLLECTION_SECTION_MAP.get(collectionName);
        if (section == null) {
            return; // Not a setup collection - skip
        }

        String entityType = resolveEntityType(collectionName);
        String userId = extractUserId(record);

        auditService.log(tenantId, userId, "UPDATED", section, entityType,
                id, null, null, toJson(visible(collectionName, record)));
    }

    @Override
    public void afterDelete(String collectionName, String id, String tenantId) {
        String section = COLLECTION_SECTION_MAP.get(collectionName);
        if (section == null) {
            return; // Not a setup collection - skip
        }

        String entityType = resolveEntityType(collectionName);

        auditService.log(tenantId, null, "DELETED", section, entityType,
                id, null, null, null);
    }

    /**
     * Resolves a human-readable entity type from the collection name.
     * For junction tables, returns a simplified name.
     */
    static String resolveEntityType(String collectionName) {
        if (JUNCTION_COLLECTIONS.contains(collectionName)) {
            // e.g., "profile-system-permissions" -> "system-permission"
            return collectionName.substring(collectionName.indexOf('-') + 1);
        }
        // Remove trailing 's' for singular form (simple heuristic)
        if (collectionName.endsWith("s") && !collectionName.endsWith("ss")) {
            return collectionName.substring(0, collectionName.length() - 1);
        }
        return collectionName;
    }

    /**
     * Extracts a display name from record data for audit entries.
     */
    private static String extractEntityName(Map<String, Object> data) {
        for (String key : new String[]{"name", "label", "apiName", "entityName", "email"}) {
            Object value = data.get(key);
            if (value != null) {
                String str = value.toString();
                if (!str.isBlank()) {
                    return str.length() > 200 ? str.substring(0, 200) : str;
                }
            }
        }
        return null;
    }

    /**
     * Extracts the record ID from the record data.
     */
    private static String extractRecordId(Map<String, Object> data) {
        Object id = data.get("id");
        return id != null ? id.toString() : null;
    }

    /**
     * Extracts the user ID from the record data.
     * Looks for createdBy or updatedBy fields injected by the router.
     */
    private static String extractUserId(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Object userId = data.get("createdBy");
        if (userId == null) {
            userId = data.get("updatedBy");
        }
        return userId != null ? userId.toString() : null;
    }

    /**
     * The record as the audit trail may store it: without the collection's server-only fields
     * (encrypted secrets, secret hashes). The trail is itself readable through the
     * {@code setup-audit-entries} collection, so anything written here is effectively served.
     * Every audited collection is a system collection, so its canonical definition decides.
     */
    static Map<String, Object> visible(String collectionName, Map<String, Object> record) {
        CollectionDefinition definition = SYSTEM_DEFINITIONS.get(collectionName);
        return definition == null ? record : definition.withoutServerOnlyFields(record);
    }

    private String toJson(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JacksonException e) {
            log.warn("Failed to serialize audit data to JSON: {}", e.getMessage());
            return null;
        }
    }
}
