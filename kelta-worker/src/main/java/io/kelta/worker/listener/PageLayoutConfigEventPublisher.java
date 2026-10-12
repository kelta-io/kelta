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
 * After-save hook for the "page-layouts" system collection. Publishes a
 * layout-changed event for create, update, and delete (the delete callback
 * provides the layout id directly).
 *
 * <p>Subject: {@code kelta.config.layout.changed.<layoutId>}.
 */
@Component
public class PageLayoutConfigEventPublisher implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(PageLayoutConfigEventPublisher.class);

    static final String SUBJECT_PREFIX = "kelta.config.layout.changed.";
    static final String EVENT_TYPE = "kelta.config.layout.changed";

    private final PlatformEventPublisher eventPublisher;

    public PageLayoutConfigEventPublisher(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getCollectionName() {
        return "page-layouts";
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        String layoutId = (String) record.get("id");
        Object name = record.get("name");
        publish(layoutId, name != null ? name.toString() : null, tenantId, ChangeType.CREATED);
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                             Map<String, Object> previous, String tenantId) {
        Object name = record.get("name");
        publish(id, name != null ? name.toString() : null, tenantId, ChangeType.UPDATED);
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        publish(id, null, tenantId, ChangeType.DELETED);
    }

    private void publish(String layoutId, String name, String tenantId, ChangeType changeType) {
        if (layoutId == null) {
            log.warn("Page layout {} event missing id, cannot broadcast", changeType);
            return;
        }

        CollectionChangedPayload payload = new CollectionChangedPayload();
        payload.setId(layoutId);
        payload.setName(name);
        payload.setChangeType(changeType);

        PlatformEvent<CollectionChangedPayload> event = EventFactory.createEvent(EVENT_TYPE, payload);
        event.setTenantId(tenantId);
        String subject = SUBJECT_PREFIX + layoutId;
        log.info("Publishing page-layout {} event (layoutId={})", changeType, layoutId);
        eventPublisher.publish(subject, event);
    }
}
