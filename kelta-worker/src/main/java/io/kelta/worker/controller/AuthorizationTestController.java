package io.kelta.worker.controller;

import io.kelta.worker.service.CerbosAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin API for testing Cerbos authorization policies.
 *
 * <p>Allows admins to test whether a specific user/resource/action combination
 * would be allowed or denied by the current Cerbos policies.
 */
@RestController
@RequestMapping("/api/admin/authorization")
public class AuthorizationTestController {

    private static final String PERMISSION = "MANAGE_USERS";

    private static final Logger log = LoggerFactory.getLogger(AuthorizationTestController.class);

    private final CerbosAuthorizationService authzService;
    private final AdminPermissionGuard permissionGuard;

    public AuthorizationTestController(CerbosAuthorizationService authzService, AdminPermissionGuard permissionGuard) {
        this.authzService = authzService;
        this.permissionGuard = permissionGuard;
    }

    /**
     * Tests an authorization check against Cerbos.
     *
     * @param body request containing email, tenantId, resourceKind, resourceId,
     *             resourceAttributes, and action
     * @return result with allowed/denied status
     */
    @PostMapping("/test")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> testAuthorization(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        permissionGuard.require(request, PERMISSION);
        String email = (String) body.get("email");
        String profileId = (String) body.get("profileId");
        String tenantId = (String) body.get("tenantId");
        String resourceKind = (String) body.get("resourceKind");
        String resourceId = (String) body.get("resourceId");
        String action = (String) body.get("action");
        Map<String, Object> resourceAttributes = body.get("resourceAttributes") != null
                ? (Map<String, Object>) body.get("resourceAttributes")
                : Map.of();

        log.info("Authorization test: user={} resource={}/{} action={}",
                email, resourceKind, resourceId, action);

        boolean allowed;
        if ("field".equals(resourceKind)) {
            String collectionId = (String) resourceAttributes.get("collectionId");
            allowed = authzService.checkFieldAccess(email, profileId, tenantId,
                    collectionId, resourceId, action);
        } else if ("record".equals(resourceKind)) {
            String collectionId = (String) resourceAttributes.get("collectionId");
            allowed = authzService.checkRecordAccess(email, profileId, tenantId,
                    collectionId, resourceId, resourceAttributes, action);
        } else {
            // Default: treat as record check
            allowed = authzService.checkRecordAccess(email, profileId, tenantId,
                    resourceKind, resourceId != null ? resourceId : "test", resourceAttributes, action);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("allowed", allowed);
        response.put("email", email);
        response.put("resourceKind", resourceKind);
        response.put("resourceId", resourceId);
        response.put("action", action);

        return ResponseEntity.ok(response);
    }
}
