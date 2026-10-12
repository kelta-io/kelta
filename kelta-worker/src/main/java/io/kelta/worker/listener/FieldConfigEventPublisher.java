package io.kelta.worker.listener;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.CollectionChangedPayload;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CollectionLifecycleManager;
import io.kelta.worker.service.FormulaRecomputeService;
import io.kelta.worker.service.SearchIndexService;
import io.kelta.worker.service.VectorMaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Before-save hook for the "fields" system collection that publishes
 * collection config change events after field create/update/delete.
 *
 * <p>When a field is added, modified, or removed from a collection, this hook
 * publishes a collection UPDATED event so that workers can refresh the collection
 * schema (e.g., ALTER TABLE ADD COLUMN) and the gateway can update its routes.
 *
 * @since 1.0.0
 */
@Component
public class FieldConfigEventPublisher implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(FieldConfigEventPublisher.class);

    static final String SUBJECT_PREFIX = "kelta.config.collection.changed.";

    private static final String SELECT_COLLECTION_NAME = """
            SELECT name FROM collection WHERE id = ? LIMIT 1
            """;

    private final PlatformEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;
    private final CollectionLifecycleManager lifecycleManager;
    private final CerbosAuthorizationService cerbosAuthorizationService;
    private final FormulaRecomputeService formulaRecomputeService;
    private final SearchIndexService searchIndexService;
    private final VectorMaintenanceService vectorMaintenanceService;

    public FieldConfigEventPublisher(PlatformEventPublisher eventPublisher,
                                      JdbcTemplate jdbcTemplate,
                                      CollectionLifecycleManager lifecycleManager,
                                      CerbosAuthorizationService cerbosAuthorizationService,
                                      FormulaRecomputeService formulaRecomputeService,
                                      SearchIndexService searchIndexService,
                                      VectorMaintenanceService vectorMaintenanceService) {
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
        this.lifecycleManager = lifecycleManager;
        this.cerbosAuthorizationService = cerbosAuthorizationService;
        this.formulaRecomputeService = formulaRecomputeService;
        this.searchIndexService = searchIndexService;
        this.vectorMaintenanceService = vectorMaintenanceService;
    }

    @Override
    public String getCollectionName() {
        return "fields";
    }

    @Override
    public int getOrder() {
        // Run after the SchemaLifecycleModule's FieldLifecycleHook (order 0)
        return 100;
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        publishCollectionUpdated(record, tenantId);
        if (isFormulaField(record) && expressionOf(record) != null) {
            scheduleFormulaRecompute(record, tenantId);
        }
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                             Map<String, Object> previous, String tenantId) {
        publishCollectionUpdated(record, tenantId);
        if (isFormulaField(record) && expressionChanged(record, previous)) {
            scheduleFormulaRecompute(record, tenantId);
        }
        // A field gaining (or losing) a data-masking config changes what may be
        // full-text indexed: masking-configured fields are excluded from the
        // shared search index. Existing rows were indexed under the old config,
        // so re-index the collection to purge (or restore) the field's plaintext.
        if (maskingPresenceChanged(record, previous)) {
            scheduleReindex(record, tenantId);
            // A masked field must not remain embedded: purge any VECTOR field that embeds
            // this field so a stale plaintext-derived vector can't leak via semantic-search
            // ranking. Rows re-embed correctly on their next write.
            scheduleVectorPurge(record, tenantId);
        }
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        // For deletes we don't have the collection ID readily available,
        // but the field ID alone is not enough. The collection schema listener
        // will handle this via a full refresh when it receives the event.
        log.info("Field '{}' deleted — collection refresh will occur on next schema sync", id);
    }

    private void publishCollectionUpdated(Map<String, Object> record, String tenantId) {
        String collectionId = getString(record, "collectionId");
        if (collectionId == null) {
            log.warn("Field record missing collectionId, cannot publish collection changed event");
            return;
        }

        String collectionName = resolveCollectionName(collectionId);
        if (collectionName == null) {
            log.warn("Could not resolve collection name for id={}, skipping collection-changed broadcast", collectionId);
            return;
        }

        CollectionChangedPayload payload = new CollectionChangedPayload();
        payload.setId(collectionId);
        payload.setName(collectionName);
        payload.setChangeType(ChangeType.UPDATED);

        PlatformEvent<CollectionChangedPayload> event =
                EventFactory.createEvent("kelta.config.collection.changed", payload);
        String subject = SUBJECT_PREFIX + collectionId;
        log.info("Publishing collection UPDATED event (triggered by field change) for collectionId={}, collectionName={}",
                collectionId, collectionName);
        eventPublisher.publish(subject, event);

        // Read-after-write (issue #910): the NATS broadcast above keeps OTHER
        // pods consistent, but the originating pod consumes its own event
        // asynchronously and can serve a stale CollectionDefinition (e.g. a
        // just-added rollup_summary field missing from getById) until the
        // self-consume lands. Refresh this pod synchronously as well.
        lifecycleManager.refreshOrInitializeLocally(collectionId);

        // Evict CerbosAuthorizationService field-access cache for this
        // (tenant, collection): the cached allow-list was built before the
        // new field existed and would otherwise strip it from the response
        // for up to CACHE_TTL (10 min). The local refresh above is useless
        // if Cerbos still considers the field unknown. Other pods perform
        // the equivalent eviction when they consume the NATS event above
        // (see CollectionSchemaListener).
        cerbosAuthorizationService.evictForCollection(tenantId, collectionId);
    }

    private String resolveCollectionName(String collectionId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    SELECT_COLLECTION_NAME, collectionId);
            if (!rows.isEmpty()) {
                return (String) rows.get(0).get("name");
            }
        } catch (Exception e) {
            log.warn("Failed to resolve collection name for id={}: {}", collectionId, e.getMessage());
        }
        return null;
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private static boolean isFormulaField(Map<String, Object> record) {
        Object type = record.get("type");
        return type instanceof String s && "FORMULA".equalsIgnoreCase(s);
    }

    private static String expressionOf(Map<String, Object> record) {
        Object config = record.get("fieldTypeConfig");
        if (config instanceof Map<?, ?> m) {
            Object expression = m.get("expression");
            if (expression instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static boolean expressionChanged(Map<String, Object> record, Map<String, Object> previous) {
        String current = expressionOf(record);
        String prior = previous == null ? null : expressionOf(previous);
        return !Objects.equals(current, prior);
    }

    /** True when the field's {@code fieldTypeConfig.masking} presence flipped between save and prior. */
    private static boolean maskingPresenceChanged(Map<String, Object> record, Map<String, Object> previous) {
        return hasMaskingConfig(record) != (previous != null && hasMaskingConfig(previous));
    }

    private static boolean hasMaskingConfig(Map<String, Object> record) {
        Object config = record.get("fieldTypeConfig");
        return config instanceof Map<?, ?> m && m.get("masking") != null;
    }

    private void scheduleReindex(Map<String, Object> record, String tenantId) {
        if (searchIndexService == null) {
            return;
        }
        String collectionId = getString(record, "collectionId");
        if (collectionId == null) {
            return;
        }
        String collectionName = resolveCollectionName(collectionId);
        if (collectionName == null) {
            return;
        }
        log.info("Masking config toggled on a field of '{}' — rebuilding search index (tenant={})",
                collectionName, tenantId);
        searchIndexService.rebuildCollectionIndexAsync(tenantId, collectionName);
    }

    private void scheduleVectorPurge(Map<String, Object> record, String tenantId) {
        if (vectorMaintenanceService == null) {
            return;
        }
        String collectionId = getString(record, "collectionId");
        String fieldName = getString(record, "name");
        if (collectionId == null || fieldName == null || fieldName.isBlank()) {
            return;
        }
        String collectionName = resolveCollectionName(collectionId);
        if (collectionName == null) {
            return;
        }
        vectorMaintenanceService.purgeVectorsForSourceAsync(tenantId, collectionName, fieldName);
    }

    private void scheduleFormulaRecompute(Map<String, Object> record, String tenantId) {
        if (formulaRecomputeService == null) {
            return;
        }
        String collectionId = getString(record, "collectionId");
        String fieldName = getString(record, "name");
        if (collectionId == null || fieldName == null || fieldName.isBlank()) {
            return;
        }
        String collectionName = resolveCollectionName(collectionId);
        if (collectionName == null) {
            return;
        }
        formulaRecomputeService.recomputeAsync(tenantId, collectionName, fieldName);
    }
}
