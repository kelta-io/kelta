package io.kelta.worker.flow;

import io.kelta.runtime.flow.FlowStore.ConsumedResume;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code flow_pending_resume} claim SQL of {@link JdbcFlowStore} against a real Postgres with
 * the real Flyway migrations (V223 adds {@code claimed_at}): the poller takes over a claim older
 * than the lease, whatever the row's {@code resume_at}; only the current claim consumes a row, so a
 * resume that lost its claim does nothing; a released claim is due at once; claims and consumes
 * stay within their tenant. Runs only where Docker is available (Testcontainers), under {@code mvn verify}.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("flow_pending_resume claims, lease and consume (Postgres)")
class JdbcFlowStorePendingResumeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT_A = "cccccccc-0000-0000-0000-000000000001";
    static final String TENANT_B = "cccccccc-0000-0000-0000-000000000002";
    static final Duration LEASE = Duration.ofMinutes(5);

    static JdbcTemplate jdbc;
    static JdbcFlowStore store;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholderReplacement(false)
                .load()
                .migrate();
        for (String tenant : List.of(TENANT_A, TENANT_B)) {
            jdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", tenant, "resume-" + tenant.substring(35),
                    "resume");
            jdbc.update("INSERT INTO flow (id, tenant_id, name, flow_type, definition) VALUES (?, ?, ?, ?, '{}'::jsonb)",
                    flowId(tenant), tenant, "resume", "AUTOLAUNCHED");
        }
        store = new JdbcFlowStore(jdbc, new ObjectMapper());
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM flow_pending_resume");
        jdbc.update("DELETE FROM flow_execution");
    }

    private static String flowId(String tenant) {
        return tenant.replace("cccccccc", "dddddddd");
    }

    private static String waiting(String tenant) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO flow_execution (id, tenant_id, flow_id, status) VALUES (?, ?, ?, 'WAITING')",
                id, tenant, flowId(tenant));
        return id;
    }

    private static Map<String, Object> row(String executionId) {
        return jdbc.queryForMap("SELECT claimed_by, claimed_at, resume_at FROM flow_pending_resume WHERE execution_id = ?",
                executionId);
    }

    private static void ageClaim(String executionId, Duration by) {
        jdbc.update("UPDATE flow_pending_resume SET claimed_at = claimed_at - (? * INTERVAL '1 second') "
                + "WHERE execution_id = ?", by.toSeconds(), executionId);
    }

    @Test
    @DisplayName("a delivered task's claim within its lease is left alone; past it the poller takes it over")
    void staleClaimIsTakenOver() {
        String execution = waiting(TENANT_A);
        store.createPendingResume(execution, TENANT_A, Instant.now().plus(Duration.ofHours(2)), "module-task:a");

        assertThat(store.claimPendingResumeByEvent(TENANT_A, "module-task:a", "slow")).contains(execution);
        assertThat(row(execution).get("claimed_at")).as("a claim records its time").isNotNull();
        assertThat(store.claimPendingResumes("poller-1", 25, LEASE)).as("within the lease, not before expiry")
                .isEmpty();

        ageClaim(execution, Duration.ofMinutes(6));
        assertThat(store.claimPendingResumes("poller-2", 25, LEASE)).containsExactly(execution);
        assertThat(row(execution).get("claimed_by")).isEqualTo("poller-2");

        assertThat(store.consumePendingResume(TENANT_A, execution, "slow")).as("the lost claim consumes nothing")
                .isEmpty();
        assertThat(store.consumePendingResume(TENANT_A, execution, "poller-2"))
                .contains(new ConsumedResume("module-task:a"));
        assertThat(store.consumePendingResume(TENANT_A, execution, "poller-2")).as("once").isEmpty();
    }

    @Test
    @DisplayName("due unclaimed rows are claimed, future ones are not; a row claimed before V223 is never taken over")
    void dueAndLegacy() {
        String due = waiting(TENANT_A);
        String later = waiting(TENANT_A);
        String legacy = waiting(TENANT_B);
        store.createPendingResume(due, TENANT_A, Instant.now().minusSeconds(1), null);
        store.createPendingResume(later, TENANT_A, Instant.now().plus(Duration.ofHours(1)), null);
        store.createPendingResume(legacy, TENANT_B, Instant.now().minusSeconds(3600), null);
        jdbc.update("UPDATE flow_pending_resume SET claimed_by = 'old-pod', claimed_at = NULL WHERE execution_id = ?",
                legacy);

        assertThat(store.claimPendingResumes("poller-1", 25, LEASE)).containsExactly(due);
        assertThat(store.consumePendingResume(TENANT_A, due, "poller-1")).contains(new ConsumedResume(null));
        assertThat(row(legacy).get("claimed_by")).isEqualTo("old-pod");
    }

    @Test
    @DisplayName("a released claim is unclaimed and due now; another claimer's row is left alone")
    void release() {
        String execution = waiting(TENANT_A);
        store.createPendingResume(execution, TENANT_A, Instant.now().plus(Duration.ofHours(2)), "module-task:r");
        store.claimPendingResumeByEvent(TENANT_A, "module-task:r", "claim-1");

        store.releasePendingResume(execution, "claim-2");
        assertThat(row(execution).get("claimed_by")).isEqualTo("claim-1");

        store.releasePendingResume(execution, "claim-1");
        Map<String, Object> released = row(execution);
        assertThat(released.get("claimed_by")).isNull();
        assertThat(released.get("claimed_at")).isNull();
        assertThat(((Timestamp) released.get("resume_at")).toInstant()).isBeforeOrEqualTo(Instant.now());
        assertThat(store.claimPendingResumes("poller-1", 25, LEASE)).containsExactly(execution);
    }

    @Test
    @DisplayName("claims and consumes stay within their tenant")
    void tenantScoped() {
        String execution = waiting(TENANT_A);
        store.createPendingResume(execution, TENANT_A, null, "approval.granted");

        assertThat(store.claimPendingResumeByEvent(TENANT_B, "approval.granted", "claim-b")).isEmpty();
        assertThat(store.claimPendingResumeByEvent(TENANT_A, "approval.granted", "claim-a")).contains(execution);
        assertThat(store.consumePendingResume(TENANT_B, execution, "claim-a")).isEmpty();
        assertThat(store.consumePendingResume(TENANT_B, execution, null)).isEmpty();
        assertThat(store.consumePendingResume(TENANT_A, execution, null)).as("a resume without a claim")
                .contains(new ConsumedResume("approval.granted"));
        assertThat(Optional.ofNullable(jdbc.queryForObject("SELECT count(*) FROM flow_pending_resume", Integer.class)))
                .contains(0);
    }
}
