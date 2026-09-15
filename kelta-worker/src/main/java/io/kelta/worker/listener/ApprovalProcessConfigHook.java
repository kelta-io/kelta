package io.kelta.worker.listener;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.CollectionChangedPayload;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * After-save hook for the "approval-processes" system collection that broadcasts
 * a collection-changed event for the process's TARGET collection whenever an
 * approval process is created or updated.
 *
 * <p>This ensures all worker pods refresh their knowledge of available approval
 * processes when configuration changes are made. The event is consumed by
 * {@link CollectionSchemaListener} on every pod.
 *
 * <p>The payload's id and name MUST both describe the target collection. This
 * hook used to publish the target collection's id with the literal name
 * "approval-processes" — the gateway's ConfigEventListener consumed that as a
 * real collection change and re-pointed the target collection's route to
 * /api/approval-processes/**, leaving the target collection unroutable (404)
 * until its next genuine config event.
 *
 * @since 1.0.0
 */
public class ApprovalProcessConfigHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(ApprovalProcessConfigHook.class);

    private final PlatformEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;

    public ApprovalProcessConfigHook(PlatformEventPublisher eventPublisher,
                                     JdbcTemplate jdbcTemplate) {
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getCollectionName() {
        return "approval-processes";
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        publishEvent(record, ChangeType.CREATED, tenantId);
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                             Map<String, Object> previous, String tenantId) {
        publishEvent(record, ChangeType.UPDATED, tenantId);
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        log.info("Approval process deleted (id={}), broadcasting refresh", id);
    }

    private void publishEvent(Map<String, Object> record, ChangeType changeType, String tenantId) {
        String rawCollectionId = (String) record.get("collectionId");
        if (rawCollectionId == null) {
            rawCollectionId = (String) record.get("collection_id");
        }
        if (rawCollectionId == null) {
            log.warn("Approval process record missing collectionId, cannot broadcast refresh");
            return;
        }

        final String collectionId = rawCollectionId;

        String targetName = resolveCollectionName(collectionId);
        if (targetName == null) {
            log.warn("Approval process targets unknown collection '{}', skipping refresh broadcast",
                    collectionId);
            return;
        }

        // id and name must BOTH describe the target collection — consumers treat
        // this as a real collection change (the gateway rebuilds the route from it).
        CollectionChangedPayload payload = new CollectionChangedPayload();
        payload.setId(collectionId);
        payload.setName(targetName);
        payload.setChangeType(ChangeType.UPDATED);

        PlatformEvent<CollectionChangedPayload> event =
                EventFactory.createEvent("kelta.config.collection.changed", payload);
        event.setTenantId(tenantId);
        String subject = CollectionConfigEventPublisher.SUBJECT_PREFIX + collectionId;
        log.info("Publishing approval process config change event (collection {} '{}', processChange={})",
                collectionId, targetName, changeType);
        eventPublisher.publish(subject, event);
    }

    private String resolveCollectionName(String collectionId) {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM collection WHERE id = ?", String.class, collectionId);
        return names.isEmpty() ? null : names.get(0);
    }
}
