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
 * After-save hook for the {@code ui-translations} system collection (tenant i18n
 * authoring, app-intelligence slice 4). Publishes a translation-changed event on
 * create, update, and delete so every pod evicts its
 * {@link io.kelta.runtime.router.SystemCollectionCache} entries for the collection
 * (Critical Rule 1 — a write must never leave other pods serving stale config).
 *
 * <p>Subject: {@code kelta.config.translation.changed.<tenantId>} — tenant-scoped,
 * since the consumer eviction is per tenant + collection, not per record. Mirrors
 * {@link MenuConfigEventPublisher}; consumed by
 * {@link TranslationCacheInvalidationListener}.
 */
@Component
public class TranslationConfigEventPublisher implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(TranslationConfigEventPublisher.class);

    static final String SUBJECT_PREFIX = "kelta.config.translation.changed.";
    static final String EVENT_TYPE = "kelta.config.translation.changed";

    private final PlatformEventPublisher eventPublisher;

    public TranslationConfigEventPublisher(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getCollectionName() {
        return "ui-translations";
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        Object id = record.get("id");
        Object key = record.get("key");
        publish(id != null ? id.toString() : null,
                key != null ? key.toString() : null, tenantId, ChangeType.CREATED);
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                            Map<String, Object> previous, String tenantId) {
        Object key = record.get("key");
        publish(id, key != null ? key.toString() : null, tenantId, ChangeType.UPDATED);
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        publish(id, null, tenantId, ChangeType.DELETED);
    }

    private void publish(String recordId, String key, String tenantId, ChangeType changeType) {
        if (tenantId == null) {
            log.warn("Translation {} event missing tenantId, cannot broadcast", changeType);
            return;
        }

        CollectionChangedPayload payload = new CollectionChangedPayload();
        payload.setId(recordId);
        payload.setName(key);
        payload.setChangeType(changeType);

        PlatformEvent<CollectionChangedPayload> event = EventFactory.createEvent(EVENT_TYPE, payload);
        event.setTenantId(tenantId);
        String subject = SUBJECT_PREFIX + tenantId;
        log.info("Publishing translation {} event (id={}, tenant={})", changeType, recordId, tenantId);
        eventPublisher.publish(subject, event);
    }
}
