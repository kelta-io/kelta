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
 * After-save hook for the "layout-sections" system collection. Publishes a
 * layout-changed event whenever a section is created or updated so admin UIs
 * and other pods refresh promptly.
 *
 * <p>Subject: {@code kelta.config.layout.changed.<layoutId>}.
 */
@Component
public class LayoutSectionRefreshHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(LayoutSectionRefreshHook.class);

    static final String SUBJECT_PREFIX = "kelta.config.layout.changed.";
    static final String EVENT_TYPE = "kelta.config.layout.changed";

    private final PlatformEventPublisher eventPublisher;

    public LayoutSectionRefreshHook(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getCollectionName() {
        return "layout-sections";
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
        log.info("Layout section deleted (id={}); clients refresh on next layout fetch", id);
    }

    private void publishLayoutChanged(Map<String, Object> record, String tenantId, ChangeType changeType) {
        String layoutId = (String) record.get("layoutId");
        if (layoutId == null) {
            log.warn("Layout section record missing layoutId, cannot broadcast refresh");
            return;
        }

        CollectionChangedPayload payload = new CollectionChangedPayload();
        payload.setId(layoutId);
        payload.setChangeType(changeType);

        PlatformEvent<CollectionChangedPayload> event = EventFactory.createEvent(EVENT_TYPE, payload);
        event.setTenantId(tenantId);
        String subject = SUBJECT_PREFIX + layoutId;
        log.info("Publishing layout-changed event from section change (layoutId={}, changeType={})",
                layoutId, changeType);
        eventPublisher.publish(subject, event);
    }
}
