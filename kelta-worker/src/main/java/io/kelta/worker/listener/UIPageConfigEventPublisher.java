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
 * After-save hook for the "ui-pages" system collection. Publishes a page-changed event on create,
 * update, and delete so other pods (and the gateway/UI caches) can invalidate any cached render
 * contract for that page.
 *
 * <p>Subject: {@code kelta.config.page.changed.<pageId>}. Mirrors {@link PageLayoutConfigEventPublisher}.
 */
@Component("uiPageConfigEventPublisher")
public class UIPageConfigEventPublisher implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(UIPageConfigEventPublisher.class);

    static final String SUBJECT_PREFIX = "kelta.config.page.changed.";
    static final String EVENT_TYPE = "kelta.config.page.changed";

    private final PlatformEventPublisher eventPublisher;

    public UIPageConfigEventPublisher(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getCollectionName() {
        return "ui-pages";
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        String pageId = (String) record.get("id");
        Object name = record.get("name");
        publish(pageId, name != null ? name.toString() : null, tenantId, ChangeType.CREATED);
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

    private void publish(String pageId, String name, String tenantId, ChangeType changeType) {
        if (pageId == null) {
            log.warn("UI page {} event missing id, cannot broadcast", changeType);
            return;
        }

        CollectionChangedPayload payload = new CollectionChangedPayload();
        payload.setId(pageId);
        payload.setName(name);
        payload.setChangeType(changeType);

        PlatformEvent<CollectionChangedPayload> event = EventFactory.createEvent(EVENT_TYPE, payload);
        event.setTenantId(tenantId);
        String subject = SUBJECT_PREFIX + pageId;
        log.info("Publishing ui-page {} event (pageId={})", changeType, pageId);
        eventPublisher.publish(subject, event);
    }
}
