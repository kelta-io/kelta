package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("PortalAuthSettingsController")
class PortalAuthSettingsControllerTest {

    private static final String TENANT = "t1";
    private static final String ALLOWED = "https://portal.example.com/auth/callback";

    private JdbcTemplate jdbcTemplate;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private PortalAuthSettingsController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        controller = new PortalAuthSettingsController(
                jdbcTemplate, permissionResolver, bootstrapRepository, new ObjectMapper());
        request = new MockHttpServletRequest();
        grantManageUsers(true);
    }

    private void grantManageUsers(boolean granted) {
        when(permissionResolver.getProfileId(request)).thenReturn("prof-1");
        when(bootstrapRepository.findProfileSystemPermissions("prof-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_USERS", "granted", granted)));
    }

    private <T> T withTenant(java.util.function.Supplier<T> action) {
        return TenantContext.callWithTenant(TENANT, action::get);
    }

    @Test
    @DisplayName("GET returns the stored allowlist and invite redirect")
    void getReturnsSettings() {
        when(jdbcTemplate.queryForList(contains("portalAuth,redirectUris"),
                eq(String.class), eq(TENANT))).thenReturn(List.of(ALLOWED));
        when(jdbcTemplate.queryForList(contains("portalAuth,inviteRedirectUri"),
                eq(String.class), eq(TENANT))).thenReturn(List.of(ALLOWED));

        ResponseEntity<PortalAuthSettingsController.PortalAuthSettings> response =
                withTenant(() -> controller.get(request));

        assertThat(response.getBody().redirectUris()).containsExactly(ALLOWED);
        assertThat(response.getBody().inviteRedirectUri()).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("GET returns defaults for a tenant with no portalAuth settings")
    void getReturnsDefaultsWhenPortalAuthAbsent() {
        // The tenant row exists, so the scalar #>> lookups yield one NULL row each.
        when(jdbcTemplate.queryForList(contains("portalAuth,redirectUris"),
                eq(String.class), eq(TENANT))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("portalAuth,inviteRedirectUri"),
                eq(String.class), eq(TENANT))).thenReturn(java.util.Collections.singletonList(null));
        when(jdbcTemplate.queryForList(contains("portalAuth,selfSignupEnabled"),
                eq(String.class), eq(TENANT))).thenReturn(java.util.Collections.singletonList(null));

        ResponseEntity<PortalAuthSettingsController.PortalAuthSettings> response =
                withTenant(() -> controller.get(request));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().redirectUris()).isEmpty();
        assertThat(response.getBody().inviteRedirectUri()).isNull();
        assertThat(response.getBody().selfSignupEnabled()).isFalse();
    }

    @Test
    @DisplayName("PUT persists the portalAuth settings as one jsonb_set")
    void putPersists() {
        ResponseEntity<PortalAuthSettingsController.PortalAuthSettings> response =
                withTenant(() -> controller.put(request,
                        new PortalAuthSettingsController.PortalAuthSettings(
                                List.of(ALLOWED), ALLOWED, false)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(jdbcTemplate).update(contains("jsonb_set"),
                contains("\"redirectUris\""), eq(TENANT));
    }

    @Test
    @DisplayName("PUT rejects http (non-localhost), fragments, too many entries, and stray invite URIs")
    void putValidation() {
        assertThatThrownBy(() -> withTenant(() -> controller.put(request,
                new PortalAuthSettingsController.PortalAuthSettings(
                        List.of("http://portal.example.com/cb"), null, false))))
                .isInstanceOf(ResponseStatusException.class);

        assertThatThrownBy(() -> withTenant(() -> controller.put(request,
                new PortalAuthSettingsController.PortalAuthSettings(
                        List.of("https://portal.example.com/cb#frag"), null, false))))
                .isInstanceOf(ResponseStatusException.class);

        List<String> eleven = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> "https://portal.example.com/cb" + i).toList();
        assertThatThrownBy(() -> withTenant(() -> controller.put(request,
                new PortalAuthSettingsController.PortalAuthSettings(eleven, null, false))))
                .isInstanceOf(ResponseStatusException.class);

        assertThatThrownBy(() -> withTenant(() -> controller.put(request,
                new PortalAuthSettingsController.PortalAuthSettings(
                        List.of(ALLOWED), "https://other.example.com/cb", false))))
                .isInstanceOf(ResponseStatusException.class);

        verify(jdbcTemplate, never()).update(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("localhost http is allowed for dev")
    void putAllowsLocalhost() {
        ResponseEntity<PortalAuthSettingsController.PortalAuthSettings> response =
                withTenant(() -> controller.put(request,
                        new PortalAuthSettingsController.PortalAuthSettings(
                                List.of("http://localhost:3000/auth/callback"), null, false)));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("both endpoints require MANAGE_USERS")
    void requiresPermission() {
        grantManageUsers(false);
        assertThatThrownBy(() -> withTenant(() -> controller.get(request)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("MANAGE_USERS");
        assertThatThrownBy(() -> withTenant(() -> controller.put(request,
                new PortalAuthSettingsController.PortalAuthSettings(List.of(), null, false))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("MANAGE_USERS");
    }
}
