package io.kelta.worker.flow;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.FlowStepLogging;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * The engine reads each flow's {@code step_logging} through {@link JdbcFlowStore}, and the
 * failing step's snapshots under ERRORS_ONLY/NONE go through the same PLT-601 cap as FULL.
 */
@DisplayName("JdbcFlowStore step logging level")
class JdbcFlowStoreStepLoggingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("reads step_logging from the tenant's flow row")
    void readsStoredLevel() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("SELECT step_logging FROM flow WHERE tenant_id = ? AND id = ?"),
                eq(String.class), eq("t1"), eq("f1"))).thenReturn(List.of("ERRORS_ONLY"));
        JdbcFlowStore store = new JdbcFlowStore(jdbc, objectMapper);

        assertThat(store.findStepLogging("t1", "f1")).isEqualTo(FlowStepLogging.ERRORS_ONLY);
    }

    @Test
    @DisplayName("a missing flow or a failed read falls back to FULL")
    void fallsBackToFull() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("t1"), eq("gone"))).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(String.class), eq("t1"), eq("broken")))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        JdbcFlowStore store = new JdbcFlowStore(jdbc, objectMapper);

        assertThat(store.findStepLogging("t1", "gone")).isEqualTo(FlowStepLogging.FULL);
        assertThat(store.findStepLogging("t1", "broken")).isEqualTo(FlowStepLogging.FULL);
    }

    @Test
    @DisplayName("ERRORS_ONLY: success rows carry no snapshots; the failing row's snapshots are capped")
    void errorsOnlyThroughTheStore() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("step_logging"), eq(String.class), eq("t1"), eq("f1")))
                .thenReturn(List.of("ERRORS_ONLY"));
        JdbcFlowStore store = new JdbcFlowStore(jdbc, objectMapper, 1024, null);

        ActionHandlerRegistry handlers = new ActionHandlerRegistry();
        handlers.register(new ActionHandler() {
            @Override
            public String getActionTypeKey() { return "FAIL_HANDLER"; }

            @Override
            public ActionResult execute(ActionContext context) {
                return ActionResult.failure("nope");
            }
        });
        FlowEngine engine = new FlowEngine(store, handlers, objectMapper, 1);
        try {
            engine.executeSynchronous("t1", "f1", """
                {
                    "StartAt": "Prepare",
                    "States": {
                        "Prepare": { "Type": "Pass", "Next": "Boom" },
                        "Boom": { "Type": "Task", "Resource": "FAIL_HANDLER", "End": true }
                    }
                }
                """, Map.of("catalog", "x".repeat(50_000)), "u1");
        } finally {
            engine.shutdown();
        }

        List<Object[]> stepInserts = mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("update"))
                .filter(i -> ((String) i.getArgument(0)).contains("INSERT INTO flow_step_log"))
                .map(i -> Arrays.copyOfRange(i.getArguments(), 1, i.getArguments().length))
                .map(a -> a.length == 1 && a[0] instanceof Object[] arr ? arr : a)
                .toList();
        assertThat(mockingDetails(jdbc).getInvocations())
                .noneMatch(i -> i.getMethod().getName().equals("update")
                        && ((String) i.getArgument(0)).contains("UPDATE flow_step_log"));

        // Bind order: id, execution_id, state_id, state_name, state_type, status,
        // input_snapshot, output_snapshot, ...
        assertThat(stepInserts).hasSize(2);
        Object[] prepare = stepInserts.get(0);
        assertThat(prepare[2]).isEqualTo("Prepare");
        assertThat(prepare[5]).isEqualTo("SUCCEEDED");
        assertThat(prepare[6]).isNull();
        assertThat(prepare[7]).isNull();

        Object[] boom = stepInserts.get(1);
        assertThat(boom[2]).isEqualTo("Boom");
        assertThat(boom[5]).isEqualTo("FAILED");
        assertThat(boom[6]).asString().contains("\"_truncated\":true").hasSizeLessThan(1024);
        assertThat(boom[7]).asString().contains("\"_truncated\":true").hasSizeLessThan(1024);
    }
}
