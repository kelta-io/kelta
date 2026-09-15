package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.SecurityAuditLogger;
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
 * Admin-on-behalf-of personal access token minting for managed users — {@code MANAGE_USERS}
 * holders can mint a PAT for another user (e.g. to script an integration on their behalf)
 * without ever seeing that user's password. {@code /api/admin/**} is a static gateway route
 * with only the blanket API_ACCESS check, so the permission is enforced here, following the
 * same manual-check pattern as {@link PasswordResetAdminController}.
 *
 * <p>Delegates the actual mint (generation, SHA-256 hash, Redis cache) to
 * {@link PersonalAccessTokenController#mintToken}, passing the caller's identity as the audit
 * actor and {@link SecurityAuditLogger.EventType#PAT_ADMIN_CREATED} so admin-minted tokens are
 * distinguishable from self-service ones in the audit trail.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminPersonalAccessTokenController {

    private static final String PERMISSION = "MANAGE_USERS";

    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;
    private final PersonalAccessTokenController personalAccessTokenController;

    public AdminPersonalAccessTokenController(CerbosPermissionResolver permissionResolver,
                                              BootstrapRepository bootstrapRepository,
                                              PersonalAccessTokenController personalAccessTokenController) {
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
        this.personalAccessTokenController = personalAccessTokenController;
    }

    /**
     * Mint a personal access token for {@code id} (the target user), on the caller's behalf.
     * Returns the plaintext token exactly once, same as the self-service endpoint.
     */
    @PostMapping("/{id}/tokens")
    public ResponseEntity<?> createToken(HttpServletRequest request, @PathVariable("id") String id,
                                         @RequestBody Map<String, Object> body) {
        requirePermission(request);
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        String actorEmail = permissionResolver.getEmail(request);
        return personalAccessTokenController.mintToken(
                id, tenantId, body, actorEmail, SecurityAuditLogger.EventType.PAT_ADMIN_CREATED);
    }

    private void requirePermission(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, PERMISSION + " permission required");
        }
    }
}
