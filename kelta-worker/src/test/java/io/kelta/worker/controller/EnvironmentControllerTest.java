package io.kelta.worker.controller;

import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.RemotePromotionClient;
import io.kelta.worker.service.SandboxEnvironmentService;
import io.kelta.worker.service.SandboxProvisioningService;
import io.kelta.worker.util.WithTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
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
    @DisplayName("createEnvironment maps validation failures to 400")
    void createMapsValidationFailure() {
        grantPermission();
        when(provisioningService.createSandbox(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("A tenant with slug 'acme--dev' already exists"));

        Map<String, Object> body = new HashMap<>();
        body.put("name", "dev");

        var response = controller.createEnvironment(body, request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
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
