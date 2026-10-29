package io.kelta.worker.service.availability;

import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.worker.listener.AvailabilityEventListener;
import io.kelta.worker.repository.AlertDeliveryRepository;
import io.kelta.worker.repository.AlertRepository;
import io.kelta.worker.repository.AvailabilityStateRepository;
import io.kelta.worker.repository.WatchRepository;
import io.kelta.worker.repository.WatchTarget;
import io.kelta.worker.repository.WatchTargetRepository;
import io.kelta.worker.service.availability.SyntheticAvailabilityService.Injection;
import io.kelta.worker.service.availability.SyntheticAvailabilityService.Status;
import io.kelta.worker.service.entitlement.EntitlementService;
import io.kelta.worker.service.push.DefaultPushService;
import io.kelta.worker.service.sms.SmsProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The admin synthetic-event injector against real Postgres: one OPEN observation runs through the
 * production {@link AvailabilityMatchService} and {@link AlertDispatchService}, so each ACTIVE watch
 * on a synthetic target gets one {@code alert} row and one {@code alert_delivery} row. Only the
 * outbound senders (push, email, SMS, webhook) and the entitlement lookup are stubbed.
 *
 * <p>Each test creates its own target, so tests share the container without seeing each other's
 * rows.
 *
 * <p>Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Synthetic availability injection — match and dispatch against real Postgres")
class SyntheticAvailabilityIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT = "dddddddd-0000-0000-0000-000000000001";
    static final String SUBJECT = "kelta.availability.event." + TENANT + ".probe";

    static JdbcTemplate jdbc;
    static WatchTargetRepository targetRepository;
    static AvailabilityEventListener listener;
    static SyntheticAvailabilityService service;

    @BeforeAll
    static void migrateAndWire() {
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

        ObjectMapper objectMapper = new ObjectMapper();
        targetRepository = new WatchTargetRepository(jdbc);
        AvailabilityMatchService matchService = new AvailabilityMatchService(targetRepository,
                new WatchRepository(jdbc), new AvailabilityStateRepository(jdbc),
                new AlertRepository(jdbc), objectMapper, true, 30);
        AlertDispatchService dispatchService = new AlertDispatchService(
                new AlertDeliveryRepository(jdbc), mock(DefaultPushService.class),
                mock(EmailService.class), mock(SmsProvider.class), mock(WebhookAlertSender.class),
                mock(EntitlementService.class), jdbc, objectMapper, new SimpleMeterRegistry());
        listener = new AvailabilityEventListener(matchService, dispatchService,
                mock(PlatformEventPublisher.class), objectMapper);
        service = new SyntheticAvailabilityService(targetRepository, listener);
    }

    private static String target(String externalId, boolean synthetic) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                        INSERT INTO watch_target (id, tenant_id, source, external_id, name, metadata,
                                                  active, synthetic)
                        VALUES (?, ?, 'probe', ?, ?, '{}'::jsonb, true, ?)
                        """,
                id, TENANT, externalId, "Target " + externalId, synthetic);
        return id;
    }

    private static String watch(String targetId, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                        INSERT INTO watch (id, tenant_id, member_id, target_id, criteria, channels,
                                           status)
                        VALUES (?, ?, ?, ?, '{}'::jsonb, '["push"]'::jsonb, ?)
                        """,
                id, TENANT, UUID.randomUUID().toString(), targetId, status);
        return id;
    }

    private static List<String> alertedWatches(String targetId, String slotKey) {
        return jdbc.queryForList(
                "SELECT watch_id FROM alert WHERE tenant_id = ? AND target_id = ? AND slot_key = ?",
                String.class, TENANT, targetId, slotKey);
    }

    private static List<Map<String, Object>> deliveries(String targetId, String slotKey) {
        return jdbc.queryForList("""
                        SELECT a.watch_id, d.channel, d.status FROM alert_delivery d
                          JOIN alert a ON a.id = d.alert_id
                         WHERE a.tenant_id = ? AND a.target_id = ? AND a.slot_key = ?
                        """,
                TENANT, targetId, slotKey);
    }

    private static int stateRows(String targetId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM availability_state WHERE tenant_id = ? AND target_id = ?",
                Integer.class, TENANT, targetId);
    }

    @Test
    @DisplayName("one alert and one delivery row per ACTIVE watch on the synthetic target")
    void oneAlertAndDeliveryPerActiveWatch() {
        String target = target("probe-fanout", true);
        String w1 = watch(target, "ACTIVE");
        String w2 = watch(target, "ACTIVE");
        watch(target, "PAUSED");

        Injection injection = service.inject(TENANT, target, null, null, null, null);

        assertThat(injection.status()).isEqualTo(Status.INJECTED);
        assertThat(injection.slotKey()).startsWith(SyntheticAvailabilityService.SLOT_PREFIX);
        assertThat(injection.alerts()).hasSize(2);
        assertThat(alertedWatches(target, injection.slotKey()))
                .containsExactlyInAnyOrder(w1, w2);
        List<Map<String, Object>> deliveries = deliveries(target, injection.slotKey());
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries).extracting(d -> d.get("watch_id")).containsExactlyInAnyOrder(w1, w2);
        assertThat(deliveries).allSatisfy(d -> {
            assertThat(d.get("channel")).isEqualTo("push");
            assertThat(d.get("status")).isEqualTo(AlertDeliveryRepository.STATUS_SENT);
        });
    }

    @Test
    @DisplayName("with no slotKey, every injection mints a fresh slot and alerts again")
    void freshSlotPerInjectionAlertsAgain() {
        String target = target("probe-canary", true);
        watch(target, "ACTIVE");

        Injection first = service.inject(TENANT, target, null, null, null, null);
        Injection second = service.inject(TENANT, target, null, null, null, null);

        assertThat(second.slotKey()).isNotEqualTo(first.slotKey());
        assertThat(first.alerts()).hasSize(1);
        assertThat(second.alerts()).hasSize(1);
        assertThat(deliveries(target, second.slotKey())).hasSize(1);
    }

    @Test
    @DisplayName("re-injecting an explicit slotKey that is still OPEN does not re-alert")
    void explicitSlotStillOpenDoesNotReAlert() {
        String target = target("probe-dedupe", true);
        watch(target, "ACTIVE");

        Injection first = service.inject(TENANT, target, "2026-08-14", null, null, null);
        Injection second = service.inject(TENANT, target, "2026-08-14", null, null, null);

        assertThat(first.alerts()).hasSize(1);
        assertThat(second.status()).isEqualTo(Status.INJECTED);
        assertThat(second.alerts()).isEmpty();
        assertThat(alertedWatches(target, "2026-08-14")).hasSize(1);
        assertThat(deliveries(target, "2026-08-14")).hasSize(1);
    }

    @Test
    @DisplayName("a real target is refused and nothing is recorded for it")
    void realTargetRefused() {
        String target = target("probe-real", false);
        watch(target, "ACTIVE");

        Injection injection = service.inject(TENANT, target, null, null, null, null);

        assertThat(injection.status()).isEqualTo(Status.TARGET_NOT_SYNTHETIC);
        assertThat(stateRows(target)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert WHERE target_id = ?",
                Integer.class, target)).isZero();
    }

    @Test
    @DisplayName("an unknown target id is reported as not found")
    void unknownTarget() {
        assertThat(service.inject(TENANT, UUID.randomUUID().toString(), null, null, null, null)
                .status()).isEqualTo(Status.TARGET_NOT_FOUND);
    }

    @Test
    @DisplayName("the poller work list excludes synthetic targets")
    void pollerWorkListExcludesSynthetic() {
        String synthetic = target("probe-list-synthetic", true);
        String real = target("probe-list-real", false);

        List<WatchTarget> workList = targetRepository.findActiveBySource(TENANT, "probe");

        assertThat(workList).extracting(WatchTarget::id).contains(real).doesNotContain(synthetic);
        assertThat(workList).noneMatch(WatchTarget::synthetic);
        assertThat(targetRepository.findById(TENANT, synthetic)).get()
                .extracting(WatchTarget::synthetic).isEqualTo(true);
    }

    @Test
    @DisplayName("a poller-reported OPEN for a synthetic target records nothing and alerts no one")
    void pollerEventForSyntheticTargetDropped() {
        String target = target("probe-poller", true);
        watch(target, "ACTIVE");

        listener.handleAvailabilityEvent(SUBJECT, """
                {"source":"probe","targetExternalId":"probe-poller","slotKey":"s1","status":"OPEN"}
                """);

        assertThat(stateRows(target)).isZero();
        assertThat(alertedWatches(target, "s1")).isEmpty();
    }
}
