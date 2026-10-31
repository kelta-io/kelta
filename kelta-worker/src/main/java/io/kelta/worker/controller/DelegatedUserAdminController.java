package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.query.SortField;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.DelegatedAdminScopeRepository;
import io.kelta.worker.service.AuthSessionRevocationClient;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.SecurityAuditLogger;
import io.kelta.worker.service.UserInviteService;
import io.kelta.worker.service.delegated.DelegatedAdminService;
import io.kelta.worker.service.delegated.DelegatedAdminService.EffectiveDelegatedScope;
import io.kelta.worker.service.delegated.DelegatedWriteContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scoped user management for delegated admins — callers who hold no {@code MANAGE_USERS} but are
 * listed in an active delegated-admin scope. Every endpoint resolves the caller's
 * {@link EffectiveDelegatedScope} fresh and enforces it in-controller ({@code /api/admin/**} is a
 * static gateway route with only the blanket API_ACCESS check):
 *
 * <ul>
 *   <li>reads are server-filtered to users whose profile is in the manageable set;</li>
 *   <li>writes use a strict field whitelist — {@code email}, {@code managerId},
 *       {@code mfaEnabled}, {@code settings} are immutable through this path (changing an
 *       in-scope user's email and resetting the password would be account takeover);</li>
 *   <li>self-edit is always rejected;</li>
 *   <li>profile assignments must stay inside the manageable set (which
 *       {@code DelegatedAdminService} has already re-filtered against privileged grants);</li>
 *   <li>validated writes run inside {@link DelegatedWriteContext} so the
 *       {@code IdentityCollectionGuardHook} admits them.</li>
 * </ul>
 *
 * <p>Full admins use the generic {@code /api/users} collection route; this controller is not for
 * them (a caller holding {@code MANAGE_USERS} may still call it, subject to the same scoping).
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/admin/delegated")
public class DelegatedUserAdminController {

    private static final Logger log = LoggerFactory.getLogger(DelegatedUserAdminController.class);

    private static final int MAX_PAGE = 200;
    private static final Set<String> CREATE_FIELDS =
            Set.of("email", "firstName", "lastName", "username", "locale", "timezone", "profileId");
    private static final Set<String> UPDATE_FIELDS =
            Set.of("firstName", "lastName", "username", "locale", "timezone", "profileId", "status");
    private static final Set<String> ALLOWED_STATUS = Set.of("ACTIVE", "INACTIVE");
    private static final String SERVICE_USER_TYPE = "SERVICE";

    private final DelegatedAdminService delegatedAdminService;
    private final DelegatedAdminScopeRepository scopeRepository;
    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final CerbosPermissionResolver permissionResolver;
    private final UserInviteService userInviteService;
    private final JdbcTemplate jdbcTemplate;
    private final AuthSessionRevocationClient authSessions;

    public DelegatedUserAdminController(DelegatedAdminService delegatedAdminService,
                                        DelegatedAdminScopeRepository scopeRepository,
                                        QueryEngine queryEngine,
                                        CollectionRegistry collectionRegistry,
                                        CerbosPermissionResolver permissionResolver,
                                        UserInviteService userInviteService,
                                        JdbcTemplate jdbcTemplate,
                                        AuthSessionRevocationClient authSessions) {
        this.delegatedAdminService = delegatedAdminService;
        this.scopeRepository = scopeRepository;
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.permissionResolver = permissionResolver;
        this.userInviteService = userInviteService;
        this.jdbcTemplate = jdbcTemplate;
        this.authSessions = authSessions;
    }

    // ------------------------------------------------------------------ me

    /**
     * The caller's delegated-administration summary — the UI probe. Never cached (unlike the
     * profile-keyed {@code /api/me/permissions}, this is per-user data).
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        String tenantId = requireTenant();
        EffectiveDelegatedScope scope = delegatedAdminService.effectiveScope(
                permissionResolver.getEmail(request), tenantId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("delegated", scope.delegated());
        body.put("canCreateUsers", scope.canCreateUsers());
        body.put("canDeactivateUsers", scope.canDeactivateUsers());
        body.put("canResetPasswords", scope.canResetPasswords());
        body.put("manageableProfiles", named(scope.manageableProfileIds(),
                scopeRepository.findProfileNames(scope.manageableProfileIds(), tenantId)));
        return ResponseEntity.ok(body);
    }

    // --------------------------------------------------------------- users

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers(HttpServletRequest request,
                                                         @RequestParam(defaultValue = "50") int limit,
                                                         @RequestParam(defaultValue = "1") int page) {
        Caller caller = requireDelegated(request);
        if (caller.scope.manageableProfileIds().isEmpty()) {
            return ResponseEntity.ok(JsonApiResponseBuilder.collection("users", List.of()));
        }
        QueryResult result = queryEngine.executeQuery(usersDefinition(), new QueryRequest(
                new Pagination(Math.max(1, page), Math.min(Math.max(1, limit), MAX_PAGE)),
                List.of(SortField.asc("email")),
                List.of(),
                List.of(FilterCondition.in("profileId", caller.scope.manageableProfileIds()),
                        FilterCondition.neq("userType", SERVICE_USER_TYPE))));
        List<Map<String, Object>> records = result.data().stream().map(this::projectUser).toList();
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("users", records));
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(HttpServletRequest request,
                                                          @RequestBody Map<String, Object> body) {
        Caller caller = requireDelegated(request);
        if (!caller.scope.canCreateUsers()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Scope does not allow creating users");
        }
        Map<String, Object> attrs = attributes(body);
        rejectUnknownFields(attrs, CREATE_FIELDS);
        String email = str(attrs.get("email"));
        String profileId = str(attrs.get("profileId"));
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required");
        }
        if (profileId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is required");
        }
        requireManageableProfile(caller, profileId);

        Map<String, Object> data = new LinkedHashMap<>(attrs);
        data.put("tenantId", caller.tenantId);
        data.put("status", "PENDING_ACTIVATION");
        Map<String, Object> created = DelegatedWriteContext.callAuthorized(
                () -> queryEngine.create(usersDefinition(), data));
        String id = String.valueOf(created.get("id"));
        userInviteService.inviteUser(caller.tenantId, id);
        audit(caller, id, "user created (profile " + profileId + "), invite queued");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(JsonApiResponseBuilder.single("users", id, projectUser(created)));
    }

    @PatchMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(HttpServletRequest request,
                                                          @PathVariable String id,
                                                          @RequestBody Map<String, Object> body) {
        Caller caller = requireDelegated(request);
        Map<String, Object> target = requireManageableTarget(caller, id);
        Map<String, Object> attrs = attributes(body);
        rejectUnknownFields(attrs, UPDATE_FIELDS);

        String newProfileId = str(attrs.get("profileId"));
        if (newProfileId != null && !newProfileId.equals(str(target.get("profileId")))) {
            requireManageableProfile(caller, newProfileId);
        }
        String newStatus = str(attrs.get("status"));
        if (newStatus != null && !newStatus.equals(str(target.get("status")))) {
            if (!caller.scope.canDeactivateUsers()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Scope does not allow activating/deactivating users");
            }
            if (!ALLOWED_STATUS.contains(newStatus)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "status must be one of " + ALLOWED_STATUS);
            }
        }

        Map<String, Object> updated = DelegatedWriteContext.callAuthorized(
                () -> queryEngine.update(usersDefinition(), id, new LinkedHashMap<>(attrs)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        audit(caller, id, "user updated: " + attrs.keySet());
        return ResponseEntity.ok(JsonApiResponseBuilder.single("users", id, projectUser(updated)));
    }

    @PostMapping("/users/{id}/invite")
    public ResponseEntity<Map<String, String>> invite(HttpServletRequest request, @PathVariable String id) {
        Caller caller = requireDelegated(request);
        requireManageableTarget(caller, id);
        String token = userInviteService.inviteUser(caller.tenantId, id);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        audit(caller, id, "invite re-sent");
        return ResponseEntity.ok(Map.of("status", "QUEUED"));
    }

    /**
     * Forces a password change on next login — the same {@code user_credential} flag the
     * MANAGE_USERS-gated {@code PasswordResetAdminController} sets, scoped to delegated targets
     * and gated on {@code canResetPasswords} — and signs the user out of kelta-auth. (A status
     * change through {@link #updateUser} signs the user out via {@code UserSessionRevocationHook}.)
     */
    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(HttpServletRequest request,
                                                             @PathVariable String id) {
        Caller caller = requireDelegated(request);
        if (!caller.scope.canResetPasswords()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Scope does not allow password resets");
        }
        requireManageableTarget(caller, id);
        int updated = jdbcTemplate.update(
                "UPDATE user_credential SET force_change_on_login = true, updated_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now()), id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No credential record found for this user");
        }
        SecurityAuditLogger.log(SecurityAuditLogger.EventType.PASSWORD_RESET_ADMIN,
                caller.email, id, caller.tenantId, "success", "delegated force_change_on_login=true");
        authSessions.revokeAfterCommit(caller.tenantId, id);
        return ResponseEntity.ok(Map.of("status", "reset_initiated", "userId", id));
    }

    // ------------------------------------------------------------- Helpers

    private record Caller(String email, String userId, String tenantId, EffectiveDelegatedScope scope) {
    }

    private Caller requireDelegated(HttpServletRequest request) {
        String tenantId = requireTenant();
        String email = permissionResolver.getEmail(request);
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        EffectiveDelegatedScope scope = delegatedAdminService.effectiveScope(email, tenantId);
        if (!scope.delegated()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a delegated admin");
        }
        String userId = delegatedAdminService.resolveUserId(email, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity"));
        return new Caller(email, userId, tenantId, scope);
    }

    /**
     * Loads the target user and requires their CURRENT profile to be manageable. Out-of-scope
     * targets (including every user with no profile) read as 404 so scope contents don't leak
     * user existence. Self-edit is always rejected.
     */
    private Map<String, Object> requireManageableTarget(Caller caller, String targetId) {
        Map<String, Object> target = queryEngine.getById(usersDefinition(), targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String profileId = str(target.get("profileId"));
        // A SERVICE user is a module's machine principal, managed by its module, never by a
        // delegated admin — even when its generated profile was put in the scope.
        if (profileId == null || !caller.scope.canManageProfile(profileId)
                || SERVICE_USER_TYPE.equals(str(target.get("userType")))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        if (targetId.equals(caller.userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Delegated admins cannot edit themselves");
        }
        return target;
    }

    private void requireManageableProfile(Caller caller, String profileId) {
        if (!caller.scope.canManageProfile(profileId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Profile is not in the delegated scope");
        }
    }

    private void rejectUnknownFields(Map<String, Object> attrs, Set<String> allowed) {
        for (String key : attrs.keySet()) {
            if (!allowed.contains(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Field '" + key + "' cannot be set through delegated administration");
            }
        }
    }

    private Map<String, Object> projectUser(Map<String, Object> row) {
        Map<String, Object> attrs = new LinkedHashMap<>(row);
        attrs.remove("id");
        attrs.remove("tenantId");
        attrs.remove("settings");
        return attrs;
    }

    private List<Map<String, Object>> named(Set<String> ids, Map<String, String> names) {
        return ids.stream()
                .sorted()
                .map(id -> Map.<String, Object>of("id", id, "name", names.getOrDefault(id, id)))
                .toList();
    }

    private CollectionDefinition usersDefinition() {
        return requireDefinition("users");
    }

    private CollectionDefinition requireDefinition(String name) {
        CollectionDefinition definition = collectionRegistry.get(name);
        if (definition == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    name + " collection not registered");
        }
        return definition;
    }

    private void audit(Caller caller, String targetId, String detail) {
        SecurityAuditLogger.log(SecurityAuditLogger.EventType.DELEGATED_ADMIN_ACTION,
                caller.email, targetId, caller.tenantId, "success", detail);
        log.info("Delegated admin {} in tenant {}: {} (target {})",
                caller.email, caller.tenantId, detail, targetId);
    }

    private String requireTenant() {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tenant context");
        }
        return tenantId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attributes(Map<String, Object> body) {
        if (body == null) {
            return Map.of();
        }
        Object data = body.get("data");
        if (data instanceof Map<?, ?> dataMap && dataMap.get("attributes") instanceof Map<?, ?> attrs) {
            return (Map<String, Object>) attrs;
        }
        return body;
    }

    private String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
