package io.kelta.worker.listener;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.CredentialChangedPayload;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Broadcasts {@code kelta.config.credential.changed.<id>} after credential
 * create/update/delete so all worker pods can invalidate their resolver caches.
 */
@Component
public class CredentialEventPublisher implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(CredentialEventPublisher.class);
    static final String SUBJECT_PREFIX = "kelta.config.credential.changed.";
    private static final String EVENT_TYPE = "kelta.config.credential.changed";

    private final PlatformEventPublisher eventPublisher;

    public CredentialEventPublisher(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override public String getCollectionName() { return "credentials"; }
    @Override public int getOrder() { return 100; }   // run after CredentialEncryptionHook

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
        CredentialChangedPayload payload = new CredentialChangedPayload();
        payload.setId(id);
        payload.setChangeType(ChangeType.DELETED);
        send(payload, tenantId);
    }

    private void publish(Map<String, Object> record, ChangeType changeType, String tenantId) {
        CredentialChangedPayload payload = new CredentialChangedPayload();
        payload.setId(asString(record.get("id")));
        payload.setName(asString(record.get("name")));
        payload.setType(asString(record.get("type")));
        payload.setChangeType(changeType);
        if (payload.getId() == null) {
            log.warn("Skipping credential changed event: record missing id");
            return;
        }
        send(payload, tenantId);
    }

    private void send(CredentialChangedPayload payload, String tenantId) {
        PlatformEvent<CredentialChangedPayload> event =
                EventFactory.createEvent(EVENT_TYPE, payload);
        event.setTenantId(tenantId);
        String subject = SUBJECT_PREFIX + payload.getId();
        log.info("Publishing credential {} event for {} (id={}) to '{}'",
                payload.getChangeType(), payload.getName(), payload.getId(), subject);
        eventPublisher.publish(subject, event);
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
