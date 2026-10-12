package io.kelta.worker.listener;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Broadcasts {@code kelta.config.api-spec.changed.<id>} after spec
 * create/update/delete so all worker pods can refresh local caches if they
 * keep any (currently the {@link io.kelta.worker.service.api.JdbcApiSpecStore}
 * is stateless, but PR 4's CALL_API handler may add a parsed-spec cache).
 */
@Component
public class ApiSpecConfigHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(ApiSpecConfigHook.class);
    static final String SUBJECT_PREFIX = "kelta.config.api-spec.changed.";
    private static final String EVENT_TYPE = "kelta.config.api-spec.changed";

    private final PlatformEventPublisher eventPublisher;

    public ApiSpecConfigHook(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override public String getCollectionName() { return "api-specs"; }
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("changeType", ChangeType.DELETED.name());
        send(payload, id, tenantId);
    }

    private void publish(Map<String, Object> record, ChangeType changeType, String tenantId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", record.get("id"));
        payload.put("name", record.get("name"));
        payload.put("changeType", changeType.name());
        Object id = record.get("id");
        if (id == null) {
            log.warn("Skipping api-spec changed event: record missing id");
            return;
        }
        send(payload, id.toString(), tenantId);
    }

    private void send(Map<String, Object> payload, String id, String tenantId) {
        PlatformEvent<Map<String, Object>> event = EventFactory.createEvent(EVENT_TYPE, payload);
        event.setTenantId(tenantId);
        eventPublisher.publish(SUBJECT_PREFIX + id, event);
    }
}
