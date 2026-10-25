package io.kelta.worker.controller;

import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AdminPermissionGuard")
class AdminPermissionGuardTest {

    private final BootstrapRepository bootstrapRepository = mock(BootstrapRepository.class);
    private final AdminPermissionGuard guard = new AdminPermissionGuard(new CerbosPermissionResolver(), bootstrapRepository);

    private static MockHttpServletRequest requestFrom(String profileId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Profile-Id", profileId);
        return request;
    }

    @Test
    @DisplayName("Passes when the profile holds the permission granted")
    void grantedPasses() {
        when(bootstrapRepository.findProfileSystemPermissions("p-1")).thenReturn(List.of(
                Map.of("permission_name", "API_ACCESS", "granted", true),
                Map.of("permission_name", "MANAGE_USERS", "granted", true)));

        assertThatCode(() -> guard.require(requestFrom("p-1"), "MANAGE_USERS")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("403 when the permission row exists but is not granted")
    void notGrantedIsForbidden() {
        when(bootstrapRepository.findProfileSystemPermissions("p-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_USERS", "granted", false)));

        AdminPermissionGuardFixture.assertForbidden(() -> guard.require(requestFrom("p-1"), "MANAGE_USERS"),
                "MANAGE_USERS");
    }

    @Test
    @DisplayName("403 when the profile holds only API_ACCESS")
    void apiAccessOnlyIsForbidden() {
        when(bootstrapRepository.findProfileSystemPermissions("p-1")).thenReturn(List.of(
                Map.of("permission_name", "API_ACCESS", "granted", true)));

        AdminPermissionGuardFixture.assertForbidden(() -> guard.require(requestFrom("p-1"), "MANAGE_TENANTS"),
                "MANAGE_TENANTS");
    }

    @Test
    @DisplayName("403 without a profile header, before any lookup")
    void noProfileIsForbidden() {
        AdminPermissionGuardFixture.assertForbiddenNoIdentity(
                () -> guard.require(new MockHttpServletRequest(), "MANAGE_USERS"));
        AdminPermissionGuardFixture.assertForbiddenNoIdentity(() -> guard.require(requestFrom(" "), "MANAGE_USERS"));
        verifyNoInteractions(bootstrapRepository);
    }
}
