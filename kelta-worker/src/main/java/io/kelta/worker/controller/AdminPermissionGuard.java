package io.kelta.worker.controller;

import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Enforces a system permission on an {@code /api/admin/**} handler.
 *
 * <p>The gateway gives {@code /api/admin/**} only the blanket {@code API_ACCESS} check (plus a
 * deny for portal principals), so each admin controller must check the specific permission the
 * admin UI gates its page on. Same behaviour as the {@code requirePermission} copies in
 * {@link PortalAuthSettingsController} / {@link AdminInviteController}: the caller's profile
 * (gateway-set {@code X-User-Profile-Id}) must hold the permission granted, else 403.
 */
@Component
public class AdminPermissionGuard {

    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public AdminPermissionGuard(CerbosPermissionResolver permissionResolver,
                                BootstrapRepository bootstrapRepository) {
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    /**
     * @throws ResponseStatusException 403 when the request carries no profile or the profile
     *         does not hold {@code permission}
     */
    public void require(HttpServletRequest request, String permission) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> permission.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, permission + " permission required");
        }
    }
}
