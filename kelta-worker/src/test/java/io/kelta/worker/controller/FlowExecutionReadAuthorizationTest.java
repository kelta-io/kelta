package io.kelta.worker.controller;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.FlowExecutionData;
import io.kelta.runtime.flow.FlowStepLogData;
import io.kelta.runtime.flow.FlowStore;
import io.kelta.runtime.flow.InitialStateBuilder;
import io.kelta.worker.repository.FlowRepository;
import io.kelta.worker.service.FlowActorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The reads of {@link FlowExecutionController}. The gateway leaves every GET under
 * {@code /api/flows/**} at {@code API_ACCESS} and the record advice does not filter these bodies,
 * so the controller is the only check: {@code MANAGE_WORKFLOWS} or {@code VIEW_SETUP} reads every
 * execution, anyone else only one they started.
 */
@DisplayName("FlowExecutionController reads")
class FlowExecutionReadAuthorizationTest {

    private static final String EXECUTION_ID = "exec-1";
    private static final String FLOW_ID = "flow-1";
    private static final String RECORD_ID = "rec-1";
    private static final String STARTER = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_USER = "22222222-2222-2222-2222-222222222222";

    private static final CallerContext STARTER_MEMBER =
            new CallerContext(STARTER, CallerContext.UserType.PORTAL, false, false);
    private static final CallerContext OTHER_MEMBER =
            new CallerContext(OTHER_USER, CallerContext.UserType.PORTAL, false, false);
    private static final CallerContext OTHER_STAFF =
            new CallerContext(OTHER_USER, CallerContext.UserType.INTERNAL, true, true);

    private FlowStore flowStore;
    private FlowRepository flowRepository;
    private final MockHttpServletRequest request = AdminPermissionGuardFixture.request();

    @BeforeEach
    void setUp() {
        flowStore = mock(FlowStore.class);
        flowRepository = mock(FlowRepository.class);
        when(flowStore.loadExecution(EXECUTION_ID)).thenReturn(Optional.of(execution(STARTER)));
        when(flowStore.loadStepLogs(EXECUTION_ID)).thenReturn(List.of(step()));
    }

    private FlowExecutionController controller(AdminPermissionGuard guard) {
        return new FlowExecutionController(mock(FlowEngine.class), flowStore, mock(InitialStateBuilder.class),
                flowRepository, mock(FlowActorResolver.class), guard);
    }

    private FlowExecutionController member() {
        return controller(AdminPermissionGuardFixture.apiAccessOnly());
    }

    private static <T> T as(CallerContext caller, Supplier<T> call) {
        return CallerContext.callAs(caller, call::get);
    }

    @Nested
    @DisplayName("GET /executions/{id} and /steps")
    class ExecutionDetail {

        @Test
        @DisplayName("a member reads the execution they started, state and steps included")
        void starterReadsOwnExecution() {
            ResponseEntity<Map<String, Object>> detail =
                    as(STARTER_MEMBER, () -> member().getExecution(EXECUTION_ID, request));
            ResponseEntity<Map<String, Object>> steps =
                    as(STARTER_MEMBER, () -> member().getExecutionSteps(EXECUTION_ID, request));

            assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(attributes(detail)).containsEntry("startedBy", STARTER).containsKey("stateData");
            assertThat(steps.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((List<?>) steps.getBody().get("data")).hasSize(1);
        }

        @Test
        @DisplayName("the starter id matches case-insensitively")
        void starterMatchIgnoresCase() {
            CallerContext upper = new CallerContext(STARTER.toUpperCase(), CallerContext.UserType.PORTAL, false, false);
            assertThat(as(upper, () -> member().getExecution(EXECUTION_ID, request)).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("another member's execution is a 404 and its steps are never loaded")
        void otherMembersExecutionIsNotFound() {
            assertThat(as(OTHER_MEMBER, () -> member().getExecution(EXECUTION_ID, request)).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(as(OTHER_MEMBER, () -> member().getExecutionSteps(EXECUTION_ID, request)).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            verify(flowStore, never()).loadStepLogs(any());
        }

        @Test
        @DisplayName("VIEW_ALL_DATA does not widen execution reads; only the flow permissions do")
        void viewAllDataDoesNotWiden() {
            assertThat(as(OTHER_STAFF, () -> member().getExecution(EXECUTION_ID, request)).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("no bound caller is a 404, even for an execution with no starter")
        void noCallerIsNotFound() {
            when(flowStore.loadExecution(EXECUTION_ID)).thenReturn(Optional.of(execution(null)));

            assertThat(member().getExecution(EXECUTION_ID, request).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("MANAGE_WORKFLOWS or VIEW_SETUP reads any execution")
        void flowPermissionsReadAll() {
            for (String permission : List.of("MANAGE_WORKFLOWS", "VIEW_SETUP")) {
                FlowExecutionController admin = controller(AdminPermissionGuardFixture.granting(permission));

                assertThat(as(OTHER_STAFF, () -> admin.getExecution(EXECUTION_ID, request)).getStatusCode())
                        .as(permission).isEqualTo(HttpStatus.OK);
                assertThat(as(OTHER_STAFF, () -> admin.getExecutionSteps(EXECUTION_ID, request)).getStatusCode())
                        .as(permission).isEqualTo(HttpStatus.OK);
            }
        }

        @Test
        @DisplayName("a missing execution is still a 404 for an administrator")
        void missingExecutionIsNotFound() {
            when(flowStore.loadExecution("missing")).thenReturn(Optional.empty());
            FlowExecutionController admin = controller(AdminPermissionGuardFixture.granting("MANAGE_WORKFLOWS"));

            assertThat(admin.getExecution("missing", request).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("GET /record-executions")
    class RecordExecutions {

        @Test
        @DisplayName("a member sees only the executions they started on the record")
        void memberSeesOwnOnly() {
            when(flowRepository.findExecutionsByTriggerRecordStartedBy(RECORD_ID, STARTER, 20))
                    .thenReturn(List.of(Map.of("id", EXECUTION_ID, "flow_id", FLOW_ID, "status", "COMPLETED")));

            ResponseEntity<Map<String, Object>> response =
                    as(STARTER_MEMBER, () -> member().listExecutionsByRecord(RECORD_ID, 20, request));

            assertThat((List<?>) response.getBody().get("data")).hasSize(1);
            verify(flowRepository, never()).findExecutionsByTriggerRecord(anyString(), anyInt());
        }

        @Test
        @DisplayName("no bound caller gets an empty list without a query")
        void noCallerGetsNothing() {
            ResponseEntity<Map<String, Object>> response = member().listExecutionsByRecord(RECORD_ID, 20, request);

            assertThat((List<?>) response.getBody().get("data")).isEmpty();
            verifyNoInteractions(flowRepository);
        }

        @Test
        @DisplayName("VIEW_SETUP sees every execution on the record")
        void viewSetupSeesAll() {
            when(flowRepository.findExecutionsByTriggerRecord(RECORD_ID, 20)).thenReturn(List.of(
                    Map.of("id", "exec-a"), Map.of("id", "exec-b")));

            ResponseEntity<Map<String, Object>> response = as(OTHER_STAFF, () ->
                    controller(AdminPermissionGuardFixture.granting("VIEW_SETUP"))
                            .listExecutionsByRecord(RECORD_ID, 20, request));

            assertThat((List<?>) response.getBody().get("data")).hasSize(2);
            verify(flowRepository, never()).findExecutionsByTriggerRecordStartedBy(any(), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("GET /{flowId}/flow-executions and versions")
    class SetupReads {

        @Test
        @DisplayName("a caller with API_ACCESS only is refused, before any lookup")
        void memberRefused() {
            String expected = "MANAGE_WORKFLOWS or VIEW_SETUP";
            AdminPermissionGuardFixture.assertForbidden(() -> as(STARTER_MEMBER,
                    () -> member().listExecutions(FLOW_ID, 50, 0, request)), expected);
            AdminPermissionGuardFixture.assertForbidden(() -> member().listVersions(FLOW_ID, request), expected);
            AdminPermissionGuardFixture.assertForbidden(() -> member().getVersion(FLOW_ID, 1, request), expected);
            verify(flowStore, never()).findExecutionsByFlow(any(), anyInt(), anyInt());
            verifyNoInteractions(flowRepository);
        }

        @Test
        @DisplayName("VIEW_SETUP admits the execution list and the versions")
        void viewSetupAdmits() {
            when(flowStore.findExecutionsByFlow(FLOW_ID, 50, 0)).thenReturn(List.of(execution(STARTER)));
            when(flowRepository.findFlowVersions(FLOW_ID)).thenReturn(List.of());
            FlowExecutionController viewer = controller(AdminPermissionGuardFixture.granting("VIEW_SETUP"));

            assertThat((List<?>) viewer.listExecutions(FLOW_ID, 50, 0, request).getBody().get("data")).hasSize(1);
            assertThat(viewer.listVersions(FLOW_ID, request).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) ((Map<?, ?>) response.getBody().get("data")).get("attributes");
    }

    private static FlowExecutionData execution(String startedBy) {
        return new FlowExecutionData(EXECUTION_ID, "tenant-1", FLOW_ID, FlowExecutionData.STATUS_COMPLETED,
                startedBy, null, Map.of("secret", "state"), null, null, null, 1, 10,
                Map.of("input", Map.of()), false, Instant.parse("2026-10-31T00:00:00Z"),
                Instant.parse("2026-10-31T00:00:01Z"));
    }

    private static FlowStepLogData step() {
        return new FlowStepLogData("step-1", EXECUTION_ID, "s1", "Step", "Task", FlowStepLogData.STATUS_SUCCEEDED,
                Map.of("in", 1), Map.of("out", 2), null, null, 1, 5, Instant.parse("2026-10-31T00:00:00Z"),
                Instant.parse("2026-10-31T00:00:01Z"));
    }
}
