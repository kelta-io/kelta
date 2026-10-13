package io.kelta.worker.controller;

import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.RemotePromotionClient;
import io.kelta.worker.service.SandboxEnvironmentService;
import io.kelta.worker.service.SandboxProvisioningService;
import io.kelta.worker.util.WithTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("EnvironmentController")
@WithTenant("t1")
class EnvironmentControllerTest {

    private SandboxEnvironmentService environmentService;
    private SandboxProvisioningService provisioningService;
    private RemotePromotionClient remotePromotionClient;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private HttpServletRequest request;
    private EnvironmentController controller;

    @BeforeEach
    void setUp() {
        environmentService = mock(SandboxEnvironmentService.class);
        provisioningService = mock(SandboxProvisioningService.class);
        remotePromotionClient = mock(RemotePromotionClient.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        request = mock(HttpServletRequest.class);
        controller = new EnvironmentController(environmentService, provisioningService,
                remotePromotionClient, permissionResolver, bootstrapRepository);
    }

    private void grantPermission() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_SANDBOXES", "granted", true)));
    }

    @Test
    @DisplayName("rejects a caller without any identity")
    void rejectsNoIdentity() {
        when(permissionResolver.getProfileId(request)).thenReturn(null);

        assertThatThrownBy(() -> controller.listEnvironments(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(environmentService);
    }

    @Test
    @DisplayName("rejects a profile without MANAGE_SANDBOXES")
    void rejectsMissingPermission() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "CUSTOMIZE_APPLICATION", "granted", true),
                Map.of("permission_name", "MANAGE_SANDBOXES", "granted", false)));

        assertThatThrownBy(() -> controller.listEnvironments(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(environmentService);
    }

    @Test
    @DisplayName("400s without a tenant context")
    @WithTenant(none = true)
    void rejectsNoTenant() {
        assertThatThrownBy(() -> controller.listEnvironments(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("lists environments for the tenant")
    void listsEnvironments() {
        grantPermission();
        when(environmentService.listEnvironments("t1")).thenReturn(List.of(
                Map.of("id", "env-1", "name", "Production"),
                Map.of("id", "env-2", "name", "dev")));

        var response = controller.listEnvironments(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(environmentService).listEnvironments("t1");
    }

    @Test
    @DisplayName("getEnvironment returns 404 when not found")
    void getReturns404() {
        grantPermission();
        when(environmentService.getEnvironment("env-x", "t1")).thenReturn(Optional.empty());

        var response = controller.getEnvironment("env-x", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("createEnvironment requires a name")
    void createRequiresName() {
        grantPermission();

        var response = controller.createEnvironment(new HashMap<>(), request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(provisioningService);
    }

    @Test
    @DisplayName("createEnvironment provisions a local sandbox with the caller as creator")
    void createProvisionsSandbox() {
        grantPermission();
        when(provisioningService.createSandbox("t1", "dev", null, "SANDBOX", "user-1"))
                .thenReturn(Map.of("id", "env-new", "sandboxSlug", "acme--dev"));

        Map<String, Object> body = new HashMap<>();
        body.put("name", "dev");

        var response = controller.createEnvironment(body, request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(provisioningService).createSandbox("t1", "dev", null, "SANDBOX", "user-1");
    }

    @Test
    @DisplayName("createEnvironment registers a remote target when remoteBaseUrl is supplied")
    void createRegistersRemote() {
        grantPermission();
        when(provisioningService.createRemoteEnvironment("t1", "prod-eu", null, "PRODUCTION",
                "https://eu.kelta.example", "acme", "vault-1", "user-1"))
                .thenReturn(Map.of("id", "env-remote"));

        Map<String, Object> body = new HashMap<>();
        body.put("name", "prod-eu");
        body.put("type", "PRODUCTION");
        body.put("remoteBaseUrl", "https://eu.kelta.example");
        body.put("remoteTenantSlug", "acme");
        body.put("credentialRef", "vault-1");

        var response = controller.createEnvironment(body, request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verify(provisioningService).createRemoteEnvironment("t1", "prod-eu", null, "PRODUCTION",
                "https://eu.kelta.example", "acme", "vault-1", "user-1");
        verify(provisioningService, never()).createSandbox(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("createEnvironment unwraps a JSON:API envelope")
    void createUnwrapsJsonApiBody() {
        grantPermission();
        when(provisioningService.createSandbox("t1", "dev", null, "SANDBOX", "user-1"))
                .thenReturn(Map.of("id", "env-new"));

        Map<String, Object> body = Map.of("data", Map.of(
                "type", "environments",
                "attributes", Map.of("name", "dev")));

        var response = controller.createEnvironment(body, request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    @DisplayName("createEnvironment answers 409 naming the archived sandbox that holds the name")
    void createConflictsWithArchivedSandbox() {
        grantPermission();
        String detail = "Environment name 'dev' is already used by environment 'dev' "
                + "(id env-old, status ARCHIVED) in this tenant. Choose a different name.";
        when(provisioningService.createSandbox("t1", "dev", null, "SANDBOX", "user-1"))
                .thenThrow(new SandboxProvisioningService.EnvironmentConflictException(
                        "env-old", "dev", "ARCHIVED", detail));

        Map<String, Object> body = Map.of("data", Map.of("attributes", Map.of("name", "dev")));

        var response = controller.createEnvironment(body, request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> error = firstError(response);
        assertThat(error)
                .containsEntry("status", "409")
                .containsEntry("code", "ENVIRONMENT_NAME_CONFLICT")
                .containsEntry("detail", detail)
                .containsEntry("source", Map.of("pointer", "/data/attributes/name"));
        assertThat(error.get("meta")).isEqualTo(Map.of("conflictingEnvironment",
                Map.of("id", "env-old", "name", "dev", "status", "ARCHIVED")));
    }

    @Test
    @DisplayName("createEnvironment answers 409 when a concurrent create wins the unique index")
    void createMapsDuplicateKeyToConflict() {
        grantPermission();
        when(provisioningService.createSandbox(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("idx_environment_tenant_name"));

        var response = controller.createEnvironment(new HashMap<>(Map.of("name", "dev")), request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(firstError(response)).containsEntry("code", "ENVIRONMENT_NAME_CONFLICT");
    }

    @Test
    @DisplayName("createEnvironment answers 400 with the reason and a pointer to the offending attribute")
    void createMapsValidationFailureWithPointer() {
        grantPermission();
        when(provisioningService.createSandbox(any(), any(), any(), any(), any()))
                .thenThrow(new SandboxProvisioningService.InvalidEnvironmentRequestException(
                        "name", "Sandbox name must contain letters or digits"));

        Map<String, Object> body = Map.of("data", Map.of("attributes", Map.of("name", "!!!")));

        var response = controller.createEnvironment(body, request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(response))
                .containsEntry("status", "400")
                .containsEntry("code", "VALIDATION_FAILED")
                .containsEntry("detail", "Sandbox name must contain letters or digits")
                .containsEntry("source", Map.of("pointer", "/data/attributes/name"));
    }

    @Test
    @DisplayName("createEnvironment points a missing name at /name for a flat body")
    void createRequiresNamePointer() {
        grantPermission();

        var response = controller.createEnvironment(new HashMap<>(), request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(response))
                .containsEntry("detail", "Environment name is required")
                .containsEntry("source", Map.of("pointer", "/name"));
    }

    @Test
    @DisplayName("createEnvironment rejects a non-string attribute with a pointer instead of a 500")
    void createRejectsNonStringAttribute() {
        grantPermission();

        Map<String, Object> body = Map.of("data", Map.of("attributes", Map.of("name", 42)));

        var response = controller.createEnvironment(body, request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(response))
                .containsEntry("detail", "name must be a string")
                .containsEntry("source", Map.of("pointer", "/data/attributes/name"));
        verifyNoInteractions(provisioningService);
    }

    @Test
    @DisplayName("createEnvironment answers 400 without a pointer for a failure not tied to an attribute")
    void createMapsValidationFailure() {
        grantPermission();
        when(provisioningService.createSandbox(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Sandboxes cannot be created from a sandbox tenant"));

        var response = controller.createEnvironment(new HashMap<>(Map.of("name", "dev")), request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(response))
                .containsEntry("detail", "Sandboxes cannot be created from a sandbox tenant")
                .doesNotContainKey("source");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstError(org.springframework.http.ResponseEntity<?> response) {
        var errors = (List<Map<String, Object>>) ((Map<String, Object>) response.getBody()).get("errors");
        assertThat(errors).hasSize(1);
        return errors.get(0);
    }

    @Test
    @DisplayName("refresh delegates to the provisioning service")
    void refreshDelegates() {
        grantPermission();
        when(provisioningService.refreshSandbox("env-1", "t1"))
                .thenReturn(Map.of("id", "env-1", "status", "REFRESHING"));

        var response = controller.refreshEnvironment("env-1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(provisioningService).refreshSandbox("env-1", "t1");
    }

    @Test
    @DisplayName("delete decommissions the backing tenant for local sandboxes")
    void deleteDecommissionsSandbox() {
        grantPermission();
        Map<String, Object> env = new HashMap<>();
        env.put("id", "env-1");
        env.put("sandbox_tenant_id", "sbx-tenant");
        when(environmentService.getEnvironment("env-1", "t1")).thenReturn(Optional.of(env));

        var response = controller.deleteEnvironment("env-1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(provisioningService).deleteSandbox("env-1", "t1");
        verify(environmentService, never()).archiveEnvironment(any(), any());
    }

    @Test
    @DisplayName("delete archives plain environment rows without a sandbox tenant")
    void deleteArchivesPlainRow() {
        grantPermission();
        Map<String, Object> env = new HashMap<>();
        env.put("id", "env-2");
        env.put("sandbox_tenant_id", null);
        when(environmentService.getEnvironment("env-2", "t1")).thenReturn(Optional.of(env));

        var response = controller.deleteEnvironment("env-2", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(environmentService).archiveEnvironment("env-2", "t1");
        verify(provisioningService, never()).deleteSandbox(any(), any());
    }

    @Test
    @DisplayName("test endpoint returns 502 when the remote check fails")
    void testReturns502OnFailure() {
        grantPermission();
        when(remotePromotionClient.testConnection("env-r", "t1"))
                .thenReturn(Map.of("ok", false, "error", "Connection failed"));

        var response = controller.testEnvironment("env-r", request);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
    }

    @Test
    @DisplayName("diff delegates to compareWithParent")
    void diffDelegates() {
        grantPermission();
        when(provisioningService.compareWithParent("env-1", "t1"))
                .thenReturn(Map.of("status", "COMPARED", "changes", List.of()));

        var response = controller.diffEnvironment("env-1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(provisioningService).compareWithParent("env-1", "t1");
    }
}
