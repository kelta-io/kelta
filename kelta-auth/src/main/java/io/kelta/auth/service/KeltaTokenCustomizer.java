package io.kelta.auth.service;

import io.kelta.auth.model.KeltaUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class KeltaTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private static final Logger log = LoggerFactory.getLogger(KeltaTokenCustomizer.class);

    private final JdbcTemplate jdbcTemplate;
    private final ConnectedAppTokenRecorder tokenRecorder;

    public KeltaTokenCustomizer(JdbcTemplate jdbcTemplate, ConnectedAppTokenRecorder tokenRecorder) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenRecorder = tokenRecorder;
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
                customizeAccessToken(context, userDetails);
            }
        }
    }

    /**
     * Enriches client_credentials tokens with connected app metadata.
     * These tokens identify machine clients, not human users.
     */
    private void customizeClientCredentialsToken(JwtEncodingContext context) {
        String clientId = context.getRegisteredClient().getClientId();

        // Look up connected app by client_id to get tenant_id and scopes
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, scopes FROM connected_app WHERE client_id = ? AND active = true",
                clientId
        );

        if (results.isEmpty()) {
            log.warn("No active connected app found for client_id={}", clientId);
            return;
        }

        Map<String, Object> app = results.get(0);
        String appId = String.valueOf(app.get("id"));
        String tenantId = app.get("tenant_id") != null ? app.get("tenant_id").toString() : null;
        Object scopes = app.get("scopes");
        String scopesJson = scopes != null ? scopes.toString() : null;

        // Pin a stable jti so the recorded token row and a later revoke can reference the same id.
        String jti = java.util.UUID.randomUUID().toString();

        context.getClaims().claims(claims -> {
            claims.put("tenant_id", app.get("tenant_id"));
            claims.put("connected_app_id", app.get("id"));
            claims.put("auth_method", "api_key");
            if (scopesJson != null) {
                claims.put("app_scopes", scopesJson);
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

    /** Returns the active connected app for a client_id (id, tenant_id, scopes), or null. */
    private Map<String, Object> lookupConnectedApp(String clientId) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, scopes FROM connected_app WHERE client_id = ? AND active = true",
                clientId
        );
        return results.isEmpty() ? null : results.get(0);
    }
}
