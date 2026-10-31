package io.kelta.worker.flow;

import io.kelta.runtime.flow.FlowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code flow_pending_resume} has no RLS, so a claim by resume event names its tenant itself: a
 * module task's completion in one tenant must never claim another tenant's parked step. A claim
 * carries its time, the poller takes over claims older than the lease, and only the current
 * claim consumes a row. The SQL runs against Postgres in {@code JdbcFlowStorePendingResumeIntegrationTest}.
 */
@DisplayName("JdbcFlowStore pending-resume claims")
class JdbcFlowStorePendingResumeTest {

    @Test
    @DisplayName("claims one unclaimed row of the tenant with that event, skipping locked rows")
    void claimIsTenantScoped() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("pod-1"), eq("t1"), eq("module-task:x")))
                .thenReturn(List.of("exec-1"));
        JdbcFlowStore store = new JdbcFlowStore(jdbc, new ObjectMapper());

        assertThat(store.claimPendingResumeByEvent("t1", "module-task:x", "pod-1")).contains("exec-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.captor();
        verify(jdbc).queryForList(sql.capture(), eq(String.class), eq("pod-1"), eq("t1"), eq("module-task:x"));
        assertThat(sql.getValue()).contains("claimed_by IS NULL AND tenant_id = ? AND resume_event = ?",
                "FOR UPDATE SKIP LOCKED", "RETURNING execution_id");
    }

    @Test
    @DisplayName("nothing to claim is empty")
    void nothingToClaim() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("pod-1"), eq("t2"), eq("module-task:x")))
                .thenReturn(List.of());

        assertThat(new JdbcFlowStore(jdbc, new ObjectMapper())
                .claimPendingResumeByEvent("t2", "module-task:x", "pod-1")).isEmpty();
    }

    @Test
    @DisplayName("the poller claims due rows and claims older than the lease, stamping claimed_at")
    void claimWithLease() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("poll-1"), eq(300L), eq(25)))
                .thenReturn(List.of("exec-1"));

        assertThat(new JdbcFlowStore(jdbc, new ObjectMapper())
                .claimPendingResumes("poll-1", 25, Duration.ofMinutes(5))).containsExactly("exec-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.captor();
        verify(jdbc).queryForList(sql.capture(), eq(String.class), eq("poll-1"), eq(300L), eq(25));
        assertThat(sql.getValue()).contains("SET claimed_by = ?, claimed_at = NOW()",
                "(claimed_by IS NULL AND resume_at <= NOW())",
                "(claimed_by IS NOT NULL AND claimed_at < NOW() - (? * INTERVAL '1 second'))",
                "FOR UPDATE SKIP LOCKED");
    }

    @Test
    @DisplayName("a resume consumes only the row its claim holds, within the tenant")
    void consumeByClaim() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("exec-1"), eq("t1"), eq("claim-1")))
                .thenReturn(List.of(Map.of("resume_event", "module-task:x")));
        JdbcFlowStore store = new JdbcFlowStore(jdbc, new ObjectMapper());

        assertThat(store.consumePendingResume("t1", "exec-1", "claim-1"))
                .contains(new FlowStore.ConsumedResume("module-task:x"));
        ArgumentCaptor<String> sql = ArgumentCaptor.captor();
        verify(jdbc).queryForList(sql.capture(), eq("exec-1"), eq("t1"), eq("claim-1"));
        assertThat(sql.getValue()).contains("DELETE FROM flow_pending_resume",
                "execution_id = ? AND tenant_id = ? AND claimed_by = ?", "RETURNING resume_event");

        assertThat(store.consumePendingResume("t1", "exec-2", "claim-1")).as("a lost claim").isEmpty();
    }

    @Test
    @DisplayName("a release unclaims the row and makes it due now, only for the claim that held it")
    void release() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        new JdbcFlowStore(jdbc, new ObjectMapper()).releasePendingResume("exec-1", "claim-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.captor();
        verify(jdbc).update(sql.capture(), eq("exec-1"), eq("claim-1"));
        assertThat(sql.getValue()).contains("claimed_by = NULL, claimed_at = NULL",
                "resume_at = LEAST(COALESCE(resume_at, NOW()), NOW())", "WHERE execution_id = ? AND claimed_by = ?");
    }
}
