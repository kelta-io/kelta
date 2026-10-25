package io.kelta.worker.controller;

import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MfaAdminController Tests")
@WithTenant("tenant-1")
class MfaAdminControllerTest {

    private final MockHttpServletRequest adminRequest = AdminPermissionGuardFixture.request();

    @Mock private JdbcTemplate jdbcTemplate;

    private MfaAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new MfaAdminController(jdbcTemplate,
                AdminPermissionGuardFixture.granting("MANAGE_USERS"));
    }

    @Nested
    @DisplayName("GET /users/{userId}/status")
    class GetStatus {
        @Test
        void shouldReturnMfaStatusForEnrolledUser() {
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("id", "user-1", "mfa_enabled", true)));
            when(jdbcTemplate.queryForList(contains("user_totp_secret"), eq("user-1")))
                    .thenReturn(List.of(Map.of("verified", true, "created_at", "2026-03-22")));
            when(jdbcTemplate.queryForList(contains("user_recovery_code"), eq("user-1")))
                    .thenReturn(List.of(Map.of("cnt", 6L)));

            var response = controller.getUserMfaStatus(adminRequest, "user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void shouldReturn404ForCrossTenantUser() {
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of());

            var response = controller.getUserMfaStatus(adminRequest, "user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("POST /users/{userId}/reset")
    class ResetMfa {
        @Test
        void shouldResetMfaForUser() {
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("id", "user-1")));

            var response = controller.resetUserMfa(adminRequest, "user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(jdbcTemplate).update(contains("DELETE FROM user_recovery_code"), eq("user-1"));
            verify(jdbcTemplate).update(contains("DELETE FROM user_totp_secret"), eq("user-1"));
            verify(jdbcTemplate).update(contains("UPDATE platform_user SET mfa_enabled = false"), any(), eq("user-1"));
        }

        @Test
        void shouldReturn404ForCrossTenantReset() {
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of());

            var response = controller.resetUserMfa(adminRequest, "user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("PUT /policy")
    class UpdatePolicy {
        @Test
        void shouldUpdateMfaRequired() {
            when(jdbcTemplate.update(contains("UPDATE password_policy"), eq(true), eq("tenant-1")))
                    .thenReturn(1);

            var response = controller.updateMfaPolicy(adminRequest, Map.of("mfaRequired", true));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void shouldReturn400WhenMfaRequiredMissing() {
            var response = controller.updateMfaPolicy(adminRequest, Map.of());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("A profile without MANAGE_USERS (API_ACCESS only, like Portal User) gets 403 on every handler")
    void deniedWithoutManageUsers() {
        controller = new MfaAdminController(jdbcTemplate, AdminPermissionGuardFixture.apiAccessOnly());

        AdminPermissionGuardFixture.assertForbidden(() -> controller.getUserMfaStatus(adminRequest, "user-1"), "MANAGE_USERS");
        AdminPermissionGuardFixture.assertForbidden(() -> controller.resetUserMfa(adminRequest, "user-1"), "MANAGE_USERS");
        AdminPermissionGuardFixture.assertForbidden(() -> controller.getMfaPolicy(adminRequest), "MANAGE_USERS");
        AdminPermissionGuardFixture.assertForbidden(
                () -> controller.updateMfaPolicy(adminRequest, Map.of("mfaRequired", true)), "MANAGE_USERS");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("A profile holding MANAGE_USERS can reset a user's MFA")
    void allowedWithManageUsers() {
        when(jdbcTemplate.queryForList(contains("platform_user"), eq("user-1"), eq("tenant-1")))
                .thenReturn(List.of(Map.of("id", "user-1")));

        assertThat(controller.resetUserMfa(adminRequest, "user-1").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
