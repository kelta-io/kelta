package io.kelta.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kelta.runtime.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link RegisteredClientRepository} that resolves connected-app OAuth2 clients
 * <b>dynamically</b> from the {@code connected_app} table on each lookup, and
 * delegates everything else to a wrapped {@link RegisteredClientRepository}
 * (the JDBC-backed store that holds the platform UI + Superset + internal
 * clients).
 *
 * <p>This is the connected-app analogue of {@code DynamicClientRegistrationRepository}
 * (external OIDC) and {@code DynamicRelyingPartyRegistrationRepository} (SAML):
 * config lives in the tenant database and takes effect <b>without a redeploy</b>.
 * It replaces the old startup {@code ConnectedAppClientSynchronizer}, which only
 * ever <i>added</i> authorization-code apps into {@code oauth2_registered_client}
 * at boot — so enabling/editing an app needed a kelta-auth restart, edits never
 * propagated, deactivations never removed the client, and client-credentials-only
 * apps were never registered at all.
 *
 * <p>Both grant families are served: an app's {@code grant_types} column drives
 * the {@link AuthorizationGrantType}s, so client-credentials and authorization-code
 * (with PKCE / consent) apps both work through the same fresh build. A short
 * cache ({@value #CACHE_TTL_SECONDS}s) keeps the OAuth hot path off the database
 * while keeping redirect-URI / scope edits near-live.
 *
 * <p><b>Identity stability:</b> the built {@link RegisteredClient} id is the
 * {@code connected_app.id} (UUID), so the id stored on an {@code OAuth2Authorization}
 * at {@code /oauth2/authorize} resolves back to the same app on the later token
 * exchange ({@link #findById}).
 */
public class ConnectedAppRegisteredClientRepository implements RegisteredClientRepository {

    private static final Logger log = LoggerFactory.getLogger(ConnectedAppRegisteredClientRepository.class);

    private static final long CACHE_TTL_SECONDS = 30;

    private static final String SELECT_BY_CLIENT_ID = """
            SELECT id, client_id, client_secret_hash, name, redirect_uris, scopes,
                   grant_types, require_pkce, consent_required
            FROM connected_app WHERE client_id = ? AND active = true
            """;

    private static final String SELECT_BY_ID = """
            SELECT id, client_id, client_secret_hash, name, redirect_uris, scopes,
                   grant_types, require_pkce, consent_required
            FROM connected_app WHERE id = ? AND active = true
            """;

    private final RegisteredClientRepository delegate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record CachedClient(RegisteredClient client, Instant cachedAt) {}

    private final Map<String, CachedClient> cache = new ConcurrentHashMap<>();

    public ConnectedAppRegisteredClientRepository(RegisteredClientRepository delegate,
                                                  JdbcTemplate jdbcTemplate) {
        this.delegate = delegate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        // Connected apps are authored in the connected_app table (via the generic
        // collection route), never through this repository — persist only the
        // config-registered clients (platform UI, Superset, internal).
        delegate.save(registeredClient);
    }

    @Override
    public RegisteredClient findById(String id) {
        RegisteredClient built = fromConnectedApp("id:" + id, SELECT_BY_ID, id);
        return built != null ? built : delegate.findById(id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        RegisteredClient built = fromConnectedApp("cid:" + clientId, SELECT_BY_CLIENT_ID, clientId);
        return built != null ? built : delegate.findByClientId(clientId);
    }

    /**
     * Builds a {@link RegisteredClient} from an active connected_app row (cached),
     * or {@code null} when no such app exists (caller falls back to the delegate).
     */
    private RegisteredClient fromConnectedApp(String cacheKey, String sql, String param) {
        CachedClient cached = cache.get(scoped(cacheKey));
        if (cached != null && cached.cachedAt().plusSeconds(CACHE_TTL_SECONDS).isAfter(Instant.now())) {
            return cached.client();
        }
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql, param);
        } catch (Exception e) {
            // Table not migrated yet, or a transient DB error — fall back to the delegate.
            log.debug("connected_app lookup failed ({}): {}", param, e.getMessage());
            return null;
        }
        if (rows.isEmpty()) {
            return null;
        }
        RegisteredClient client = build(rows.get(0));
        // Cache under both keys so /authorize (by clientId) and the token exchange
        // (by id) share one build within the TTL.
        Instant now = Instant.now();
        cache.put(scoped("id:" + client.getId()), new CachedClient(client, now));
        cache.put(scoped("cid:" + client.getClientId()), new CachedClient(client, now));
        return client;
    }

    /**
     * Prefixes a cache key with the tenant the lookup ran under. The {@code connected_app}
     * query carries no tenant predicate, so what it returns is whatever the connection's
     * row-level security allows — a tenant-bound /authorize sees only its own apps, a
     * session-less token exchange sees all of them. A cache keyed on the client id alone
     * would let the first caller's answer serve the next tenant's request.
     */
    private static String scoped(String cacheKey) {
        String tenantId = TenantContext.get();
        return (tenantId == null || tenantId.isBlank() ? "" : tenantId) + "|" + cacheKey;
    }

    private RegisteredClient build(Map<String, Object> app) {
        String id = String.valueOf(app.get("id"));
        String clientId = (String) app.get("client_id");
        String secretHash = (String) app.get("client_secret_hash");
        String name = (String) app.get("name");
        boolean requirePkce = Boolean.TRUE.equals(app.get("require_pkce"));
        boolean consentRequired = Boolean.TRUE.equals(app.get("consent_required"));

        List<String> redirectUris = parseJsonArray(app.get("redirect_uris"));
        List<String> scopes = parseJsonArray(app.get("scopes"));
        List<String> grantTypes = parseJsonArray(app.get("grant_types"));
        if (grantTypes.isEmpty()) {
            grantTypes = List.of("client_credentials");
        }

        RegisteredClient.Builder builder = RegisteredClient.withId(id)
                .clientId(clientId)
                .clientName(name);

        // A public (PKCE) client has no secret; a confidential client's secret is
        // already BCrypt-hashed in the DB (Spring expects the {bcrypt} prefix).
        if (requirePkce) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        } else {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
            if (secretHash != null && !secretHash.isBlank()) {
                builder.clientSecret("{bcrypt}" + secretHash);
            }
        }

        boolean hasAuthCode = false;
        for (String grantType : grantTypes) {
            switch (grantType) {
                case "authorization_code" -> {
                    builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
                    hasAuthCode = true;
                }
                case "client_credentials" ->
                        builder.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
                case "refresh_token" ->
                        builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
                default -> { /* ignore unknown grant types */ }
            }
        }
        if (hasAuthCode) {
            builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        }

        for (String uri : redirectUris) {
            builder.redirectUri(uri);
        }

        builder.scope(OidcScopes.OPENID);
        builder.scope(OidcScopes.PROFILE);
        builder.scope("email");
        for (String scope : scopes) {
            builder.scope(scope);
        }

        builder.clientSettings(ClientSettings.builder()
                .requireAuthorizationConsent(consentRequired)
                .requireProofKey(requirePkce)
                .build());
        builder.tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofHours(1))
                .refreshTokenTimeToLive(Duration.ofDays(7))
                .reuseRefreshTokens(false)
                .build());

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonArray(Object value) {
        if (value == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value.toString(), List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
