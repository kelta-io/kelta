package io.kelta.worker.handler;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.UserAnonymizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AnonymizeUserActionHandler")
class AnonymizeUserActionHandlerTest {

    private final UserAnonymizationService service = mock(UserAnonymizationService.class);
    private final BootstrapRepository bootstrapRepository = mock(BootstrapRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AnonymizeUserActionHandler handler =
            new AnonymizeUserActionHandler(service, bootstrapRepository, jdbc, JsonMapper.builder().build());

    @BeforeEach
    void actorHasProfile() {
        when(jdbc.queryForList(contains("FROM platform_user"), eq(String.class), eq("admin-1"), eq("t1")))
                .thenReturn(List.of("profile-1"));
    }

    private void grant(boolean manageUsers) {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_USERS", "granted", manageUsers)));
    }

    private static ActionContext step(String actor, String config) {
        return ActionContext.builder().tenantId("t1").userId(actor).actionConfigJson(config).build();
    }

    @Test
    @DisplayName("registers as ANONYMIZE_USER")
    void key() {
        assertThat(handler.getActionTypeKey()).isEqualTo("ANONYMIZE_USER");
    }

    @Test
    @DisplayName("anonymizes the userId from the parameters with the flow's actor, binding the flow's tenant")
    void anonymizes() {
        grant(true);
        AtomicReference<String> boundTenant = new AtomicReference<>();
        when(service.anonymize("t1", "u-1", "admin-1")).thenAnswer(inv -> {
            boundTenant.set(TenantContext.get());
            return new UserAnonymizationService.Result("u-1", false);
        });

        ActionResult result = handler.execute(step("admin-1", "{\"userId\":\"u-1\"}"));

        assertThat(result.successful()).isTrue();
        assertThat(result.outputData()).containsEntry("userId", "u-1").containsEntry("alreadyAnonymized", false);
        assertThat(boundTenant.get()).isEqualTo("t1");
    }

    @Test
    @DisplayName("fails the step when the flow's actor lacks MANAGE_USERS, without anonymizing")
    void refusesWithoutManageUsers() {
        grant(false);

        ActionResult result = handler.execute(step("admin-1", "{\"userId\":\"u-1\"}"));

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage()).contains("MANAGE_USERS");
        verify(service, never()).anonymize(any(), any(), any());
    }

    @Test
    @DisplayName("fails the step when the flow has no actor, or the actor is not an active user of the tenant")
    void refusesWithoutAnActiveActor() {
        assertThat(handler.execute(step(null, "{\"userId\":\"u-1\"}")).successful()).isFalse();
        assertThat(handler.execute(step("stranger", "{\"userId\":\"u-1\"}")).successful()).isFalse();
        verify(service, never()).anonymize(any(), any(), any());
    }

    @Test
    @DisplayName("fails the step without a userId parameter")
    void requiresUserId() {
        assertThat(handler.execute(step("admin-1", "{}")).successful()).isFalse();
        assertThat(handler.execute(step("admin-1", null)).successful()).isFalse();
        assertThatThrownBy(() -> handler.validate("{}")).isInstanceOf(IllegalArgumentException.class);
        verify(service, never()).anonymize(any(), any(), any());
    }

    @Test
    @DisplayName("a refused or unknown user fails the step with the service's reason")
    void serviceRefusalFailsTheStep() {
        grant(true);
        when(service.anonymize("t1", "system", "admin-1"))
                .thenThrow(new UserAnonymizationService.AnonymizationRefusedException("The system user cannot be anonymized"));

        ActionResult result = handler.execute(step("admin-1", "{\"userId\":\"system\"}"));

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage()).contains("system user");
    }
}
