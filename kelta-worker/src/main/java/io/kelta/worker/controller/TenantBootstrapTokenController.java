package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
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

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets a platform admin act as a managed tenant's admin for a bounded time:
 * {@code POST /api/tenants/{id}/bootstrap-token} with {@code {"expiresIn": "1h", "userId"?: ...}}
 * mints a personal access token in the <em>target</em> tenant for its seeded System Administrator
 * (or for {@code userId} / {@code email} when it names a user of that tenant). The gateway rejects
 * a PAT on any tenant URL but its own, so the token works only in the target tenant.
 *
 * <p>{@code expiresIn} is ISO-8601 ({@code PT1H}) or shorthand minutes/hours ({@code 30m},
 * {@code 2h}); default 1 h, max 24 h. The platform tenant itself is refused, and a non-UUID id
 * (the internal {@code system} tenant) is never a target. The plaintext token
 * is returned once and is never logged.
 *
 * <p>Requires {@code MANAGE_TENANTS}, checked here the same way as
 * {@link TenantAdminInviteController} — under the caller's own tenant, which
 * {@code TenantManagementScopeFilter} leaves bound on this deeper path. Every attempt is audited
 * as {@code TENANT_BOOTSTRAP_TOKEN_ISSUED}.
 */
@RestController
@RequestMapping("/api/tenants")
public class TenantBootstrapTokenController {

    static final Duration DEFAULT_LIFETIME = Duration.ofHours(1);
    static final Duration MAX_LIFETIME = Duration.ofHours(24);
    private static final String PERMISSION = "MANAGE_TENANTS";
    private static final Pattern SHORTHAND = Pattern.compile("(\\d{1,6})([mh])");
    private static final int MAX_ACTOR_IN_NAME = 150;

    private final TenantAdminInviteService tenantUsers;
    private final PersonalAccessTokenController personalAccessTokenController;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public TenantBootstrapTokenController(TenantAdminInviteService tenantUsers,
                                          PersonalAccessTokenController personalAccessTokenController,
                                          CerbosPermissionResolver permissionResolver,
                                          BootstrapRepository bootstrapRepository) {
        this.tenantUsers = tenantUsers;
        this.personalAccessTokenController = personalAccessTokenController;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    public record BootstrapTokenRequest(Object expiresIn, String userId, String email) {}

    @PostMapping("/{id}/bootstrap-token")
    public ResponseEntity<Map<String, Object>> issue(HttpServletRequest request,
                                                     @PathVariable("id") String id,
                                                     @RequestBody(required = false) BootstrapTokenRequest body) {
        String actor = requirePermission(request, id);

        UUID tenantUuid = parseUuid(id);
        if (tenantUuid == null) {
            return refuse(actor, id, HttpStatus.NOT_FOUND, "Tenant not found");
        }
        String tenantId = tenantUuid.toString();
        if (tenantId.equals(SystemCollectionDefinitions.SYSTEM_TENANT_ID)) {
            return refuse(actor, tenantId, HttpStatus.FORBIDDEN, "The platform tenant cannot be bootstrapped");
        }

        Duration lifetime = parseLifetime(body != null ? body.expiresIn() : null);
        if (lifetime == null) {
            return refuse(actor, tenantId, HttpStatus.BAD_REQUEST,
                    "expiresIn must be a positive duration of at most 24h (e.g. \"1h\", \"30m\", \"PT2H\")");
        }

        String userId = null;
        String email = null;
        if (body != null && body.userId() != null) {
            UUID userUuid = parseUuid(body.userId());
            if (userUuid == null) {
                return refuse(actor, tenantId, HttpStatus.BAD_REQUEST, "userId must be a UUID");
            }
            userId = userUuid.toString();
        } else if (body != null && body.email() != null) {
            email = TenantAdminInviteService.normalizeEmail(body.email());
            if (email == null) {
                return refuse(actor, tenantId, HttpStatus.BAD_REQUEST, "A valid email is required");
            }
        }

        TenantAdminInviteService.TenantUser target = tenantUsers.findTenantUser(tenantId, userId, email).orElse(null);
        if (target == null) {
            return refuse(actor, tenantId, HttpStatus.NOT_FOUND, "Tenant or target user not found in that tenant");
        }
        if (!"ACTIVE".equals(target.status())) {
            return refuse(actor, tenantId, HttpStatus.CONFLICT, "Target user is not active");
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(lifetime);
        String name = tokenName(actor, now);
        String detail = "name=" + name + " expiresAt=" + expiresAt + " by platform admin from tenant " + TenantContext.get();
        PersonalAccessTokenController.MintedToken minted;
        try {
            minted = TenantContext.callWithTenant(target.tenantId(), target.slug(), () ->
                    personalAccessTokenController.issueToken(target.userId(), target.tenantId(), name,
                            List.of("api"), expiresAt, actor,
                            SecurityAuditLogger.EventType.TENANT_BOOTSTRAP_TOKEN_ISSUED, detail));
        } catch (ResponseStatusException e) {
            audit(actor, target.userId(), tenantId, "failure", e.getReason());
            throw e;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", minted.token());
        response.put("tokenPrefix", minted.tokenPrefix());
        response.put("name", minted.name());
        response.put("tenantId", target.tenantId());
        response.put("userId", target.userId());
        response.put("expiresAt", minted.expiresAt().toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Parses {@code expiresIn}: absent means {@link #DEFAULT_LIFETIME}; otherwise an ISO-8601
     * duration or {@code <n>m} / {@code <n>h}. Returns {@code null} when unparseable, not
     * positive, or longer than {@link #MAX_LIFETIME}.
     */
    static Duration parseLifetime(Object raw) {
        if (raw == null) {
            return DEFAULT_LIFETIME;
        }
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        String value = s.trim();
        Duration lifetime;
        Matcher shorthand = SHORTHAND.matcher(value);
        if (shorthand.matches()) {
            long amount = Long.parseLong(shorthand.group(1));
            lifetime = "h".equals(shorthand.group(2)) ? Duration.ofHours(amount) : Duration.ofMinutes(amount);
        } else {
            try {
                lifetime = Duration.parse(value);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0) {
            return null;
        }
        return lifetime;
    }

    /** {@code bootstrap-<actor>-<timestamp>}, recognisable in the target user's token list. */
    static String tokenName(String actor, Instant now) {
        String who = actor == null || actor.isBlank() ? "unknown" : actor.trim();
        if (who.length() > MAX_ACTOR_IN_NAME) {
            who = who.substring(0, MAX_ACTOR_IN_NAME);
        }
        return "bootstrap-" + who + "-" + now.truncatedTo(ChronoUnit.MILLIS);
    }

    private static UUID parseUuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    private static ResponseEntity<Map<String, Object>> refuse(String actor, String tenantId,
                                                              HttpStatus status, String reason) {
        audit(actor, tenantId, tenantId, "failure", reason);
        return ResponseEntity.status(status).body(Map.of("error", reason));
    }

    private static void audit(String actor, String target, String tenantId, String result, String detail) {
        SecurityAuditLogger.log(SecurityAuditLogger.EventType.TENANT_BOOTSTRAP_TOKEN_ISSUED,
                actor, target, tenantId, result, detail);
    }
}
