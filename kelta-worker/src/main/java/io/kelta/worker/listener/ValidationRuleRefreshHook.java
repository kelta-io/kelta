package io.kelta.worker.listener;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.CollectionChangedPayload;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * After-save hook for the "validation-rules" system collection that broadcasts
 * a collection-changed event whenever a validation rule is created,
 * updated, or deleted.
 *
 * <p>This event is consumed by {@link CollectionSchemaListener} on <em>every</em>
 * worker pod, which calls {@code CollectionLifecycleManager.refreshCollection()},
 * reloading validation rules from the database into the in-memory
 * {@link io.kelta.runtime.validation.ValidationRuleRegistry}.
 *
 * <p>Without this hook, changes to validation rules (create, activate,
 * deactivate, edit formula, delete) would only take effect after a full
 * pod restart. A delete passes no record to {@code afterDelete}, so the rule's
 * collection is looked up in {@code beforeDelete}, while the row still exists.
 *
 * @since 1.0.0
 */
@Component
public class ValidationRuleRefreshHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(ValidationRuleRefreshHook.class);

    private static final String SELECT_COLLECTION_NAME = """
            SELECT name, tenant_id FROM collection WHERE id = ? LIMIT 1
            """;

    static final String SELECT_RULE_COLLECTION_ID = """
            SELECT collection_id FROM validation_rule WHERE id = ?
            """;

    /** Bounds {@link #pendingDeletes} should deletes whose {@code afterDelete} never runs pile up. */
    private static final int MAX_PENDING_DELETES = 1000;

    private final PlatformEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;

    /** Rule ID to its collection ID, captured before the rule row is deleted. */
    private final Map<String, String> pendingDeletes = new ConcurrentHashMap<>();

    public ValidationRuleRefreshHook(PlatformEventPublisher eventPublisher,
                                      JdbcTemplate jdbcTemplate) {
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getCollectionName() {
        return "validation-rules";
    }

    @Override
    public int getOrder() {
        // Run after the audit hook
        return 200;
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        publishCollectionChanged(record, tenantId);
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                             Map<String, Object> previous, String tenantId) {
        publishCollectionChanged(record, tenantId);
    }

    @Override
    public BeforeSaveResult beforeDelete(String id, String tenantId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_RULE_COLLECTION_ID, id);
            if (!rows.isEmpty() && rows.get(0).get("collection_id") != null) {
                if (pendingDeletes.size() >= MAX_PENDING_DELETES) {
                    pendingDeletes.clear();
                }
                pendingDeletes.put(id, String.valueOf(rows.get(0).get("collection_id")));
            }
        } catch (Exception e) {
            log.warn("Failed to resolve collection of deleted validation rule {}: {}", id, e.getMessage());
        }
        return BeforeSaveResult.ok();
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        String collectionId = pendingDeletes.remove(id);
        if (collectionId == null) {
            log.warn("No collection captured for deleted validation rule {}, cannot broadcast refresh", id);
            return;
        }
        publishCollectionChanged(collectionId, tenantId);
    }

    private void publishCollectionChanged(Map<String, Object> record, String tenantId) {
        String collectionId = (String) record.get("collectionId");
        if (collectionId == null) {
            log.warn("Validation rule record missing collectionId, cannot broadcast refresh");
            return;
        }
        publishCollectionChanged(collectionId, tenantId);
    }

    private void publishCollectionChanged(String collectionId, String tenantId) {

        // Look up the collection name for the event payload
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
        event.setTenantId(tenantId);
        String subject = CollectionConfigEventPublisher.SUBJECT_PREFIX + collectionId;
        log.info("Publishing collection-changed event for validation rule change " +
                "(collectionId={}, collectionName={})", collectionId, collectionName);
        eventPublisher.publish(subject, event);
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
}
