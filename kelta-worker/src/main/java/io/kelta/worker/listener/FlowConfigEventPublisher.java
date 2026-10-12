package io.kelta.worker.listener;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.FlowChangedPayload;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Broadcasts {@code kelta.config.flow.changed.<tenantId>} after flow
 * create/update/delete so all worker pods can invalidate the per-tenant
 * trigger config cache held by {@link FlowEventListener}. Without this, a
 * flow change made via the UI is invisible to pods that already populated
 * their cache for that tenant — they'd keep using the stale snapshot until
 * restart, which violates the multi-pod NATS rule documented in CLAUDE.md.
 *
 * <p>Subject prefix uses the tenant id (rather than the flow id like the
 * collection/credential publishers) because the listener cache is keyed
 * by tenant — every flow change for a tenant invalidates the same cache
 * entry, so per-flow subjects would just create extra messages.
 */
@Component
public class FlowConfigEventPublisher implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(FlowConfigEventPublisher.class);
    static final String SUBJECT_PREFIX = "kelta.config.flow.changed.";
    private static final String EVENT_TYPE = "kelta.config.flow.changed";

    private final PlatformEventPublisher eventPublisher;

    public FlowConfigEventPublisher(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override public String getCollectionName() { return "flows"; }
    @Override public int getOrder() { return 100; }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        publish(record, ChangeType.CREATED, tenantId);
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                             Map<String, Object> previous, String tenantId) {
        publish(record, ChangeType.UPDATED, tenantId);
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        FlowChangedPayload payload = new FlowChangedPayload();
        payload.setId(id);
        payload.setChangeType(ChangeType.DELETED);
        send(payload, tenantId);
    }

    private void publish(Map<String, Object> record, ChangeType changeType, String tenantId) {
        FlowChangedPayload payload = new FlowChangedPayload();
        payload.setId(asString(record.get("id")));
        payload.setName(asString(record.get("name")));
        payload.setFlowType(asString(record.get("flowType")));
        Object active = record.get("active");
        payload.setActive(active instanceof Boolean b ? b : true);
        payload.setChangeType(changeType);
        if (payload.getId() == null) {
            log.warn("Skipping flow changed event: record missing id");
            return;
        }
        send(payload, tenantId);
    }

    private void send(FlowChangedPayload payload, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("Skipping flow changed event for id={}: tenantId is blank", payload.getId());
            return;
        }
        PlatformEvent<FlowChangedPayload> event =
                EventFactory.createEvent(EVENT_TYPE, payload);
        event.setTenantId(tenantId);
        String subject = SUBJECT_PREFIX + tenantId;
        log.info("Publishing flow {} event for {} (id={}) to '{}'",
                payload.getChangeType(), payload.getName(), payload.getId(), subject);
        eventPublisher.publish(subject, event);
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
