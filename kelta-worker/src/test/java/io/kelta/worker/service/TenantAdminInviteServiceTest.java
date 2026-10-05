package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("TenantAdminInviteService")
class TenantAdminInviteServiceTest {

    private static final String TENANT = "managed-tenant";
    private static final String SLUG = "acme";

    private JdbcTemplate jdbcTemplate;
    private UserInviteService userInviteService;
    private TenantAdminInviteService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        userInviteService = mock(UserInviteService.class);
        service = new TenantAdminInviteService(jdbcTemplate, userInviteService);
    }

    private void stubTenantAndAdmin() {
        when(jdbcTemplate.queryForList("SELECT slug FROM tenant WHERE id = ?", String.class, TENANT))
                .thenReturn(List.of(SLUG));
        when(jdbcTemplate.queryForList(contains("FROM platform_user"), eq(TENANT), eq(SLUG + "-admin")))
                .thenReturn(List.of(Map.of("id", "admin-user")));
    }

    @Test
    @DisplayName("sets the seeded admin's email and invites them, bound to the managed tenant")
    void invitesUnderManagedTenant() {
        stubTenantAndAdmin();
        List<String> bound = new ArrayList<>();
        when(userInviteService.inviteUser(TENANT, "admin-user")).thenAnswer(inv -> {
            bound.add(TenantContext.get());
            return "token";
        });

        var result = TenantContext.callWithTenant("caller-tenant",
                () -> service.inviteSeededAdmin(TENANT, "owner@example.com"));

        assertThat(result).contains(new TenantAdminInviteService.AdminInvite("admin-user", "owner@example.com"));
        verify(jdbcTemplate).update(contains("UPDATE platform_user SET email"),
                eq("owner@example.com"), eq("admin-user"), eq(TENANT));
        assertThat(bound).containsExactly(TENANT);
    }

    @Test
    @DisplayName("empty when the tenant does not exist")
    void emptyForUnknownTenant() {
        when(jdbcTemplate.queryForList("SELECT slug FROM tenant WHERE id = ?", String.class, TENANT))
                .thenReturn(List.of());

        assertThat(service.inviteSeededAdmin(TENANT, "owner@example.com")).isEmpty();
        verifyNoInteractions(userInviteService);
    }

    @Test
    @DisplayName("empty when the tenant has no seeded admin")
    void emptyWithoutSeededAdmin() {
        when(jdbcTemplate.queryForList("SELECT slug FROM tenant WHERE id = ?", String.class, TENANT))
                .thenReturn(List.of(SLUG));
        when(jdbcTemplate.queryForList(contains("FROM platform_user"), eq(TENANT), eq(SLUG + "-admin")))
                .thenReturn(List.of());

        assertThat(service.inviteSeededAdmin(TENANT, "owner@example.com")).isEmpty();
        verify(jdbcTemplate, never()).update(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(userInviteService);
    }

    @Test
    @DisplayName("409 when another user in the tenant already has the email")
    void conflictOnDuplicateEmail() {
        stubTenantAndAdmin();
        when(jdbcTemplate.update(contains("UPDATE platform_user SET email"), anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("uq_user_tenant_email"));

        assertThatThrownBy(() -> service.inviteSeededAdmin(TENANT, "taken@example.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);
        verifyNoInteractions(userInviteService);
    }

    @Test
    @DisplayName("normalizeEmail trims, lower-cases and rejects implausible addresses")
    void normalizeEmail() {
        assertThat(TenantAdminInviteService.normalizeEmail(" Owner@Example.COM ")).isEqualTo("owner@example.com");
        assertThat(TenantAdminInviteService.normalizeEmail(null)).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail(42)).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("")).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("no-at-sign")).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("@example.com")).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("owner@")).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("a@b@c.com")).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("own er@example.com")).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("a".repeat(250) + "@x.io")).isNull();
    }
}
