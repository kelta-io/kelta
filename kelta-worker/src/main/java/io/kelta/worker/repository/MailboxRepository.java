package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes the {@code mailbox} table.
 *
 * <p>Hand-written SQL over {@link JdbcTemplate}, matching the house repository style
 * (see {@code EmailSuppressionRepository}). Every tenant-scoped statement also carries
 * an explicit {@code tenant_id = ?} predicate: RLS is the actual boundary, the predicate
 * is defence in depth and makes the intent readable in the SQL itself.
 *
 * @since 1.0.0
 */
@Repository
public class MailboxRepository {

    /**
     * Columns a caller may set. Deliberately excludes {@code webhook_key} and the three
     * credential-reference columns — those are minted and rotated by
     * {@code MailboxSecretService} and must never be settable from a request body, or a
     * caller could point a mailbox at a secret belonging to something else.
     */
    private static final List<String> WRITABLE = List.of(
            "name", "description", "address", "reply_from_address", "reply_from_name",
            "verp_domain", "inbound_provider", "provider_topic_arn", "inbound_allowed_cidrs",
            "max_message_bytes", "max_attachments", "max_attachment_bytes",
            "subject_threading_days", "sla_first_response_minutes", "sla_resolution_minutes",
            "sla_risk_threshold_pct", "business_timezone", "escalation_user_id",
            "escalation_group_id", "auto_reply_enabled", "auto_reply_min_confidence",
            "max_auto_replies_per_thread", "max_auto_replies_per_day",
            "auto_reply_blocked_categories", "ai_draft_enabled",
            "require_verified_sender_for_account_data", "default_assignee_id", "active");

    /**
     * Writable columns whose type is {@code jsonb}. A Java list bound to a plain {@code ?} is not
     * something the driver can hand Postgres for a jsonb column, so these are serialised and bound
     * with an explicit cast. Before this existed the PATCH returned 200 and the value was dropped —
     * the kill-switch list for auto-reply could only be changed at a psql prompt.
     */
    private static final List<String> JSON_COLUMNS =
            List.of("inbound_allowed_cidrs", "auto_reply_blocked_categories");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MailboxRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves a mailbox by its webhook key, across every tenant.
     *
     * <p><b>Deliberately not tenant-scoped.</b> This is the inbound-webhook entry point,
     * which runs before any tenant is bound — the mailbox row is what *determines* the
     * tenant. It therefore relies on the {@code admin_bypass} RLS policy, exactly as
     * {@code EmailRepository.findTenantIdByFromAddress} does for SES bounce notifications.
     *
     * <p>The key is an identifier, not a secret: knowing it gets a caller as far as
     * "which mailbox", never as far as "authenticated". Authentication is a separate,
     * always-required step performed by the ingest adapter.
     */
    public Optional<Map<String, Object>> findByWebhookKey(String webhookKey) {
        if (webhookKey == null || webhookKey.isBlank()) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM mailbox WHERE webhook_key = ?", webhookKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Map<String, Object>> findById(String id, String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM mailbox WHERE id = ? AND tenant_id = ?", id, tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<Map<String, Object>> list(String tenantId, int limit, int offset) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM mailbox WHERE tenant_id = ? ORDER BY name LIMIT ? OFFSET ?",
                tenantId, limit, offset);
    }

    /** True when another mailbox in the tenant already claims {@code address}. */
    public boolean addressExists(String tenantId, String address, String excludeId) {
        Integer n = jdbcTemplate.queryForObject(
                // The excludeId parameter is cast explicitly. In `? IS NULL` Postgres has no
                // context from which to infer a parameter's type and rejects the statement with
                // "could not determine data type of parameter" — a failure invisible to a test
                // that mocks JdbcTemplate, because the SQL is never sent to a server.
                "SELECT count(*) FROM mailbox WHERE tenant_id = ? AND lower(address) = lower(?) "
                        + "AND (?::text IS NULL OR id <> ?)",
                Integer.class, tenantId, address, excludeId, excludeId);
        return n != null && n > 0;
    }

    /**
     * Inserts a mailbox. {@code webhookKey} is supplied by the caller rather than generated
     * here so that key generation lives in one place with the secret minting it belongs with.
     */
    public String create(String tenantId, String webhookKey, Map<String, Object> attrs, String actor) {
        String id = UUID.randomUUID().toString();
        StringBuilder cols = new StringBuilder("id, tenant_id, webhook_key, created_by, updated_by");
        StringBuilder vals = new StringBuilder("?, ?, ?, ?, ?");
        List<Object> args = new java.util.ArrayList<>(List.of(id, tenantId, webhookKey,
                actor == null ? "" : actor, actor == null ? "" : actor));

        for (String col : WRITABLE) {
            if (attrs.containsKey(col)) {
                cols.append(", ").append(col);
                vals.append(", ?");
                args.add(attrs.get(col));
            }
        }
        jdbcTemplate.update("INSERT INTO mailbox (" + cols + ") VALUES (" + vals + ")", args.toArray());
        return id;
    }

    /** Applies only whitelisted columns; unknown keys are ignored rather than rejected. */
    public int update(String id, String tenantId, Map<String, Object> attrs, String actor) {
        StringBuilder set = new StringBuilder("updated_at = NOW(), updated_by = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(actor == null ? "" : actor);

        for (String col : WRITABLE) {
            if (attrs.containsKey(col)) {
                if (JSON_COLUMNS.contains(col)) {
                    set.append(", ").append(col).append(" = ?::jsonb");
                    args.add(json(attrs.get(col)));
                } else {
                    set.append(", ").append(col).append(" = ?");
                    args.add(attrs.get(col));
                }
            }
        }
        if (args.size() == 1) {
            return 0;
        }
        args.add(id);
        args.add(tenantId);
        return jdbcTemplate.update(
                "UPDATE mailbox SET " + set + " WHERE id = ? AND tenant_id = ?", args.toArray());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new IllegalArgumentException("value is not serialisable as JSON", e);
        }
    }

    /**
     * Points the mailbox at a freshly minted inbound secret, demoting the current one to the
     * previous slot with an expiry.
     *
     * <p>The overlap is the whole point: a provider cannot switch secrets at the same instant
     * we do, so for a window both must verify or in-flight deliveries are rejected.
     */
    public int rotateSecret(String id, String tenantId, String newCredentialId,
                            String hint, int overlapMinutes, String actor) {
        return jdbcTemplate.update("""
                UPDATE mailbox
                   SET inbound_prev_secret_credential_id = inbound_secret_credential_id,
                       inbound_prev_secret_expires_at    = NOW() + make_interval(mins => ?),
                       inbound_secret_credential_id      = ?,
                       inbound_secret_hint               = ?,
                       inbound_secret_rotated_at         = NOW(),
                       updated_at                        = NOW(),
                       updated_by                        = ?
                 WHERE id = ? AND tenant_id = ?
                """, overlapMinutes, newCredentialId, hint, actor == null ? "" : actor, id, tenantId);
    }

    public int delete(String id, String tenantId) {
        return jdbcTemplate.update("DELETE FROM mailbox WHERE id = ? AND tenant_id = ?", id, tenantId);
    }
}
