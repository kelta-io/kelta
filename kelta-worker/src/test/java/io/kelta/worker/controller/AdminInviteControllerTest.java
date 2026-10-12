package io.kelta.worker.controller;

import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.UserInviteService;
import io.kelta.worker.util.WithTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminInviteController Tests")
@WithTenant("tenant-1")
class AdminInviteControllerTest {

    @Mock private UserInviteService userInviteService;
    @Mock private CerbosPermissionResolver permissionResolver;
    @Mock private BootstrapRepository bootstrapRepository;
    @Mock private HttpServletRequest request;

    private AdminInviteController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminInviteController(userInviteService, permissionResolver, bootstrapRepository);
    }

    private void grantManageUsers() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_USERS", "granted", true)));
    }

    @Test
    @DisplayName("403 when the caller's profile lacks MANAGE_USERS")
    void shouldRejectWithoutManageUsers() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_USERS", "granted", false)));

        assertThatThrownBy(() -> controller.invite(request, "user-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
        verifyNoInteractions(userInviteService);
    }

    @Test
    @DisplayName("403 when no gateway identity headers are present")
    void shouldRejectWithoutIdentity() {
        when(permissionResolver.getProfileId(request)).thenReturn(null);

        assertThatThrownBy(() -> controller.invite(request, "user-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
        verifyNoInteractions(userInviteService);
    }

    @Test
    @DisplayName("queues the invite and returns QUEUED")
    void shouldQueueInvite() {
        grantManageUsers();
        when(userInviteService.inviteUser("tenant-1", "user-1")).thenReturn("token");

        ResponseEntity<Map<String, String>> response = controller.invite(request, "user-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "QUEUED");
    }

    @Test
    @DisplayName("404 when the user is not found in the tenant")
    void shouldReturn404WhenUserNotFound() {
        grantManageUsers();
        when(userInviteService.inviteUser("tenant-1", "user-1")).thenReturn(null);

        ResponseEntity<Map<String, String>> response = controller.invite(request, "user-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    @DisplayName("400 when no tenant context is bound")
    @WithTenant(none = true)
    void shouldReturn400WhenNoTenantContext() {
        grantManageUsers();

        ResponseEntity<Map<String, String>> response = controller.invite(request, "user-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(userInviteService);
    }
}
