package io.kelta.worker.listener;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.CollectionChangedPayload;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.service.CollectionLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * After-save hook for the "picklist-values" system collection that broadcasts a
 * collection-changed event for every collection whose picklist fields draw on the changed value.
 *
 * <p>User-collection field definitions carry their active picklist values as enum values, which
 * {@code DefaultValidationEngine} enforces on every write. Without this broadcast a value added,
 * deactivated or deleted would only reach the cached definitions on the next restart or field
 * edit: a new value would be rejected and a removed one still accepted.
 *
 * <p>A {@code FIELD} value affects the collection of its field; a {@code GLOBAL} value affects
 * every collection with an active field bound to that global picklist
 * ({@code fieldTypeConfig.globalPicklistId}). The event is consumed by
 * {@link CollectionSchemaListener} on every worker pod; this pod also refreshes locally so a
 * record written right after the value change sees it (read-after-write, #910).
 */
@Component
public class PicklistValueRefreshHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(PicklistValueRefreshHook.class);

    static final String SELECT_FIELD_COLLECTION = """
            SELECT c.id, c.name FROM field f JOIN collection c ON c.id = f.collection_id
            WHERE f.id = ?
            """;

    static final String SELECT_GLOBAL_PICKLIST_COLLECTIONS = """
            SELECT DISTINCT c.id, c.name FROM field f JOIN collection c ON c.id = f.collection_id
            WHERE f.tenant_id = ? AND f.active = true AND f.field_type_config->>'globalPicklistId' = ?
            """;

    static final String SELECT_VALUE_SOURCE = """
            SELECT picklist_source_type, picklist_source_id FROM picklist_value WHERE id = ?
            """;

    private final PlatformEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;
    private final CollectionLifecycleManager lifecycleManager;

    /** Collections affected by a value being deleted, resolved before the row is gone. */
    private final Map<String, Map<String, String>> pendingDeletes = new ConcurrentHashMap<>();

    public PicklistValueRefreshHook(PlatformEventPublisher eventPublisher, JdbcTemplate jdbcTemplate,
                                    CollectionLifecycleManager lifecycleManager) {
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
        this.lifecycleManager = lifecycleManager;
    }

    @Override
    public String getCollectionName() {
        return "picklist-values";
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        publish(affectedCollections(record, tenantId), tenantId);
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                            Map<String, Object> previous, String tenantId) {
        Map<String, String> collections = affectedCollections(record, tenantId);
        if (previous != null) {
            collections.putAll(affectedCollections(previous, tenantId));
        }
        publish(collections, tenantId);
    }

    @Override
    public BeforeSaveResult beforeDelete(String id, String tenantId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_VALUE_SOURCE, id);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                pendingDeletes.put(id, resolve(
                        (String) row.get("picklist_source_type"), (String) row.get("picklist_source_id"), tenantId));
            }
        } catch (Exception e) {
            log.warn("Failed to resolve collections for deleted picklist value {}: {}", id, e.getMessage());
        }
        return BeforeSaveResult.ok();
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        Map<String, String> collections = pendingDeletes.remove(id);
        if (collections != null) {
            publish(collections, tenantId);
        }
    }

    private Map<String, String> affectedCollections(Map<String, Object> record, String tenantId) {
        if (record == null) {
            return new LinkedHashMap<>();
        }
        return resolve((String) record.get("picklistSourceType"), (String) record.get("picklistSourceId"), tenantId);
    }

    /** Collection id → name of every collection drawing on the given picklist source. */
    private Map<String, String> resolve(String sourceType, String sourceId, String tenantId) {
        Map<String, String> collections = new LinkedHashMap<>();
        if (sourceType == null || sourceId == null) {
            return collections;
        }
        try {
            List<Map<String, Object>> rows = switch (sourceType) {
                case "FIELD" -> jdbcTemplate.queryForList(SELECT_FIELD_COLLECTION, sourceId);
                case "GLOBAL" -> jdbcTemplate.queryForList(SELECT_GLOBAL_PICKLIST_COLLECTIONS, tenantId, sourceId);
                default -> List.of();
            };
            for (Map<String, Object> row : rows) {
                collections.put(String.valueOf(row.get("id")), (String) row.get("name"));
            }
        } catch (Exception e) {
            log.warn("Failed to resolve collections for picklist source {}:{}: {}", sourceType, sourceId, e.getMessage());
        }
        return collections;
    }

    private void publish(Map<String, String> collections, String tenantId) {
        collections.forEach((collectionId, collectionName) -> {
            CollectionChangedPayload payload = new CollectionChangedPayload();
            payload.setId(collectionId);
            payload.setName(collectionName);
            payload.setChangeType(ChangeType.UPDATED);

            PlatformEvent<CollectionChangedPayload> event =
                    EventFactory.createEvent("kelta.config.collection.changed", payload);
            event.setTenantId(tenantId);
            log.info("Publishing collection-changed event for picklist value change "
                    + "(collectionId={}, collectionName={})", collectionId, collectionName);
            eventPublisher.publish(CollectionConfigEventPublisher.SUBJECT_PREFIX + collectionId, event);
            lifecycleManager.refreshOrInitializeLocally(collectionId);
        });
    }
}
