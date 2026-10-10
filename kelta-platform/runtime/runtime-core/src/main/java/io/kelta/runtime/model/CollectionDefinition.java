package io.kelta.runtime.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory representation of a collection definition.
 *
 * <p>Contains all configuration for a runtime-defined resource type including fields,
 * validation rules, storage configuration, API configuration, and authorization configuration.
 *
 * <p>This record is immutable and uses defensive copying for collection fields.
 *
 * @param name Collection name (required, must be non-null and non-blank)
 * @param displayName Human-readable display name
 * @param description Description of the collection
 * @param fields List of field definitions (required, must have at least one field)
 * @param storageConfig Storage configuration
 * @param apiConfig API configuration (enabled operations, base path)
 * @param authzConfig Authorization configuration (roles)
 * @param version Version number for optimistic locking
 * @param createdAt Timestamp when the collection was created
 * @param updatedAt Timestamp when the collection was last updated
 * @param systemCollection Whether this is a system-defined collection (managed by the platform)
 * @param tenantScoped Whether this collection's data is scoped to a tenant
 * @param readOnly Whether this collection is read-only (no create/update/delete via API)
 * @param immutableFields Set of field names that cannot be updated after creation
 * @param columnMapping Map of API field name to physical database column name
 * @param displayFieldName Name of the field used for display-value lookups (nullable).
 *                         When set and the referenced field is unique and required,
 *                         GET-by-id requests accept either a UUID or this field's value.
 * @param tenantId The tenant that owns this collection (nullable for system collections).
 * @param trackHistory Whether every record create/update/delete is captured as a full
 *                     snapshot in {@code record_version}. When true, all fields are
 *                     tracked and per-field {@link FieldDefinition#trackHistory()} is
 *                     superseded for this collection.
 * @param captureGeo Whether HTTP record writes stamp the request-origin geolocation
 *                   (gateway {@code X-Geo-*} headers) into the {@code created_geo} /
 *                   {@code updated_geo} JSONB system columns. Flow/system writes leave
 *                   them null (no HTTP origin).
 * @param ownerField Name of the field holding the owning {@code users.id} UUID — a LOOKUP to
 *                   {@code users}, or the system {@code createdBy}. {@code null} = not owned.
 * @param ownerScope Which callers are limited to the rows they own ({@link OwnerScope});
 *                   never null ({@code NONE} when unset).
 * @param ownerScopeReads Whether owner scoping also narrows reads. {@code false} = owner-only
 *                   writes, but everyone with read permission still reads every row.
 *
 * @since 1.0.0
 */
public record CollectionDefinition(
    String name,
    String displayName,
    String description,
    List<FieldDefinition> fields,
    StorageConfig storageConfig,
    ApiConfig apiConfig,
    AuthzConfig authzConfig,
    long version,
    Instant createdAt,
    Instant updatedAt,
    boolean systemCollection,
    boolean tenantScoped,
    boolean readOnly,
    Set<String> immutableFields,
    Map<String, String> columnMapping,
    String displayFieldName,
    String tenantId,
    boolean trackHistory,
    boolean captureGeo,
    String ownerField,
    OwnerScope ownerScope,
    boolean ownerScopeReads
) {
    /**
     * Compact constructor with validation and defensive copying.
     */
    public CollectionDefinition {
        Objects.requireNonNull(name, "name cannot be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        Objects.requireNonNull(fields, "fields cannot be null");
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields cannot be empty");
        }

        // Defensive copy for fields list
        fields = List.copyOf(fields);
        // Defensive copy for immutableFields and columnMapping
        immutableFields = immutableFields != null ? Set.copyOf(immutableFields) : Set.of();
        columnMapping = columnMapping != null ? Map.copyOf(columnMapping) : Map.of();
        if (ownerField != null && ownerField.isBlank()) {
            ownerField = null;
        }
        if (ownerScope == null) {
            ownerScope = OwnerScope.NONE;
        }
    }

    /**
     * Backward-compatible constructor without the ownership parameters
     * (ownerField=null, ownerScope=NONE, ownerScopeReads=true).
     */
    public CollectionDefinition(
            String name, String displayName, String description,
            List<FieldDefinition> fields, StorageConfig storageConfig,
            ApiConfig apiConfig, AuthzConfig authzConfig,
            long version, Instant createdAt, Instant updatedAt,
            boolean systemCollection, boolean tenantScoped, boolean readOnly,
            Set<String> immutableFields, Map<String, String> columnMapping,
            String displayFieldName, String tenantId, boolean trackHistory, boolean captureGeo) {
        this(name, displayName, description, fields, storageConfig, apiConfig,
             authzConfig, version, createdAt, updatedAt,
             systemCollection, tenantScoped, readOnly, immutableFields, columnMapping,
             displayFieldName, tenantId, trackHistory, captureGeo, null, OwnerScope.NONE, true);
    }

    /**
     * Backward-compatible constructor without system collection parameters.
     * Defaults: systemCollection=false, tenantScoped=true, readOnly=false,
     * immutableFields=empty, columnMapping=empty.
     */
    public CollectionDefinition(
            String name, String displayName, String description,
            List<FieldDefinition> fields, StorageConfig storageConfig,
            ApiConfig apiConfig, AuthzConfig authzConfig,
            long version, Instant createdAt, Instant updatedAt) {
        this(name, displayName, description, fields, storageConfig, apiConfig,
             authzConfig, version, createdAt, updatedAt,
             false, true, false, Set.of(), Map.of(), null, null, false, false);
    }

    /**
     * Backward-compatible constructor without displayFieldName parameter.
     */
    public CollectionDefinition(
            String name, String displayName, String description,
            List<FieldDefinition> fields, StorageConfig storageConfig,
            ApiConfig apiConfig, AuthzConfig authzConfig,
            long version, Instant createdAt, Instant updatedAt,
            boolean systemCollection, boolean tenantScoped, boolean readOnly,
            Set<String> immutableFields, Map<String, String> columnMapping) {
        this(name, displayName, description, fields, storageConfig, apiConfig,
             authzConfig, version, createdAt, updatedAt,
             systemCollection, tenantScoped, readOnly, immutableFields, columnMapping, null, null, false, false);
    }

    /**
     * Backward-compatible constructor without tenantId parameter.
     */
    public CollectionDefinition(
            String name, String displayName, String description,
            List<FieldDefinition> fields, StorageConfig storageConfig,
            ApiConfig apiConfig, AuthzConfig authzConfig,
            long version, Instant createdAt, Instant updatedAt,
            boolean systemCollection, boolean tenantScoped, boolean readOnly,
            Set<String> immutableFields, Map<String, String> columnMapping,
            String displayFieldName) {
        this(name, displayName, description, fields, storageConfig, apiConfig,
             authzConfig, version, createdAt, updatedAt,
             systemCollection, tenantScoped, readOnly, immutableFields, columnMapping,
             displayFieldName, null, false, false);
    }

    /**
     * Backward-compatible constructor without trackHistory parameter.
     */
    public CollectionDefinition(
            String name, String displayName, String description,
            List<FieldDefinition> fields, StorageConfig storageConfig,
            ApiConfig apiConfig, AuthzConfig authzConfig,
            long version, Instant createdAt, Instant updatedAt,
            boolean systemCollection, boolean tenantScoped, boolean readOnly,
            Set<String> immutableFields, Map<String, String> columnMapping,
            String displayFieldName, String tenantId) {
        this(name, displayName, description, fields, storageConfig, apiConfig,
             authzConfig, version, createdAt, updatedAt,
             systemCollection, tenantScoped, readOnly, immutableFields, columnMapping,
             displayFieldName, tenantId, false, false);
    }

    /**
     * Backward-compatible constructor without captureGeo parameter.
     */
    public CollectionDefinition(
            String name, String displayName, String description,
            List<FieldDefinition> fields, StorageConfig storageConfig,
            ApiConfig apiConfig, AuthzConfig authzConfig,
            long version, Instant createdAt, Instant updatedAt,
            boolean systemCollection, boolean tenantScoped, boolean readOnly,
            Set<String> immutableFields, Map<String, String> columnMapping,
            String displayFieldName, String tenantId, boolean trackHistory) {
        this(name, displayName, description, fields, storageConfig, apiConfig,
             authzConfig, version, createdAt, updatedAt,
             systemCollection, tenantScoped, readOnly, immutableFields, columnMapping,
             displayFieldName, tenantId, trackHistory, false);
    }

    /**
     * Returns the registry key for this collection, combining tenantId and name
     * for tenant-scoped collections. System collections (null tenantId) use name only.
     *
     * @return the registry key
     */
    public String registryKey() {
        return tenantId != null ? tenantId + ":" + name : name;
    }
    
    /**
     * Whether this collection limits some callers to the rows they own: an owner field is set
     * and the scope is not {@link OwnerScope#NONE}. Who exactly is limited is decided per caller
     * ({@code CallerContext#ownerScoped}).
     */
    public boolean isOwnerScoped() {
        return ownerField != null && ownerScope != OwnerScope.NONE;
    }

    /**
     * Returns {@code true} if this collection is virtual (has no physical storage).
     * A virtual collection has a {@code null} storage configuration.
     *
     * @return true if this collection is virtual
     */
    public boolean isVirtual() {
        return storageConfig() == null;
    }

    /**
     * Gets a field definition by name.
     * 
     * @param fieldName the field name to look up
     * @return the field definition, or null if not found
     */
    public FieldDefinition getField(String fieldName) {
        return fields.stream()
            .filter(f -> f.name().equals(fieldName))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Checks if a field exists in this collection.
     * 
     * @param fieldName the field name to check
     * @return true if the field exists, false otherwise
     */
    public boolean hasField(String fieldName) {
        return getField(fieldName) != null;
    }

    /**
     * Every record carries these regardless of what the collection declares. They are real,
     * filterable columns — {@code PhysicalTableStorageAdapter#resolveColumnName} maps each to its
     * snake_case column — but they are NOT in {@link #fields()}, so {@link #hasField} returns
     * false for all of them.
     */
    public static final Set<String> SYSTEM_FIELD_NAMES =
        Set.of("id", "createdAt", "updatedAt", "createdBy", "updatedBy", "createdGeo", "updatedGeo");

    /**
     * Checks whether {@code fieldName} can be used in a filter, sort, or aggregate — that is, a
     * declared field OR a system field every record carries.
     *
     * <p>Prefer this over {@link #hasField} whenever the question is "may I query on this?".
     * {@code hasField} answers only "did the collection declare this?", and the two differ for
     * every audit field. Guarding a query with {@code hasField} silently rejects a perfectly valid
     * column: that is how per-member quota enforcement came to fail open on every collection
     * (issue #1384) — the hook checked {@code hasField("createdBy")}, got false, and allowed the
     * write.
     */
    public boolean hasQueryableField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        if (SYSTEM_FIELD_NAMES.contains(fieldName)) {
            return true;
        }
        if ("tenantId".equals(fieldName) && tenantScoped()) {
            return true;
        }
        return hasField(fieldName);
    }

    /**
     * Gets all field names in this collection.
     *
     * @return list of field names
     */
    public List<String> getFieldNames() {
        return fields.stream()
            .map(FieldDefinition::name)
            .toList();
    }

    /**
     * Returns true when any field carries a data-masking policy
     * ({@code fieldTypeConfig.masking}). Used to flag record-change events so
     * broadcast consumers without per-user field security (e.g. the realtime
     * bridge) suppress record data. Deliberately ignores whether the field's
     * type is maskable — a misconfigured policy suppresses rather than leaks.
     */
    public boolean hasMaskingConfiguredFields() {
        return fields.stream().anyMatch(f ->
            f.fieldTypeConfig() != null
                && f.fieldTypeConfig().get("masking") instanceof java.util.Map<?, ?> cfg
                && !cfg.isEmpty());
    }
    
    /**
     * Creates a new collection definition with an incremented version.
     *
     * @return a new collection definition with version + 1 and updated timestamp
     */
    public CollectionDefinition withIncrementedVersion() {
        return new CollectionDefinition(
            name, displayName, description, fields,
            storageConfig, apiConfig, authzConfig,
            version + 1, createdAt, Instant.now(),
            systemCollection, tenantScoped, readOnly,
            immutableFields, columnMapping, displayFieldName, tenantId, trackHistory, captureGeo,
            ownerField, ownerScope, ownerScopeReads
        );
    }

    /**
     * Creates a new collection definition with updated fields.
     *
     * @param newFields the new field definitions
     * @return a new collection definition with the updated fields
     */
    public CollectionDefinition withFields(List<FieldDefinition> newFields) {
        return new CollectionDefinition(
            name, displayName, description, newFields,
            storageConfig, apiConfig, authzConfig,
            version + 1, createdAt, Instant.now(),
            systemCollection, tenantScoped, readOnly,
            immutableFields, columnMapping, displayFieldName, tenantId, trackHistory, captureGeo,
            ownerField, ownerScope, ownerScopeReads
        );
    }

    /**
     * Gets the effective column name for an API field name.
     * Checks the field-level columnName first, then falls back to collection-level
     * columnMapping, then uses the field name as-is.
     *
     * @param fieldName the API field name
     * @return the physical database column name
     */
    public String getEffectiveColumnName(String fieldName) {
        // Check field-level columnName first
        FieldDefinition field = getField(fieldName);
        if (field != null && field.columnName() != null) {
            return field.columnName();
        }
        // Fall back to collection-level columnMapping
        return columnMapping.getOrDefault(fieldName, fieldName);
    }
    
    /**
     * Creates a new builder for constructing collection definitions.
     * 
     * @return a new builder instance
     */
    public static CollectionDefinitionBuilder builder() {
        return new CollectionDefinitionBuilder();
    }
}
