package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.repository.BootstrapRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin management of the headless portal-auth allowlist (telehealth slice 8,
 * {@code specs/telehealth/8-portal-auth-headless.md}), stored under
 * {@code tenant.settings.portalAuth}:
 *
 * <ul>
 *   <li>{@code GET /api/admin/tenant/portal-auth-settings} — current
 *       {@code redirectUris} allowlist + {@code inviteRedirectUri}.</li>
 *   <li>{@code PUT /api/admin/tenant/portal-auth-settings} — replaces both.</li>
 * </ul>
 *
 * <p>kelta-auth reads these settings per-request straight from the tenant row
 * (no cache), so there is no cross-pod invalidation to publish. Requires
 * {@code MANAGE_USERS} — {@code /api/admin/**} gets only the blanket
 * API_ACCESS check at the gateway, so the permission is enforced here.
 */
@RestController
@RequestMapping("/api/admin/tenant/portal-auth-settings")
public class PortalAuthSettingsController {

    private static final Logger log = LoggerFactory.getLogger(PortalAuthSettingsController.class);
    private static final Logger audit = LoggerFactory.getLogger("security.audit");

    private static final String PERMISSION = "MANAGE_USERS";
    private static final int MAX_REDIRECT_URIS = 10;

    private final JdbcTemplate jdbcTemplate;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;
    private final ObjectMapper objectMapper;

    public PortalAuthSettingsController(JdbcTemplate jdbcTemplate,
                                        CerbosPermissionResolver permissionResolver,
                                        BootstrapRepository bootstrapRepository,
                                        ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param selfSignupEnabled gates public portal self-signup; null on input is
     *        treated as false, so the flag must be set deliberately
     */
    public record PortalAuthSettings(List<String> redirectUris, String inviteRedirectUri,
                                     Boolean selfSignupEnabled) {}

    @GetMapping
    public ResponseEntity<PortalAuthSettings> get(HttpServletRequest request) {
        requirePermission(request);
        String tenantId = requireTenant();

        List<String> uris = jdbcTemplate.queryForList(
                "SELECT jsonb_array_elements_text(settings#>'{portalAuth,redirectUris}') "
                        + "FROM tenant WHERE id = ?",
                String.class, tenantId);
        String invite = jdbcTemplate.queryForList(
                        "SELECT settings#>>'{portalAuth,inviteRedirectUri}' FROM tenant WHERE id = ?",
                        String.class, tenantId).stream()
                .filter(v -> v != null && !v.isBlank())
                .findFirst().orElse(null);
        boolean selfSignup = jdbcTemplate.queryForList(
                        "SELECT settings#>>'{portalAuth,selfSignupEnabled}' FROM tenant WHERE id = ?",
                        String.class, tenantId).stream()
                .filter(v -> v != null)
                .findFirst().map(Boolean::parseBoolean).orElse(false);
        return ResponseEntity.ok(new PortalAuthSettings(uris, invite, selfSignup));
    }

    @PutMapping
    public ResponseEntity<PortalAuthSettings> put(HttpServletRequest request,
                                                  @RequestBody PortalAuthSettings body) {
        requirePermission(request);
        String tenantId = requireTenant();

        List<String> uris = body == null || body.redirectUris() == null
                ? List.of()
                : List.copyOf(new ArrayList<>(body.redirectUris()));
        if (uris.size() > MAX_REDIRECT_URIS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At most " + MAX_REDIRECT_URIS + " redirect URIs are allowed");
        }
        for (String uri : uris) {
            if (!isAcceptableRedirectUri(uri)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Redirect URIs must be absolute https URLs without fragments "
                                + "(http only for localhost): " + uri);
            }
        }
        String invite = body == null ? null : body.inviteRedirectUri();
        if (invite != null && !invite.isBlank() && !uris.contains(invite)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "inviteRedirectUri must be one of redirectUris");
        }

        // This PUT REPLACES the whole {portalAuth} object, so every field must be
        // written back — omitting one silently resets it. selfSignupEnabled is
        // written unconditionally for exactly that reason: leaving it out on an
        // unrelated allowlist edit would quietly switch public signup off (or, if
        // the default ever flipped, on).
        boolean selfSignup = body != null && Boolean.TRUE.equals(body.selfSignupEnabled());
        Map<String, Object> portalAuth = new LinkedHashMap<>();
        portalAuth.put("redirectUris", uris);
        portalAuth.put("selfSignupEnabled", selfSignup);
        if (invite != null && !invite.isBlank()) {
            portalAuth.put("inviteRedirectUri", invite);
        }
        jdbcTemplate.update(
                "UPDATE tenant SET settings = jsonb_set(COALESCE(settings, '{}'::jsonb), "
                        + "'{portalAuth}', ?::jsonb), updated_at = NOW() WHERE id = ?",
                objectMapper.writeValueAsString(portalAuth), tenantId);

        audit.info("security_event=PORTAL_AUTH_SETTINGS_CHANGED actor={} tenant={} redirect_uris={} "
                        + "invite_redirect={} self_signup={}",
                request.getHeader("X-User-Id"), tenantId, uris.size(),
                invite != null && !invite.isBlank(), selfSignup);
        log.info("Portal auth settings updated for tenant {} ({} redirect URIs)", tenantId, uris.size());
        return ResponseEntity.ok(new PortalAuthSettings(uris,
                invite != null && !invite.isBlank() ? invite : null, selfSignup));
    }

    /**
     * Exact, conservative shape check: absolute https (http for localhost dev
     * only), no fragment — the allowlist is compared by exact string match in
     * kelta-auth, so anything fancier only widens the attack surface.
     */
    static boolean isAcceptableRedirectUri(String uri) {
        if (uri == null || uri.isBlank() || uri.contains("#")) {
            return false;
        }
        if (uri.startsWith("https://")) {
            return uri.length() > "https://".length();
        }
        return uri.startsWith("http://localhost") || uri.startsWith("http://127.0.0.1");
    }

    private static String requireTenant() {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant context required");
        }
        return tenantId;
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
