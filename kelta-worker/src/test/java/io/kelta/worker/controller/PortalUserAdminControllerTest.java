package io.kelta.worker.controller;

import io.kelta.runtime.context.CallerContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.PortalUserService;
import io.kelta.worker.service.UserAnonymizationService;
import io.kelta.worker.util.WithTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PortalUserAdminController")
@WithTenant("tenant-1")
class PortalUserAdminControllerTest {

    private final PortalUserService portalUserService = mock(PortalUserService.class);
    private final CerbosPermissionResolver permissionResolver = mock(CerbosPermissionResolver.class);
    private final BootstrapRepository bootstrapRepository = mock(BootstrapRepository.class);
    private final UserAnonymizationService anonymization = mock(UserAnonymizationService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private PortalUserAdminController controller;

    private static final CallerContext ADMIN =
            new CallerContext("admin-1", CallerContext.UserType.INTERNAL, false, false);

    @BeforeEach
    void setUp() {
        controller = new PortalUserAdminController(portalUserService, permissionResolver, bootstrapRepository,
                anonymization);
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
    }

    private void grant(boolean manageUsers) {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_USERS", "granted", manageUsers)));
    }

    private ResponseEntity<Map<String, Object>> anonymizeAsAdmin(String id) {
        return CallerContext.callAs(ADMIN, () -> controller.anonymizeUser(request, id));
    }

    private static void assertStatus(Throwable thrown, HttpStatus status) {
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(status);
    }

    @Nested
    @DisplayName("POST /api/admin/users/{id}/anonymize")
    class Anonymize {

        @Test
        @DisplayName("200 with MANAGE_USERS; the service runs for the bound tenant with the caller as actor")
        void anonymizes() {
            grant(true);
            when(anonymization.anonymize("tenant-1", "u-1", "admin-1"))
                    .thenReturn(new UserAnonymizationService.Result("u-1", false));

            ResponseEntity<Map<String, Object>> response = anonymizeAsAdmin("u-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("userId", "u-1").containsEntry("status", "ANONYMIZED")
                    .containsEntry("alreadyAnonymized", false);
        }

        @Test
        @DisplayName("200 again for an anonymized user, reporting alreadyAnonymized")
        void idempotent() {
            grant(true);
            when(anonymization.anonymize("tenant-1", "u-1", "admin-1"))
                    .thenReturn(new UserAnonymizationService.Result("u-1", true));

            ResponseEntity<Map<String, Object>> response = anonymizeAsAdmin("u-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("alreadyAnonymized", true);
        }

        @Test
        @DisplayName("403 without MANAGE_USERS, and nothing is anonymized")
        void forbiddenWithoutManageUsers() {
            grant(false);

            assertThatThrownBy(() -> anonymizeAsAdmin("u-1"))
                    .satisfies(thrown -> assertStatus(thrown, HttpStatus.FORBIDDEN));
            verify(anonymization, never()).anonymize(any(), any(), any());
        }

        @Test
        @DisplayName("403 with no profile identity")
        void forbiddenWithoutIdentity() {
            when(permissionResolver.getProfileId(request)).thenReturn(null);

            assertThatThrownBy(() -> anonymizeAsAdmin("u-1"))
                    .satisfies(thrown -> assertStatus(thrown, HttpStatus.FORBIDDEN));
            verify(anonymization, never()).anonymize(any(), any(), any());
        }

        @Test
        @DisplayName("404 for a user id of another tenant (the service does not find it in this one)")
        void notFoundAcrossTenants() {
            grant(true);
            when(anonymization.anonymize("tenant-1", "other-tenant-user", "admin-1"))
                    .thenThrow(new UserAnonymizationService.UserNotFoundException("other-tenant-user"));

            assertThatThrownBy(() -> anonymizeAsAdmin("other-tenant-user"))
                    .satisfies(thrown -> assertStatus(thrown, HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("400 for the system user")
        void refusesTheSystemUser() {
            grant(true);
            when(anonymization.anonymize("tenant-1", "system", "admin-1"))
                    .thenThrow(new UserAnonymizationService.AnonymizationRefusedException("no"));

            assertThatThrownBy(() -> anonymizeAsAdmin("system"))
                    .satisfies(thrown -> assertStatus(thrown, HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("400 for the caller's own id, before the service is called")
        void refusesSelf() {
            grant(true);

            assertThatThrownBy(() -> anonymizeAsAdmin("admin-1"))
                    .satisfies(thrown -> assertStatus(thrown, HttpStatus.BAD_REQUEST));
            verify(anonymization, never()).anonymize(any(), any(), any());
        }
    }
}
