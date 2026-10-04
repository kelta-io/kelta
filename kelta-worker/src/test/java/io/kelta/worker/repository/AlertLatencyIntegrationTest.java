package io.kelta.worker.repository;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.AlertDeliveryRepository.DeliveryLatencyRow;
import io.kelta.worker.service.availability.AlertLatencySummary;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AlertDeliveryRepository#findSentSince} against real Postgres, with two tenants' alerts
 * side by side.
 *
 * <p>The connection is the container superuser, which never evaluates an RLS policy — so this
 * proves the explicit {@code alert.tenant_id = ?} predicate on its own keeps the other tenant's
 * deliveries out, independent of the parent-alert policy on {@code alert_delivery}.
 *
 * <p>Runs only where Docker is available (Testcontainers), under {@code -Pintegration-tests}.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Alert delivery latency — tenant-scoped query against real Postgres")
class AlertLatencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String TENANT_B = "bbbbbbbb-0000-0000-0000-000000000001";
    static final Instant T0 = Instant.parse("2026-08-01T12:00:00Z");

    static JdbcTemplate jdbc;
    static AlertDeliveryRepository repository;

    @BeforeAll
    static void migrateAndSeed() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
        repository = new AlertDeliveryRepository(jdbc);

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

        // Tenant A: push failed then email sent at +10s; push sent at +4s; a PENDING-only alert;
        // a FAILED-only alert; and an alert older than the window.
        String a1 = alert(TENANT_A, T0);
        delivery(a1, "push", "FAILED", null);
        delivery(a1, "email", "SENT", T0.plusSeconds(10));
        String a2 = alert(TENANT_A, T0);
        delivery(a2, "push", "SENT", T0.plusSeconds(4));
        delivery(alert(TENANT_A, T0), "push", "PENDING", null);
        delivery(alert(TENANT_A, T0), "email", "FAILED", null);
        delivery(alert(TENANT_A, T0.minusSeconds(86_400 * 30)), "push", "SENT",
                T0.minusSeconds(86_400 * 30 - 1));

        // Tenant B: slow deliveries that would drag A's numbers if they leaked in.
        String b1 = alert(TENANT_B, T0);
        delivery(b1, "push", "SENT", T0.plusSeconds(500));
        delivery(b1, "email", "SENT", T0.plusSeconds(900));
    }

    private static String alert(String tenantId, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                        INSERT INTO alert (id, tenant_id, watch_id, target_id, slot_key, episode_id,
                                           created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                id, tenantId, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "2026-08-14", UUID.randomUUID().toString(), Timestamp.from(createdAt));
        return id;
    }

    private static void delivery(String alertId, String channel, String status, Instant sentAt) {
        jdbc.update("""
                        INSERT INTO alert_delivery (id, alert_id, channel, status, sent_at, created_at)
                        VALUES (?, ?, ?, ?, ?, NOW())
                        """,
                UUID.randomUUID().toString(), alertId, channel, status,
                sentAt == null ? null : Timestamp.from(sentAt));
    }

    private static AlertLatencySummary summaryFor(String tenantId, String channel) {
        return TenantContext.callWithTenant(tenantId, () -> AlertLatencySummary.summarize(
                repository.findSentSince(tenantId, T0.minusSeconds(3600), channel), channel));
    }

    @Test
    @DisplayName("a tenant's numbers exclude the other tenant's deliveries")
    void excludesOtherTenant() {
        List<DeliveryLatencyRow> rows = TenantContext.callWithTenant(TENANT_A,
                () -> repository.findSentSince(TENANT_A, T0.minusSeconds(3600), null));
        assertThat(rows).extracting(DeliveryLatencyRow::status).containsOnly("SENT");
        assertThat(rows).hasSize(2);

        AlertLatencySummary a = summaryFor(TENANT_A, null);
        assertThat(a.count()).as("a1 (email after failed push) + a2; PENDING/FAILED/old excluded")
                .isEqualTo(2);
        assertThat(a.p50Seconds()).isEqualTo(7.0);
        assertThat(a.p90Seconds()).isLessThanOrEqualTo(10.0);

        AlertLatencySummary b = summaryFor(TENANT_B, null);
        assertThat(b.count()).isEqualTo(1);
        assertThat(b.p50Seconds()).as("B's alert counts once, at its earliest SENT delivery")
                .isEqualTo(500.0);
    }

    @Test
    @DisplayName("a channel filter stays tenant-scoped")
    void channelFilterIsTenantScoped() {
        AlertLatencySummary email = summaryFor(TENANT_A, "email");
        assertThat(email.count()).isEqualTo(1);
        assertThat(email.p50Seconds()).isEqualTo(10.0);
    }
}
