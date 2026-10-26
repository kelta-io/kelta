package io.kelta.worker.controller;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.PortalUserService;
import io.kelta.worker.service.UserAnonymizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Admin endpoints for users: create + invite external portal users (telehealth slice 1), and
 * anonymize any user ({@link UserAnonymizationService}).
 *
 * <p>Portal invite: creates + invites an external portal user.
 * The service forces {@code user_type=PORTAL} + the seeded Portal User profile
 * and never writes a {@code user_credential} row — portal users sign in only
 * via magic links. Re-posting an existing portal user's email re-sends a fresh
 * invite link instead of failing.
 *
 * <p>Requires {@code MANAGE_USERS} — {@code /api/admin/**} is a static gateway
 * route with only the blanket API_ACCESS check, so the permission is enforced
 * here (same idiom as {@link AdminInviteController}).
 */
@RestController
@RequestMapping("/api/admin/users")
public class PortalUserAdminController {

    private static final Logger log = LoggerFactory.getLogger(PortalUserAdminController.class);
    private static final String PERMISSION = "MANAGE_USERS";

    private final PortalUserService portalUserService;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;
    private final UserAnonymizationService anonymizationService;

    public PortalUserAdminController(PortalUserService portalUserService,
                                     CerbosPermissionResolver permissionResolver,
                                     BootstrapRepository bootstrapRepository,
                                     UserAnonymizationService anonymizationService) {
        this.portalUserService = portalUserService;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
        this.anonymizationService = anonymizationService;
    }

    public record PortalInviteRequest(String email, String firstName, String lastName) {}

    @PostMapping("/portal-invite")
    public ResponseEntity<Map<String, String>> invitePortalUser(HttpServletRequest request,
                                                                @RequestBody PortalInviteRequest body) {
        requirePermission(request);
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tenant context required"));
        }
        if (body == null || body.email() == null || body.email().isBlank()
                || !body.email().contains("@")) {
            return ResponseEntity.badRequest().body(Map.of("error", "A valid email is required"));
        }

        String actorId = request.getHeader("X-User-Id");
        PortalUserService.PortalInviteResult result = portalUserService.invitePortalUser(
                tenantId, actorId, body.email(), body.firstName(), body.lastName());

        log.info("Portal invite {} for user {} in tenant {}",
                result.created() ? "created" : "re-sent", result.userId(), tenantId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(Map.of("userId", result.userId(),
                        "status", result.created() ? "INVITED" : "REINVITED"));
    }

    /**
     * Erases the user's personal data and keeps the row (see {@link UserAnonymizationService}).
     * 404 for a user of another tenant; 400 for the {@code system} user and for the caller
     * themself; 200 again, changing nothing, for an anonymized user.
     */
    @PostMapping("/{id}/anonymize")
    public ResponseEntity<Map<String, Object>> anonymizeUser(HttpServletRequest request, @PathVariable String id) {
        requirePermission(request);
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant context required");
        }
        String actorId = CallerContext.current().map(CallerContext::userId)
                .orElse(request.getHeader("X-User-Id"));
        if (id.equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot anonymize yourself");
        }

        UserAnonymizationService.Result result;
        try {
            result = anonymizationService.anonymize(tenantId, id, actorId);
        } catch (UserAnonymizationService.UserNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        } catch (UserAnonymizationService.AnonymizationRefusedException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ResponseEntity.ok(Map.of("userId", result.userId(),
                "status", UserAnonymizationService.ANONYMIZED,
                "alreadyAnonymized", result.alreadyAnonymized()));
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
