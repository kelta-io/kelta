package io.kelta.worker.repository;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.InitialStateBuilder;
import io.kelta.runtime.module.integration.spi.ScriptExecutor;
import io.kelta.worker.service.DataExportService;
import io.kelta.worker.service.FlowActorResolver;
import io.kelta.worker.service.ReportExecutionService;
import io.kelta.worker.service.ScheduledJobExecutorService;
import io.kelta.worker.service.TenantSlugResolver;
import io.kelta.worker.service.email.DefaultEmailService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Scheduled jobs against a real Postgres, polled by several worker "pods" at the same moment:
 * each due slot must run exactly once.
 *
 * <p>The scheduler used to list due jobs with {@code SELECT … FOR UPDATE SKIP LOCKED} and treat
 * that as leader election, then advance {@code next_run_at} only after the job ran. The scheduler
 * thread has no tenant bound, so the query ran in autocommit and its row locks were gone the moment
 * it returned: two pods whose polls landed a few milliseconds apart both listed — and both ran —
 * every due job. A Mockito test cannot see that; it needs concurrent sessions on a real database.
 * Here every pod is held at a barrier right after listing, so all of them hold the same candidate
 * list before any of them claims — the exact interleaving that double-fired production flows.
 *
 * <p>Runs only where Docker is available (Testcontainers), under {@code -Pintegration-tests}.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("scheduled_job slot claim across worker pods")
class ScheduledJobClaimIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT = "bbbbbbbb-0000-0000-0000-000000000001";
    static final String CRON = "0 0 4 1 * *";
    static final int PODS = 4;
    static final int JOBS = 10;

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAndSeed() {
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

        jdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", TENANT, "tenant-a", "tenant-a");
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM job_execution_log");
        jdbc.update("DELETE FROM scheduled_job");
        jdbc.update("DELETE FROM flow WHERE tenant_id = ?", TENANT);
    }

    @Test
    @DisplayName("pods polling at the same moment start each due flow exactly once")
    void eachDueSlotRunsOnceAcrossPods() throws Exception {
        List<String> jobIds = new ArrayList<>();
        for (int i = 0; i < JOBS; i++) {
            jobIds.add(seedDueFlowJob("Scheduled flow " + i, "now() - interval '1 minute'"));
        }

        Map<String, AtomicInteger> startsByFlow = new ConcurrentHashMap<>();
        FlowEngine flowEngine = mock(FlowEngine.class);
        when(flowEngine.startExecution(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String flowId = invocation.getArgument(1);
                    startsByFlow.computeIfAbsent(flowId, k -> new AtomicInteger()).incrementAndGet();
                    return UUID.randomUUID().toString();
                });

        CyclicBarrier allListed = new CyclicBarrier(PODS);
        List<Integer> listedPerPod = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pods = Executors.newFixedThreadPool(PODS);
        List<Future<?>> polls = new ArrayList<>();
        try {
            for (int p = 0; p < PODS; p++) {
                ScheduledJobRepository podRepository = new ScheduledJobRepository(jdbc) {
                    @Override
                    public List<Map<String, Object>> findDueJobs() {
                        List<Map<String, Object>> due = super.findDueJobs();
                        listedPerPod.add(due.size());
                        try {
                            allListed.await(30, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        return due;
                    }
                };
                ScheduledJobExecutorService pod = executorFor(podRepository, flowEngine);
                polls.add(pods.submit(pod::executeAll));
            }
            for (Future<?> poll : polls) {
                poll.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pods.shutdownNow();
        }

        // Listing is not claiming: every pod saw every due job.
        assertThat(listedPerPod).hasSize(PODS).allMatch(n -> n == JOBS);
        // Yet each slot ran on exactly one pod.
        assertThat(startsByFlow).hasSize(JOBS);
        assertThat(startsByFlow.values()).allSatisfy(starts -> assertThat(starts.get()).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_execution_log", Integer.class)).isEqualTo(JOBS);
        for (String jobId : jobIds) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT next_run_at, last_status FROM scheduled_job WHERE id = ?", jobId);
            assertThat(((Timestamp) row.get("next_run_at")).toInstant()).isAfter(Instant.now());
            assertThat(row.get("last_status")).isEqualTo("SUCCESS");
        }
    }

    @Test
    @DisplayName("the slot read can be claimed once; a second claim of it matches nothing")
    void aSlotIsClaimedOnce() {
        // A sub-second value proves the claim's equality check survives the JDBC round trip.
        String jobId = seedDueFlowJob("Sub-second slot", "timestamptz '2026-10-01 04:00:00.123456+00'");
        ScheduledJobRepository repository = new ScheduledJobRepository(jdbc);
        Instant scheduledFor = slotOf(repository.findDueJobs(), jobId);
        Instant next = ScheduledJobRepository.calculateNextRunAt(CRON, "UTC");

        assertThat(repository.claim(jobId, scheduledFor, next)).isTrue();
        assertThat(repository.claim(jobId, scheduledFor, next)).isFalse();
        assertThat(nextRunAt(jobId)).isEqualTo(next);
    }

    @Test
    @DisplayName("a schedule edit or a pause between listing and claiming wins over the claim")
    void aConcurrentEditBeatsTheClaim() {
        ScheduledJobRepository repository = new ScheduledJobRepository(jdbc);
        String edited = seedDueFlowJob("Edited", "now() - interval '1 minute'");
        String paused = seedDueFlowJob("Paused", "now() - interval '1 minute'");
        List<Map<String, Object>> due = repository.findDueJobs();
        Instant editedSlot = slotOf(due, edited);
        Instant pausedSlot = slotOf(due, paused);

        Instant rescheduled = Instant.parse("2099-01-01T00:00:00Z");
        repository.updateNextRunAt(edited, rescheduled);
        repository.pause(paused, TENANT);

        Instant next = ScheduledJobRepository.calculateNextRunAt(CRON, "UTC");
        assertThat(repository.claim(edited, editedSlot, next)).isFalse();
        assertThat(repository.claim(paused, pausedSlot, next)).isFalse();
        assertThat(nextRunAt(edited)).isEqualTo(rescheduled);
        assertThat(nextRunAt(paused)).isNull();
    }

    @Test
    @DisplayName("recording a run's outcome leaves the next_run_at the claim set")
    void recordingTheOutcomeKeepsTheClaimedNextRun() {
        String jobId = seedDueFlowJob("Outcome", "now() - interval '1 minute'");
        ScheduledJobRepository repository = new ScheduledJobRepository(jdbc);
        Instant next = ScheduledJobRepository.calculateNextRunAt(CRON, "UTC");
        assertThat(repository.claim(jobId, slotOf(repository.findDueJobs(), jobId), next)).isTrue();

        repository.updateAfterExecution(jobId, "SUCCESS", Instant.now());

        assertThat(nextRunAt(jobId)).isEqualTo(next);
    }

    private String seedDueFlowJob(String name, String nextRunAtSql) {
        String flowId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO flow (id, tenant_id, name, flow_type, active, definition, trigger_config)
                VALUES (?, ?, ?, 'SCHEDULED', true, '{"StartAt":"Start"}'::jsonb, ?::jsonb)
                """, flowId, TENANT, name, "{\"cron\":\"" + CRON + "\"}");
        String jobId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO scheduled_job (id, tenant_id, name, job_type, job_reference_id, "
                        + "cron_expression, timezone, active, next_run_at) "
                        + "VALUES (?, ?, ?, 'FLOW', ?, ?, 'UTC', true, " + nextRunAtSql + ")",
                jobId, TENANT, name, flowId, CRON);
        return jobId;
    }

    private static Instant slotOf(List<Map<String, Object>> due, String jobId) {
        return due.stream()
                .filter(row -> jobId.equals(row.get("id")))
                .map(row -> ((Timestamp) row.get("next_run_at")).toInstant())
                .findFirst()
                .orElseThrow(() -> new AssertionError("job " + jobId + " was not listed as due"));
    }

    private static Instant nextRunAt(String jobId) {
        Timestamp value = jdbc.queryForObject(
                "SELECT next_run_at FROM scheduled_job WHERE id = ?", Timestamp.class, jobId);
        return value != null ? value.toInstant() : null;
    }

    @SuppressWarnings("unchecked")
    private static ScheduledJobExecutorService executorFor(ScheduledJobRepository repository,
                                                           FlowEngine flowEngine) {
        TenantSlugResolver slugs = mock(TenantSlugResolver.class);
        when(slugs.resolveSlug(anyString())).thenReturn(Optional.of("tenant-a"));
        ObjectProvider<DefaultEmailService> email = mock(ObjectProvider.class);
        return new ScheduledJobExecutorService(
                repository, flowEngine, mock(FlowActorResolver.class), new InitialStateBuilder(),
                new ObjectMapper(), mock(ScriptExecutor.class), mock(ReportExecutionService.class),
                mock(DataExportService.class), mock(DataExportRepository.class), slugs, email);
    }
}
