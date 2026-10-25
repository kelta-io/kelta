package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.*;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.validation.ValidationRuleDefinition;
import io.kelta.runtime.validation.ValidationRuleRegistry;
import io.kelta.worker.config.WorkerMetricsConfig;
import io.kelta.worker.repository.PicklistValueRepository;
import io.kelta.worker.repository.PicklistValueRepository.PicklistOption;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of collections on this worker.
 *
 * <p>Reads collection and field definitions directly from the database using JDBC,
 * eliminating the need for a control plane service.
 *
 * <p>For system collections (those in {@link SystemCollectionDefinitions}), the canonical
 * in-memory definition is used directly, ensuring complete metadata (StorageConfig, ApiConfig,
 * immutableFields, columnMapping, etc.) is always available.
 *
 * <p>For user-defined collections, definitions are built from database records.
 *
 * <p>Handles:
 * <ul>
 *   <li>Loading collection definitions from the database</li>
 *   <li>Building runtime CollectionDefinition objects</li>
 *   <li>Registering collections in the local registry</li>
 *   <li>Initializing storage (creating database tables for user collections)</li>
 *   <li>Tearing down collections when unassigned</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Service
public class CollectionLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(CollectionLifecycleManager.class);

    private static final String SELECT_COLLECTION_BY_ID = """
            SELECT id, name, display_name, description, active,
                   current_version, system_collection, path, tenant_id, display_field_id,
                   adapter_config, track_history, capture_geo,
                   owner_field, owner_scope, owner_scope_reads
            FROM collection WHERE id = ? AND active = true
            """;

    private static final String SELECT_COLLECTION_BY_NAME = """
            SELECT id, name, display_name, description, active,
                   current_version, system_collection, path, tenant_id, display_field_id,
                   adapter_config, track_history, capture_geo,
                   owner_field, owner_scope, owner_scope_reads
            FROM collection WHERE name = ? AND active = true
            LIMIT 1
            """;

    private static final String SELECT_FIELD_NAME_BY_ID = """
            SELECT name FROM field WHERE id = ? AND active = true
            """;

    private static final String SELECT_FIELDS_BY_COLLECTION = """
            SELECT name, type, required, unique_constraint, indexed, default_value,
                   constraints, field_type_config, reference_target, reference_collection_id,
                   relationship_type, relationship_name, cascade_delete, field_order, column_name,
                   immutable, searchable, track_history, description, system_maintained
            FROM field WHERE collection_id = ? AND active = true
            ORDER BY field_order, created_at, id
            """;

    private static final String SELECT_COLLECTION_NAME_BY_ID = """
            SELECT name FROM collection WHERE id = ? AND active = true
            """;

    private static final String SELECT_SEARCHABLE_FIELDS = """
            SELECT f.name FROM field f
            JOIN collection c ON c.id = f.collection_id
            WHERE c.name = ? AND f.active = true AND f.searchable = true
            """;

    private static final String SELECT_DISPLAY_FIELD_NAME = """
            SELECT f.name FROM field f
            JOIN collection c ON c.display_field_id = f.id
            WHERE c.name = ? AND c.active = true AND f.active = true
            """;

    private static final String SELECT_COLLECTION_ID_BY_NAME = """
            SELECT id FROM collection WHERE name = ? AND active = true LIMIT 1
            """;

    /** The tenant's own collection first, else the system collection — never another tenant's. */
    private static final String SELECT_TENANT_VISIBLE_COLLECTION_ID = """
            SELECT id FROM collection
            WHERE name = ? AND active = true AND (tenant_id = ? OR system_collection = true)
            ORDER BY system_collection ASC
            LIMIT 1
            """;

    private static final String SELECT_VALIDATION_RULES = """
            SELECT name, error_condition_formula, error_message, error_field,
                   evaluate_on, active, severity
            FROM validation_rule WHERE collection_id = ? AND active = true
            """;

    private static final String SELECT_TENANT_SLUG = """
            SELECT slug FROM tenant WHERE id = ?
            """;

    /** Types whose {@code min}/{@code max} constraints bound the value rather than the length. */
    private static final Set<FieldType> NUMERIC_TYPES = EnumSet.of(
            FieldType.INTEGER, FieldType.LONG, FieldType.DOUBLE, FieldType.CURRENCY, FieldType.PERCENT);

    /** Text types whose {@code min}/{@code max} constraints bound the length. */
    private static final Set<FieldType> LENGTH_TYPES = EnumSet.of(
            FieldType.STRING, FieldType.TEXT, FieldType.RICH_TEXT, FieldType.EMAIL, FieldType.URL,
            FieldType.PHONE, FieldType.EXTERNAL_ID);

    /**
     * The {@code field.constraints} keys mapped onto {@link ValidationRules} or enum values. Both
     * dialects are stored: the field editor writes {@code min}/{@code max}/{@code pattern} (and
     * {@code email}/{@code url}, which are not enforced), {@code MigrationFieldRepository} the
     * runtime names plus {@code enumValues}.
     */
    private static final Set<String> SUPPORTED_CONSTRAINT_KEYS = Set.of(
            "min", "max", "minLength", "maxLength", "minValue", "maxValue", "pattern", "enumValues");

    private final CollectionRegistry collectionRegistry;
    private final StorageAdapter storageAdapter;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** Canonical system collection definitions keyed by name. */
    private final Map<String, CollectionDefinition> systemDefinitions;

    /** Optional validation rule registry for storing custom validation rules. */
    private ValidationRuleRegistry validationRuleRegistry;

    /** Source of the active picklist values enforced as user-collection fields' enum values. */
    private PicklistValueRepository picklistValueRepository;

    /**
     * Optional metrics config for updating initializing collection count.
     * Injected lazily to avoid circular dependency (MetricsConfig depends on this bean).
     */
    private WorkerMetricsConfig metricsConfig;

    /**
     * Tracks which collection IDs are actively managed by this worker.
     * Maps collection ID to collection name for quick lookup.
     */
    private final ConcurrentHashMap<String, String> activeCollections = new ConcurrentHashMap<>();

    /**
     * Collection ID to its {@link CollectionDefinition#registryKey()}. Tenants share collection
     * names ({@code orders} in two tenants), so teardown must unregister the exact key: the
     * registry's by-name fallback removes whichever tenant's entry it meets first.
     */
    private final ConcurrentHashMap<String, String> registryKeys = new ConcurrentHashMap<>();

    /**
     * One monitor per collection ID. Initialize and refresh each read the collection from the
     * database and then register the definition; a NATS consumer and a read-after-write refresh
     * (#910) for the same collection run concurrently, and without ordering a definition read
     * before a committed change could register after the one read after it, leaving the pod
     * serving the stale definition (e.g. without a just-set owner scope) until the next event.
     * Holding the monitor across read → register means the later refresh always reads later.
     */
    private final ConcurrentHashMap<String, Object> collectionLocks = new ConcurrentHashMap<>();

    public CollectionLifecycleManager(CollectionRegistry collectionRegistry,
                                       StorageAdapter storageAdapter,
                                       JdbcTemplate jdbcTemplate,
                                       ObjectMapper objectMapper) {
        this.collectionRegistry = collectionRegistry;
        this.storageAdapter = storageAdapter;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.systemDefinitions = SystemCollectionDefinitions.byName();
    }

    @Autowired(required = false)
    public void setValidationRuleRegistry(ValidationRuleRegistry validationRuleRegistry) {
        this.validationRuleRegistry = validationRuleRegistry;
    }

    @Autowired(required = false)
    public void setPicklistValueRepository(PicklistValueRepository picklistValueRepository) {
        this.picklistValueRepository = picklistValueRepository;
    }

    @Autowired(required = false)
    public void setMetricsConfig(WorkerMetricsConfig metricsConfig) {
        this.metricsConfig = metricsConfig;
    }

    /**
     * Initializes a collection on this worker by reading its definition from
     * the database, registering it locally, and initializing storage.
     *
     * @param collectionId the collection ID to initialize
     */
    public void initializeCollection(String collectionId) {
        initializeCollection(collectionId, true);
    }

    /**
     * Initializes a collection on this worker, optionally skipping the storage (DDL) half.
     *
     * <p>{@code applySchema=false} registers the definition and its validation rules in this
     * pod's in-memory state without touching the database schema. Startup bootstrap uses it so
     * that N worker replicas don't each run {@code CREATE TABLE}/{@code reconcileSchema} for
     * every collection against the same database — concurrent DDL that the storage adapter can
     * only paper over after the fact (see its {@code DuplicateKeyException} race handling). The
     * schema is applied once, ahead of the rollout, by the migrate Job.
     *
     * <p>Runtime paths (NATS config events, read-after-write refresh) keep {@code true}: a
     * collection created after deploy has no Job to lean on and needs its table immediately.
     *
     * @param collectionId the collection ID to initialize
     * @param applySchema whether to create/reconcile the physical table
     */
    public void initializeCollection(String collectionId, boolean applySchema) {
        try {
            initializeCollectionOrThrow(collectionId, applySchema);
        } catch (Exception e) {
            log.error("Failed to initialize collection {}: {}", collectionId, e.getMessage(), e);
        }
    }

    /**
     * Same as {@link #initializeCollection(String, boolean)} but propagates failures instead of
     * logging them.
     *
     * <p>Used by the migrate Job's schema bootstrap, where a failed {@code CREATE TABLE} must
     * abort the Job so the ArgoCD PreSync hook fails and the worker rollout never starts. On
     * the request-serving pods the swallowing variant is still right — one broken collection
     * must not stop the pod from serving the other few hundred.
     *
     * @param collectionId the collection ID to initialize
     * @param applySchema whether to create/reconcile the physical table
     * @throws RuntimeException if the definition cannot be loaded or the schema cannot be applied
     */
    public void initializeCollectionOrThrow(String collectionId, boolean applySchema) {
        synchronized (lockFor(collectionId)) {
            initializeCollectionLocked(collectionId, applySchema);
        }
    }

    private void initializeCollectionLocked(String collectionId, boolean applySchema) {
        log.info("Initializing collection: {} (applySchema={})", collectionId, applySchema);

        if (metricsConfig != null) {
            metricsConfig.getInitializingCount().incrementAndGet();
        }

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    SELECT_COLLECTION_BY_ID, collectionId);

            if (rows.isEmpty()) {
                log.error("Collection not found in database: {}", collectionId);
                return;
            }

            Map<String, Object> collectionRow = rows.get(0);
            String collectionName = (String) collectionRow.get("name");

            if (collectionName == null || collectionName.isBlank()) {
                log.error("Collection {} has no name", collectionId);
                return;
            }

            // Build CollectionDefinition
            CollectionDefinition definition = buildDefinition(collectionId, collectionName, collectionRow);

            // Skip VIRTUAL collections — they have no physical storage
            if (definition.isVirtual()) {
                log.info("Skipping VIRTUAL collection '{}' (id={}) — no storage to initialize",
                        collectionName, collectionId);
                collectionRegistry.register(definition);
                registryKeys.put(collectionId, definition.registryKey());
                activeCollections.put(collectionId, collectionName);
                return;
            }

            // Register in local registry (makes it available to DynamicCollectionRouter)
            collectionRegistry.register(definition);

            if (applySchema) {
                // Initialize storage (creates database table if needed) in the row's tenant
                // scope so the storage adapter uses the correct schema. System collections have
                // Flyway-managed tables, so initializeCollection is a no-op for them.
                runInRowTenantScope(collectionRow, () -> storageAdapter.initializeCollection(definition));
            }

            // Load and register validation rules from DB
            loadAndRegisterValidationRules(collectionId, collectionName);

            // Track as active
            registryKeys.put(collectionId, definition.registryKey());
            activeCollections.put(collectionId, collectionName);

            log.info("Successfully initialized collection '{}' (id={})", collectionName, collectionId);

        } finally {
            if (metricsConfig != null) {
                metricsConfig.getInitializingCount().decrementAndGet();
            }
        }
    }

    /**
     * Refreshes a collection definition on this worker by reading the latest
     * schema from the database and migrating the storage schema.
     *
     * <p>This is called when a collection's fields change (e.g., a new field is added).
     * It reads the updated definition, re-registers it, and triggers schema migration
     * (ALTER TABLE ADD COLUMN) for any new fields.
     *
     * @param collectionId the collection ID to refresh
     */
    public void refreshCollection(String collectionId) {
        synchronized (lockFor(collectionId)) {
            refreshCollectionLocked(collectionId);
        }
    }

    private void refreshCollectionLocked(String collectionId) {
        String collectionName = activeCollections.get(collectionId);
        if (collectionName == null) {
            log.warn("Cannot refresh unknown collection: {}", collectionId);
            return;
        }

        log.info("Refreshing collection definition: '{}' (id={})", collectionName, collectionId);

        try {
            CollectionDefinition oldDefinition = collectionRegistry.get(collectionName);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    SELECT_COLLECTION_BY_ID, collectionId);

            if (rows.isEmpty()) {
                log.error("Collection not found in database for refresh: {}", collectionId);
                return;
            }

            Map<String, Object> collectionRow = rows.get(0);
            CollectionDefinition newDefinition = buildDefinition(collectionId, collectionName, collectionRow);

            // Re-register with updated definition
            collectionRegistry.register(newDefinition);

            // Migrate the storage schema in the row's tenant scope so the storage adapter
            // uses the correct schema
            runInRowTenantScope(collectionRow, () -> {
                if (oldDefinition != null) {
                    storageAdapter.updateCollectionSchema(oldDefinition, newDefinition);
                    log.info("Schema migration completed for collection '{}' (id={})",
                            collectionName, collectionId);
                } else {
                    storageAdapter.initializeCollection(newDefinition);
                    log.info("Storage initialized for collection '{}' (id={})",
                            collectionName, collectionId);
                }
            });

            // Refresh validation rules
            loadAndRegisterValidationRules(collectionId, collectionName);

        } catch (Exception e) {
            log.error("Failed to refresh collection '{}' (id={}): {}",
                    collectionName, collectionId, e.getMessage(), e);
        }
    }

    /**
     * Makes the local pod's CollectionDefinition and storage schema consistent
     * for a collection immediately, without waiting for the async NATS
     * config-changed round-trip (issue #910 read-after-write).
     *
     * <p>Mirrors the UPDATED branch of
     * {@code CollectionSchemaListener.processCollectionChange}: refresh if the
     * collection is already active on this worker, otherwise initialize it
     * (covers a freshly-created collection whose CREATED event has not yet
     * self-consumed).
     *
     * <p>This is a local-only operation. Callers MUST still publish the NATS
     * config event so other pods stay consistent — never use this as a
     * substitute for the broadcast.
     *
     * <p>Failures are swallowed (logged at WARN): the originating request must
     * not break, and the async NATS event remains the cross-pod backstop.
     *
     * @param collectionId the collection ID to make locally consistent
     */
    public void refreshOrInitializeLocally(String collectionId) {
        try {
            synchronized (lockFor(collectionId)) {
                if (getActiveCollections().contains(collectionId)) {
                    refreshCollection(collectionId);
                } else {
                    initializeCollection(collectionId);
                }
            }
        } catch (Exception e) {
            log.warn("Local read-after-write refresh failed for collection {} "
                    + "(NATS event remains the backstop): {}", collectionId, e.getMessage(), e);
        }
    }

    private Object lockFor(String collectionId) {
        return collectionLocks.computeIfAbsent(collectionId, id -> new Object());
    }

    /**
     * Tears down a collection on this worker by removing it from the local registry.
     * Data is not dropped -- it persists in the database.
     *
     * @param collectionId the collection ID to tear down
     */
    public void teardownCollection(String collectionId) {
        String collectionName = activeCollections.remove(collectionId);
        String registryKey = registryKeys.remove(collectionId);
        if (collectionName != null) {
            collectionRegistry.unregister(registryKey != null ? registryKey : collectionName);
            if (validationRuleRegistry != null) {
                validationRuleRegistry.unregister(collectionName);
            }
            log.info("Torn down collection '{}' (id={})", collectionName, collectionId);
        } else {
            log.warn("Attempted to tear down unknown collection: {}", collectionId);
        }
    }

    /**
     * Returns the set of collection IDs actively managed by this worker.
     *
     * @return set of active collection IDs
     */
    public Set<String> getActiveCollections() {
        return new HashSet<>(activeCollections.keySet());
    }

    /**
     * Returns the number of active collections.
     *
     * @return active collection count
     */
    public int getActiveCollectionCount() {
        return activeCollections.size();
    }

    /**
     * Attempts to load and initialize a collection by name and tenant ID.
     *
     * <p>Looks up the collection from the database by name, then initializes it
     * locally (register + storage + validation rules). If the collection is already
     * loaded, returns the existing definition.
     *
     * <p>This method is thread-safe. Concurrent calls for the same collection
     * will only initialize it once due to the {@code activeCollections} check.
     *
     * @param collectionName the collection name
     * @param tenantId       the tenant ID, may be {@code null}
     * @return the loaded collection definition, or {@code null} if not found
     */
    public CollectionDefinition loadCollectionByName(String collectionName, String tenantId) {
        // Check if already loaded (TenantContext drives tenant-scoped lookup)
        CollectionDefinition existing = collectionRegistry.get(collectionName);
        if (existing != null) {
            return existing;
        }

        // Bind the tenant so RLS filters the query correctly. A tenant the caller already bound
        // (the request filter on the on-demand path) wins, as it did with the ThreadLocal setter.
        return TenantContext.callWithTenantIfUnbound(tenantId, null,
                () -> loadCollectionByNameInScope(collectionName, tenantId));
    }

    private CollectionDefinition loadCollectionByNameInScope(String collectionName, String tenantId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    SELECT_COLLECTION_BY_NAME, collectionName);

            if (rows.isEmpty()) {
                log.debug("Collection '{}' not found in database (tenantId={})",
                        collectionName, tenantId);
                return null;
            }

            Map<String, Object> collectionRow = rows.get(0);
            String collectionId = (String) collectionRow.get("id");

            // Check again in case another thread initialized it
            if (activeCollections.containsKey(collectionId)) {
                return collectionRegistry.get(collectionName);
            }

            // Initialize the collection fully
            initializeCollection(collectionId);
            return collectionRegistry.get(collectionName);

        } catch (Exception e) {
            log.warn("Failed to load collection '{}' on demand (tenantId={}): {}",
                    collectionName, tenantId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Tenant Context Helpers
    // =========================================================================

    /**
     * Runs {@code operation} with the collection row's tenant (ID and slug) in TenantContext.
     * This is needed during bootstrap and refresh operations where no HTTP request is in
     * progress, so the storage adapter can resolve the correct tenant schema.
     *
     * <p>A tenant or slug the caller already bound (the request filter, on the on-demand and
     * read-after-write paths) wins, as it did with the ThreadLocal setters this replaces; the
     * row's values only fill what is unbound.
     */
    private void runInRowTenantScope(Map<String, Object> collectionRow, Runnable operation) {
        String tenantId = (String) collectionRow.get("tenant_id");
        if (tenantId == null || tenantId.isBlank()) {
            operation.run();
            return;
        }
        String slug = TenantContext.callWithTenantIfUnbound(tenantId, null, () -> {
            try {
                return jdbcTemplate.queryForObject(SELECT_TENANT_SLUG, String.class, tenantId);
            } catch (Exception e) {
                log.debug("Could not resolve tenant slug for tenant '{}': {}", tenantId, e.getMessage());
                return null;
            }
        });
        TenantContext.runWithTenantIfUnbound(tenantId, slug, operation);
    }

    // =========================================================================
    // Definition Building
    // =========================================================================

    /**
     * Builds a CollectionDefinition for the given collection.
     *
     * <p>For system collections, uses the canonical definition from
     * {@link SystemCollectionDefinitions} which includes complete metadata
     * (StorageConfig with table name, ApiConfig, immutableFields,
     * columnMapping, etc.).
     *
     * <p>For user-defined collections, builds the definition from database records.
     */
    private CollectionDefinition buildDefinition(String collectionId, String collectionName,
                                                   Map<String, Object> collectionRow) {
        // Use canonical definition for system collections
        CollectionDefinition systemDef = systemDefinitions.get(collectionName);
        if (systemDef != null) {
            return systemDef;
        }

        // Build from DB data for user-defined collections
        return buildDefinitionFromDb(collectionId, collectionName, collectionRow);
    }

    /**
     * Builds a CollectionDefinition from database records for user-defined collections.
     * Package-private for unit testing.
     */
    CollectionDefinition buildDefinitionFromDb(String collectionId, String collectionName,
                                                        Map<String, Object> data) {
        CollectionDefinitionBuilder builder = new CollectionDefinitionBuilder()
                .name(collectionName)
                .displayName(getStringOrNull(data, "display_name", collectionName))
                .description(getStringOrNull(data, "description", null))
                .apiConfig(ApiConfig.allEnabled("/api/" + collectionName));

        // Parse fields from the database
        List<FieldDefinition> fields = loadFieldsFromDb(
                collectionId, collectionName, (String) data.get("tenant_id"));
        if (fields.isEmpty()) {
            fields.add(FieldDefinition.string("name"));
        }

        // Inject system audit fields — physical columns on every tenant table
        // but not stored in the field table. Needed so DynamicCollectionRouter
        // serializes createdBy/updatedBy as relationships for include resolution.
        // Use snake_case names matching the physical column names because the
        // PhysicalTableStorageAdapter only remaps column→field names for system
        // collections; tenant collection records retain their raw column names.
        fields.add(FieldDefinition.datetime("created_at"));
        fields.add(FieldDefinition.lookup("created_by", "users", "Created By"));
        fields.add(FieldDefinition.datetime("updated_at"));
        fields.add(FieldDefinition.lookup("updated_by", "users", "Updated By"));

        builder.fields(fields);

        // Set tenant ID
        String tenantId = (String) data.get("tenant_id");
        if (tenantId != null && !tenantId.isBlank()) {
            builder.tenantId(tenantId);
        }

        // Resolve display field ID to field name
        String displayFieldId = getStringOrNull(data, "display_field_id", null);
        if (displayFieldId != null) {
            String displayFieldName = resolveFieldName(displayFieldId);
            if (displayFieldName != null) {
                builder.displayFieldName(displayFieldName);
            }
        }

        // Storage config — physical table by default; an external adapter is
        // selected when adapter_config carries an adapterType (Rec 4).
        Map<String, String> adapterConfig = parseAdapterConfig(data.get("adapter_config"), collectionName);
        builder.storageConfig(adapterConfig.isEmpty()
                ? StorageConfig.physicalTable(collectionName)
                : new StorageConfig(collectionName, adapterConfig));

        // Parse system collection flag
        Boolean systemCollection = (Boolean) data.get("system_collection");
        if (Boolean.TRUE.equals(systemCollection)) {
            builder.systemCollection(true);
        }

        // Collection-level record versioning toggle
        builder.trackHistory(Boolean.TRUE.equals(data.get("track_history")));

        // Collection-level request-origin geo stamping toggle
        builder.captureGeo(Boolean.TRUE.equals(data.get("capture_geo")));

        // Member data ownership: owner field + which callers are limited to their own rows
        builder.ownerField(getStringOrNull(data, "owner_field", null));
        builder.ownerScope(OwnerScope.parse(data.get("owner_scope")));
        builder.ownerScopeReads(!Boolean.FALSE.equals(data.get("owner_scope_reads")));

        return builder.build();
    }

    /**
     * Resolves a relationship field's target collection NAME. Prefers the denormalized
     * {@code reference_target} column; when it is null/blank, derives the name from
     * {@code reference_collection_id} so fields persisted without the denormalized name still build a
     * {@link ReferenceConfig}. Package-private for unit testing.
     *
     * @param referenceTarget     the denormalized target collection name (may be null/blank)
     * @param referenceCollectionId the FK to the target collection (may be null)
     * @return the resolved target collection name, or {@code null} if neither source yields one
     */
    String resolveReferenceTarget(String referenceTarget, Object referenceCollectionId) {
        if (referenceTarget != null && !referenceTarget.isBlank()) {
            return referenceTarget;
        }
        if (referenceCollectionId == null) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    SELECT_COLLECTION_NAME_BY_ID, String.class, referenceCollectionId.toString());
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * Loads field definitions from the database for a given collection.
     */
    private List<FieldDefinition> loadFieldsFromDb(String collectionId, String collectionName, String tenantId) {
        List<FieldDefinition> fields = new ArrayList<>();

        List<Map<String, Object>> fieldRows = jdbcTemplate.queryForList(
                SELECT_FIELDS_BY_COLLECTION, collectionId);
        Map<String, List<PicklistOption>> picklistValues = loadActivePicklistValues(
                fieldRows, collectionName, tenantId);

        for (Map<String, Object> row : fieldRows) {
            String fieldName = (String) row.get("name");
            String typeStr = (String) row.get("type");

            if (fieldName == null || typeStr == null) {
                continue;
            }

            FieldType fieldType = reverseMapFieldType(typeStr);
            boolean required = Boolean.TRUE.equals(row.get("required"));
            boolean unique = Boolean.TRUE.equals(row.get("unique_constraint"));
            boolean immutable = Boolean.TRUE.equals(row.get("immutable"));
            boolean trackHistory = Boolean.TRUE.equals(row.get("track_history"));
            boolean systemMaintained = Boolean.TRUE.equals(row.get("system_maintained"));
            String columnName = (String) row.get("column_name");

            // Parse reference config. `reference_target` (the denormalized target collection NAME)
            // is the primary source, but many relationship fields were persisted with only
            // `reference_collection_id` set and a NULL `reference_target` — in that case the field
            // would otherwise lose its ReferenceConfig entirely, so the JSON:API serializer emits a
            // raw FK id attribute (no relationship, no `include` resolution). Derive the target name
            // from `reference_collection_id` so those fields still resolve. (#lookup-display)
            ReferenceConfig refConfig = null;
            String relationshipType = (String) row.get("relationship_type");
            if (relationshipType == null && fieldType != null && fieldType.isRelationship()) {
                relationshipType = fieldType.name();
            }
            String referenceTarget = resolveReferenceTarget(
                    (String) row.get("reference_target"), row.get("reference_collection_id"));
            if (referenceTarget != null && !referenceTarget.isBlank()) {
                String relationshipName = (String) row.get("relationship_name");
                if ("MASTER_DETAIL".equals(relationshipType)) {
                    refConfig = ReferenceConfig.masterDetail(referenceTarget, relationshipName);
                } else if ("LOOKUP".equals(relationshipType)) {
                    refConfig = ReferenceConfig.lookup(referenceTarget, relationshipName);
                } else {
                    refConfig = ReferenceConfig.toCollection(referenceTarget);
                }
            }

            // Parse fieldTypeConfig JSON. PostgreSQL returns JSONB columns as
            // org.postgresql.util.PGobject through the standard JDBC API; .toString()
            // yields the raw JSON. Earlier branches covered the String / Map cases
            // but missed PGobject, so newly-loaded rollup_summary fields surfaced
            // with a null fieldTypeConfig and silently skipped compute.
            Map<String, Object> parsedFieldTypeConfig = null;
            Object fieldTypeConfigObj = row.get("field_type_config");
            String ftcJson = null;
            if (fieldTypeConfigObj instanceof String ftcStr && !ftcStr.isBlank()) {
                ftcJson = ftcStr;
            } else if (fieldTypeConfigObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> configMap = (Map<String, Object>) fieldTypeConfigObj;
                parsedFieldTypeConfig = configMap;
            } else if (fieldTypeConfigObj != null
                    && "org.postgresql.util.PGobject".equals(fieldTypeConfigObj.getClass().getName())) {
                String raw = fieldTypeConfigObj.toString();
                if (raw != null && !raw.isBlank()) {
                    ftcJson = raw;
                }
            }
            if (ftcJson != null) {
                try {
                    parsedFieldTypeConfig = objectMapper.readValue(ftcJson,
                            objectMapper.getTypeFactory().constructMapType(
                                    HashMap.class, String.class, Object.class));
                } catch (Exception ex) {
                    log.warn("Failed to parse fieldTypeConfig for field '{}': {}",
                            fieldName, ex.getMessage());
                }
            }

            Object parsedDefaultValue = parseJsonbValue(row.get("default_value"), fieldName, "default_value");
            Map<String, Object> constraints = parseConstraints(row.get("constraints"), fieldName);
            ValidationRules validationRules = toValidationRules(constraints, fieldName, fieldType);
            List<String> enumValues = toEnumValues(picklistValues.get(fieldName), constraints);

            FieldDefinition fieldDef = new FieldDefinition(
                    fieldName, fieldType, !required, immutable, unique,
                    parsedDefaultValue, validationRules, enumValues, refConfig, parsedFieldTypeConfig, columnName, trackHistory,
                    (String) row.get("description"), false, systemMaintained);
            fields.add(fieldDef);
        }

        return fields;
    }

    /**
     * Active picklist values of the collection's fields, keyed by field name — one query for the
     * whole collection, skipped when no field is a picklist. A lookup failure degrades to no enum
     * enforcement rather than failing the load.
     */
    private Map<String, List<PicklistOption>> loadActivePicklistValues(
            List<Map<String, Object>> fieldRows, String collectionName, String tenantId) {
        if (picklistValueRepository == null || tenantId == null || tenantId.isBlank()
                || fieldRows.stream().noneMatch(row -> isPicklistType((String) row.get("type")))) {
            return Map.of();
        }
        try {
            return picklistValueRepository.findActiveValuesByCollection(tenantId, collectionName);
        } catch (Exception ex) {
            log.warn("Failed to load picklist values for collection '{}': {}", collectionName, ex.getMessage());
            return Map.of();
        }
    }

    private static boolean isPicklistType(String typeStr) {
        return typeStr != null
                && ("picklist".equalsIgnoreCase(typeStr) || "multi_picklist".equalsIgnoreCase(typeStr));
    }

    /**
     * Decodes the {@code constraints} JSONB column into a map. The field editor sends the object
     * as a JSON string, which the {@code fields} JSON column stores as a JSONB string — so a
     * decoded string is parsed once more.
     */
    private Map<String, Object> parseConstraints(Object raw, String fieldName) {
        Object parsed = raw;
        for (int decode = 0; decode < 2 && !(parsed instanceof Map); decode++) {
            String text = parsed instanceof String s ? s
                    : parsed != null && "org.postgresql.util.PGobject".equals(parsed.getClass().getName())
                            ? parsed.toString() : null;
            if (text == null || text.isBlank()) {
                return Map.of();
            }
            try {
                parsed = objectMapper.readValue(text, Object.class);
            } catch (Exception ex) {
                log.warn("Ignoring unparseable constraints for field '{}': {}", fieldName, ex.getMessage());
                return Map.of();
            }
        }
        if (parsed instanceof Map<?, ?> map) {
            Map<String, Object> constraints = new LinkedHashMap<>();
            map.forEach((key, value) -> constraints.put(String.valueOf(key), value));
            return constraints;
        }
        return Map.of();
    }

    /**
     * Maps stored constraints onto {@link ValidationRules}: {@code minLength}/{@code maxLength}/
     * {@code minValue}/{@code maxValue}/{@code pattern} as named, and the field editor's
     * {@code min}/{@code max} to the value bounds of a numeric field or the length bounds of a text
     * field (other types, e.g. {@code DATE}, ignore them). An explicit runtime name wins over
     * {@code min}/{@code max}. Unsupported keys (e.g.
     * {@code email}, {@code url}) and malformed values are ignored with one WARN per field.
     * Package-private for unit testing.
     */
    ValidationRules toValidationRules(Map<String, Object> constraints, String fieldName, FieldType fieldType) {
        if (constraints.isEmpty()) {
            return null;
        }
        List<String> ignored = new ArrayList<>();
        for (String key : constraints.keySet()) {
            if (!SUPPORTED_CONSTRAINT_KEYS.contains(key)) {
                ignored.add(key);
            }
        }
        Double minValue = toDouble(constraints, "minValue", ignored);
        Double maxValue = toDouble(constraints, "maxValue", ignored);
        Integer minLength = toInteger(constraints, "minLength", ignored);
        Integer maxLength = toInteger(constraints, "maxLength", ignored);
        if (NUMERIC_TYPES.contains(fieldType)) {
            minValue = minValue != null ? minValue : toDouble(constraints, "min", ignored);
            maxValue = maxValue != null ? maxValue : toDouble(constraints, "max", ignored);
        } else if (LENGTH_TYPES.contains(fieldType)) {
            minLength = minLength != null ? minLength : toInteger(constraints, "min", ignored);
            maxLength = maxLength != null ? maxLength : toInteger(constraints, "max", ignored);
        } else {
            // e.g. a DATE range: the engine has no date bounds, so min/max are not enforced.
            for (String key : List.of("min", "max")) {
                if (constraints.containsKey(key)) {
                    ignored.add(key);
                }
            }
        }
        String pattern = null;
        Object rawPattern = constraints.get("pattern");
        if (rawPattern instanceof String p && !p.isBlank()) {
            pattern = p;
        } else if (rawPattern != null) {
            ignored.add("pattern");
        }
        if (!ignored.isEmpty()) {
            log.warn("Field '{}': constraints {} are not enforced and were ignored", fieldName, ignored);
        }
        if (minValue == null && maxValue == null && minLength == null && maxLength == null && pattern == null) {
            return null;
        }
        return new ValidationRules(minValue, maxValue, minLength, maxLength, pattern);
    }

    private static Double toDouble(Map<String, Object> constraints, String key, List<String> ignored) {
        Object value = constraints.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.valueOf(text.trim());
            } catch (NumberFormatException ex) {
                // fall through to ignored
            }
        }
        ignored.add(key);
        return null;
    }

    private static Integer toInteger(Map<String, Object> constraints, String key, List<String> ignored) {
        Double value = toDouble(constraints, key, ignored);
        if (value == null) {
            return null;
        }
        if (value < 0 || value != Math.floor(value) || value > Integer.MAX_VALUE) {
            ignored.add(key);
            return null;
        }
        return value.intValue();
    }

    /**
     * The field's active picklist values (field-level or its global picklist); a field with none
     * falls back to a stored {@code constraints.enumValues} list. {@code null} when neither exists.
     */
    private static List<String> toEnumValues(List<PicklistOption> options, Map<String, Object> constraints) {
        if (options != null && !options.isEmpty()) {
            return options.stream().map(PicklistOption::value).toList();
        }
        if (constraints.get("enumValues") instanceof List<?> stored && !stored.isEmpty()) {
            return stored.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        return null;
    }

    /**
     * Decodes a JSONB column value into a native Java object. PostgreSQL JDBC
     * may return JSONB as {@code String}, {@code Map}, primitive (Boolean/Number),
     * or {@code org.postgresql.util.PGobject}. Returns null for blank/null inputs.
     */
    private Object parseJsonbValue(Object raw, String fieldName, String columnLabel) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean || raw instanceof Number || raw instanceof Map || raw instanceof List) {
            return raw;
        }
        String text;
        if (raw instanceof String s) {
            text = s;
        } else if ("org.postgresql.util.PGobject".equals(raw.getClass().getName())) {
            text = raw.toString();
        } else {
            return raw;
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (Exception ex) {
            log.warn("Failed to parse JSONB column '{}' for field '{}': {}",
                    columnLabel, fieldName, ex.getMessage());
            return text;
        }
    }

    /**
     * Loads validation rules from the database and registers them.
     */
    private void loadAndRegisterValidationRules(String collectionId, String collectionName) {
        if (validationRuleRegistry == null) {
            return;
        }

        try {
            List<Map<String, Object>> ruleRows = jdbcTemplate.queryForList(
                    SELECT_VALIDATION_RULES, collectionId);

            List<ValidationRuleDefinition> rules = new ArrayList<>();
            for (Map<String, Object> row : ruleRows) {
                String name = (String) row.get("name");
                String formula = (String) row.get("error_condition_formula");
                String errorMessage = (String) row.get("error_message");
                String errorField = (String) row.get("error_field");
                String evaluateOn = (String) row.get("evaluate_on");
                boolean active = Boolean.TRUE.equals(row.get("active"));
                String severity = (String) row.get("severity");

                if (name != null && formula != null && errorMessage != null) {
                    rules.add(new ValidationRuleDefinition(
                            name, formula, errorMessage, errorField,
                            evaluateOn != null ? evaluateOn : "CREATE_AND_UPDATE",
                            active,
                            severity != null ? severity : ValidationRuleDefinition.SEVERITY_ERROR));
                }
            }

            validationRuleRegistry.register(collectionName, rules);
            long activeCount = rules.stream().filter(ValidationRuleDefinition::active).count();
            log.info("Registered {} validation rules ({} active) for collection '{}'",
                    rules.size(), activeCount, collectionName);

        } catch (Exception e) {
            log.warn("Failed to load validation rules for collection '{}': {}",
                    collectionName, e.getMessage());
            validationRuleRegistry.register(collectionName, List.of());
        }
    }

    // =========================================================================
    // Field Type Mapping
    // =========================================================================

    /**
     * Reverse-maps the database field type string to a FieldType enum.
     *
     * <p>The database stores simplified type names (e.g., "string", "number")
     * from the forward mapping in SystemCollectionSeeder.mapFieldType().
     * This method converts them back to FieldType enum values.
     *
     * <p>For "number", defaults to DOUBLE (consistent with control-plane convention
     * where FieldService maps "number" to DOUBLE).
     */
    static FieldType reverseMapFieldType(String dbType) {
        if (dbType == null) {
            return FieldType.STRING;
        }
        return switch (dbType.toLowerCase()) {
            case "string" -> FieldType.STRING;
            case "number" -> FieldType.DOUBLE;
            case "boolean" -> FieldType.BOOLEAN;
            case "date" -> FieldType.DATE;
            case "datetime" -> FieldType.DATETIME;
            case "object" -> FieldType.JSON;
            case "array" -> FieldType.ARRAY;
            case "reference" -> FieldType.REFERENCE;
            case "text" -> FieldType.TEXT;
            case "rich_text" -> FieldType.RICH_TEXT;
            case "vector" -> FieldType.VECTOR;
            default -> {
                // Try direct enum match for any non-standard values
                try {
                    yield FieldType.valueOf(dbType.toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown DB field type '{}', defaulting to STRING", dbType);
                    yield FieldType.STRING;
                }
            }
        };
    }

    /**
     * Resolves a field ID to its field name.
     */
    private String resolveFieldName(String fieldId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    SELECT_FIELD_NAME_BY_ID, fieldId);
            if (!rows.isEmpty()) {
                return (String) rows.get(0).get("name");
            }
        } catch (Exception e) {
            log.warn("Failed to resolve field name for id '{}': {}", fieldId, e.getMessage());
        }
        return null;
    }

    private String getStringOrNull(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        if (value instanceof String str && !str.isBlank()) {
            return str;
        }
        return defaultValue;
    }

    /**
     * Parse the {@code adapter_config} JSONB column into a flat string map for
     * {@link StorageConfig}. A {@code PGobject} or JSON string is parsed; values are
     * stringified. Returns an empty map for null/blank/malformed input (so a parse
     * failure degrades to the safe physical-table default rather than failing the load).
     *
     * <p>Package-private for direct unit testing.
     */
    Map<String, String> parseAdapterConfig(Object raw, String collectionName) {
        if (raw == null) {
            return Map.of();
        }
        String json = raw.toString();
        if (json.isBlank() || "{}".equals(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            Map<String, String> result = new LinkedHashMap<>();
            parsed.forEach((key, value) -> {
                if (value != null) {
                    result.put(key, value.toString());
                }
            });
            return result;
        } catch (RuntimeException e) {
            log.warn("Failed to parse adapter_config for collection '{}': {}", collectionName, e.getMessage());
            return Map.of();
        }
    }

    // =========================================================================
    // Search Index Support
    // =========================================================================

    /**
     * Returns the set of field names marked as searchable for a given collection.
     *
     * @param collectionName the collection name
     * @return set of searchable field names (never null)
     */
    public Set<String> getSearchableFieldNames(String collectionName) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    SELECT_SEARCHABLE_FIELDS, collectionName);
            Set<String> names = new HashSet<>();
            for (Map<String, Object> row : rows) {
                String name = (String) row.get("name");
                if (name != null) {
                    names.add(name);
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("Failed to load searchable fields for collection '{}': {}",
                    collectionName, e.getMessage());
            return Set.of();
        }
    }

    /**
     * Returns the display field name for a given collection,
     * or {@code null} if no display field is configured.
     *
     * @param collectionName the collection name
     * @return the display field name, or null
     */
    public String getDisplayFieldName(String collectionName) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    SELECT_DISPLAY_FIELD_NAME, collectionName);
            if (!rows.isEmpty()) {
                return (String) rows.get(0).get("name");
            }
        } catch (Exception e) {
            log.warn("Failed to resolve display field for collection '{}': {}",
                    collectionName, e.getMessage());
        }
        return null;
    }

    /**
     * Returns the collection ID for a given collection name,
     * or {@code null} if not found.
     *
     * @param collectionName the collection name
     * @return the collection ID, or null
     */
    public String getCollectionIdByName(String collectionName) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    SELECT_COLLECTION_ID_BY_NAME, collectionName);
            if (!rows.isEmpty()) {
                return (String) rows.get(0).get("id");
            }
        } catch (Exception e) {
            log.warn("Failed to resolve collection ID for '{}': {}", collectionName, e.getMessage());
        }
        return null;
    }

    /**
     * Returns the id of the collection named {@code collectionName} that {@code tenantId} can
     * address — its own collection, else the system collection of that name, never another
     * tenant's — or {@code null} if there is none. This is the id the gateway's route for that
     * collection carries, and so the one the Cerbos {@code collection} policy is keyed on.
     *
     * @param collectionName the collection name
     * @param tenantId       the caller's tenant id
     * @return the collection ID, or null
     */
    public String getTenantVisibleCollectionId(String collectionName, String tenantId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    SELECT_TENANT_VISIBLE_COLLECTION_ID, collectionName, tenantId);
            if (!rows.isEmpty()) {
                return (String) rows.get(0).get("id");
            }
        } catch (Exception e) {
            log.warn("Failed to resolve collection ID for '{}' (tenant {}): {}",
                    collectionName, tenantId, e.getMessage());
        }
        return null;
    }

    /**
     * Returns the collection name (API name) for a given collection ID,
     * or {@code null} if not found.
     *
     * @param collectionId the collection ID
     * @return the collection name, or null
     */
    public String getCollectionNameById(String collectionId) {
        try {
            return jdbcTemplate.queryForObject(
                    SELECT_COLLECTION_NAME_BY_ID, String.class, collectionId);
        } catch (Exception e) {
            log.warn("Failed to resolve collection name for id '{}': {}", collectionId, e.getMessage());
            return null;
        }
    }
}
