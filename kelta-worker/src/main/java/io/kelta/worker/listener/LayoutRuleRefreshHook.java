package io.kelta.worker.listener;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.CollectionChangedPayload;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * After-save hook for the "layout-rules" system collection. Publishes a
 * layout-changed event whenever a client-side rule is created, updated,
 * or (best-effort) deleted, so admin UIs and form clients can re-fetch
 * rules on the next form open.
 *
 * <p>Subject: {@code kelta.config.layout.changed.<layoutId>} — same
 * naming convention as collection config events. The payload reuses
 * {@link CollectionChangedPayload}: {@code id} carries the layout id,
 * {@code name} the rule name (best-effort, for logging/debugging),
 * and {@code changeType} the operation.
 */
@Component
public class LayoutRuleRefreshHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(LayoutRuleRefreshHook.class);

    static final String SUBJECT_PREFIX = "kelta.config.layout.changed.";
    static final String EVENT_TYPE = "kelta.config.layout.changed";

    private final PlatformEventPublisher eventPublisher;

    public LayoutRuleRefreshHook(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getCollectionName() {
        return "layout-rules";
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        publishLayoutChanged(record, tenantId, ChangeType.CREATED);
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                             Map<String, Object> previous, String tenantId) {
        publishLayoutChanged(record, tenantId, ChangeType.UPDATED);
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        // The record data is not provided to afterDelete, so we cannot resolve
        // the layoutId for a targeted broadcast. Layout-rule deletions are rare
        // and clients refresh rules on next form open regardless, so the missed
        // broadcast is acceptable.
        log.info("Layout rule deleted (id={}); client refresh will occur on next form open", id);
    }

    private void publishLayoutChanged(Map<String, Object> record, String tenantId, ChangeType changeType) {
        String layoutId = (String) record.get("layoutId");
        if (layoutId == null) {
            log.warn("Layout rule record missing layoutId, cannot broadcast refresh");
            return;
        }

        CollectionChangedPayload payload = new CollectionChangedPayload();
        payload.setId(layoutId);
        Object name = record.get("name");
        payload.setName(name != null ? name.toString() : null);
        payload.setChangeType(changeType);

        PlatformEvent<CollectionChangedPayload> event = EventFactory.createEvent(EVENT_TYPE, payload);
        event.setTenantId(tenantId);
        String subject = SUBJECT_PREFIX + layoutId;
        log.info("Publishing layout-changed event (layoutId={}, changeType={})", layoutId, changeType);
        eventPublisher.publish(subject, event);
    }
}
