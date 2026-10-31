package io.kelta.worker.config;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.FlowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FlowResumePollerConfig")
class FlowResumePollerConfigTest {

    @Mock
    private FlowStore flowStore;

    @Mock
    private FlowEngine flowEngine;

    private FlowResumePollerConfig poller() {
        return new FlowResumePollerConfig(flowStore, flowEngine, 300);
    }

    @Test
    @DisplayName("resumes every claimed execution in claim order, under the cycle's claim and the lease")
    void resumesClaimedExecutions() {
        when(flowStore.claimPendingResumes(anyString(), anyInt(), any())).thenReturn(List.of("exec-1", "exec-2"));

        poller().pollAndResume();

        ArgumentCaptor<String> claim = ArgumentCaptor.captor();
        verify(flowStore).claimPendingResumes(claim.capture(), eq(25), eq(Duration.ofSeconds(300)));
        assertThat(claim.getValue()).startsWith("worker-");
        InOrder order = inOrder(flowEngine);
        order.verify(flowEngine).resumeExecution("exec-1", claim.getValue());
        order.verify(flowEngine).resumeExecution("exec-2", claim.getValue());
    }

    @Test
    @DisplayName("each cycle claims under a new identifier, so a takeover is a different claim")
    void claimPerCycle() {
        when(flowStore.claimPendingResumes(anyString(), anyInt(), any())).thenReturn(List.of());
        FlowResumePollerConfig poller = poller();

        poller.pollAndResume();
        poller.pollAndResume();

        ArgumentCaptor<String> claims = ArgumentCaptor.captor();
        verify(flowStore, times(2)).claimPendingResumes(claims.capture(), anyInt(), any());
        assertThat(claims.getAllValues().get(0)).isNotEqualTo(claims.getAllValues().get(1));
    }

    @Test
    @DisplayName("a resume that throws does not stop the rest of the batch")
    void oneFailureDoesNotStrandTheBatch() {
        when(flowStore.claimPendingResumes(anyString(), anyInt(), any())).thenReturn(List.of("exec-1", "exec-2"));
        doThrow(new IllegalStateException("rejected")).when(flowEngine).resumeExecution(eq("exec-1"), anyString());

        poller().pollAndResume();

        verify(flowEngine).resumeExecution(eq("exec-2"), anyString());
    }

    @Test
    @DisplayName("no due resumes → no engine calls")
    void noDueResumes() {
        when(flowStore.claimPendingResumes(anyString(), anyInt(), any())).thenReturn(List.of());

        poller().pollAndResume();

        verify(flowEngine, never()).resumeExecution(anyString(), anyString());
    }

    @Test
    @DisplayName("a claim failure is swallowed — the poll cycle never throws")
    void claimFailureSwallowed() {
        when(flowStore.claimPendingResumes(anyString(), anyInt(), any())).thenThrow(new RuntimeException("db down"));

        poller().pollAndResume();

        verify(flowEngine, never()).resumeExecution(anyString(), anyString());
    }

    @Test
    @DisplayName("a lease under one second is refused")
    void leaseBound() {
        assertThatThrownBy(() -> new FlowResumePollerConfig(flowStore, flowEngine, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
