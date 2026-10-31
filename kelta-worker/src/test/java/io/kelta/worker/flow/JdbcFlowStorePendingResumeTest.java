package io.kelta.worker.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code flow_pending_resume} has no RLS, so a claim by resume event names its tenant itself: a
 * module task's completion in one tenant must never claim another tenant's parked step.
 */
@DisplayName("JdbcFlowStore pending-resume claims by event")
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
    @DisplayName("reads the resume event of an execution's pending row, latest first")
    void findsResumeEvent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("exec-1"))).thenReturn(List.of("module-task:x"));

        assertThat(new JdbcFlowStore(jdbc, new ObjectMapper()).findPendingResumeEvent("exec-1"))
                .contains("module-task:x");
        ArgumentCaptor<String> sql = ArgumentCaptor.captor();
        verify(jdbc).queryForList(sql.capture(), eq(String.class), eq("exec-1"));
        assertThat(sql.getValue()).contains("resume_event IS NOT NULL", "ORDER BY created_at DESC");
    }
}
