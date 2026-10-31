package io.kelta.auth.service;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.config.InternalClientRegistrar;
import io.kelta.auth.model.KeltaUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Adds Kelta claims to tokens minted by the authorization server.
 *
 * <p>Fails closed on {@code tenant_id}: the gateway rejects every JWT without one on a tenant
 * route, so an access token for a tenant-scoped principal that cannot be given a tenant is
 * refused here rather than issued. The one tenant-less access token is the platform-scoped
 * {@code client_credentials} token of an internal service client registered by
 * {@link InternalClientRegistrar}, which calls the worker's {@code /internal/**} directly and
 * never reaches a gateway tenant route.
 *
 * <p>A {@code client_credentials} token for a connected app bound to a {@code SERVICE} user
 * ({@code connected_app.run_as_user_id}, a module service principal) also carries that user's
 * identity — {@code run_as_user_id}, {@code user_type}, {@code profile_id}, {@code profile_name},
 * {@code email} / {@code preferred_username} — so the gateway authorizes it as that user. The
 * binding is refused unless the user is an active {@code SERVICE} user of the app's own tenant
 * with a profile: an app pointed at anything else gets no token rather than a weaker one.
 */
@Component
public class KeltaTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private static final Logger log = LoggerFactory.getLogger(KeltaTokenCustomizer.class);

    /** {@code platform_user.user_type} of a module service principal. */
    static final String SERVICE_USER_TYPE = "SERVICE";

    static final String SELECT_CLIENT_CREDENTIALS_APP =
            "SELECT id, tenant_id, scopes, run_as_user_id FROM connected_app WHERE client_id = ? AND active = true";

    /**
     * The run-as user of a connected app: only an active SERVICE user of the app's own tenant
     * qualifies. Runs with no tenant bound (the token endpoint is session-less), so the tenant
     * predicate is explicit.
     */
    static final String SELECT_RUN_AS_USER = """
            SELECT u.id, u.email, u.profile_id, p.name AS profile_name
            FROM platform_user u
            LEFT JOIN profile p ON p.id = u.profile_id AND p.tenant_id = u.tenant_id
            WHERE u.id = ? AND u.tenant_id = ? AND u.user_type = 'SERVICE' AND u.status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ConnectedAppTokenRecorder tokenRecorder;
    private final AuthProperties authProperties;

    public KeltaTokenCustomizer(JdbcTemplate jdbcTemplate, ConnectedAppTokenRecorder tokenRecorder,
                                AuthProperties authProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenRecorder = tokenRecorder;
        this.authProperties = authProperties;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        // Handle client_credentials grant (connected apps / API keys)
        if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
            if (context.getTokenType().getValue().equals("access_token")) {
                customizeClientCredentialsToken(context);
            }
            return;
        }

        // Handle user authentication (authorization_code grant)
        Authentication principal = context.getPrincipal();
        if (principal.getPrincipal() instanceof KeltaUserDetails userDetails) {
            if (context.getTokenType().getValue().equals(OidcParameterNames.ID_TOKEN)) {
                customizeIdToken(context, userDetails);
            } else if (context.getTokenType().getValue().equals("access_token")) {
                if (isBlank(userDetails.getTenantId())) {
                    throw tenantlessToken("User " + userDetails.getId() + " has no tenant");
                }
                customizeAccessToken(context, userDetails);
            }
        } else if (context.getTokenType().getValue().equals("access_token")) {
            Object unsupported = principal.getPrincipal();
            throw tenantlessToken("Unsupported principal type "
                    + (unsupported != null ? unsupported.getClass().getSimpleName() : null));
        }
    }

    /**
     * Enriches client_credentials tokens with connected app metadata.
     * These tokens identify machine clients, not human users.
     */
    private void customizeClientCredentialsToken(JwtEncodingContext context) {
        String clientId = context.getRegisteredClient().getClientId();

        // Look up connected app by client_id to get tenant_id, scopes and the run-as user
        List<Map<String, Object>> results = jdbcTemplate.queryForList(SELECT_CLIENT_CREDENTIALS_APP, clientId);

        if (results.isEmpty()) {
            if (isInternalPlatformClient(context.getRegisteredClient())) {
                log.debug("Issuing platform-scoped token for internal client {}", clientId);
                return;
            }
            throw tenantlessToken("No active connected app for client " + clientId);
        }

        Map<String, Object> app = results.get(0);
        String appId = String.valueOf(app.get("id"));
        String tenantId = app.get("tenant_id") != null ? app.get("tenant_id").toString() : null;
        if (isBlank(tenantId)) {
            throw tenantlessToken("Connected app " + appId + " has no tenant");
        }
        Object scopes = app.get("scopes");
        String scopesJson = scopes != null ? scopes.toString() : null;
        Object runAsUserId = app.get("run_as_user_id");
        Map<String, Object> runAs = runAsUserId != null && !runAsUserId.toString().isBlank()
                ? resolveRunAsUser(appId, runAsUserId.toString(), tenantId)
                : null;

        // Pin a stable jti so the recorded token row and a later revoke can reference the same id.
        String jti = java.util.UUID.randomUUID().toString();

        context.getClaims().claims(claims -> {
            claims.put("tenant_id", app.get("tenant_id"));
            claims.put("connected_app_id", app.get("id"));
            claims.put("auth_method", "api_key");
            if (scopesJson != null) {
                claims.put("app_scopes", scopesJson);
            }
            if (runAs != null) {
                claims.put("run_as_user_id", runAs.get("id"));
                claims.put("user_type", SERVICE_USER_TYPE);
                claims.put("profile_id", runAs.get("profile_id"));
                if (runAs.get("profile_name") != null) {
                    claims.put("profile_name", runAs.get("profile_name"));
                }
                claims.put("email", runAs.get("email"));
                claims.put("preferred_username", runAs.get("email"));
            }
        });
        context.getClaims().id(jti);
        log.debug("Enriched client_credentials token for app {} (client_id={})", appId, clientId);

        // Record the issued token so it appears in the app's token list, refreshes
        // last_used_at, and leaves an audit trail. Best-effort — never blocks issuance.
        var tokenSettings = context.getRegisteredClient().getTokenSettings();
        java.time.Duration accessTtl = tokenSettings != null ? tokenSettings.getAccessTokenTimeToLive() : null;
        tokenRecorder.recordIssuedToken(appId, tenantId, scopesJson, jti, accessTtl);
    }

    /**
     * The identity a run-as connected app's token carries, or a refusal: the bound user must be an
     * active {@code SERVICE} user of the app's tenant with a profile. Any other binding — an
     * interactive user, another tenant's user, a deactivated principal — would let whoever can
     * point {@code run_as_user_id} at a row mint that row's identity, so no token is issued.
     */
    private Map<String, Object> resolveRunAsUser(String appId, String userId, String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_RUN_AS_USER, userId, tenantId);
        if (rows.isEmpty()) {
            throw runAsRefused("Connected app " + appId + " runs as " + userId
                    + ", which is not an active SERVICE user of tenant " + tenantId);
        }
        Map<String, Object> user = rows.get(0);
        if (user.get("profile_id") == null || isBlank(user.get("profile_id").toString())) {
            throw runAsRefused("Run-as user " + userId + " of connected app " + appId + " has no profile");
        }
        return user;
    }

    private static OAuth2AuthenticationException runAsRefused(String reason) {
        log.warn("Refusing to issue a run-as access token: {}", reason);
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED,
                "Access token cannot be issued for this client", null));
    }

    private void customizeIdToken(JwtEncodingContext context, KeltaUserDetails userDetails) {
        context.getClaims().claims(claims -> {
            claims.put("email", userDetails.getEmail());
            claims.put("name", userDetails.getDisplayName());
            claims.put("preferred_username", userDetails.getEmail());
            claims.put("tenant_id", userDetails.getTenantId());
            claims.put("profile_id", userDetails.getProfileId());
            claims.put("user_type", userDetails.getUserType());
            if (userDetails.getProfileName() != null) {
                claims.put("profile_name", userDetails.getProfileName());
            }
        });
    }

    private void customizeAccessToken(JwtEncodingContext context, KeltaUserDetails userDetails) {
        // For the authorization_code flow via a connected app, resolve the app so we
        // can both enrich the token and record it in the app's token list.
        boolean authCode = AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType());
        Map<String, Object> app = authCode
                ? lookupConnectedApp(context.getRegisteredClient().getClientId())
                : null;
        // Pin a stable jti so the recorded token row and a later revoke reference the same id.
        String jti = app != null ? java.util.UUID.randomUUID().toString() : null;

        context.getClaims().claims(claims -> {
            claims.put("email", userDetails.getEmail());
            claims.put("preferred_username", userDetails.getEmail());
            claims.put("tenant_id", userDetails.getTenantId());
            claims.put("profile_id", userDetails.getProfileId());
            claims.put("user_type", userDetails.getUserType());
            if (userDetails.getProfileName() != null) {
                claims.put("profile_name", userDetails.getProfileName());
            }
            // Indicate whether this user authenticated via internal login or SSO. Not derived
            // from getPassword(): the hash is erased from the principal after authentication.
            String authMethod = userDetails.hasLocalCredential() ? "internal" : "sso";
            claims.put("auth_method", authMethod);

            if (app != null) {
                // Connected-app user-delegated token: surface app metadata so the
                // gateway can apply connected-app rate limits / scope enforcement.
                claims.put("connected_app_id", app.get("id"));
                Object scopes = app.get("scopes");
                if (scopes != null) {
                    claims.put("app_scopes", scopes.toString());
                }
                claims.put("auth_method", "connected_app");
            }
        });

        if (app != null) {
            context.getClaims().id(jti);
            String appId = String.valueOf(app.get("id"));
            String tenantId = app.get("tenant_id") != null ? app.get("tenant_id").toString() : null;
            Object scopes = app.get("scopes");
            String scopesJson = scopes != null ? scopes.toString() : null;
            var tokenSettings = context.getRegisteredClient().getTokenSettings();
            java.time.Duration accessTtl = tokenSettings != null ? tokenSettings.getAccessTokenTimeToLive() : null;
            // So an authorization_code token also appears in the app's token list /
            // audit trail (the same promise client_credentials already keeps).
            tokenRecorder.recordIssuedToken(appId, tenantId, scopesJson, jti, accessTtl, "authorization_code");
        }
    }

    /**
     * An internal service client is one {@link InternalClientRegistrar} provisions: configured
     * under {@code kelta.auth.internal-clients} with a secret, and holding the {@code internal}
     * scope. Its tokens are platform-scoped, so they legitimately carry no {@code tenant_id}.
     */
    private boolean isInternalPlatformClient(RegisteredClient client) {
        AuthProperties.InternalClient configured = authProperties.getInternalClients().get(client.getClientId());
        return configured != null
                && !isBlank(configured.getSecret())
                && client.getScopes().contains(InternalClientRegistrar.INTERNAL_SCOPE);
    }

    private static OAuth2AuthenticationException tenantlessToken(String reason) {
        log.warn("Refusing to issue an access token without tenant_id: {}", reason);
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED,
                "Access token cannot be issued without a tenant", null));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Returns the active connected app for a client_id (id, tenant_id, scopes), or null. */
    private Map<String, Object> lookupConnectedApp(String clientId) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, scopes FROM connected_app WHERE client_id = ? AND active = true",
                clientId
        );
        return results.isEmpty() ? null : results.get(0);
    }
}
