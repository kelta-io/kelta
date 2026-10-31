package io.kelta.auth.controller;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.LoginMfaPolicy;
import io.kelta.auth.service.PasswordPolicyService;
import io.kelta.auth.service.TotpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Direct login endpoint for programmatic authentication.
 * <p>
 * Accepts username/password and returns JWT tokens directly, bypassing the
 * browser-based OIDC authorization code flow. This enables automated testing
 * (e.g. headless Chrome e2e tests) to authenticate without browser redirects.
 * <p>
 * Enabled only when {@code kelta.auth.direct-login.enabled=true}. The toggle
 * is evaluated per-request rather than via {@code @ConditionalOnProperty}
 * because Spring AOT (GraalVM native image) resolves bean conditions at build
 * time, where the runtime env var {@code DIRECT_LOGIN_ENABLED} is unset and
 * the bean would otherwise be pruned from the binary.
 * <p>
 * Same password checks as the browser sign-in: the request goes through the shared
 * {@link AuthenticationManager}, so a lockout refuses it and a wrong password counts towards one
 * ({@code AuthenticationEventListener}). There is no second step here, so an account the browser
 * sign-in would send to an MFA step ({@link LoginMfaPolicy}: an enrolled TOTP, or a tenant password
 * policy that requires MFA) is refused with {@code 401 mfa_required} and gets no token.
 */
@RestController
@RequestMapping("/auth/direct-login")
public class DirectLoginController {

    private static final Logger log = LoggerFactory.getLogger(DirectLoginController.class);

    private static final long ACCESS_TOKEN_TTL_HOURS = 8;
    private static final long REFRESH_TOKEN_TTL_DAYS = 7;

    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final AuthProperties authProperties;
    private final LoginMfaPolicy mfaPolicy;

    public DirectLoginController(AuthenticationManager authenticationManager,
                                 JwtEncoder jwtEncoder,
                                 AuthProperties authProperties,
                                 TotpService totpService,
                                 PasswordPolicyService passwordPolicyService) {
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.authProperties = authProperties;
        this.mfaPolicy = new LoginMfaPolicy(totpService, passwordPolicyService);
    }

    @PostMapping
    public ResponseEntity<?> login(@RequestBody DirectLoginRequest request, HttpSession session,
                                    HttpServletRequest httpRequest) {
        if (!authProperties.getDirectLogin().isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        if (request.username() == null || request.password() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "username and password are required"));
        }

        // Set tenant context in the session so KeltaUserDetailsService can scope
        // the user lookup to the correct tenant (same as LoginController does for OIDC).
        if (request.tenantSlug() != null && !request.tenantSlug().isBlank()) {
            session.setAttribute("tenantId", request.tenantSlug());
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (CredentialsExpiredException e) {
            log.info("Direct login rejected for user {}: credentials expired (force change required)",
                    request.username());
            String forceChangeUrl = ServletUriComponentsBuilder.fromRequestUri(httpRequest)
                    .replacePath("/change-password")
                    .replaceQuery(null)
                    .build()
                    .toUriString();
            return ResponseEntity.status(403)
                    .body(Map.of(
                            "error", "credentials_expired",
                            "error_description", "Password must be changed before this account can authenticate",
                            "force_change_url", forceChangeUrl));
        } catch (AuthenticationException e) {
            log.warn("Direct login failed for user {}: {}", request.username(), e.getMessage());
            return ResponseEntity.status(401)
                    .body(Map.of("error", "invalid_credentials", "error_description", "Authentication failed"));
        }

        if (!(authentication.getPrincipal() instanceof KeltaUserDetails userDetails)) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "server_error", "error_description", "Unexpected principal type"));
        }

        // The browser sign-in would continue with an MFA step here; this endpoint has none.
        LoginMfaPolicy.Step mfaStep = mfaPolicy.requiredStep(userDetails.getId(), userDetails.getTenantId());
        if (mfaStep != LoginMfaPolicy.Step.NONE) {
            log.info("Direct login refused for user {} (tenant: {}): the account requires MFA ({})",
                    userDetails.getEmail(), userDetails.getTenantId(), mfaStep);
            return ResponseEntity.status(401)
                    .body(Map.of(
                            "error", "mfa_required",
                            "error_description", "This account requires multi-factor authentication; "
                                    + "use the browser sign-in"));
        }

        Instant now = Instant.now();
        String issuer = authProperties.getIssuerUri();
        String subject = userDetails.getId();

        // Build access token with the same claims KeltaTokenCustomizer would add
        JwtClaimsSet accessTokenClaims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .audience(java.util.List.of("kelta-platform"))
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TOKEN_TTL_HOURS, ChronoUnit.HOURS))
                .id(UUID.randomUUID().toString())
                .claim("scope", "openid profile email")
                .claim("email", userDetails.getEmail())
                .claim("preferred_username", userDetails.getEmail())
                .claim("tenant_id", userDetails.getTenantId())
                .claim("profile_id", userDetails.getProfileId())
                .claim("profile_name", userDetails.getProfileName())
                .claim("auth_method", "internal")
                .build();

        JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String accessToken = jwtEncoder.encode(
                JwtEncoderParameters.from(jwsHeader, accessTokenClaims)).getTokenValue();

        // Build ID token
        JwtClaimsSet idTokenClaims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .audience(java.util.List.of("kelta-platform"))
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TOKEN_TTL_HOURS, ChronoUnit.HOURS))
                .id(UUID.randomUUID().toString())
                .claim("email", userDetails.getEmail())
                .claim("name", userDetails.getDisplayName())
                .claim("preferred_username", userDetails.getEmail())
                .claim("tenant_id", userDetails.getTenantId())
                .claim("profile_id", userDetails.getProfileId())
                .claim("profile_name", userDetails.getProfileName())
                .build();

        String idToken = jwtEncoder.encode(
                JwtEncoderParameters.from(jwsHeader, idTokenClaims)).getTokenValue();

        // Build refresh token (opaque, shorter claims)
        JwtClaimsSet refreshTokenClaims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(REFRESH_TOKEN_TTL_DAYS, ChronoUnit.DAYS))
                .id(UUID.randomUUID().toString())
                .claim("token_type", "refresh")
                .build();

        String refreshToken = jwtEncoder.encode(
                JwtEncoderParameters.from(jwsHeader, refreshTokenClaims)).getTokenValue();

        log.info("Direct login succeeded for user {} (tenant: {})", userDetails.getEmail(), userDetails.getTenantId());

        return ResponseEntity.ok(Map.of(
                "access_token", accessToken,
                "id_token", idToken,
                "refresh_token", refreshToken,
                "token_type", "Bearer",
                "expires_in", ACCESS_TOKEN_TTL_HOURS * 3600
        ));
    }

    public record DirectLoginRequest(String username, String password, String tenantSlug) {}
}
