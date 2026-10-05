package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.SecurityAuditLogger;
import io.kelta.worker.service.TenantAdminInviteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/tenants/{id}/admin-invite}. The router stand-in is registered deliberately —
 * {@code DynamicCollectionRouter} maps {@code POST /api/{parent}/{parentId}/{child}}, and the
 * literal segments here must win over it.
 */
@DisplayName("TenantAdminInviteController")
class TenantAdminInviteControllerTest {

    private static final String CALLER_TENANT = "platform-tenant";
    private static final String MANAGED_TENANT = "managed-tenant";
    private static final String PROFILE = "caller-profile";
    private static final String ACTOR = "ops@example.com";

    @RestController
    @RequestMapping("/api")
    static class NestedRouteStub {
        @PostMapping("/{parentName}/{parentId}/{childName}")
        String createChild(@PathVariable String parentName) {
            return "router";
        }
    }

    private TenantAdminInviteService inviteService;
    private BootstrapRepository bootstrapRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        inviteService = mock(TenantAdminInviteService.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        CerbosPermissionResolver permissionResolver = mock(CerbosPermissionResolver.class);
        when(permissionResolver.getProfileId(any())).thenReturn(PROFILE);
        when(permissionResolver.getEmail(any())).thenReturn(ACTOR);

        mvc = MockMvcBuilders.standaloneSetup(
                new TenantAdminInviteController(inviteService, permissionResolver, bootstrapRepository),
                new NestedRouteStub()).build();
    }

    private void grant(String permission, boolean granted) {
        when(bootstrapRepository.findProfileSystemPermissions(PROFILE)).thenReturn(
                List.of(Map.of("permission_name", permission, "granted", granted)));
    }

    private ResultActions perform(RequestBuilder request) {
        return TenantContext.callWithTenant(CALLER_TENANT, () -> {
            try {
                return mvc.perform(request);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static RequestBuilder invite(String body) {
        return post("/api/tenants/" + MANAGED_TENANT + "/admin-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    @DisplayName("403 without MANAGE_TENANTS, audited, and nothing is sent")
    void forbiddenWithoutManageTenants() throws Exception {
        grant("MANAGE_USERS", true);

        try (MockedStatic<SecurityAuditLogger> audit = mockStatic(SecurityAuditLogger.class)) {
            perform(invite("{\"email\":\"owner@example.com\"}")).andExpect(status().isForbidden());

            audit.verify(() -> SecurityAuditLogger.log(SecurityAuditLogger.EventType.TENANT_ADMIN_INVITED,
                    ACTOR, MANAGED_TENANT, MANAGED_TENANT, "failure", "MANAGE_TENANTS not granted"));
        }
        verifyNoInteractions(inviteService);
    }

    @Test
    @DisplayName("403 when MANAGE_TENANTS is present but not granted")
    void forbiddenWhenNotGranted() throws Exception {
        grant("MANAGE_TENANTS", false);

        perform(invite("{\"email\":\"owner@example.com\"}")).andExpect(status().isForbidden());

        verifyNoInteractions(inviteService);
    }

    @Test
    @DisplayName("200 with MANAGE_TENANTS: invites the managed tenant's admin and audits the actor")
    void invitesWithManageTenants() throws Exception {
        grant("MANAGE_TENANTS", true);
        when(inviteService.inviteSeededAdmin(MANAGED_TENANT, "owner@example.com"))
                .thenReturn(Optional.of(new TenantAdminInviteService.AdminInvite("admin-user", "owner@example.com")));

        try (MockedStatic<SecurityAuditLogger> audit = mockStatic(SecurityAuditLogger.class)) {
            perform(invite("{\"email\":\" Owner@Example.com \"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("INVITED"))
                    .andExpect(jsonPath("$.tenantId").value(MANAGED_TENANT))
                    .andExpect(jsonPath("$.userId").value("admin-user"))
                    .andExpect(jsonPath("$.email").value("owner@example.com"));

            audit.verify(() -> SecurityAuditLogger.log(eq(SecurityAuditLogger.EventType.TENANT_ADMIN_INVITED),
                    eq(ACTOR), eq("admin-user"), eq(MANAGED_TENANT), eq("success"), anyString()));
        }
    }

    @Test
    @DisplayName("400 on a malformed email")
    void badRequestOnMalformedEmail() throws Exception {
        grant("MANAGE_TENANTS", true);

        perform(invite("{\"email\":\"not-an-email\"}")).andExpect(status().isBadRequest());

        verifyNoInteractions(inviteService);
    }

    @Test
    @DisplayName("404 when the tenant or its seeded admin does not exist")
    void notFoundWithoutSeededAdmin() throws Exception {
        grant("MANAGE_TENANTS", true);
        when(inviteService.inviteSeededAdmin(MANAGED_TENANT, "owner@example.com")).thenReturn(Optional.empty());

        perform(invite("{\"email\":\"owner@example.com\"}")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("409 passes through when the email belongs to another user")
    void conflictPassesThrough() throws Exception {
        grant("MANAGE_TENANTS", true);
        when(inviteService.inviteSeededAdmin(MANAGED_TENANT, "owner@example.com"))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "taken"));

        perform(invite("{\"email\":\"owner@example.com\"}")).andExpect(status().isConflict());
    }
}
