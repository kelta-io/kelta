package io.kelta.worker.repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for email templates and email log persistence.
 *
 * <p>Uses JdbcTemplate to match the worker's existing data access pattern.
 * Operates against the {@code email_template} and {@code email_log} tables
 * created in V25.
 *
 * @since 1.0.0
 */
@Repository
public class EmailRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EmailRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------------
    // Email Templates
    // -----------------------------------------------------------------------

    /**
     * Retrieves an email template by tenant and ID.
     */
    public Optional<Map<String, Object>> findTemplateByTenantAndId(String tenantId, String templateId) {
        var results = jdbcTemplate.queryForList(
                "SELECT id, subject, body_html, body_text FROM email_template " +
                        "WHERE tenant_id = ? AND id = ? AND is_active = true",
                tenantId, templateId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Resolves a template by its stable {@code template_key} (e.g. "user.invite").
     * Prefers the tenant's own override; falls back to the {@code 'system'} tenant's
     * default. Returns empty when no template exists for the key in either scope.
     */
    public Optional<Map<String, Object>> findTemplateByKey(String tenantId, String templateKey) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, subject, body_html, body_text, tenant_id "
                        + "FROM email_template "
                        + "WHERE template_key = ? AND is_active = true "
                        + "  AND tenant_id IN (?, 'system') "
                        + "ORDER BY CASE WHEN tenant_id = ? THEN 0 ELSE 1 END "
                        + "LIMIT 1",
                templateKey, tenantId, tenantId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Resolves a template by its {@code name} column (e.g. "password_reset").
     * Prefers the tenant's own override; falls back to the {@code 'system'} tenant's
     * default seeded in V141. Returns empty when no template exists for the name in
     * either scope.
     */
    public Optional<Map<String, Object>> findTemplateByName(String tenantId, String name) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, subject, body_html, body_text, tenant_id "
                        + "FROM email_template "
                        + "WHERE name = ? AND is_active = true "
                        + "  AND tenant_id IN (?, 'system') "
                        + "ORDER BY CASE WHEN tenant_id = ? THEN 0 ELSE 1 END "
                        + "LIMIT 1",
                name, tenantId, tenantId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // -----------------------------------------------------------------------
    // Email Log
    // -----------------------------------------------------------------------

    /**
     * Creates a new email log entry with status QUEUED.
     *
     * @return the generated log ID
     */
    public String createEmailLog(String tenantId, String recipientEmail, String subject,
                                 String source, String sourceId) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO email_log (id, tenant_id, recipient_email, subject, status, source, source_id, created_at) " +
                        "VALUES (?, ?, ?, ?, 'QUEUED', ?, ?, ?)",
                id, tenantId, recipientEmail, subject, source, sourceId, Timestamp.from(Instant.now())
        );
        return id;
    }

    /**
     * Lists email log entries whose {@code source_id} references the given
     * record (newest first) — backs the record activity timeline. RLS scopes
     * rows to the current tenant.
     */
    public java.util.List<java.util.Map<String, Object>> findLogsBySourceId(String sourceId, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id, recipient_email, subject, status, sent_at, created_at " +
                        "FROM email_log WHERE source_id = ? ORDER BY created_at DESC LIMIT ?",
                sourceId, limit
        );
    }

    /**
     * Counts {@code email_log} rows created for a tenant since {@code since}. Backs the
     * per-tenant send rate limit on the transactional-send endpoint — every queued email
     * (regardless of eventual delivery status) writes a log row, so this reflects real send
     * volume within the window.
     *
     * @return the number of emails logged for the tenant since {@code since}
     */
    public int countRecentByTenant(String tenantId, Instant since) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_log WHERE tenant_id = ? AND created_at > ?",
                Integer.class, tenantId, Timestamp.from(since)
        );
        return count == null ? 0 : count;
    }

    /**
     * Updates the email log status to SENDING.
     */
    public void markSending(String logId) {
        jdbcTemplate.update(
                "UPDATE email_log SET status = 'SENDING' WHERE id = ?",
                logId
        );
    }

    /**
     * Updates the email log status to SENT with the SMTP host used.
     */
    public void markSent(String logId, String smtpHost) {
        jdbcTemplate.update(
                "UPDATE email_log SET status = 'SENT', sent_at = ?, smtp_host = ? WHERE id = ?",
                Timestamp.from(Instant.now()), smtpHost, logId
        );
    }

    /**
     * Updates the email log status to SUPPRESSED: the recipient is on the tenant's
     * suppression list, so nothing was sent.
     */
    public void markSuppressed(String logId) {
        jdbcTemplate.update(
                "UPDATE email_log SET status = 'SUPPRESSED', error_message = ? WHERE id = ?",
                "Recipient is on the suppression list", logId
        );
    }

    /**
     * Updates the email log status to FAILED with error details.
     */
    public void markFailed(String logId, String errorMessage, String smtpHost) {
        jdbcTemplate.update(
                "UPDATE email_log SET status = 'FAILED', error_message = ?, smtp_host = ? WHERE id = ?",
                errorMessage, smtpHost, logId
        );
    }

    // -----------------------------------------------------------------------
    // Tenant Settings
    // -----------------------------------------------------------------------

    /**
     * Retrieves the settings JSONB for a tenant.
     *
     * @return the parsed JsonNode, or null if tenant not found
     */
    public JsonNode getTenantSettings(String tenantId) {
        var results = jdbcTemplate.queryForList(
                "SELECT settings FROM tenant WHERE id = ?",
                tenantId
        );
        if (results.isEmpty()) {
            return null;
        }
        Object settings = results.get(0).get("settings");
        if (settings == null) {
            return null;
        }
        try {
            if (settings instanceof String s) {
                return objectMapper.readTree(s);
            }
            return objectMapper.valueToTree(settings);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Loads the tenant's structured email config (vault FK + from overrides + legacy JSONB).
     * The legacy JSONB blob is still returned so callers can fall back when no credential is set.
     *
     * @return the config row, or null if the tenant does not exist
     */
    public TenantEmailConfigRow findTenantEmailConfig(String tenantId) {
        var rows = jdbcTemplate.queryForList(
                "SELECT email_smtp_credential_id, email_from_address, email_from_name, "
                        + "auto_invite_on_create, settings "
                        + "FROM tenant WHERE id = ?",
                tenantId
        );
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.get(0);
        JsonNode legacy = null;
        Object settings = row.get("settings");
        if (settings != null) {
            try {
                legacy = settings instanceof String s
                        ? objectMapper.readTree(s)
                        : objectMapper.valueToTree(settings);
            } catch (Exception ignored) {
                // legacy JSONB unreadable — treat as absent
            }
        }
        Object autoInvite = row.get("auto_invite_on_create");
        return new TenantEmailConfigRow(
                (String) row.get("email_smtp_credential_id"),
                (String) row.get("email_from_address"),
                (String) row.get("email_from_name"),
                autoInvite instanceof Boolean b ? b : true,
                legacy
        );
    }

    /**
     * Resolves the tenant whose {@code email_from_address} exactly matches the given address
     * (case-insensitive). Backs SES bounce/complaint handling: the SNS notification carries
     * {@code mail.source} — the exact From address used for that send — which is the only signal
     * available to route the event back to a tenant.
     */
    public Optional<String> findTenantIdByFromAddress(String fromAddress) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id FROM tenant WHERE lower(email_from_address) = lower(?)",
                fromAddress
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.get(0).get("id"));
    }

    /**
     * Tenant-level email configuration row. Decryption of the credential
     * happens elsewhere via {@code CredentialResolver}.
     */
    public record TenantEmailConfigRow(
            String smtpCredentialId,
            String fromAddress,
            String fromName,
            boolean autoInviteOnCreate,
            JsonNode legacySettings
    ) {}
}
