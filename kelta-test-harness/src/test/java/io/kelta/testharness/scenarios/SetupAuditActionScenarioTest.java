package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every audit verb the code writes must be one the database accepts.
 *
 * <p>This gap was real and silent. {@code CredentialResolverImpl} writes
 * {@code CREDENTIAL_RESOLVE} on every credential access, but
 * {@code setup_audit_trail.chk_audit_action} permitted only the five mutation verbs, so
 * Postgres rejected each row. {@code SetupAuditService} swallows write failures by design
 * ("audit failures never disrupt normal operations"), so there was no record of secret
 * access on any tenant and nothing failed — only a log line.
 *
 * <p>The unit tests could not catch it: they mock {@code JdbcTemplate} and assert
 * {@code eq("CREDENTIAL_RESOLVE")} was passed, which is the argument, never that the
 * database accepts it. Only a real Postgres has the constraint.
 *
 * <p>The harness has no dependency on kelta-worker, so the INSERT below is copied verbatim
 * from {@code SetupAuditService.log} and the verb list from its callers —
 * <b>keep both in sync</b>, the same contract {@code FlowLogRetentionScenarioTest} follows.
 */
@DisplayName("Setup Audit Action Scenario")
class SetupAuditActionScenarioTest extends ScenarioBase {

    /** Verbatim from SetupAuditService.log. */
    private static final String INSERT_AUDIT = """
            INSERT INTO setup_audit_trail \
            (id, tenant_id, user_id, action, section, entity_type, entity_id, entity_name, \
            old_value, new_value, timestamp, created_at, updated_at) \
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)\
            """;

    /**
     * Every action string reaching SetupAuditService.log today:
     * AuditBeforeSaveHook (CREATED/UPDATED/DELETED), MetadataPromotionService (UPDATED),
     * CredentialResolverImpl (CREDENTIAL_RESOLVE), UserAnonymizationService (ANONYMIZED, V217).
     * ACTIVATED/DEACTIVATED are permitted by
     * the constraint and asserted here so narrowing it later fails loudly.
     */
    private static final List<String> ACTIONS_WRITTEN_BY_THE_CODEBASE = List.of(
            "CREATED", "UPDATED", "DELETED", "ACTIVATED", "DEACTIVATED", "CREDENTIAL_RESOLVE",
            "ANONYMIZED");

    @Test
    @DisplayName("every audit verb the code writes is actually accepted and readable back")
    void everyAuditActionIsAccepted() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String suffix = Long.toHexString(System.nanoTime());

        try (Connection db = openDbConnection()) {
            for (String action : ACTIONS_WRITTEN_BY_THE_CODEBASE) {
                String id = UUID.randomUUID().toString();
                String entityName = "audit-probe-" + suffix + "-" + action;

                insertAudit(db, id, tenantId, action, entityName);

                // Written is not enough — read it back, since a swallowed failure looks
                // identical to a success from the writer's side.
                assertThat(readActionById(db, id))
                        .as("audit row for action %s must persist", action)
                        .isEqualTo(action);
            }
        }
    }

    @Test
    @DisplayName("an unknown verb is still rejected — the constraint was widened, not dropped")
    void unknownActionIsStillRejected() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);

        try (Connection db = openDbConnection()) {
            assertThatThrownBy(() -> insertAudit(db, UUID.randomUUID().toString(), tenantId,
                    "NOT_A_REAL_ACTION", "audit-probe-bogus"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_audit_action");
        }
    }

    private void insertAudit(Connection db, String id, String tenantId, String action,
                             String entityName) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement ps = db.prepareStatement(INSERT_AUDIT)) {
            ps.setString(1, id);
            ps.setString(2, tenantId);
            ps.setString(3, null);
            ps.setString(4, action);
            ps.setString(5, "credentials");
            ps.setString(6, "credential");
            ps.setString(7, UUID.randomUUID().toString());
            ps.setString(8, entityName);
            ps.setString(9, null);
            ps.setString(10, "{\"purpose\":\"HARNESS_PROBE\"}");
            ps.setTimestamp(11, now);
            ps.setTimestamp(12, now);
            ps.setTimestamp(13, now);
            ps.executeUpdate();
        }
    }

    private String readActionById(Connection db, String id) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT action FROM setup_audit_trail WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
