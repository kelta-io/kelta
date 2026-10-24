package io.kelta.worker.controller;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.FlowStore;
import io.kelta.runtime.flow.InitialStateBuilder;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.FlowRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.FlowActorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("FlowExecutionController.executeFlow")
class FlowExecutionControllerTest {

    private static final String FLOW_ID = "flow-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String USER_ID = "user-1";
    private static final String PROFILE_ID = "profile-1";
    private static final String DEFINITION = "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Succeed\"}}}";

    private FlowEngine flowEngine;
    private FlowRepository flowRepository;
    private InitialStateBuilder initialStateBuilder;
    private FlowActorResolver flowActorResolver;
    private BootstrapRepository bootstrapRepository;
    private FlowExecutionController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        flowEngine = mock(FlowEngine.class);
        flowRepository = mock(FlowRepository.class);
        initialStateBuilder = mock(InitialStateBuilder.class);
        flowActorResolver = mock(FlowActorResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        controller = new FlowExecutionController(flowEngine, mock(FlowStore.class), initialStateBuilder,
                flowRepository, flowActorResolver, new CerbosPermissionResolver(), bootstrapRepository);

        request = new MockHttpServletRequest("POST", "/api/flows/" + FLOW_ID + "/execute");
        request.addHeader("X-User-Profile-Id", PROFILE_ID);

        when(initialStateBuilder.buildFromApiInvocation(anyMap(), anyString(), anyString(), anyString(),
                anyString())).thenReturn(Map.of("input", Map.of()));
        when(flowActorResolver.resolve(TENANT_ID, FLOW_ID, USER_ID)).thenReturn(USER_ID);
        when(flowEngine.startExecution(eq(TENANT_ID), eq(FLOW_ID), eq(DEFINITION), anyMap(), eq(USER_ID),
                isNull(), anyBoolean())).thenReturn("exec-1");
    }

    @Test
    @DisplayName("an active flow starts and answers a flow-executions resource")
    void activeFlowStarts() {
        givenFlow(true);

        ResponseEntity<Map<String, Object>> response = execute(Map.of("input", Map.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("type")).isEqualTo("flow-executions");
        assertThat(data.get("id")).isEqualTo("exec-1");
        verify(flowEngine).startExecution(eq(TENANT_ID), eq(FLOW_ID), eq(DEFINITION), anyMap(), eq(USER_ID),
                isNull(), eq(false));
    }

    @Test
    @DisplayName("an inactive flow answers 409 FLOW_INACTIVE and never starts")
    void inactiveFlowRejected() {
        givenFlow(false);

        ResponseEntity<Map<String, Object>> response = execute(Map.of("input", Map.of()));

        assertFlowInactive(response);
    }

    @Test
    @DisplayName("a flow whose active flag is null is treated as inactive")
    void nullActiveRejected() {
        givenFlow(null);

        assertFlowInactive(execute(null));
    }

    @Test
    @DisplayName("a test run of an inactive flow starts when the caller's profile holds MANAGE_WORKFLOWS")
    void inactiveTestRunWithPermissionStarts() {
        givenFlow(false);
        givenProfilePermissions(Map.of("permission_name", "MANAGE_WORKFLOWS", "granted", true));

        ResponseEntity<Map<String, Object>> response = execute(Map.of("input", Map.of(), "test", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody().get("data")).get("type")).isEqualTo("flow-executions");
        verify(flowEngine).startExecution(eq(TENANT_ID), eq(FLOW_ID), eq(DEFINITION), anyMap(), eq(USER_ID),
                isNull(), eq(true));
    }

    @Test
    @DisplayName("a test run of an inactive flow answers 409 when the caller lacks MANAGE_WORKFLOWS")
    void inactiveTestRunWithoutPermissionRejected() {
        givenFlow(false);
        givenProfilePermissions(Map.of("permission_name", "API_ACCESS", "granted", true));

        assertFlowInactive(execute(Map.of("input", Map.of(), "test", true)));
    }

    @Test
    @DisplayName("a revoked MANAGE_WORKFLOWS grant does not admit a test run of an inactive flow")
    void inactiveTestRunWithRevokedPermissionRejected() {
        givenFlow(false);
        givenProfilePermissions(Map.of("permission_name", "MANAGE_WORKFLOWS", "granted", false));

        assertFlowInactive(execute(Map.of("input", Map.of(), "test", true)));
    }

    @Test
    @DisplayName("a test run of an inactive flow without a profile answers 409")
    void inactiveTestRunWithoutProfileRejected() {
        givenFlow(false);
        request = new MockHttpServletRequest("POST", "/api/flows/" + FLOW_ID + "/execute");

        assertFlowInactive(execute(Map.of("input", Map.of(), "test", true)));
        verify(bootstrapRepository, never()).findProfileSystemPermissions(any());
    }

    @Test
    @DisplayName("an unknown flow still answers 404")
    void unknownFlowNotFound() {
        when(flowRepository.findFlowById(FLOW_ID)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = execute(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNeverStarted();
    }

    @Test
    @DisplayName("a flow without a definition still answers 400, even when inactive")
    void missingDefinitionBadRequest() {
        Map<String, Object> flow = new HashMap<>();
        flow.put("tenant_id", TENANT_ID);
        flow.put("active", false);
        when(flowRepository.findFlowById(FLOW_ID)).thenReturn(Optional.of(flow));

        ResponseEntity<Map<String, Object>> response = execute(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(response).get("detail")).isEqualTo("Flow has no definition");
        verifyNeverStarted();
    }

    private ResponseEntity<Map<String, Object>> execute(Map<String, Object> body) {
        return controller.executeFlow(FLOW_ID, body, TENANT_ID, USER_ID, request);
    }

    private void givenFlow(Boolean active) {
        Map<String, Object> flow = new HashMap<>();
        flow.put("id", FLOW_ID);
        flow.put("tenant_id", TENANT_ID);
        flow.put("definition", DEFINITION);
        flow.put("active", active);
        when(flowRepository.findFlowById(FLOW_ID)).thenReturn(Optional.of(flow));
    }

    private void givenProfilePermissions(Map<String, Object> permission) {
        when(bootstrapRepository.findProfileSystemPermissions(PROFILE_ID)).thenReturn(List.of(permission));
    }

    private void assertFlowInactive(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<?, ?> error = error(response);
        assertThat(error.get("status")).isEqualTo("409");
        assertThat(error.get("code")).isEqualTo("FLOW_INACTIVE");
        assertThat(error.get("detail")).isEqualTo("Flow is not active");
        verifyNeverStarted();
    }

    private static Map<?, ?> error(ResponseEntity<Map<String, Object>> response) {
        return (Map<?, ?>) ((List<?>) response.getBody().get("errors")).get(0);
    }

    private void verifyNeverStarted() {
        verify(flowEngine, never()).startExecution(any(), any(), any(), any(), any(), any(), anyBoolean());
    }
}
