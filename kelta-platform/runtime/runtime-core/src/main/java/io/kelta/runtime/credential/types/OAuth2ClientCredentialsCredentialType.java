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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * OAuth 2.0 Client Credentials grant. The platform exchanges client_id/secret
 * for an access token at the token URL on each test/refresh.
 *
 * <p>The token URL is administrator-entered metadata, so the exchange goes through the
 * service's {@link OutboundUrlGuard} — see {@link OAuthTokenEndpointClient} for the limits.
 */
@Component
public class OAuth2ClientCredentialsCredentialType extends AbstractCredentialType {

    private final ObjectMapper objectMapper;
    private final OAuthTokenEndpointClient tokenEndpoint;

    @Autowired
    public OAuth2ClientCredentialsCredentialType(ObjectMapper objectMapper,
                                                 ObjectProvider<OutboundUrlGuard> urlGuard) {
        this(objectMapper, OAuthTokenEndpointClient.from(urlGuard));
    }

    /** With a pre-built client — tests use it to point at a loopback server. */
    OAuth2ClientCredentialsCredentialType(ObjectMapper objectMapper, OutboundHttpClient httpClient) {
        this(objectMapper, new OAuthTokenEndpointClient(httpClient));
    }

    private OAuth2ClientCredentialsCredentialType(ObjectMapper objectMapper,
                                                  OAuthTokenEndpointClient tokenEndpoint) {
        super(objectMapper);
        this.objectMapper = objectMapper;
        this.tokenEndpoint = tokenEndpoint;
    }

    @Override public String getKey()         { return "oauth2_client_credentials"; }
    @Override public String getDisplayName() { return "OAuth 2.0 — Client Credentials"; }
    @Override public String getDescription() {
        return "Server-to-server OAuth: exchange client ID/secret for an access token.";
    }

    @Override public Set<String> getSecretFields()   { return Set.of("clientId", "clientSecret"); }
    @Override public Set<String> getMetadataFields() {
        return Set.of("tokenUrl", "scopes", "audience");
    }

    @Override public boolean supportsOAuthRefresh() { return true; }

    @Override
    public List<String> validate(ObjectNode plaintext) {
        return validateRequired(plaintext, "clientId", "clientSecret", "tokenUrl");
    }

    @Override
    public CredentialTestResult test(CredentialMaterial material, ObjectNode metadata) {
        try {
            OAuthRefreshOutcome outcome = exchangeToken(material.plaintext(), metadata);
            return CredentialTestResult.success(
                "Token exchange succeeded; access token expires "
                    + (outcome.expiresAt() == null ? "n/a" : outcome.expiresAt()));
        } catch (Exception e) {
            return CredentialTestResult.failure("Token exchange failed: " + e.getMessage());
        }
    }

    @Override
    public OAuthRefreshOutcome refresh(CredentialMaterial material,
                                        ObjectNode metadata,
                                        OAuthTokenState current) {
        try {
            return exchangeToken(material.plaintext(), metadata);
        } catch (Exception e) {
            throw new RuntimeException("OAuth client_credentials refresh failed: "
                + e.getMessage(), e);
        }
    }

    private OAuthRefreshOutcome exchangeToken(ObjectNode plaintext, ObjectNode metadata)
            throws Exception {
        String tokenUrl = string(metadata, "tokenUrl");
        String clientId = string(plaintext, "clientId");
        String clientSecret = string(plaintext, "clientSecret");
        if (tokenUrl == null) {
            throw new IllegalStateException("tokenUrl missing");
        }

        StringBuilder body = new StringBuilder();
        body.append("grant_type=client_credentials");
        appendForm(body, "client_id", clientId);
        appendForm(body, "client_secret", clientSecret);

        JsonNode scopesNode = metadata.get("scopes");
        if (scopesNode != null && scopesNode.isArray() && !scopesNode.isEmpty()) {
            String scopes = StreamSupport.stream(scopesNode.spliterator(), false)
                .map(n -> n.isTextual() ? n.stringValue() : n.toString())
                .collect(Collectors.joining(" "));
            appendForm(body, "scope", scopes);
        }
        String audience = string(metadata, "audience");
        if (audience != null) {
            appendForm(body, "audience", audience);
        }

        OutboundHttpClient.Response res = tokenEndpoint.postForm(tokenUrl, body.toString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                "Token endpoint returned HTTP " + res.statusCode());
        }
        JsonNode json = objectMapper.readTree(res.bodyAsString());
        String accessToken = textOrNull(json, "access_token");
        if (accessToken == null) {
            throw new IllegalStateException("Token response missing access_token");
        }
        Long expiresIn = json.has("expires_in") && json.get("expires_in").isNumber()
            ? json.get("expires_in").asLong()
            : null;
        Instant expiresAt = expiresIn != null
            ? Instant.now().plusSeconds(expiresIn)
            : null;

        ObjectNode sanitized = objectMapper.createObjectNode();
        Iterator<java.util.Map.Entry<String, JsonNode>> it = json.properties().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            // Keep only non-secret fields in the audit copy of the response.
            if (!"access_token".equals(entry.getKey())
                    && !"refresh_token".equals(entry.getKey())
                    && !"id_token".equals(entry.getKey())) {
                sanitized.set(entry.getKey(), entry.getValue());
            }
        }

        return new OAuthRefreshOutcome(
            accessToken,
            null,                                      // client_credentials: no refresh token
            textOrNull(json, "token_type"),
            expiresAt,
            textOrNull(json, "scope"),
            sanitized);
    }

    private static void appendForm(StringBuilder sb, String key, String value) {
        if (value == null) return;
        sb.append('&').append(java.net.URLEncoder.encode(key, StandardCharsets.UTF_8))
          .append('=').append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.stringValue() : null;
    }
}
