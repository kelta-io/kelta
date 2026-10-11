package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The win model's guarantees against real Postgres (consumer-alerting slice 9).
 *
 * <p>What only a real database can prove:
 * <ul>
 *   <li><b>RLS isolates tenants</b> — one tenant's wins are invisible to another, verified as a
 *       non-superuser (the harness DB user bypasses RLS even on FORCE'd tables).</li>
 *   <li><b>The ticker feed query returns only public rows, newest first</b> — the partial index
 *       predicate and the {@code is_public} filter either match or they do not.</li>
 *   <li><b>Per-target stats count correctly</b> across public and private wins.</li>
 * </ul>
 *
 * <p>Owner-scoping (a member cannot touch another's win) comes from the {@code wins} ownership
 * metadata ({@code ownerField=memberId}, {@code ownerScope=ALL}), enforced by the generic
 * {@code OwnerScopeGuardHook} and covered by {@code OwnerScopeGuardHookTest} and
 * {@code OwnerScopedCollectionScenarioTest}. Read queries below are copied verbatim from
 * {@code WinRepository} — <b>keep them in sync</b>.
 */
@DisplayName("Win Scenario")
class WinScenarioTest extends ScenarioBase {

    private static final String PROBE_ROLE = "kelta_harness_win_probe";
    private static final String PROBE_PASSWORD = "kelta-harness-win-probe";

    /** Verbatim from WinRepository.findRecentPublic. */
    private static final String RECENT_PUBLIC = """
            SELECT id, member_id, target_id, watch_id, alert_id, category, summary, quantity,
                   is_public, claimant_name, claimed_at
              FROM win WHERE tenant_id = ? AND is_public = true
             ORDER BY claimed_at DESC LIMIT ?
            """;

    /** Verbatim from WinRepository.statsForTarget. */
    private static final String STATS_FOR_TARGET = """
            SELECT COUNT(*) AS n, MAX(claimed_at) AS last_at
              FROM win WHERE tenant_id = ? AND target_id = ?
            """;

    @Test
    @DisplayName("RLS isolates one tenant's wins from another's")
    void rlsIsolatesTenants() throws Exception {
        String suffix = Long.toHexString(System.nanoTime());
        String tenantA = "win-a-" + suffix;
        String tenantB = "win-b-" + suffix;
        Instant now = Instant.now();

        try (Connection db = openDbConnection()) {
            try {
                insertWin(db, tenantA, "member-1", "target-1", "A win", true, now);
                insertWin(db, tenantB, "member-2", "target-2", "B win", true, now);

                ensureProbeRole(db);
                try (Connection probe = openDbConnection(PROBE_ROLE, PROBE_PASSWORD)) {
                    setTenant(probe, tenantA);
                    assertThat(winTenants(probe)).as("tenant A sees only its own wins")
                            .containsOnly(tenantA);

                    setTenant(probe, tenantB);
                    assertThat(winTenants(probe)).as("tenant B sees only its own wins")
                            .containsOnly(tenantB);
                }
            } finally {
                exec(db, "DELETE FROM win WHERE tenant_id = ?", tenantA);
                exec(db, "DELETE FROM win WHERE tenant_id = ?", tenantB);
            }
        }
    }

    @Test
    @DisplayName("the ticker feed returns only public wins, newest first; stats count all wins")
    void tickerFeedAndStats() throws Exception {
        String suffix = Long.toHexString(System.nanoTime());
        String tenant = "win-feed-" + suffix;
        Instant now = Instant.now();
        Instant older = now.minus(Duration.ofHours(1));

        try (Connection db = openDbConnection()) {
            try {
                insertWin(db, tenant, "m1", "target-9", "newer public", true, now);
                insertWin(db, tenant, "m2", "target-9", "older public", true, older);
                insertWin(db, tenant, "m3", "target-9", "private win", false, now);

                List<String> feed = recentPublicSummaries(db, tenant, 10);
                assertThat(feed).as("only public wins, newest first")
                        .containsExactly("newer public", "older public");

                long count = statsCount(db, tenant, "target-9");
                assertThat(count).as("stats count public and private wins on the target").isEqualTo(3);
            } finally {
                exec(db, "DELETE FROM win WHERE tenant_id = ?", tenant);
            }
        }
    }

    // ------------------------------------------------------------- Helpers

    private void insertWin(Connection db, String tenantId, String memberId, String targetId,
                           String summary, boolean isPublic, Instant claimedAt) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO win (id, tenant_id, member_id, target_id, summary, is_public,
                                 claimed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, tenantId);
            ps.setString(3, memberId);
            ps.setString(4, targetId);
            ps.setString(5, summary);
            ps.setBoolean(6, isPublic);
            ps.setTimestamp(7, Timestamp.from(claimedAt));
            ps.executeUpdate();
        }
    }

    private List<String> recentPublicSummaries(Connection db, String tenantId, int limit)
            throws SQLException {
        List<String> summaries = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(RECENT_PUBLIC)) {
            ps.setString(1, tenantId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    summaries.add(rs.getString("summary"));
                }
            }
        }
        return summaries;
    }

    private long statsCount(Connection db, String tenantId, String targetId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(STATS_FOR_TARGET)) {
            ps.setString(1, tenantId);
            ps.setString(2, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("n") : 0L;
            }
        }
    }

    private List<String> winTenants(Connection probe) throws SQLException {
        List<String> tenants = new ArrayList<>();
        try (PreparedStatement ps = probe.prepareStatement("SELECT tenant_id FROM win");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tenants.add(rs.getString(1));
            }
        }
        return tenants;
    }

    private void ensureProbeRole(Connection admin) throws SQLException {
        try (Statement st = admin.createStatement()) {
            st.execute("""
                    DO $$ BEGIN
                        CREATE ROLE %s LOGIN PASSWORD '%s';
                    EXCEPTION WHEN duplicate_object THEN NULL;
                    END $$
                    """.formatted(PROBE_ROLE, PROBE_PASSWORD));
            String schema;
            try (ResultSet rs = st.executeQuery("SELECT current_schema()")) {
                rs.next();
                schema = rs.getString(1);
            }
            st.execute("GRANT USAGE ON SCHEMA \"" + schema + "\" TO " + PROBE_ROLE);
            st.execute("GRANT SELECT ON \"" + schema + "\".win TO " + PROBE_ROLE);
        }
    }

    private void setTenant(Connection conn, String tenantId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('app.current_tenant_id', ?, false)")) {
            ps.setString(1, tenantId);
            ps.execute();
        }
    }

    private void exec(Connection db, String sql, String param) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.executeUpdate();
        }
    }
}
