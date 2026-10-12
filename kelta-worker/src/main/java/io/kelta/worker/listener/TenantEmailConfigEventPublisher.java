package io.kelta.worker.listener;

import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes {@code kelta.config.tenant.email.changed.<tenantId>} whenever the
 * email-related columns on a {@code tenants} row change (or any other tenant
 * write — we don't bother filtering since the listener side is cheap).
 *
 * <p>Workers consume the event and evict their per-tenant
 * {@link io.kelta.worker.service.email.SmtpEmailProvider} sender cache so the
 * next outbound mail picks up the new host / credentials / from-overrides.
 */
@Component
public class TenantEmailConfigEventPublisher implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(TenantEmailConfigEventPublisher.class);
    static final String SUBJECT_PREFIX = "kelta.config.tenant.email.changed.";
    private static final String EVENT_TYPE = "kelta.config.tenant.email.changed";

    private final PlatformEventPublisher eventPublisher;

    public TenantEmailConfigEventPublisher(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override public String getCollectionName() { return "tenants"; }
    @Override public int getOrder() { return 200; }   // run after provisioning/audit hooks

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                             Map<String, Object> previous, String tenantId) {
        if (!emailFieldChanged(record, previous)) {
            return;
        }
        publish(id);
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        publish(id);
    }

    private boolean emailFieldChanged(Map<String, Object> record, Map<String, Object> previous) {
        if (previous == null) return true;
        for (String field : new String[]{
                "emailSmtpCredentialId", "emailFromAddress", "emailFromName"}) {
            if (record.containsKey(field) && !Objects.equals(record.get(field), previous.get(field))) {
                return true;
            }
        }
        return false;
    }

    private void publish(String tenantId) {
        if (tenantId == null) {
            log.warn("Skipping tenant email config event: tenant id is null");
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        PlatformEvent<Map<String, Object>> event = EventFactory.createEvent(EVENT_TYPE, payload);
        event.setTenantId(tenantId);
        String subject = SUBJECT_PREFIX + tenantId;
        log.info("Publishing tenant email config changed event for {} to '{}'", tenantId, subject);
        eventPublisher.publish(subject, event);
    }
}
