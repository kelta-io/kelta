package io.kelta.worker.controller;

import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.util.WithTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetAdminController Tests")
@WithTenant("tenant-1")
class PasswordResetAdminControllerTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private CerbosPermissionResolver permissionResolver;
    @Mock private BootstrapRepository bootstrapRepository;
    @Mock private HttpServletRequest request;

    private PasswordResetAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new PasswordResetAdminController(jdbcTemplate, permissionResolver, bootstrapRepository);
    }

    private void grantManageUsers() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_USERS", "granted", true)));
    }

    @Nested
    @DisplayName("POST /{userId}/reset-password")
    class ResetPassword {
        @Test
        void shouldSetForceChangeOnLoginFlag() {
            grantManageUsers();
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("id", "user-1", "email", "user@test.com")));
            when(jdbcTemplate.update(contains("UPDATE user_credential"), any(), eq("user-1")))
                    .thenReturn(1);

            var response = controller.resetPassword(request, "user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(jdbcTemplate).update(contains("force_change_on_login = true"), any(), eq("user-1"));
        }

        @Test
        void shouldReturn404ForCrossTenantUser() {
            grantManageUsers();
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of());

            var response = controller.resetPassword(request, "user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @WithTenant(none = true)
        void shouldReturn400WhenNoTenantContext() {
            grantManageUsers();

            var response = controller.resetPassword(request, "user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void shouldReturn404WhenNoCredentialRecord() {
            grantManageUsers();
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("id", "user-1", "email", "user@test.com")));
            when(jdbcTemplate.update(contains("UPDATE user_credential"), any(), eq("user-1")))
                    .thenReturn(0);

            var response = controller.resetPassword(request, "user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("403 when the caller's profile lacks MANAGE_USERS")
        void shouldRejectWithoutManageUsers() {
            when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
            when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                    Map.of("permission_name", "MANAGE_USERS", "granted", false)));

            assertThatThrownBy(() -> controller.resetPassword(request, "user-1"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("403 when no gateway identity headers are present")
        void shouldRejectWithoutIdentity() {
            when(permissionResolver.getProfileId(request)).thenReturn(null);

            assertThatThrownBy(() -> controller.resetPassword(request, "user-1"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
            verifyNoInteractions(jdbcTemplate);
        }
    }
}
