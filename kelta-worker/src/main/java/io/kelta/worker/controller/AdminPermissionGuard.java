package io.kelta.worker.controller;

import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Enforces a system permission on an {@code /api/admin/**} handler.
 *
 * <p>The gateway gives {@code /api/admin/**} only the blanket {@code API_ACCESS} check (plus a
 * deny for portal principals), so each admin controller must check the specific permission the
 * admin UI gates its page on. Same behaviour as the {@code requirePermission} copies in
 * {@link PortalAuthSettingsController} / {@link AdminInviteController}: the caller's profile
 * (gateway-set {@code X-User-Profile-Id}) must hold the permission granted, else 403.
 *
 * <p>Also used by the dedicated controllers under other static routes whose reads the gateway
 * leaves at {@code API_ACCESS} — the flow execution, version and schedule reads under
 * {@code /api/flows/**} ({@link FlowExecutionController}, {@link FlowScheduleStatusController}).
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
        requireAny(request, permission);
    }

    /**
     * @throws ResponseStatusException 403 when the request carries no profile or the profile
     *         holds none of {@code permissions}
     */
    public void requireAny(HttpServletRequest request, String... permissions) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        if (!profileHoldsAny(profileId, permissions)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    String.join(" or ", permissions) + " permission required");
        }
    }

    /**
     * Whether the caller's profile holds at least one of {@code permissions} granted — for a
     * handler that answers administrators in full and everyone else with less, instead of
     * refusing them. False when the request carries no profile.
     */
    public boolean holdsAny(HttpServletRequest request, String... permissions) {
        String profileId = permissionResolver.getProfileId(request);
        return profileId != null && !profileId.isBlank() && profileHoldsAny(profileId, permissions);
    }

    private boolean profileHoldsAny(String profileId, String... permissions) {
        List<String> wanted = List.of(permissions);
        return bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> wanted.contains(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
    }
}
