package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.SecurityAuditLogger;
import io.kelta.worker.service.TenantAdminInviteService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Lets a platform admin hand a tenant's seeded System Administrator to a real person:
 * {@code POST /api/tenants/{id}/admin-invite} with {@code {"email": ...}} sets the seeded
 * admin's email and sends the {@code user.invite} email. Provisioning gives that admin no
 * usable password, so this (or {@code adminEmail} on create) is how a tenant is claimed.
 *
 * <p>Requires {@code MANAGE_TENANTS}. The gateway already demands it for every write under the
 * {@code static-tenants} route, but the permission is checked here too, with the same
 * {@code profile_system_permission} lookup as {@link PasswordResetAdminController} — the check
 * runs under the caller's own tenant, which {@code TenantManagementScopeFilter} leaves bound on
 * this deeper path. Every attempt is written to {@link SecurityAuditLogger}.
 */
@RestController
@RequestMapping("/api/tenants")
public class TenantAdminInviteController {

    private static final String PERMISSION = "MANAGE_TENANTS";

    private final TenantAdminInviteService inviteService;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public TenantAdminInviteController(TenantAdminInviteService inviteService,
                                       CerbosPermissionResolver permissionResolver,
                                       BootstrapRepository bootstrapRepository) {
        this.inviteService = inviteService;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    public record AdminInviteRequest(String email) {}

    @PostMapping("/{id}/admin-invite")
    public ResponseEntity<Map<String, String>> inviteAdmin(HttpServletRequest request,
                                                           @PathVariable("id") String id,
                                                           @RequestBody(required = false) AdminInviteRequest body) {
        String actor = requirePermission(request, id);
        String email = TenantAdminInviteService.normalizeEmail(body != null ? body.email() : null);
        if (email == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "A valid email is required"));
        }

        TenantAdminInviteService.AdminInvite invite;
        try {
            invite = inviteService.inviteSeededAdmin(id, email).orElse(null);
        } catch (ResponseStatusException e) {
            audit(actor, id, id, "failure", e.getReason());
            throw e;
        }
        if (invite == null) {
            audit(actor, id, id, "failure", "tenant or seeded admin not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Tenant or its seeded admin not found"));
        }
        audit(actor, invite.userId(), id, "success", "invited by platform admin from tenant " + TenantContext.get());
        return ResponseEntity.ok(Map.of(
                "status", "INVITED",
                "tenantId", id,
                "userId", invite.userId(),
                "email", invite.email()));
    }

    private String requirePermission(HttpServletRequest request, String managedTenantId) {
        String actor = permissionResolver.getEmail(request);
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            audit(actor, managedTenantId, managedTenantId, "failure", "no identity");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            audit(actor, managedTenantId, managedTenantId, "failure", PERMISSION + " not granted");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, PERMISSION + " permission required");
        }
        return actor;
    }

    private static void audit(String actor, String target, String tenantId, String result, String detail) {
        SecurityAuditLogger.log(SecurityAuditLogger.EventType.TENANT_ADMIN_INVITED,
                actor, target, tenantId, result, detail);
    }
}
