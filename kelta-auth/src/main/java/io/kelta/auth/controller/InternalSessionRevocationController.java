package io.kelta.auth.controller;

import io.kelta.auth.session.UserSessionRevoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Service-to-service endpoint kelta-worker calls after an administrator reset a user's password,
 * deactivated or removed the account: ends every HTTP session of that tenant's user and revokes
 * the user's OAuth2 authorizations ({@link UserSessionRevoker#signOutEverywhere}).
 *
 * <p>{@code POST /internal/tenants/{tenantId}/users/{userId}/revoke-sessions}, optional JSON body
 * {@code {"loginNames": ["…"]}} (used only when the user row no longer exists). Authenticated by
 * the shared {@code kelta.internal.token} ({@code KELTA_INTERNAL_TOKEN}) in {@code X-Internal-Token},
 * compared in constant time; with no token configured every call is refused. The ingress routes
 * every path of the auth host to this service, so this check is the endpoint's only gate — the
 * path is {@code permitAll} in Spring Security and exempt from CSRF (no browser session is used).
 */
@RestController
@RequestMapping("/internal")
public class InternalSessionRevocationController {

    private static final Logger log = LoggerFactory.getLogger(InternalSessionRevocationController.class);

    static final String TOKEN_HEADER = "X-Internal-Token";

    /** Tenant and user ids are UUID strings; anything else is refused before any lookup. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final int MAX_LOGIN_NAMES = 2;
    private static final int MAX_LOGIN_NAME_LENGTH = 320;

    private final UserSessionRevoker revoker;
    private final byte[] internalToken;

    public InternalSessionRevocationController(UserSessionRevoker revoker,
                                               @Value("${kelta.internal.token:}") String internalToken) {
        this.revoker = revoker;
        this.internalToken = internalToken == null || internalToken.isBlank()
                ? null : internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/tenants/{tenantId}/users/{userId}/revoke-sessions")
    public ResponseEntity<Map<String, Object>> revokeSessions(
            @PathVariable String tenantId,
            @PathVariable String userId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) Map<String, Object> body) {
        if (internalToken == null) {
            log.warn("Internal session revocation called but kelta.internal.token is not configured");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (token == null || !MessageDigest.isEqual(internalToken, token.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Internal session revocation called without a valid internal token");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (!ID.matcher(tenantId).matches() || !ID.matcher(userId).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid id"));
        }
        UserSessionRevoker.Result result = revoker.signOutEverywhere(tenantId, userId, loginNames(body));
        return ResponseEntity.ok(Map.of(
                "sessionsEnded", result.sessionsEnded(),
                "authorizationsRevoked", result.authorizationsRevoked()));
    }

    private static List<String> loginNames(Map<String, Object> body) {
        if (body == null || !(body.get("loginNames") instanceof List<?> names)) {
            return List.of();
        }
        return names.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(name -> !name.isBlank() && name.length() <= MAX_LOGIN_NAME_LENGTH)
                .limit(MAX_LOGIN_NAMES)
                .toList();
    }
}
