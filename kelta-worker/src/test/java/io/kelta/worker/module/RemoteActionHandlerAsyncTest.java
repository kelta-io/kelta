package io.kelta.worker.module;

import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.workflow.ModuleTimeoutException;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.service.TenantSlugResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.kelta.worker.module.ExternalModuleFixtures.json;
import static io.kelta.worker.module.ExternalModuleFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A long-running action (part 9h): the call carries a task, and a {@code 202} parks the step on it. */
@DisplayName("RemoteActionHandler — long-running actions")
class RemoteActionHandlerAsyncTest {

    private static final Instant EXPIRES = Instant.parse("2026-11-01T12:00:00Z");
    private static final ModuleAsyncTaskStore.Issued TASK =
            new ModuleAsyncTaskStore.Issued("00000000-0000-4000-8000-000000000001", "kmt_" + "A".repeat(43), EXPIRES);

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleHttpClient http = mock(ModuleHttpClient.class);
    private final ExternalModuleRegistry registry = mock(ExternalModuleRegistry.class);
    private final ModuleAsyncTaskStore tasks = mock(ModuleAsyncTaskStore.class);
    private RemoteActionHandler handler;

    @BeforeEach
    void setUp() {
        ModuleAccessTokenService tokens = mock(ModuleAccessTokenService.class);
        when(tokens.accessToken(any())).thenReturn("token");
        ExternalModuleInvoker invoker = new ExternalModuleInvoker(mock(ModuleStore.class), http, tokens,
                mock(TenantSlugResolver.class), null, new ExternalModuleFixtures.MutableClock(), Runnable::run);
        endpoint(3600);
        when(tasks.issue(any(), eq("ping"), any(), anyInt())).thenReturn(TASK);
        handler = new RemoteActionHandler(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE,
                ExternalModuleFixtures.action("ping"), registry, invoker, null, null, mapper, tasks);
    }

    /** The installed manifest declares {@code ping} with {@code maxDurationSeconds}, or synchronous when null. */
    private void endpoint(Integer maxDurationSeconds) {
        ExternalModuleManifest manifest = new ExternalModuleManifest(2, 1, ExternalModuleFixtures.MODULE, "Stub",
                "1.0.0", null, null, null, ExternalModuleManifest.Permissions.NONE, null, null,
                List.of(new ExternalModuleManifest.Action("ping", "Ping", null, null, null, 1500, null, null, null,
                        maxDurationSeconds)),
                List.of(), List.of(), List.of(), List.of(), List.of(), false, null);
        when(registry.endpoint(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE)).thenReturn(Optional.of(
                new ExternalModuleRegistry.Endpoint(row(TenantModuleData.STATUS_ACTIVE), manifest,
                        ModulePrincipalAccess.NONE)));
    }

    private static ActionContext context(boolean awaitable) {
        return ActionContext.builder().tenantId(ExternalModuleFixtures.TENANT).executionLogId("exec-1")
                .workflowRuleId("flow-1").stepName("Ingest").attempt(1).awaitable(awaitable)
                .resolvedData(Map.of("input", Map.of("exportId", "e-1")))
                .build();
    }

    private ModuleHttpClient.Call sent() {
        ArgumentCaptor<ModuleHttpClient.Call> sent = ArgumentCaptor.captor();
        verify(http).invoke(any(), any(), sent.capture(), any());
        return sent.getValue();
    }

    @Test
    @DisplayName("a declared long-running action gets a task, sent as task.token and task.expiresAt, keyed per attempt")
    void sendsTheTask() throws Exception {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(202, ""));

        handler.execute(context(true));

        verify(tasks).issue(any(), eq("ping"), any(), eq(3600));
        ModuleHttpClient.Call call = sent();
        JsonNode task = mapper.readTree(call.body()).get("task");
        assertThat(task.get("token").stringValue()).isEqualTo(TASK.token());
        assertThat(task.get("expiresAt").stringValue()).isEqualTo(EXPIRES.toString());
        // Each attempt brings a new task: a replayed answer to an earlier attempt must not match.
        assertThat(call.headers()).containsEntry(RemoteActionHandler.IDEMPOTENCY_KEY_HEADER, "exec-1:Ingest:1");
    }

    @Test
    @DisplayName("202 parks the step on the task's resume key until its expiry; the task is kept")
    void acceptedAwaits() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(202, ""));

        ActionResult result = handler.execute(context(true));

        assertThat(result.isAwaiting()).isTrue();
        assertThat(result.awaiting().resumeKey()).isEqualTo("module-task:" + TASK.id());
        assertThat(result.awaiting().expiresAt()).isEqualTo(EXPIRES);
        verify(tasks, never()).discard(any(), any());
    }

    @Test
    @DisplayName("a 200 answer finishes the step as before and discards the unused task")
    void synchronousAnswerDiscards() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, "{\"success\":true,\"output\":{\"n\":1}}"));

        ActionResult result = handler.execute(context(true));

        assertThat(result.isAwaiting()).isFalse();
        assertThat(result.successful()).isTrue();
        assertThat(result.outputData()).containsEntry("n", 1);
        verify(tasks).discard(ExternalModuleFixtures.TENANT, TASK);
    }

    @Test
    @DisplayName("a failed call discards the task and fails the step as a synchronous call would")
    void failedCallDiscards() {
        when(http.invoke(any(), any(), any(), any()))
                .thenThrow(new ModuleHttpClient.ModuleCallException(0, "timed out", true));

        assertThatThrownBy(() -> handler.execute(context(true))).isInstanceOf(ModuleTimeoutException.class);
        verify(tasks).discard(ExternalModuleFixtures.TENANT, TASK);
    }

    @Test
    @DisplayName("a discard that fails is logged, not thrown: the task expires unclaimed")
    void discardFailureIsNotTheStepsFailure() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, "{\"success\":true}"));
        doThrow(new IllegalStateException("db down")).when(tasks).discard(any(), any());

        assertThat(handler.execute(context(true)).successful()).isTrue();
    }

    @Test
    @DisplayName("a step that cannot wait (a branch) gets no task, and a 202 is an invalid answer")
    void notAwaitable() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(202, ""));

        assertThatThrownBy(() -> handler.execute(context(false)))
                .isExactlyInstanceOf(ModuleUnavailableException.class)
                .hasMessageContaining("202 Accepted answers only a call that carries a task");
        verifyNoInteractions(tasks);
    }

    @Test
    @DisplayName("an action the manifest does not declare long-running gets no task, and a 202 is invalid")
    void synchronousAction() throws Exception {
        endpoint(null);
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(202, ""));

        assertThatThrownBy(() -> handler.execute(context(true)))
                .isExactlyInstanceOf(ModuleUnavailableException.class);
        verifyNoInteractions(tasks);
        ModuleHttpClient.Call call = sent();
        assertThat(mapper.readTree(call.body()).has("task")).isFalse();
        assertThat(call.headers()).as("a synchronous call keeps the key stable across retries")
                .containsEntry(RemoteActionHandler.IDEMPOTENCY_KEY_HEADER, "exec-1:Ingest");
    }

    @Test
    @DisplayName("a schedule's run carries no task, so a 202 is invalid there")
    void scheduleRunHasNoTask() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(202, ""));

        assertThatThrownBy(() -> handler.run(ActionContext.builder().tenantId(ExternalModuleFixtures.TENANT).build(),
                "job-1:2026-10-30T12:00:00Z")).isExactlyInstanceOf(ModuleUnavailableException.class);
        verifyNoInteractions(tasks);
    }

    @Test
    @DisplayName("a task that cannot be issued fails the step as ModuleUnavailableException, before any call")
    void issueFailure() {
        when(tasks.issue(any(), any(), any(), anyInt())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> handler.execute(context(true)))
                .isExactlyInstanceOf(ModuleUnavailableException.class)
                .hasMessageContaining("could not be given a task");
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("a disabled install gets no task; the call is refused as before")
    void disabledInstallNoTask() {
        when(registry.endpoint(any(), any())).thenReturn(Optional.of(new ExternalModuleRegistry.Endpoint(
                row(TenantModuleData.STATUS_DISABLED), ExternalModuleFixtures.manifest(), ModulePrincipalAccess.NONE)));

        assertThatThrownBy(() -> handler.execute(context(true))).isExactlyInstanceOf(ModuleUnavailableException.class);
        verifyNoInteractions(tasks);
    }
}
