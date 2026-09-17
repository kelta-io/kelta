package io.kelta.runtime.query;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.events.RecordEventPublisher;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.service.AutoNumberService;
import io.kelta.runtime.service.FieldEncryptionService;
import io.kelta.runtime.service.RollupSummaryService;
import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.registry.CollectionOnDemandLoader;
import io.kelta.runtime.storage.PhysicalTableStorageAdapter;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.storage.TableRef;
import io.kelta.runtime.validation.CustomValidationRuleEngine;
import io.kelta.runtime.validation.OperationType;
import io.kelta.runtime.validation.TypeCoercionService;
import io.kelta.runtime.validation.ValidationEngine;
import io.kelta.runtime.validation.FieldError;
import io.kelta.runtime.validation.ValidationException;
import io.kelta.runtime.validation.ValidationResult;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Default implementation of the QueryEngine interface.
 *
 * <p>This implementation:
 * <ul>
 *   <li>Validates query parameters against collection definitions</li>
 *   <li>Integrates with StorageAdapter for persistence</li>
 *   <li>Integrates with ValidationEngine for data validation</li>
 *   <li>Integrates with FormulaEvaluator for FORMULA field computation</li>
 *   <li>Integrates with RollupSummaryService for ROLLUP_SUMMARY computation</li>
 *   <li>Integrates with FieldEncryptionService for ENCRYPTED field handling</li>
 *   <li>Integrates with AutoNumberService for AUTO_NUMBER generation</li>
 *   <li>Integrates with RecordEventPublisher for publishing record change events</li>
 *   <li>Adds system fields (id, createdAt, updatedAt, createdBy, updatedBy) automatically</li>
 *   <li>Logs query performance metrics</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe. All operations delegate to
 * thread-safe components (StorageAdapter, ValidationEngine).
 *
 * @since 1.0.0
 */
public class DefaultQueryEngine implements QueryEngine {

    private static final Logger logger = LoggerFactory.getLogger(DefaultQueryEngine.class);

    private final StorageAdapter storageAdapter;
    private final ValidationEngine validationEngine;
    private final FieldEncryptionService encryptionService;
    private final AutoNumberService autoNumberService;
    private final FormulaEvaluator formulaEvaluator;
    private final RollupSummaryService rollupSummaryService;
    private final CustomValidationRuleEngine customValidationRuleEngine;
    private final RecordEventPublisher recordEventPublisher;
    private final BeforeSaveHookRegistry beforeSaveHookRegistry;
    private final CollectionRegistry collectionRegistry;
    /** Optional fallback to resolve a collection not yet in the in-memory registry. */
    private CollectionOnDemandLoader onDemandLoader;

    /**
     * Creates a new DefaultQueryEngine with all services including workflow engine.
     *
     * @param storageAdapter the storage adapter for persistence
     * @param validationEngine the validation engine for data validation (may be null)
     * @param encryptionService the encryption service for ENCRYPTED fields (may be null)
     * @param autoNumberService the auto-number service for AUTO_NUMBER fields (may be null)
     * @param formulaEvaluator the formula evaluator for FORMULA fields (may be null)
     * @param rollupSummaryService the rollup summary service for ROLLUP_SUMMARY fields (may be null)
     * @param customValidationRuleEngine the custom validation rule engine (may be null)
     * @param recordEventPublisher the publisher for record change events (may be null)
     * @param beforeSaveHookRegistry the before-save hook registry for lifecycle hooks (may be null)
     */
    public DefaultQueryEngine(StorageAdapter storageAdapter, ValidationEngine validationEngine,
                              FieldEncryptionService encryptionService, AutoNumberService autoNumberService,
                              FormulaEvaluator formulaEvaluator, RollupSummaryService rollupSummaryService,
                              CustomValidationRuleEngine customValidationRuleEngine,
                              RecordEventPublisher recordEventPublisher,
                              BeforeSaveHookRegistry beforeSaveHookRegistry) {
        this(storageAdapter, validationEngine, encryptionService, autoNumberService,
             formulaEvaluator, rollupSummaryService, customValidationRuleEngine,
             recordEventPublisher, beforeSaveHookRegistry, null);
    }

    /**
     * Creates a new DefaultQueryEngine with all services including the collection
     * registry needed for ROLLUP_SUMMARY child-table resolution.
     */
    public DefaultQueryEngine(StorageAdapter storageAdapter, ValidationEngine validationEngine,
                              FieldEncryptionService encryptionService, AutoNumberService autoNumberService,
                              FormulaEvaluator formulaEvaluator, RollupSummaryService rollupSummaryService,
                              CustomValidationRuleEngine customValidationRuleEngine,
                              RecordEventPublisher recordEventPublisher,
                              BeforeSaveHookRegistry beforeSaveHookRegistry,
                              CollectionRegistry collectionRegistry) {
        this.storageAdapter = Objects.requireNonNull(storageAdapter, "storageAdapter cannot be null");
        this.validationEngine = validationEngine;
        this.encryptionService = encryptionService;
        this.autoNumberService = autoNumberService;
        this.formulaEvaluator = formulaEvaluator;
        this.rollupSummaryService = rollupSummaryService;
        this.customValidationRuleEngine = customValidationRuleEngine;
        this.recordEventPublisher = recordEventPublisher;
        this.beforeSaveHookRegistry = beforeSaveHookRegistry;
        this.collectionRegistry = collectionRegistry;
    }

    /**
     * Optionally wires an on-demand collection loader used to resolve a
     * ROLLUP_SUMMARY child collection that has not yet propagated into this
     * pod's in-memory registry. Mirrors {@code DynamicCollectionRouter}'s
     * fallback so rollup reads are correct immediately after the child
     * collection is created rather than only after the NATS config event.
     */
    public void setOnDemandLoader(CollectionOnDemandLoader onDemandLoader) {
        this.onDemandLoader = onDemandLoader;
    }

    /**
     * Creates a new DefaultQueryEngine with all services including record event publisher.
     */
    public DefaultQueryEngine(StorageAdapter storageAdapter, ValidationEngine validationEngine,
                              FieldEncryptionService encryptionService, AutoNumberService autoNumberService,
                              FormulaEvaluator formulaEvaluator, RollupSummaryService rollupSummaryService,
                              CustomValidationRuleEngine customValidationRuleEngine,
                              RecordEventPublisher recordEventPublisher) {
        this(storageAdapter, validationEngine, encryptionService, autoNumberService,
             formulaEvaluator, rollupSummaryService, customValidationRuleEngine,
             recordEventPublisher, null);
    }

    /**
     * Creates a new DefaultQueryEngine with all services (backward compatible, no event publisher).
     */
    public DefaultQueryEngine(StorageAdapter storageAdapter, ValidationEngine validationEngine,
                              FieldEncryptionService encryptionService, AutoNumberService autoNumberService,
                              FormulaEvaluator formulaEvaluator, RollupSummaryService rollupSummaryService,
                              CustomValidationRuleEngine customValidationRuleEngine) {
        this(storageAdapter, validationEngine, encryptionService, autoNumberService,
             formulaEvaluator, rollupSummaryService, customValidationRuleEngine, null, null);
    }

    /**
     * Creates a new DefaultQueryEngine with all services (backward compatible).
     */
    public DefaultQueryEngine(StorageAdapter storageAdapter, ValidationEngine validationEngine,
                              FieldEncryptionService encryptionService, AutoNumberService autoNumberService,
                              FormulaEvaluator formulaEvaluator, RollupSummaryService rollupSummaryService) {
        this(storageAdapter, validationEngine, encryptionService, autoNumberService,
             formulaEvaluator, rollupSummaryService, null, null, null);
    }

    /**
     * Creates a new DefaultQueryEngine.
     *
     * @param storageAdapter the storage adapter for persistence
     * @param validationEngine the validation engine for data validation (may be null)
     */
    public DefaultQueryEngine(StorageAdapter storageAdapter, ValidationEngine validationEngine) {
        this(storageAdapter, validationEngine, null, null, null, null, null, null, null);
    }

    /**
     * Creates a new DefaultQueryEngine without validation.
     *
     * @param storageAdapter the storage adapter for persistence
     */
    public DefaultQueryEngine(StorageAdapter storageAdapter) {
        this(storageAdapter, null);
    }
    
    @Override
    public QueryResult executeQuery(CollectionDefinition definition, QueryRequest request) {
        Objects.requireNonNull(definition, "definition cannot be null");
        Objects.requireNonNull(request, "request cannot be null");
        
        long startTime = System.currentTimeMillis();
        
        // Validate sort fields exist
        validateSortFields(definition, request.sorting());
        
        // Validate filter fields exist
        validateFilterFields(definition, request.filters());
        
        // Validate requested fields exist
        validateRequestedFields(definition, request.fields());
        
        // Execute query via storage adapter
        QueryResult result = storageAdapter.query(definition, request);

        // Compute formula and rollup fields on results
        computeVirtualFields(definition, result.data());

        // Decrypt encrypted fields on results
        decryptFields(definition, result.data());

        // Log query performance
        long duration = System.currentTimeMillis() - startTime;
        logger.debug("Query executed on collection '{}': {} records returned in {}ms",
            definition.name(), result.size(), duration);

        return result;
    }
    
    @Override
    public Map<String, Object> aggregate(CollectionDefinition definition,
                                          List<FilterCondition> filters,
                                          List<AggregationSpec> specs) {
        Objects.requireNonNull(definition, "definition cannot be null");
        if (specs == null || specs.isEmpty()) {
            return Map.of();
        }

        if (filters != null) {
            validateFilterFields(definition, filters);
        }
        for (AggregationSpec spec : specs) {
            if (spec.field() != null && !isValidField(definition, spec.field())) {
                throw new InvalidQueryException(spec.field(),
                    "Aggregation field does not exist in collection '" + definition.name() + "'");
            }
        }

        return storageAdapter.aggregate(definition, filters == null ? List.of() : filters, specs);
    }

    @Override
    public Optional<Map<String, Object>> getById(CollectionDefinition definition, String id) {
        Objects.requireNonNull(definition, "definition cannot be null");
        Objects.requireNonNull(id, "id cannot be null");
        
        Optional<Map<String, Object>> result = storageAdapter.getById(definition, id);
        result.ifPresent(record -> {
            computeVirtualFields(definition, List.of(record));
            decryptFields(definition, List.of(record));
        });
        return result;
    }
    
    @Override
    public Map<String, Object> create(CollectionDefinition definition, Map<String, Object> data) {
        Objects.requireNonNull(definition, "definition cannot be null");
        Objects.requireNonNull(data, "data cannot be null");

        // Reject writes to read-only collections
        if (definition.readOnly()) {
            throw new ReadOnlyCollectionException(definition.name());
        }

        // Create mutable copy
        Map<String, Object> recordData = new HashMap<>(data);
        
        // Add system fields
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        recordData.put("id", id);
        recordData.put("createdAt", now);
        recordData.put("updatedAt", now);

        // Generate auto-number values for AUTO_NUMBER fields
        generateAutoNumbers(definition, recordData);

        // Encrypt ENCRYPTED field values before validation and storage
        encryptFields(definition, recordData);

        // Coerce string values to expected types (e.g., "10" → 10.0 for DOUBLE fields)
        TypeCoercionService.coerce(definition, recordData);

        // Apply field defaults for missing fields before validation
        applyFieldDefaults(definition, recordData);

        // Validate data (field-level constraints)
        if (validationEngine != null) {
            ValidationResult validation = validationEngine.validate(definition, recordData, OperationType.CREATE);
            if (!validation.valid()) {
                throw new ValidationException(validation);
            }
        }

        // Evaluate custom validation rules (formula-based)
        if (customValidationRuleEngine != null) {
            customValidationRuleEngine.evaluate(definition.name(), recordData, OperationType.CREATE);
        }

        // Evaluate before-create hooks (module-provided lifecycle hooks)
        evaluateBeforeCreateHooks(definition, recordData);

        // Evaluate before-save workflow rules (BEFORE_CREATE trigger)
        evaluateBeforeSaveWorkflows(definition, recordData, null, id, List.of(), "CREATE");

        // Persist via storage adapter
        Map<String, Object> created = storageAdapter.create(definition, recordData);

        logger.debug("Created record '{}' in collection '{}'", id, definition.name());

        // Invoke after-create hooks
        invokeAfterCreateHooks(definition, created);

        // Publish record change event
        publishRecordEvent(EventFactory.createRecordEvent("record.created",
                extractTenantId(recordData), extractUserId(recordData),
                RecordChangedPayload.created(definition.name(), id, created)
                        .withContainsMaskedFields(definition.hasMaskingConfiguredFields())));

        return created;
    }

    @Override
    public Optional<Map<String, Object>> update(CollectionDefinition definition, String id, Map<String, Object> data) {
        Objects.requireNonNull(definition, "definition cannot be null");
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(data, "data cannot be null");

        // Reject writes to read-only collections
        if (definition.readOnly()) {
            throw new ReadOnlyCollectionException(definition.name());
        }

        // Check if record exists
        Optional<Map<String, Object>> existing = storageAdapter.getById(definition, id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        // Create mutable copy
        Map<String, Object> recordData = new HashMap<>(data);

        // Update timestamp
        recordData.put("updatedAt", Instant.now());

        // Don't allow changing id, createdAt, or createdBy
        recordData.remove("id");
        recordData.remove("createdAt");
        recordData.remove("createdBy");

        // Reject writes that would change a collection-level immutable field.
        // These used to be stripped silently, which returned 200 for a write that
        // never happened (#1330). Only a real change is an error: a caller
        // re-sending the value the record already holds — any full-record
        // round-trip — still succeeds. Either way the field leaves the patch, so
        // the stored value is never rewritten.
        if (!definition.immutableFields().isEmpty()) {
            List<FieldError> immutableErrors = new ArrayList<>();
            Map<String, Object> existingRecord = existing.get();
            // Sorted so a multi-field rejection reads the same on every call —
            // immutableFields() is an unordered Set.
            for (String immutableField : new TreeSet<>(definition.immutableFields())) {
                if (!recordData.containsKey(immutableField)) {
                    continue;
                }
                Object submitted = recordData.remove(immutableField);
                if (isBlank(submitted)) {
                    // An empty submission is not a change request. Forms round-trip a
                    // whole record and coerce untouched inputs to null/"", and an
                    // immutable field cannot be cleared anyway — failing here would
                    // reject edits that never touched the field.
                    continue;
                }
                if (!sameStoredValue(existingRecord.get(immutableField), submitted)) {
                    immutableErrors.add(FieldError.immutable(immutableField));
                }
            }
            if (!immutableErrors.isEmpty()) {
                throw new ValidationException(ValidationResult.failure(immutableErrors));
            }
        }

        // Encrypt ENCRYPTED fields before storage
        encryptFields(definition, recordData);

        // Coerce string values to expected types (e.g., "10" → 10.0 for DOUBLE fields)
        TypeCoercionService.coerce(definition, recordData);

        // Merged view of the record (existing + patch) for cross-field custom rules.
        Map<String, Object> mergedData = new HashMap<>(existing.get());
        mergedData.putAll(recordData);

        // Field-level validation runs against the PATCH, not the merged record.
        // The engine already skips fields absent from an UPDATE payload; feeding
        // it the merged record instead makes every field "present" and
        // re-validates untouched columns against their stored read-back form,
        // which differs from the accepted input form (a DATE column reads back
        // as an ISO datetime at midnight and fails the DATE type check). That
        // rejected any PATCH on a record with a populated DATE field. Validating
        // only the patch is both correct and matches the partial-update intent.
        if (validationEngine != null) {
            ValidationResult validation = validationEngine.validate(definition, recordData, OperationType.UPDATE, id);
            if (!validation.valid()) {
                throw new ValidationException(validation);
            }
        }

        // Custom (formula) rules may reference sibling fields, so they still see
        // the merged record.
        if (customValidationRuleEngine != null) {
            customValidationRuleEngine.evaluate(definition.name(), mergedData, OperationType.UPDATE);
        }

        // Capture previous data for event publishing (before persist overwrites)
        Map<String, Object> previousData = new HashMap<>(existing.get());

        // Evaluate before-update hooks (module-provided lifecycle hooks)
        evaluateBeforeUpdateHooks(definition, id, recordData, previousData);

        // Compute changed fields for before-save workflow trigger field matching
        List<String> changedFieldsForWorkflow = computeChangedFields(previousData, recordData);

        // Evaluate before-save workflow rules (BEFORE_UPDATE trigger)
        evaluateBeforeSaveWorkflows(definition, recordData, previousData, id,
            changedFieldsForWorkflow, "UPDATE");

        // Persist via storage adapter
        Optional<Map<String, Object>> updated = storageAdapter.update(definition, id, recordData);

        updated.ifPresent(record -> {
            logger.debug("Updated record '{}' in collection '{}'", id, definition.name());

            // Compute changed fields by comparing previous data with the update data
            List<String> changedFields = computeChangedFields(previousData, recordData);

            // Invoke after-update hooks
            invokeAfterUpdateHooks(definition, id, record, previousData);

            // Publish record change event
            publishRecordEvent(EventFactory.createRecordEvent("record.updated",
                    extractTenantId(mergedData), extractUserId(mergedData),
                    RecordChangedPayload.updated(definition.name(), id, mergedData,
                            previousData, changedFields)
                            .withContainsMaskedFields(definition.hasMaskingConfiguredFields())));
        });

        return updated;
    }
    
    @Override
    public boolean delete(CollectionDefinition definition, String id) {
        Objects.requireNonNull(definition, "definition cannot be null");
        Objects.requireNonNull(id, "id cannot be null");

        // Reject deletes on read-only collections
        if (definition.readOnly()) {
            throw new ReadOnlyCollectionException(definition.name());
        }

        // Pre-fetch record data for the delete event and hook tenant scoping. The tenant-aware
        // hasHooks overload is required here: at this point no record data is fetched yet, so a
        // tenant-scoped (module-installed) hook can only be seen via TenantContext, not
        // extractTenantId(record) — using the global-only overload would silently skip it.
        Map<String, Object> recordData = null;
        boolean hasHooks = beforeSaveHookRegistry != null
                && beforeSaveHookRegistry.hasHooks(TenantContext.get(), definition.name());
        if (recordEventPublisher != null || hasHooks) {
            recordData = storageAdapter.getById(definition, id).orElse(null);
        }

        // Evaluate before-delete hooks — any error vetoes the delete
        if (hasHooks) {
            BeforeSaveResult hookResult = beforeSaveHookRegistry.evaluateBeforeDelete(
                    definition.name(), id, extractTenantId(recordData != null ? recordData : Map.of()));
            if (!hookResult.isSuccess()) {
                throw new ValidationException(ValidationResult.failure(hookResult.getErrors().stream()
                        .map(e -> new FieldError(
                                e.field() != null ? e.field() : "_record",
                                e.message(), hookErrorCode(e)))
                        .toList()));
            }
        }

        boolean deleted = storageAdapter.delete(definition, id);

        if (deleted) {
            logger.debug("Deleted record '{}' from collection '{}'", id, definition.name());

            // Invoke after-delete hooks
            invokeAfterDeleteHooks(definition, id, recordData);

            // Publish record change event with the pre-fetched data
            if (recordData != null) {
                publishRecordEvent(EventFactory.createRecordEvent("record.deleted",
                        extractTenantId(recordData), extractUserId(recordData),
                        RecordChangedPayload.deleted(definition.name(), id, recordData)
                                .withContainsMaskedFields(definition.hasMaskingConfiguredFields())));
            }
        }

        return deleted;
    }
    
    /**
     * Validates that all sort fields exist in the collection definition.
     */
    private void validateSortFields(CollectionDefinition definition, List<SortField> sorting) {
        for (SortField sortField : sorting) {
            if (!isValidField(definition, sortField.fieldName())) {
                throw new InvalidQueryException(sortField.fieldName(), 
                    "Sort field does not exist in collection '" + definition.name() + "'");
            }
        }
    }
    
    /**
     * Validates that all filter fields exist in the collection definition.
     */
    private void validateFilterFields(CollectionDefinition definition, List<FilterCondition> filters) {
        for (FilterCondition filter : filters) {
            if (!isValidField(definition, filter.fieldName())) {
                throw new InvalidQueryException(filter.fieldName(),
                    "Filter field does not exist in collection '" + definition.name() + "'");
            }
        }
    }
    
    /**
     * Validates that all requested fields exist in the collection definition.
     */
    private void validateRequestedFields(CollectionDefinition definition, List<String> fields) {
        for (String field : fields) {
            if (!isValidField(definition, field)) {
                throw new InvalidQueryException(field,
                    "Requested field does not exist in collection '" + definition.name() + "'");
            }
        }
    }
    
    /**
     * Checks if a field name is valid for the collection.
     * System fields (id, createdAt, updatedAt, createdBy, updatedBy) are always valid.
     * tenantId is valid for tenant-scoped collections (injected by DynamicCollectionRouter).
     */
    private boolean isValidField(CollectionDefinition definition, String fieldName) {
        // Delegates so there is ONE notion of "queryable field". This used to be a private copy of
        // the same rule, and a caller that reached for CollectionDefinition#hasField instead got a
        // subtly different answer for audit fields — see issue #1384.
        return definition.hasQueryableField(fieldName);
    }

    /**
     * Applies declared field defaults for fields not already present in the data.
     *
     * <p>This must run before validation so that required fields with defaults
     * (e.g., {@code currentVersion} on the collections system collection) are
     * populated before the validation engine checks required constraints.
     */
    private void applyFieldDefaults(CollectionDefinition definition, Map<String, Object> data) {
        for (FieldDefinition field : definition.fields()) {
            if (field.defaultValue() != null && !data.containsKey(field.name())) {
                data.put(field.name(), field.defaultValue());
            }
        }
    }

    /**
     * Generates auto-number values for AUTO_NUMBER fields during record creation.
     */
    private void generateAutoNumbers(CollectionDefinition definition, Map<String, Object> data) {
        if (autoNumberService == null) {
            return;
        }
        for (FieldDefinition field : definition.fields()) {
            if (field.type() == FieldType.AUTO_NUMBER && !data.containsKey(field.name())) {
                String seqName = autoNumberSequenceName(definition.name(), field.name());
                Map<String, Object> config = field.fieldTypeConfig();
                String prefix = "";
                int padding = 6;
                long startValue = 1;
                if (config != null) {
                    if (config.containsKey("prefix")) {
                        prefix = config.get("prefix").toString();
                    }
                    if (config.containsKey("padding")) {
                        padding = ((Number) config.get("padding")).intValue();
                    }
                    if (config.containsKey("startValue")) {
                        startValue = ((Number) config.get("startValue")).longValue();
                    }
                }
                try {
                    autoNumberService.ensureSequenceExists(seqName, startValue);
                    String value = autoNumberService.generateNext(seqName, prefix, padding);
                    data.put(field.name(), value);
                } catch (Exception e) {
                    logger.warn("Failed to generate auto-number for field '{}': {}", field.name(), e.getMessage());
                }
            }
        }
    }

    /**
     * Builds the Postgres sequence name for an AUTO_NUMBER field. Collection
     * names are kebab-case ({@code credit-notes}), which is not a valid
     * Postgres identifier — {@link AutoNumberService} rejects it and the
     * record would silently save with a null number. Map every non-identifier
     * character to underscore so the derived name is always valid.
     */
    static String autoNumberSequenceName(String collectionName, String fieldName) {
        return ("seq_" + collectionName + "_" + fieldName).replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * Encrypts ENCRYPTED field values before storage.
     */
    private void encryptFields(CollectionDefinition definition, Map<String, Object> data) {
        if (encryptionService == null) {
            return;
        }
        String tenantId = data.containsKey("tenantId") ? data.get("tenantId").toString() : "default";
        for (FieldDefinition field : definition.fields()) {
            if (field.type() == FieldType.ENCRYPTED && data.containsKey(field.name())) {
                Object value = data.get(field.name());
                if (value instanceof String plaintext) {
                    byte[] encrypted = encryptionService.encrypt(plaintext, tenantId);
                    data.put(field.name(), encrypted);
                }
            }
        }
    }

    /**
     * Decrypts ENCRYPTED field values after retrieval.
     */
    private void decryptFields(CollectionDefinition definition, List<Map<String, Object>> records) {
        if (encryptionService == null) {
            return;
        }
        for (Map<String, Object> record : records) {
            String tenantId = record.containsKey("tenantId") ? record.get("tenantId").toString() : "default";
            for (FieldDefinition field : definition.fields()) {
                if (field.type() == FieldType.ENCRYPTED && record.containsKey(field.name())) {
                    Object value = record.get(field.name());
                    if (value instanceof byte[] encryptedData) {
                        try {
                            String decrypted = encryptionService.decrypt(encryptedData, tenantId);
                            record.put(field.name(), decrypted);
                        } catch (Exception e) {
                            logger.warn("Failed to decrypt field '{}': {}", field.name(), e.getMessage());
                        }
                    }
                }
            }
        }
    }

    /**
     * Publishes a record change event if a publisher is configured.
     * Failures are handled gracefully — they are logged but do not cause the
     * main CRUD operation to fail.
     */
    private void publishRecordEvent(PlatformEvent<RecordChangedPayload> event) {
        if (recordEventPublisher == null) {
            return;
        }
        try {
            recordEventPublisher.publish(event);
        } catch (Exception e) {
            RecordChangedPayload payload = event.getPayload();
            logger.error("Failed to publish record change event for record '{}' in collection '{}': {}",
                payload.getRecordId(), payload.getCollectionName(), e.getMessage());
        }
    }

    /**
     * Computes the list of field names that changed between the previous record
     * and the update data. Only includes fields present in the update data
     * whose values differ from the previous record.
     */
    private List<String> computeChangedFields(Map<String, Object> previousData, Map<String, Object> updateData) {
        List<String> changedFields = new ArrayList<>();
        for (Map.Entry<String, Object> entry : updateData.entrySet()) {
            String fieldName = entry.getKey();
            // Skip system fields that are always updated
            if ("updatedAt".equals(fieldName)) {
                continue;
            }
            Object newValue = entry.getValue();
            Object oldValue = previousData.get(fieldName);
            if (!Objects.equals(newValue, oldValue)) {
                changedFields.add(fieldName);
            }
        }
        return changedFields;
    }

    /**
     * Compares a submitted value against the value already stored, tolerantly
     * enough that re-sending an unchanged field is not read as a change.
     *
     * <p>A read-back value does not always come back in the type it was written
     * as — an id column may surface as a {@code UUID}, a timestamp as an
     * {@code Instant} — so equal string forms count as equal. Only used to
     * decide whether an immutable field is actually being changed.
     */
    private boolean isBlank(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    private boolean sameStoredValue(Object stored, Object submitted) {
        if (Objects.equals(stored, submitted)) {
            return true;
        }
        if (stored == null || submitted == null) {
            return false;
        }
        return String.valueOf(stored).equals(String.valueOf(submitted));
    }

    /**
     * Extracts the tenant ID from record data, falling back to TenantContext,
     * then defaulting to "default".
     */
    private String extractTenantId(Map<String, Object> data) {
        Object tenantId = data.get("tenantId");
        if (tenantId != null) {
            return tenantId.toString();
        }
        String contextTenantId = TenantContext.get();
        if (contextTenantId != null && !contextTenantId.isBlank()) {
            return contextTenantId;
        }
        return "default";
    }

    /**
     * Extracts the user ID from record data, defaulting to "system".
     */
    private String extractUserId(Map<String, Object> data) {
        Object userId = data.get("createdBy");
        if (userId == null) {
            userId = data.get("updatedBy");
        }
        return userId != null ? userId.toString() : "system";
    }

    /**
     * Resolves the {@link FieldError#constraint()} for a hook validation error: the hook's
     * own code when it supplied one, else blank so {@code GlobalExceptionHandler} falls back
     * to its generic {@code VALIDATION_FAILED} default — never the hook-kind literal.
     */
    private static String hookErrorCode(BeforeSaveResult.ValidationError error) {
        return error.code() != null && !error.code().isBlank() ? error.code() : "";
    }

    /**
     * Evaluates before-create hooks for the collection. If any hook returns errors,
     * a ValidationException is thrown. If hooks return field updates, they are merged
     * into the record data.
     */
    private void evaluateBeforeCreateHooks(CollectionDefinition definition, Map<String, Object> recordData) {
        if (beforeSaveHookRegistry == null) {
            return;
        }
        BeforeSaveResult result = beforeSaveHookRegistry.evaluateBeforeCreate(
                definition.name(), recordData, extractTenantId(recordData));
        if (!result.isSuccess()) {
            throw new ValidationException(ValidationResult.failure(result.getErrors().stream()
                    .map(e -> new FieldError(
                            e.field() != null ? e.field() : "_record",
                            e.message(), hookErrorCode(e)))
                    .toList()));
        }
        if (result.hasFieldUpdates()) {
            recordData.putAll(result.getFieldUpdates());
        }
    }

    /**
     * Evaluates before-update hooks for the collection. If any hook returns errors,
     * a ValidationException is thrown. If hooks return field updates, they are merged
     * into the record data.
     */
    private void evaluateBeforeUpdateHooks(CollectionDefinition definition, String id,
                                            Map<String, Object> recordData,
                                            Map<String, Object> previousData) {
        if (beforeSaveHookRegistry == null) {
            return;
        }
        BeforeSaveResult result = beforeSaveHookRegistry.evaluateBeforeUpdate(
                definition.name(), id, recordData, previousData, extractTenantId(recordData));
        if (!result.isSuccess()) {
            throw new ValidationException(ValidationResult.failure(result.getErrors().stream()
                    .map(e -> new FieldError(
                            e.field() != null ? e.field() : "_record",
                            e.message(), hookErrorCode(e)))
                    .toList()));
        }
        if (result.hasFieldUpdates()) {
            recordData.putAll(result.getFieldUpdates());
        }
    }

    /**
     * Evaluates before-save workflow rules.
     * <p>
     * Legacy workflow rules have been migrated to the flow engine.
     * Before-save logic is now handled by synchronous record-triggered flows.
     * This method is a no-op placeholder for backward compatibility.
     */
    private void evaluateBeforeSaveWorkflows(CollectionDefinition definition,
                                               Map<String, Object> recordData,
                                               Map<String, Object> previousData,
                                               String recordId,
                                               List<String> changedFields,
                                               String changeType) {
        // No-op: before-save workflow rules have been migrated to synchronous flows.
        // Synchronous flow execution will be handled by FlowEngine in future.
    }

    /**
     * Invokes after-create hooks. Failures are logged but do not block the operation.
     */
    private void invokeAfterCreateHooks(CollectionDefinition definition, Map<String, Object> record) {
        if (beforeSaveHookRegistry == null) {
            return;
        }
        beforeSaveHookRegistry.invokeAfterCreate(definition.name(), record, extractTenantId(record));
    }

    /**
     * Invokes after-update hooks. Failures are logged but do not block the operation.
     */
    private void invokeAfterUpdateHooks(CollectionDefinition definition, String id,
                                         Map<String, Object> record, Map<String, Object> previous) {
        if (beforeSaveHookRegistry == null) {
            return;
        }
        beforeSaveHookRegistry.invokeAfterUpdate(definition.name(), id, record, previous,
                extractTenantId(record));
    }

    /**
     * Invokes after-delete hooks. Failures are logged but do not block the operation.
     */
    private void invokeAfterDeleteHooks(CollectionDefinition definition, String id,
                                         Map<String, Object> recordData) {
        if (beforeSaveHookRegistry == null) {
            return;
        }
        beforeSaveHookRegistry.invokeAfterDelete(definition.name(), id,
                extractTenantId(recordData != null ? recordData : Map.of()));
    }

    /**
     * Computes FORMULA and ROLLUP_SUMMARY field values after retrieval.
     */
    private void computeVirtualFields(CollectionDefinition definition, List<Map<String, Object>> records) {
        for (Map<String, Object> record : records) {
            for (FieldDefinition field : definition.fields()) {
                if (field.type() == FieldType.FORMULA && formulaEvaluator != null) {
                    record.put(field.name(), computeFormulaValue(definition, field, record));
                } else if (field.type() == FieldType.ROLLUP_SUMMARY) {
                    if (rollupSummaryService == null) {
                        logger.info("Rollup field '{}' on '{}' skipped: RollupSummaryService bean is null",
                                field.name(), definition.name());
                        continue;
                    }
                    logger.info("Rollup field '{}' on '{}' computing (config={})",
                            field.name(), definition.name(), field.fieldTypeConfig());
                    Object computed = computeRollupValue(field, record);
                    logger.info("Rollup field '{}' computed value: {}", field.name(), computed);
                    if (computed != null) {
                        record.put(field.name(), computed);
                    }
                }
            }
        }
    }

    /**
     * Evaluates a FORMULA field's expression against the record. On any
     * exception we return {@code "#ERROR"} so the UI surfaces the failure
     * rather than the raw stored value (which is never persisted for
     * computed fields). A missing or blank expression yields {@code null}.
     */
    private Object computeFormulaValue(CollectionDefinition definition, FieldDefinition field,
                                       Map<String, Object> record) {
        Map<String, Object> cfg = field.fieldTypeConfig();
        String expression = cfg == null ? null : (String) cfg.get("expression");
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            return formulaEvaluator.evaluate(expression, record);
        } catch (RuntimeException e) {
            logger.warn("Formula evaluation failed for field '{}' on collection '{}': {}",
                    field.name(), definition.name(), e.getMessage());
            return "#ERROR";
        }
    }

    /**
     * Resolves a single ROLLUP_SUMMARY field value for one parent record by
     * delegating to {@link RollupSummaryService}. Returns null when the field
     * config is incomplete or the child collection cannot be resolved.
     */
    private Object computeRollupValue(FieldDefinition field, Map<String, Object> record) {
        Map<String, Object> cfg = field.fieldTypeConfig();
        if (cfg == null || collectionRegistry == null) {
            return null;
        }
        String childCollection = (String) cfg.get("childCollection");
        String foreignKeyField = (String) cfg.get("foreignKeyField");
        String aggregateFunction = (String) cfg.get("aggregateFunction");
        String aggregateField = (String) cfg.get("aggregateField");
        @SuppressWarnings("unchecked")
        Map<String, Object> filter = (Map<String, Object>) cfg.get("filter");
        if (childCollection == null || foreignKeyField == null || aggregateFunction == null) {
            logger.debug("Rollup field '{}' has incomplete config; skipping", field.name());
            return null;
        }

        CollectionDefinition childDef = collectionRegistry.get(childCollection);
        if (childDef == null && onDemandLoader != null) {
            // The child collection may not have propagated into this pod's
            // in-memory registry yet (NATS config event lag) even though its
            // physical table is migrated. Mirror DynamicCollectionRouter's
            // on-demand fallback so the rollup resolves on the first read
            // instead of silently returning null until the registry refreshes.
            String tenantId = TenantContext.get();
            try {
                childDef = onDemandLoader.load(childCollection, tenantId);
                if (childDef != null) {
                    logger.info("Rollup field '{}': loaded child collection '{}' on demand (tenantId={})",
                            field.name(), childCollection, tenantId);
                }
            } catch (Exception e) {
                logger.warn("Rollup field '{}': on-demand load of child collection '{}' failed: {}",
                        field.name(), childCollection, e.getMessage());
            }
        }
        if (childDef == null) {
            logger.warn("Rollup field '{}' references child collection '{}' not present in registry "
                    + "and not loadable on demand; returning null", field.name(), childCollection);
            return null;
        }

        Object parentId = record.get("id");
        if (parentId == null) {
            return null;
        }

        TableRef childTable = resolveTableRef(childDef);
        String fkColumn = resolveColumnName(childDef, foreignKeyField);
        String aggColumn = aggregateField == null ? null : resolveColumnName(childDef, aggregateField);

        logger.info("Rollup '{}' executing: {} on {} {} where {}={}", field.name(),
                aggregateFunction, childTable.toSql(), aggColumn != null ? "(" + aggColumn + ")" : "(*)",
                fkColumn, parentId);
        try {
            return rollupSummaryService.compute(childTable, fkColumn, parentId.toString(),
                    aggregateFunction, aggColumn, filter);
        } catch (RuntimeException e) {
            logger.warn("Rollup compute failed for field '{}' on collection '{}': {}",
                    field.name(), childDef.name(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Resolves the schema-qualified table reference for a child collection,
     * mirroring {@link io.kelta.runtime.storage.PhysicalTableStorageAdapter}'s
     * tenant-schema rules. Tenant collections route to the current tenant's
     * schema; system collections stay in the public schema.
     */
    private TableRef resolveTableRef(CollectionDefinition def) {
        String tableName = def.storageConfig() != null && def.storageConfig().tableName() != null
                ? def.storageConfig().tableName()
                : def.name();
        if (def.systemCollection()) {
            return TableRef.publicSchema(tableName);
        }
        String tenantSlug = io.kelta.runtime.context.TenantContext.getSlug();
        if (tenantSlug != null && !tenantSlug.isBlank()) {
            return TableRef.tenantSchema(tenantSlug, tableName);
        }
        return TableRef.publicSchema(tableName);
    }

    private String resolveColumnName(CollectionDefinition def, String fieldName) {
        if (def.systemCollection()) {
            return fieldName;
        }
        return PhysicalTableStorageAdapter.toSnakeCase(fieldName);
    }
}
