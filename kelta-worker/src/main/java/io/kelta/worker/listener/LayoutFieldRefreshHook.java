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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * After-save hook for the "layout-fields" system collection. Publishes a
 * layout-changed event whenever a layout field is created or updated. The
 * record only carries {@code sectionId}, so the parent {@code layoutId} is
 * resolved via a small JDBC lookup against {@code layout_section}.
 *
 * <p>Subject: {@code kelta.config.layout.changed.<layoutId>}.
 */
@Component
public class LayoutFieldRefreshHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(LayoutFieldRefreshHook.class);

    static final String SUBJECT_PREFIX = "kelta.config.layout.changed.";
    static final String EVENT_TYPE = "kelta.config.layout.changed";

    private static final String SELECT_LAYOUT_ID =
            "SELECT layout_id FROM layout_section WHERE id = ? LIMIT 1";

    private final PlatformEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;

    public LayoutFieldRefreshHook(PlatformEventPublisher eventPublisher,
                                   JdbcTemplate jdbcTemplate) {
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getCollectionName() {
        return "layout-fields";
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
        log.info("Layout field deleted (id={}); clients refresh on next layout fetch", id);
    }

    private void publishLayoutChanged(Map<String, Object> record, String tenantId, ChangeType changeType) {
        String sectionId = (String) record.get("sectionId");
        if (sectionId == null) {
            log.warn("Layout field record missing sectionId, cannot broadcast refresh");
            return;
        }

        String layoutId = resolveLayoutId(sectionId);
        if (layoutId == null) {
            log.warn("Could not resolve layoutId for sectionId={}, skipping broadcast", sectionId);
            return;
        }

        CollectionChangedPayload payload = new CollectionChangedPayload();
        payload.setId(layoutId);
        payload.setChangeType(changeType);

        PlatformEvent<CollectionChangedPayload> event = EventFactory.createEvent(EVENT_TYPE, payload);
        event.setTenantId(tenantId);
        String subject = SUBJECT_PREFIX + layoutId;
        log.info("Publishing layout-changed event from field change (layoutId={}, changeType={})",
                layoutId, changeType);
        eventPublisher.publish(subject, event);
    }

    private String resolveLayoutId(String sectionId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_LAYOUT_ID, sectionId);
            if (!rows.isEmpty()) {
                Object value = rows.get(0).get("layout_id");
                return value != null ? value.toString() : null;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve layoutId for sectionId={}: {}", sectionId, e.getMessage());
        }
        return null;
    }
}
