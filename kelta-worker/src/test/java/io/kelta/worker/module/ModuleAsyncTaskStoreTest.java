package io.kelta.worker.module;

import io.kelta.runtime.flow.AwaitedTaskOutcomes.TaskOutcome;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ActionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.kelta.worker.module.ExternalModuleFixtures.TENANT;
import static io.kelta.worker.module.ExternalModuleFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ModuleAsyncTaskStore")
class ModuleAsyncTaskStoreTest {

    private static final String ID = "00000000-0000-4000-8000-000000000001";
    private static final String KEY = "module-task:" + ID;
    private static final String TOKEN = "kmt_" + "C".repeat(43);

    private final ExternalModuleFixtures.MutableClock clock = new ExternalModuleFixtures.MutableClock();
    private JdbcTemplate jdbc;
    private ModuleAsyncTaskStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new ModuleAsyncTaskStore(jdbc, new ObjectMapper(), 86_400, clock);
    }

    private static ActionContext step(int attempt) {
        return ActionContext.builder().tenantId(TENANT).executionLogId("exec-1").stepName("Ingest").attempt(attempt)
                .build();
    }

    @Test
    @DisplayName("issues kmt_ + 43 base64url characters, stores only its SHA-256, bound to install, execution, step and attempt")
    void issue() {
        ModuleAsyncTaskStore.Issued issued = store.issue(row(TenantModuleData.STATUS_ACTIVE), "ping", step(2), 3600);

        assertThat(issued.token()).matches("^kmt_[A-Za-z0-9_-]{43}$");
        assertThat(ModuleAsyncTaskStore.wellFormed(issued.token())).isTrue();
        assertThat(issued.expiresAt()).isEqualTo(clock.instant().plusSeconds(3600));
        assertThat(issued.resumeKey()).isEqualTo("module-task:" + issued.id());
        assertThat(issued.toString()).doesNotContain(issued.token());

        // The plaintext token is no argument: only its hash is stored.
        verify(jdbc).update(contains("INSERT INTO module_async_task"), eq(issued.id()), eq(TENANT),
                eq(ExternalModuleFixtures.INSTALL), eq("exec-1"), eq("Ingest"), eq(2), eq("ping"),
                eq(ModuleAsyncTaskStore.hash(issued.token())), eq("PENDING"), eq(Timestamp.from(issued.expiresAt())));
        assertThat(ModuleAsyncTaskStore.hash(issued.token())).hasSize(64);
    }

    @Test
    @DisplayName("expiry is whole seconds, so the request and a heartbeat (read back from the column) agree")
    void expiryIsWholeSeconds() {
        clock.advance(Duration.ofNanos(198_012_175));

        assertThat(store.issue(row(TenantModuleData.STATUS_ACTIVE), "ping", step(1), 60).expiresAt())
                .isEqualTo(Instant.parse("2026-10-30T12:01:00Z"));
    }

    @Test
    @DisplayName("each token is new")
    void tokensDiffer() {
        String a = store.issue(row(TenantModuleData.STATUS_ACTIVE), "ping", step(1), 60).token();
        String b = store.issue(row(TenantModuleData.STATUS_ACTIVE), "ping", step(1), 60).token();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("the manifest's maxDurationSeconds is capped by the platform limit")
    void expiryCapped() {
        ModuleAsyncTaskStore capped = new ModuleAsyncTaskStore(jdbc, new ObjectMapper(), 600, clock);
        assertThat(capped.issue(row(TenantModuleData.STATUS_ACTIVE), "ping", step(1), 604_800).expiresAt())
                .isEqualTo(clock.instant().plus(Duration.ofSeconds(600)));
        assertThat(capped.issue(row(TenantModuleData.STATUS_ACTIVE), "ping", step(1), 30).expiresAt())
                .isEqualTo(clock.instant().plusSeconds(30));
        assertThatThrownBy(() -> new ModuleAsyncTaskStore(jdbc, new ObjectMapper(), 0, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a completion is one conditional update: pending, unexpired, this tenant and install, by hash")
    void complete() {
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any(), any(), any())).thenReturn(List.of(ID));

        Optional<String> key = store.complete(TENANT, "install-1", TOKEN, Map.of("success", true));

        assertThat(key).contains(KEY);
        ArgumentCaptor<String> sql = ArgumentCaptor.captor();
        verify(jdbc).queryForList(sql.capture(), eq(String.class), eq("{\"success\":true}"),
                eq(ModuleAsyncTaskStore.hash(TOKEN)), eq(TENANT), eq("install-1"));
        assertThat(sql.getValue()).contains("SET status = 'COMPLETED'", "token_hash = ?", "tenant_id = ?",
                "tenant_module_id = ?", "status = 'PENDING'", "expires_at > now()", "RETURNING id");
    }

    @Test
    @DisplayName("no matching row (unknown, foreign, reused, expired) is empty; a malformed token never reaches the database")
    void completeRefused() {
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any(), any(), any())).thenReturn(List.of());
        assertThat(store.complete(TENANT, "install-1", TOKEN, Map.of("success", true))).isEmpty();

        JdbcTemplate untouched = mock(JdbcTemplate.class);
        ModuleAsyncTaskStore strict = new ModuleAsyncTaskStore(untouched, new ObjectMapper(), 60, clock);
        for (String bad : new String[] {null, "", "kmt_short", "klt_" + "C".repeat(43), TOKEN + "x", "kmt_" + "C".repeat(42) + "="}) {
            assertThat(strict.complete(TENANT, "install-1", bad, Map.of())).isEmpty();
            assertThat(strict.heartbeat(TENANT, "install-1", bad)).isEmpty();
        }
        verifyNoInteractions(untouched);
    }

    @Test
    @DisplayName("a heartbeat touches only a pending, unexpired task of this tenant and install, and answers its expiry")
    void heartbeat() {
        Instant expires = Instant.parse("2026-11-01T00:00:00Z");
        when(jdbc.queryForList(anyString(), eq(Timestamp.class), any(), any(), any()))
                .thenReturn(List.of(Timestamp.from(expires)));

        assertThat(store.heartbeat(TENANT, "install-1", TOKEN)).contains(expires);
        ArgumentCaptor<String> sql = ArgumentCaptor.captor();
        verify(jdbc).queryForList(sql.capture(), eq(Timestamp.class), eq(ModuleAsyncTaskStore.hash(TOKEN)),
                eq(TENANT), eq("install-1"));
        assertThat(sql.getValue()).contains("last_heartbeat_at = now()", "status = 'PENDING'", "expires_at > now()")
                .doesNotContain("COMPLETED");
    }

    @Test
    @DisplayName("delivered: a COMPLETED row of this tenant")
    void delivered() {
        when(jdbc.queryForObject(contains("status = 'COMPLETED'"), eq(Integer.class), eq(ID), eq(TENANT))).thenReturn(1);
        assertThat(store.delivered(TENANT, KEY)).isTrue();
        assertThat(store.delivered("tenant-2", KEY)).isFalse();
        assertThat(store.delivered(TENANT, "approval.granted")).as("not a task key").isFalse();
        assertThat(store.delivered(TENANT, "module-task:not-a-uuid")).isFalse();
    }

    @Test
    @DisplayName("settle with no outcome closes the task EXPIRED and times the step out as ModuleTimeoutException")
    void settleExpires() {
        when(jdbc.queryForList(contains("SET status = 'EXPIRED'"), eq(ID), eq(TENANT))).thenReturn(List.of(Map.of(
                "attempt", 2, "action_key", "ingest",
                "created_at", Timestamp.from(Instant.parse("2026-10-31T00:00:00Z")),
                "expires_at", Timestamp.from(Instant.parse("2026-10-31T01:00:00Z")))));

        TaskOutcome outcome = store.settle(TENANT, KEY).orElseThrow();

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("ModuleTimeoutException");
        assertThat(outcome.errorMessage()).isEqualTo("The module action 'ingest' did not complete its task within 3600 s");
        assertThat(outcome.attempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("settle of a completed task: success carries the output, failure is ActionFailed with the message")
    void settleCompleted() {
        when(jdbc.queryForList(contains("SET status = 'EXPIRED'"), eq(ID), eq(TENANT))).thenReturn(List.of());
        when(jdbc.queryForList(contains("SELECT status"), eq(ID), eq(TENANT))).thenReturn(List.of(Map.of(
                "status", "COMPLETED", "outcome", "{\"success\":true,\"output\":{\"rows\":42}}", "attempt", 1,
                "action_key", "ingest")));
        TaskOutcome success = store.settle(TENANT, KEY).orElseThrow();
        assertThat(success.success()).isTrue();
        assertThat(success.output()).containsEntry("rows", 42);

        when(jdbc.queryForList(contains("SELECT status"), eq(ID), eq(TENANT))).thenReturn(List.of(Map.of(
                "status", "COMPLETED", "outcome", "{\"success\":false,\"errorMessage\":\"EXPORT_GONE: 404\"}",
                "attempt", 3, "action_key", "ingest")));
        TaskOutcome failure = store.settle(TENANT, KEY).orElseThrow();
        assertThat(failure.success()).isFalse();
        assertThat(failure.errorCode()).isEqualTo("ActionFailed");
        assertThat(failure.errorMessage()).isEqualTo("EXPORT_GONE: 404");
        assertThat(failure.attempt()).isEqualTo(3);
    }

    @Test
    @DisplayName("settle of an already-expired row times out; of no row, or another key shape, is empty")
    void settleOther() {
        when(jdbc.queryForList(contains("SET status = 'EXPIRED'"), eq(ID), eq(TENANT))).thenReturn(List.of());
        when(jdbc.queryForList(contains("SELECT status"), eq(ID), eq(TENANT))).thenReturn(List.of(Map.of(
                "status", "EXPIRED", "attempt", 1, "action_key", "ingest")));
        assertThat(store.settle(TENANT, KEY).orElseThrow().errorCode()).isEqualTo("ModuleTimeoutException");

        when(jdbc.queryForList(contains("SELECT status"), eq(ID), eq(TENANT))).thenReturn(List.of());
        assertThat(store.settle(TENANT, KEY)).isEmpty();
        assertThat(store.settle(TENANT, "approval.granted")).isEmpty();
    }

    @Test
    @DisplayName("discard deletes the row within the tenant")
    void discard() {
        store.discard(TENANT, new ModuleAsyncTaskStore.Issued(ID, TOKEN, Instant.now()));
        verify(jdbc).update("DELETE FROM module_async_task WHERE id = ? AND tenant_id = ?", ID, TENANT);
    }
}
