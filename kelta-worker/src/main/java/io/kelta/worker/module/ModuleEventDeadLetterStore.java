package io.kelta.worker.module;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.UUID;

/**
 * {@code module_event_dead_letter} (V222): record events an external module never acknowledged
 * after the last delivery attempt ({@code specs/module-platform/9-external-modules.md} §3.6.1).
 *
 * <p>One row per install and event: a final attempt redelivered because its ack was lost after
 * the insert hits the unique key and writes nothing. The caller binds the tenant, so RLS scopes
 * the insert.
 */
public class ModuleEventDeadLetterStore {

    private final JdbcTemplate jdbcTemplate;

    public ModuleEventDeadLetterStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    /**
     * Records an undelivered event.
     *
     * @param payloadJson the {@code RecordEvent} body exactly as sent (already filtered for the module)
     * @param lastStatus  the module's last HTTP status, 0 when it never answered
     * @return true when a row was written, false when this install already dead-lettered the event
     */
    public boolean record(String tenantId, String tenantModuleId, String eventId, String payloadJson,
                          int lastStatus, String lastError, long attempts) {
        return jdbcTemplate.update("INSERT INTO module_event_dead_letter "
                        + "(id, tenant_id, tenant_module_id, event_id, payload, last_status, last_error, attempts) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?) "
                        + "ON CONFLICT (tenant_module_id, event_id) DO NOTHING",
                UUID.randomUUID().toString(), tenantId, tenantModuleId, eventId, payloadJson, lastStatus,
                lastError, (int) attempts) > 0;
    }
}
