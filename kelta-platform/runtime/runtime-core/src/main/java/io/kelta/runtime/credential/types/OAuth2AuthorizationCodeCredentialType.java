package io.kelta.runtime.credential.types;

import io.kelta.runtime.credential.CredentialMaterial;
import io.kelta.runtime.credential.CredentialTestResult;
import io.kelta.runtime.credential.OAuthRefreshOutcome;
import io.kelta.runtime.credential.OAuthTokenState;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * OAuth 2.0 Authorization Code grant. The user authorizes the platform via a
 * browser popup; the resulting code is exchanged for access/refresh tokens at
 * the token URL. Refresh uses the stored refresh_token.
 *
 * <p>The token URL is administrator-entered metadata, so code exchange and refresh go through
 * the service's {@link OutboundUrlGuard} — see {@link OAuthTokenEndpointClient} for the limits.
 */
@Component
public class OAuth2AuthorizationCodeCredentialType extends AbstractCredentialType {

    /** At most this much of an error response is quoted in the failure message. */
    private static final int MAX_ERROR_BODY_CHARS = 1_000;

    private final ObjectMapper objectMapper;
    private final OAuthTokenEndpointClient tokenEndpoint;

    @Autowired
    public OAuth2AuthorizationCodeCredentialType(ObjectMapper objectMapper,
                                                 ObjectProvider<OutboundUrlGuard> urlGuard) {
        this(objectMapper, OAuthTokenEndpointClient.from(urlGuard));
    }

    /** With a pre-built client — tests use it to point at a loopback server. */
    OAuth2AuthorizationCodeCredentialType(ObjectMapper objectMapper, OutboundHttpClient httpClient) {
        this(objectMapper, new OAuthTokenEndpointClient(httpClient));
    }

    private OAuth2AuthorizationCodeCredentialType(ObjectMapper objectMapper,
                                                  OAuthTokenEndpointClient tokenEndpoint) {
        super(objectMapper);
        this.objectMapper = objectMapper;
        this.tokenEndpoint = tokenEndpoint;
    }

    @Override public String getKey()         { return "oauth2_authorization_code"; }
    @Override public String getDisplayName() { return "OAuth 2.0 — Authorization Code"; }
    @Override public String getDescription() {
        return "Browser-based OAuth: user authorizes; platform exchanges the code for tokens.";
    }

    @Override public Set<String> getSecretFields()   { return Set.of("clientId", "clientSecret"); }
    @Override public Set<String> getMetadataFields() {
        return Set.of("authorizationUrl", "tokenUrl", "scopes", "redirectUri");
    }

    @Override public boolean supportsOAuthRefresh() { return true; }

    @Override
    public List<String> validate(ObjectNode plaintext) {
        return validateRequired(plaintext,
            "clientId", "clientSecret", "authorizationUrl", "tokenUrl");
    }

    @Override
    public CredentialTestResult test(CredentialMaterial material, ObjectNode metadata) {
        // Auth code flow can't be tested at save time without the user having
        // already completed the browser dance. The connect step does that;
        // here we just confirm the inputs parse cleanly.
        return CredentialTestResult.success(
            "Saved. Click 'Connect' to start the authorization flow.");
    }

    @Override
    public OAuthRefreshOutcome refresh(CredentialMaterial material,
                                        ObjectNode metadata,
                                        OAuthTokenState current) {
        try {
            if (current == null || current.refreshToken() == null
                    || current.refreshToken().isBlank()) {
                throw new IllegalStateException(
                    "No refresh token stored; user must reauthorize.");
            }
            return refreshToken(material.plaintext(), metadata, current.refreshToken());
        } catch (Exception e) {
            throw new RuntimeException("OAuth authorization_code refresh failed: "
                + e.getMessage(), e);
        }
    }

    /**
     * Exchanges an authorization code for tokens. Called by the OAuth completion
     * endpoint after the user finishes the browser flow.
     */
    public OAuthRefreshOutcome exchangeCode(ObjectNode plaintext, ObjectNode metadata,
                                             String code, String redirectUri) {
        try {
            return doTokenRequest(plaintext, metadata,
                "grant_type=authorization_code"
                    + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("OAuth code exchange failed: " + e.getMessage(), e);
        }
    }

    private OAuthRefreshOutcome refreshToken(ObjectNode plaintext, ObjectNode metadata,
                                              String refreshToken) throws Exception {
        return doTokenRequest(plaintext, metadata,
            "grant_type=refresh_token"
                + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8));
    }

    private OAuthRefreshOutcome doTokenRequest(ObjectNode plaintext, ObjectNode metadata,
                                                String formPrefix) throws Exception {
        String tokenUrl = textOrNull(metadata, "tokenUrl");
        String clientId = textOrNull(plaintext, "clientId");
        String clientSecret = textOrNull(plaintext, "clientSecret");
        if (tokenUrl == null) {
            throw new IllegalStateException("tokenUrl missing");
        }

        StringBuilder body = new StringBuilder(formPrefix);
        if (clientId != null) {
            body.append("&client_id=")
                .append(URLEncoder.encode(clientId, StandardCharsets.UTF_8));
        }
        if (clientSecret != null) {
            body.append("&client_secret=")
                .append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
        }

        OutboundHttpClient.Response res = tokenEndpoint.postForm(tokenUrl, body.toString());
        if (res.statusCode() / 100 != 2) {
            String errorBody = res.bodyAsString();
            throw new IllegalStateException(
                "Token endpoint returned HTTP " + res.statusCode() + ": "
                    + errorBody.substring(0, Math.min(errorBody.length(), MAX_ERROR_BODY_CHARS)));
        }

        JsonNode json = objectMapper.readTree(res.bodyAsString());
        String accessToken = textOrNull(json, "access_token");
        if (accessToken == null) {
            throw new IllegalStateException("Token response missing access_token");
        }
        Long expiresIn = json.has("expires_in") && json.get("expires_in").isNumber()
            ? json.get("expires_in").asLong() : null;
        Instant expiresAt = expiresIn != null ? Instant.now().plusSeconds(expiresIn) : null;

        ObjectNode sanitized = objectMapper.createObjectNode();
        Iterator<java.util.Map.Entry<String, JsonNode>> it = json.properties().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!"access_token".equals(entry.getKey())
                    && !"refresh_token".equals(entry.getKey())
                    && !"id_token".equals(entry.getKey())) {
                sanitized.set(entry.getKey(), entry.getValue());
            }
        }

        return new OAuthRefreshOutcome(
            accessToken,
            textOrNull(json, "refresh_token"),
            textOrNull(json, "token_type"),
            expiresAt,
            textOrNull(json, "scope"),
            sanitized);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isTextual()) {
            String s = v.stringValue();
            return s.isBlank() ? null : s;
        }
        return v.toString();
    }
}
