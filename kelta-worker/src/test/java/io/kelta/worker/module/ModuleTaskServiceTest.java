package io.kelta.worker.module;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.module.ModuleTaskService.Heartbeat;
import io.kelta.worker.module.ModuleTaskService.Refusal;
import io.kelta.worker.module.ModuleTaskService.RefusedException;
import io.kelta.worker.service.ModuleServicePrincipalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.kelta.worker.module.ExternalModuleFixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ModuleTaskService")
class ModuleTaskServiceTest {

    private static final String APP = "app-1";
    private static final String SERVICE_USER = "svc-1";
    private static final String TOKEN = "kmt_" + "B".repeat(43);
    private static final String RESUME_KEY = "module-task:00000000-0000-4000-8000-000000000001";

    private ModuleStore moduleStore;
    private ModuleServicePrincipalService principals;
    private ModuleAsyncTaskStore tasks;
    private ModuleAccessTokenService tokens;
    private FlowEngine flowEngine;
    private ModuleTaskService service;

    @BeforeEach
    void setUp() {
        moduleStore = mock(ModuleStore.class);
        principals = mock(ModuleServicePrincipalService.class);
        tasks = mock(ModuleAsyncTaskStore.class);
        tokens = mock(ModuleAccessTokenService.class);
        flowEngine = mock(FlowEngine.class);
        when(moduleStore.findByConnectedAppId(TENANT, APP)).thenReturn(Optional.of(install("ACTIVE", "https://stub.example.test")));
        when(principals.isActivePrincipal(TENANT, APP, SERVICE_USER)).thenReturn(true);
        when(tasks.complete(eq(TENANT), eq("install-1"), eq(TOKEN), anyMap())).thenReturn(Optional.of(RESUME_KEY));
        service = new ModuleTaskService(moduleStore, principals, tasks, tokens, flowEngine, new ObjectMapper());
    }

    private static TenantModuleData install(String status, String baseUrl) {
        Instant at = Instant.parse("2026-10-01T00:00:00Z");
        return new TenantModuleData("install-1", TENANT, "stub", "Stub", "1.0.0", null, baseUrl,
                null, null, null, "{}", status, "admin", at, at, null, List.of(), baseUrl, 1, APP, SERVICE_USER);
    }

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private Refusal completeRefusal(String connectedAppId, String caller, String token, byte[] body) {
        try {
            service.complete(TENANT, connectedAppId, caller, token, body);
        } catch (RefusedException e) {
            return e.refusal();
        }
        return null;
    }

    @Test
    @DisplayName("records a success with its output and resumes the parked step by the task's key")
    void completesSuccess() {
        service.complete(TENANT, APP, SERVICE_USER, TOKEN, body("{\"success\":true,\"output\":{\"rows\":42}}"));

        verify(tasks).complete(TENANT, "install-1", TOKEN, Map.of("success", true, "output", Map.of("rows", 42)));
        verify(flowEngine).resumeAwaitedTask(TENANT, RESUME_KEY);
    }

    @Test
    @DisplayName("records a failure as the synchronous answer's message (code: message)")
    void completesFailure() {
        service.complete(TENANT, APP, SERVICE_USER, TOKEN,
                body("{\"success\":false,\"error\":{\"code\":\"EXPORT_GONE\",\"message\":\"source returned 404\"}}"));

        verify(tasks).complete(TENANT, "install-1", TOKEN,
                Map.of("success", false, "errorMessage", "EXPORT_GONE: source returned 404"));
        verify(flowEngine).resumeAwaitedTask(TENANT, RESUME_KEY);
    }

    @Test
    @DisplayName("a resume that throws is not the module's problem: the completion stands")
    void resumeFailureIsSwallowed() {
        when(flowEngine.resumeAwaitedTask(any(), any())).thenThrow(new IllegalStateException("pool closed"));

        service.complete(TENANT, APP, SERVICE_USER, TOKEN, body("{\"success\":true}"));

        verify(tasks).complete(eq(TENANT), eq("install-1"), eq(TOKEN), anyMap());
    }

    @Test
    @DisplayName("callers other than the install's own active service principal are NOT_MODULE_PRINCIPAL")
    void principalRefusals() {
        byte[] ok = body("{\"success\":true}");
        assertThat(completeRefusal(null, SERVICE_USER, TOKEN, ok)).as("no app id: a user token or PAT")
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        assertThat(completeRefusal(" ", SERVICE_USER, TOKEN, ok)).isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        when(moduleStore.findByConnectedAppId(TENANT, "app-other")).thenReturn(Optional.empty());
        assertThat(completeRefusal("app-other", SERVICE_USER, TOKEN, ok)).as("a plain connected app")
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        assertThat(completeRefusal(APP, "user-1", TOKEN, ok)).as("the app id with another user's token")
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        assertThat(completeRefusal(APP, null, TOKEN, ok)).as("unauthenticated").isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        when(principals.isActivePrincipal(TENANT, APP, SERVICE_USER)).thenReturn(false);
        assertThat(completeRefusal(APP, SERVICE_USER, TOKEN, ok)).as("a deactivated principal")
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        when(principals.isActivePrincipal(TENANT, APP, SERVICE_USER)).thenReturn(true);
        when(moduleStore.findByConnectedAppId(TENANT, APP)).thenReturn(Optional.of(install("ACTIVE", null)));
        assertThat(completeRefusal(APP, SERVICE_USER, TOKEN, ok)).as("a JAR module's row")
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);

        verifyNoInteractions(tasks, flowEngine);
    }

    @Test
    @DisplayName("a disabled or quarantined install is MODULE_NOT_ACTIVE; DEGRADED may still complete")
    void inactiveInstall() {
        when(moduleStore.findByConnectedAppId(TENANT, APP))
                .thenReturn(Optional.of(install(TenantModuleData.STATUS_DISABLED, "https://stub.example.test")));
        assertThat(completeRefusal(APP, SERVICE_USER, TOKEN, body("{\"success\":true}")))
                .isEqualTo(Refusal.MODULE_NOT_ACTIVE);
        verifyNoInteractions(tasks);

        when(moduleStore.findByConnectedAppId(TENANT, APP))
                .thenReturn(Optional.of(install(TenantModuleData.STATUS_DEGRADED, "https://stub.example.test")));
        assertThat(completeRefusal(APP, SERVICE_USER, TOKEN, body("{\"success\":true}"))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[]", "\"x\"", "{}", "{\"success\":\"yes\"}",
            "{\"success\":true,\"output\":[1]}", "{\"success\":true,\"output\":\"x\"}"})
    @DisplayName("a body that is not a valid ActionResponse is INVALID_TASK_RESULT, and the token is untouched")
    void invalidBody(String json) {
        assertThat(completeRefusal(APP, SERVICE_USER, TOKEN, body(json))).isEqualTo(Refusal.INVALID_TASK_RESULT);
        assertThat(completeRefusal(APP, SERVICE_USER, TOKEN, null)).isEqualTo(Refusal.INVALID_TASK_RESULT);
        verifyNoInteractions(tasks, flowEngine);
    }

    @Test
    @DisplayName("output is held to a synchronous answer's key limit")
    void outputKeyLimit() {
        StringBuilder output = new StringBuilder("{");
        for (int i = 0; i <= RemoteActionHandler.MAX_OUTPUT_KEYS; i++) {
            output.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":").append(i);
        }
        output.append('}');
        assertThat(completeRefusal(APP, SERVICE_USER, TOKEN, body("{\"success\":true,\"output\":" + output + "}")))
                .isEqualTo(Refusal.INVALID_TASK_RESULT);
        verifyNoInteractions(tasks);
    }

    @Test
    @DisplayName("a body over a synchronous answer's byte limit is INVALID_TASK_RESULT")
    void sizeLimit() {
        byte[] big = new byte[ModuleHttpClient.MAX_RESPONSE_BYTES + 1];
        assertThat(completeRefusal(APP, SERVICE_USER, TOKEN, big)).isEqualTo(Refusal.INVALID_TASK_RESULT);
        verifyNoInteractions(tasks);
    }

    @Test
    @DisplayName("an unknown, foreign, reused or expired token is one answer: TASK_NOT_FOUND, nothing resumed")
    void unknownToken() {
        when(tasks.complete(any(), any(), anyString(), anyMap())).thenReturn(Optional.empty());

        RefusedException refused = (RefusedException) org.assertj.core.api.Assertions.catchThrowable(() ->
                service.complete(TENANT, APP, SERVICE_USER, TOKEN, body("{\"success\":true}")));

        assertThat(refused.refusal()).isEqualTo(Refusal.TASK_NOT_FOUND);
        assertThat(refused.getMessage()).doesNotContain(TOKEN);
        verify(flowEngine, never()).resumeAwaitedTask(any(), any());
    }

    @Test
    @DisplayName("a heartbeat answers a token valid at least 10 minutes and the task's expiry")
    void heartbeat() {
        Instant taskExpires = Instant.parse("2026-11-01T12:00:00Z");
        Instant tokenExpires = Instant.parse("2026-10-31T12:15:00Z");
        when(tasks.heartbeat(TENANT, "install-1", TOKEN)).thenReturn(Optional.of(taskExpires));
        when(tokens.accessToken(any(TenantModuleData.class), eq(Duration.ofMinutes(10))))
                .thenReturn(new ModuleAccessTokenService.AccessToken("fresh-token", tokenExpires));

        Heartbeat heartbeat = service.heartbeat(TENANT, APP, SERVICE_USER, TOKEN);

        assertThat(heartbeat.accessToken()).isEqualTo("fresh-token");
        assertThat(heartbeat.accessTokenExpiresAt()).isEqualTo(tokenExpires);
        assertThat(heartbeat.taskExpiresAt()).isEqualTo(taskExpires);
        assertThat(heartbeat.toString()).doesNotContain("fresh-token");
        verifyNoInteractions(flowEngine);
    }

    @Test
    @DisplayName("a heartbeat refuses as a completion does, and TOKEN_UNAVAILABLE when kelta-auth fails")
    void heartbeatRefusals() {
        assertThatThrownBy(() -> service.heartbeat(TENANT, null, SERVICE_USER, TOKEN))
                .isInstanceOfSatisfying(RefusedException.class,
                        e -> assertThat(e.refusal()).isEqualTo(Refusal.NOT_MODULE_PRINCIPAL));

        when(tasks.heartbeat(TENANT, "install-1", TOKEN)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.heartbeat(TENANT, APP, SERVICE_USER, TOKEN))
                .isInstanceOfSatisfying(RefusedException.class,
                        e -> assertThat(e.refusal()).isEqualTo(Refusal.TASK_NOT_FOUND));
        verifyNoInteractions(tokens);

        when(tasks.heartbeat(TENANT, "install-1", TOKEN)).thenReturn(Optional.of(Instant.now().plusSeconds(60)));
        when(tokens.accessToken(any(TenantModuleData.class), any(Duration.class)))
                .thenThrow(new IllegalStateException("kelta-auth said 401"));
        assertThatThrownBy(() -> service.heartbeat(TENANT, APP, SERVICE_USER, TOKEN))
                .isInstanceOfSatisfying(RefusedException.class, e -> {
                    assertThat(e.refusal()).isEqualTo(Refusal.TOKEN_UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("401");
                });
    }
}
