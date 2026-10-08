package io.kelta.runtime.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builder for constructing {@link CollectionDefinition} instances.
 *
 * <p>Provides a fluent API with method chaining for building collection definitions.
 * Required fields (name and at least one field) must be set before calling {@link #build()}.
 *
 * <p>Sensible defaults are applied for optional configuration:
 * <ul>
 *   <li>Storage: Physical tables with table name "{name}"</li>
 *   <li>API: All operations enabled</li>
 *   <li>Authorization: Disabled</li>
 *   <li>Version: 1</li>
 *   <li>System collection: false</li>
 *   <li>Tenant scoped: true</li>
 *   <li>Read only: false</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * CollectionDefinition collection = CollectionDefinition.builder()
 *     .name("products")
 *     .displayName("Products")
 *     .description("Product catalog")
 *     .addField(FieldDefinition.requiredString("sku"))
 *     .addField(FieldDefinition.requiredString("name"))
 *     .addField(FieldDefinition.doubleField("price"))
 *     .storageConfig(StorageConfig.physicalTable("products"))
 *     .build();
 * }</pre>
 *
 * @since 1.0.0
 */
public class CollectionDefinitionBuilder {

    private String name;
    private String displayName;
    private String description;
    private List<FieldDefinition> fields = new ArrayList<>();
    private StorageConfig storageConfig;
    private ApiConfig apiConfig;
    private AuthzConfig authzConfig;
    private long version = 1L;
    private Instant createdAt;
    private Instant updatedAt;
    private boolean systemCollection = false;
    private boolean tenantScoped = true;
    private boolean readOnly = false;
    private Set<String> immutableFields = new HashSet<>();
    private Map<String, String> columnMapping = new HashMap<>();
    private String displayFieldName;
    private String tenantId;
    private boolean trackHistory = false;
    private boolean captureGeo = false;
    private String ownerField;
    private OwnerScope ownerScope = OwnerScope.NONE;
    private boolean ownerScopeReads = true;

    /**
     * Creates a new collection definition builder.
     */
    public CollectionDefinitionBuilder() {
    }

    /**
     * Sets the collection name.
     *
     * @param name the collection name (required)
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder name(String name) {
        this.name = name;
        return this;
    }

    /**
     * Sets the display name.
     *
     * @param displayName the human-readable display name
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder displayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    /**
     * Sets the description.
     *
     * @param description the collection description
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder description(String description) {
        this.description = description;
        return this;
    }

    /**
     * Adds a field definition to the collection.
     *
     * @param field the field definition to add
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder addField(FieldDefinition field) {
        this.fields.add(field);
        return this;
    }

    /**
     * Sets all field definitions for the collection.
     *
     * @param fields the list of field definitions
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder fields(List<FieldDefinition> fields) {
        this.fields = new ArrayList<>(fields);
        return this;
    }

    /**
     * Sets the storage configuration.
     *
     * @param storageConfig the storage configuration
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder storageConfig(StorageConfig storageConfig) {
        this.storageConfig = storageConfig;
        return this;
    }

    /**
     * Sets the API configuration.
     *
     * @param apiConfig the API configuration
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder apiConfig(ApiConfig apiConfig) {
        this.apiConfig = apiConfig;
        return this;
    }

    /**
     * Sets the authorization configuration.
     *
     * @param authzConfig the authorization configuration
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder authzConfig(AuthzConfig authzConfig) {
        this.authzConfig = authzConfig;
        return this;
    }

    /**
     * Sets the version number.
     *
     * @param version the version number
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder version(long version) {
        this.version = version;
        return this;
    }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt the creation timestamp
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder createdAt(Instant createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    /**
     * Sets the last update timestamp.
     *
     * @param updatedAt the last update timestamp
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder updatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
        return this;
    }

    /**
     * Marks this collection as a system collection (managed by the platform).
     *
     * @param systemCollection true if this is a system collection
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder systemCollection(boolean systemCollection) {
        this.systemCollection = systemCollection;
        return this;
    }

    /**
     * Sets whether this collection's data is scoped to a tenant.
     *
     * @param tenantScoped true if tenant-scoped (default: true)
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder tenantScoped(boolean tenantScoped) {
        this.tenantScoped = tenantScoped;
        return this;
    }

    /**
     * Marks this collection as read-only (no create/update/delete via API).
     *
     * @param readOnly true if read-only
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    /**
     * Sets the field names that cannot be updated after record creation.
     *
     * @param immutableFields set of immutable field names
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder immutableFields(Set<String> immutableFields) {
        this.immutableFields = new HashSet<>(immutableFields);
        return this;
    }

    /**
     * Adds a single immutable field name.
     *
     * @param fieldName the field name that should be immutable
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder addImmutableField(String fieldName) {
        this.immutableFields.add(fieldName);
        return this;
    }

    /**
     * Sets the mapping of API field names to physical database column names.
     *
     * @param columnMapping map of API name to column name
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder columnMapping(Map<String, String> columnMapping) {
        this.columnMapping = new HashMap<>(columnMapping);
        return this;
    }

    /**
     * Adds a single column mapping entry.
     *
     * @param apiFieldName the API field name
     * @param columnName the physical database column name
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder addColumnMapping(String apiFieldName, String columnName) {
        this.columnMapping.put(apiFieldName, columnName);
        return this;
    }

    /**
     * Sets the display field name for display-value lookups.
     *
     * @param displayFieldName the field name used for display-value lookups
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder displayFieldName(String displayFieldName) {
        this.displayFieldName = displayFieldName;
        return this;
    }

    /**
     * Sets the tenant ID that owns this collection.
     *
     * @param tenantId the tenant ID (null for system collections)
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder tenantId(String tenantId) {
        this.tenantId = tenantId;
        return this;
    }

    /**
     * Sets whether record changes are captured as full snapshots in record_version.
     *
     * @param trackHistory true to snapshot every record create/update/delete
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder trackHistory(boolean trackHistory) {
        this.trackHistory = trackHistory;
        return this;
    }

    /**
     * Sets whether HTTP record writes stamp the request-origin geolocation into
     * the created_geo / updated_geo system columns.
     *
     * @param captureGeo true to stamp geo on create/update
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder captureGeo(boolean captureGeo) {
        this.captureGeo = captureGeo;
        return this;
    }

    /**
     * Sets the field holding the owning {@code users.id} (a LOOKUP to users, or {@code createdBy}).
     *
     * @param ownerField the owner field name, or null when the collection is not owned
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder ownerField(String ownerField) {
        this.ownerField = ownerField;
        return this;
    }

    /**
     * Sets which callers are limited to the rows they own.
     *
     * @param ownerScope the owner scope (null is treated as NONE)
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder ownerScope(OwnerScope ownerScope) {
        this.ownerScope = ownerScope;
        return this;
    }

    /**
     * Sets whether owner scoping also narrows reads (default true).
     *
     * @param ownerScopeReads false for owner-only writes with unscoped reads
     * @return this builder for method chaining
     */
    public CollectionDefinitionBuilder ownerScopeReads(boolean ownerScopeReads) {
        this.ownerScopeReads = ownerScopeReads;
        return this;
    }

    /**
     * Builds the collection definition.
     *
     * <p>Applies sensible defaults for optional configuration:
     * <ul>
     *   <li>Storage: Physical tables with table name "{name}"</li>
     *   <li>API: All operations enabled at "/api/{name}"</li>
     *   <li>Authorization: Disabled</li>
     *   <li>Timestamps: Current time if not set</li>
     * </ul>
     *
     * @return the constructed collection definition
     * @throws IllegalStateException if required fields (name, fields) are not set
     */
    public CollectionDefinition build() {
        // Validate required fields
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Collection name is required");
        }
        if (fields.isEmpty()) {
            throw new IllegalStateException("At least one field is required");
        }

        // Apply defaults
        if (storageConfig == null) {
            storageConfig = StorageConfig.physicalTable(name);
        }
        if (apiConfig == null) {
            apiConfig = ApiConfig.allEnabled("/api/" + name);
        }
        if (authzConfig == null) {
            authzConfig = AuthzConfig.disabled();
        }

        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }

        return new CollectionDefinition(
            name,
            displayName != null ? displayName : name,
            description,
            fields,
            storageConfig,
            apiConfig,
            authzConfig,
            version,
            createdAt,
            updatedAt,
            systemCollection,
            tenantScoped,
            readOnly,
            immutableFields,
            columnMapping,
            displayFieldName,
            tenantId,
            trackHistory,
            captureGeo,
            ownerField,
            ownerScope,
            ownerScopeReads
        );
    }
}
