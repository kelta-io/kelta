package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.runtime.workflow.HookConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FlowDeletionGuardHook Tests")
class FlowDeletionGuardHookTest {

    private static final String FLOW_ID = "flow-1";
    private static final String TENANT = "tenant-1";

    @Mock private JdbcTemplate jdbcTemplate;

    private FlowDeletionGuardHook hook;

    @BeforeEach
    void setUp() {
        hook = new FlowDeletionGuardHook(jdbcTemplate);
    }

    private void activeExecutions(int count) {
        when(jdbcTemplate.queryForObject(FlowDeletionGuardHook.ACTIVE_EXECUTIONS_SQL, Integer.class,
                FLOW_ID, "RUNNING", "WAITING")).thenReturn(count);
    }

    @Test
    @DisplayName("guards the flows collection ahead of its other hooks")
    void targetsFlows() {
        assertThat(hook.getCollectionName()).isEqualTo("flows");
        assertThat(hook.getOrder()).isLessThan(new FlowDefinitionValidationHook(JsonMapper.builder().build()).getOrder());
    }

    @Test
    @DisplayName("refuses with 409 FLOW_HAS_ACTIVE_EXECUTIONS while an execution is RUNNING or WAITING")
    void refusesWhileActive() {
        activeExecutions(2);

        assertThatThrownBy(() -> hook.beforeDelete("flows", FLOW_ID, TENANT))
                .isInstanceOfSatisfying(HookConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("FLOW_HAS_ACTIVE_EXECUTIONS");
                    assertThat(e.getMessage()).contains("2 running or waiting execution(s)");
                    assertThat(e.getMeta()).containsEntry("activeExecutions", 2);
                });
    }

    @Test
    @DisplayName("allows the delete when every execution has finished")
    void allowsWhenOnlyFinished() {
        activeExecutions(0);

        BeforeSaveResult result = hook.beforeDelete("flows", FLOW_ID, TENANT);

        assertThat(result.isSuccess()).isTrue();
        verify(jdbcTemplate).queryForObject(eq(FlowDeletionGuardHook.ACTIVE_EXECUTIONS_SQL),
                eq(Integer.class), eq(FLOW_ID), eq("RUNNING"), eq("WAITING"));
    }

    @Test
    @DisplayName("counts by flow id alone, matching the cascade, even when no tenant is bound")
    void countsWithoutTenant() {
        activeExecutions(1);

        assertThatThrownBy(() -> hook.beforeDelete("flows", FLOW_ID, null))
                .isInstanceOf(HookConflictException.class);
        assertThat(FlowDeletionGuardHook.ACTIVE_EXECUTIONS_SQL).doesNotContain("tenant_id");
    }

    @Test
    @DisplayName("passes a delete with no id through untouched")
    void nullId() {
        assertThat(hook.beforeDelete("flows", null, TENANT).isSuccess()).isTrue();
        verifyNoInteractions(jdbcTemplate);
    }
}
