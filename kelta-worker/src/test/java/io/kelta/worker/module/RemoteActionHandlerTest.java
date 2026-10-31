package io.kelta.worker.module;

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

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static io.kelta.worker.module.ExternalModuleFixtures.json;
import static io.kelta.worker.module.ExternalModuleFixtures.manifest;
import static io.kelta.worker.module.ExternalModuleFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RemoteActionHandler")
class RemoteActionHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleHttpClient http = mock(ModuleHttpClient.class);
    private final ExternalModuleRegistry registry = mock(ExternalModuleRegistry.class);
    private RemoteActionHandler handler;

    @BeforeEach
    void setUp() {
        ModuleAccessTokenService tokens = mock(ModuleAccessTokenService.class);
        when(tokens.accessToken(any())).thenReturn("token");
        ExternalModuleInvoker invoker = new ExternalModuleInvoker(mock(ModuleStore.class), http, tokens,
                mock(TenantSlugResolver.class), null, new ExternalModuleFixtures.MutableClock(), Runnable::run);
        endpoint(TenantModuleData.STATUS_ACTIVE);
        handler = new RemoteActionHandler(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE,
                ExternalModuleFixtures.action("ping"), registry, invoker, null, null, mapper);
    }

    private void endpoint(String status) {
        when(registry.endpoint(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE)).thenReturn(Optional.of(
                new ExternalModuleRegistry.Endpoint(row(status), manifest(), ModulePrincipalAccess.NONE)));
    }

    private ActionContext context() {
        return ActionContext.builder().tenantId(ExternalModuleFixtures.TENANT).executionLogId("exec-1")
                .userId("user-1").workflowRuleId("flow-1").stepName("Charge").attempt(2).triggerDepth(1)
                .actionConfigJson("{\"amount\":10}")
                .resolvedData(Map.of("input", Map.of("orderId", "o-1")))
                .build();
    }

    @Test
    @DisplayName("POSTs /kelta/v1/actions/{key} with the step's input, config, execution and Idempotency-Key")
    void sendsTheActionRequest() throws Exception {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, "{\"success\":true,\"output\":{\"ok\":1}}"));
        ModuleAssertionSigner.Actor runUser = new ModuleAssertionSigner.Actor("user-1", "PORTAL", null);
        when(registry.flowActor(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE, "user-1")).thenReturn(runUser);

        ActionResult result = handler.execute(context());

        assertThat(result.successful()).isTrue();
        assertThat(result.outputData()).containsEntry("ok", 1);
        ArgumentCaptor<ModuleHttpClient.Call> sent = ArgumentCaptor.captor();
        verify(http).invoke(any(), any(), sent.capture(), any());
        ModuleHttpClient.Call call = sent.getValue();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/kelta/v1/actions/ping");
        assertThat(call.timeout()).as("the manifest's timeoutMs").isEqualTo(Duration.ofMillis(1500));
        assertThat(call.headers()).containsEntry("Idempotency-Key", "exec-1:Charge");
        assertThat(call.actor()).as("the run's user acts, as a JAR action's context.userId()").isEqualTo(runUser);

        JsonNode body = mapper.readTree(call.body());
        assertThat(body.get("tenantId").stringValue()).isEqualTo(ExternalModuleFixtures.TENANT);
        assertThat(body.get("input").get("orderId").stringValue()).as("$.input, unwrapped").isEqualTo("o-1");
        assertThat(body.get("config").get("amount").asInt()).isEqualTo(10);
        JsonNode execution = body.get("execution");
        assertThat(execution.get("executionId").stringValue()).isEqualTo("exec-1");
        assertThat(execution.get("flowId").stringValue()).isEqualTo("flow-1");
        assertThat(execution.get("stepName").stringValue()).isEqualTo("Charge");
        assertThat(execution.get("attempt").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("success:false is an ActionResult failure carrying the module's code and message")
    void moduleFailureIsActionFailed() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200,
                "{\"success\":false,\"error\":{\"code\":\"CARD_DECLINED\",\"message\":\"declined\"}}"));

        ActionResult result = handler.execute(context());

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("CARD_DECLINED: declined");
    }

    @Test
    @DisplayName("a timeout throws ModuleTimeoutException, so a flow Catch can name it")
    void timeoutThrows() {
        when(http.invoke(any(), any(), any(), any()))
                .thenThrow(new ModuleHttpClient.ModuleCallException(0, "timed out", true));

        assertThatThrownBy(() -> handler.execute(context())).isInstanceOf(ModuleTimeoutException.class);
    }

    @Test
    @DisplayName("a disabled install, an unknown install or a body without success is ModuleUnavailableException")
    void unavailable() {
        endpoint(TenantModuleData.STATUS_DISABLED);
        assertThatThrownBy(() -> handler.execute(context())).isExactlyInstanceOf(ModuleUnavailableException.class);

        when(registry.endpoint(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> handler.execute(context())).isExactlyInstanceOf(ModuleUnavailableException.class)
                .hasMessageContaining("not installed");

        endpoint(TenantModuleData.STATUS_ACTIVE);
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, "{\"output\":{}}"));
        assertThatThrownBy(() -> handler.execute(context())).isExactlyInstanceOf(ModuleUnavailableException.class)
                .hasMessageContaining("success must be a boolean");
    }

    @Test
    @DisplayName("an explicit Idempotency-Key (a schedule's jobId:fireTime) is sent as given")
    void scheduledKey() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, "{\"success\":true}"));

        handler.run(ActionContext.builder().tenantId(ExternalModuleFixtures.TENANT).build(),
                "job-1:2026-10-30T12:00:00Z");

        ArgumentCaptor<ModuleHttpClient.Call> sent = ArgumentCaptor.captor();
        verify(http).invoke(any(), any(), sent.capture(), any());
        assertThat(sent.getValue().headers()).containsEntry("Idempotency-Key", "job-1:2026-10-30T12:00:00Z");
        assertThat(sent.getValue().actor()).as("a module schedule has no acting user").isNull();
    }

    @Test
    @DisplayName("is registered under <moduleId>:<key> and describes itself from the action row")
    void keyAndDescriptor() {
        assertThat(handler.getActionTypeKey()).isEqualTo("stub:ping");
        assertThat(handler.getDescriptor().getDisplayName()).isEqualTo("Ping ping");
        assertThat(handler.getDescriptor().getCategory()).isEqualTo("Integration");
    }
}
