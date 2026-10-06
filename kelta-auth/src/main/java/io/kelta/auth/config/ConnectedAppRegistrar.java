package io.kelta.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Registers connected apps (e.g. Superset) as OAuth2 clients at startup.
 * Client credentials are read from environment/config so secrets stay out of migrations.
 */
@Component
public class ConnectedAppRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConnectedAppRegistrar.class);

    static final Duration PLATFORM_ACCESS_TOKEN_TTL = Duration.ofHours(1);
    static final Duration PLATFORM_REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private final RegisteredClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public ConnectedAppRegistrar(RegisteredClientRepository clientRepository,
                                 PasswordEncoder passwordEncoder,
                                 AuthProperties properties) {
        this.clientRepository = clientRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        registerPlatformClient();
        registerCliClient();
        registerSupersetClient();
    }

    /**
     * Registers the platform UI as a public OAuth2 client.
     * The UI is a browser SPA that uses PKCE (no client secret).
     * Redirect URIs cover all tenant slugs: {ui-base-url}/{tenant}/auth/callback
     */
    private void registerPlatformClient() {
        String clientId = "kelta-platform";

        RegisteredClient existing = clientRepository.findByClientId(clientId);

        String uiBaseUrl = properties.getUiBaseUrl();
        if (uiBaseUrl == null || uiBaseUrl.isBlank()) {
            uiBaseUrl = "http://localhost:5173";
        }
        // Remove trailing slash
        if (uiBaseUrl.endsWith("/")) {
            uiBaseUrl = uiBaseUrl.substring(0, uiBaseUrl.length() - 1);
        }

        String registrationId = existing != null ? existing.getId() : UUID.randomUUID().toString();
        String expectedRedirectUri = uiBaseUrl + "/auth/callback";

        // If the client already exists with the correct redirect URI and token settings,
        // nothing to do. Token settings are compared too: before they were, a TTL change in
        // code never reached an existing database row.
        TokenSettings tokenSettings = platformTokenSettings();
        if (existing != null && existing.getRedirectUris().contains(expectedRedirectUri)
                && existing.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)
                && sameTokenLifetimes(existing.getTokenSettings(), tokenSettings)) {
            log.info("Platform OAuth2 client '{}' already registered with correct redirect URI", clientId);
            return;
        }

        RegisteredClient platformClient = RegisteredClient.withId(registrationId)
                .clientId(clientId)
                .clientName("Kelta Platform UI")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                // Base redirect URI — the actual tenant-scoped URI is validated by
                // PlatformRedirectUriValidator which allows any path under this origin
                .redirectUri(expectedRedirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("email")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .build())
                .tokenSettings(tokenSettings)
                .build();

        clientRepository.save(platformClient);
        if (existing == null) {
            log.info("Registered Platform OAuth2 client '{}' with redirect URI '{}'", clientId, expectedRedirectUri);
        } else {
            log.info("Updated Platform OAuth2 client '{}': redirect URI '{}' (was '{}'), access TTL {}, refresh TTL {}",
                    clientId, expectedRedirectUri, existing.getRedirectUris(),
                    tokenSettings.getAccessTokenTimeToLive(), tokenSettings.getRefreshTokenTimeToLive());
        }
    }

    /**
     * Token lifetimes for the platform UI. The SPA renews its access token in the background
     * before it expires, so the access token can stay short; the refresh token is rotated on
     * every use and each new one gets a fresh TTL, so {@link #PLATFORM_REFRESH_TOKEN_TTL} is an
     * idle limit — a session only ends after that long with no open tab refreshing it.
     */
    static TokenSettings platformTokenSettings() {
        return TokenSettings.builder()
                .accessTokenTimeToLive(PLATFORM_ACCESS_TOKEN_TTL)
                .refreshTokenTimeToLive(PLATFORM_REFRESH_TOKEN_TTL)
                .reuseRefreshTokens(false)
                .build();
    }

    private static boolean sameTokenLifetimes(TokenSettings actual, TokenSettings expected) {
        return expected.getAccessTokenTimeToLive().equals(actual.getAccessTokenTimeToLive())
                && expected.getRefreshTokenTimeToLive().equals(actual.getRefreshTokenTimeToLive())
                && expected.isReuseRefreshTokens() == actual.isReuseRefreshTokens();
    }

    /**
     * Registers the kelta CLI as a public OAuth2 client (spec: specs/kelta-cli/2-browser-login.md).
     * <p>
     * The CLI runs authorization_code + PKCE with an RFC 8252 loopback redirect —
     * {@code http://127.0.0.1:<os-assigned port>/<tenant-slug>/auth/callback} — validated
     * port-agnostically by {@code PlatformRedirectUriValidator}. The URI registered here is
     * only a template: the validator's loopback branch does the real matching, so an existing
     * registration from an earlier release keeps working without a migration. The tenant slug
     * is part of the path because {@code TenantContextFilter} derives the login's tenant from
     * exactly that shape, the same way it does for the SPA. No refresh token is issued on
     * purpose: the access token lives just long enough for the CLI to mint a PAT via
     * {@code POST /api/me/tokens} and is then discarded, so the PAT is the only durable
     * credential on the developer's machine.
     */
    private void registerCliClient() {
        String clientId = "kelta-cli";

        if (clientRepository.findByClientId(clientId) != null) {
            log.info("CLI OAuth2 client '{}' already registered", clientId);
            return;
        }

        RegisteredClient cliClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName("Kelta CLI")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1/tenant/auth/callback")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("email")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .build())
                .build();

        clientRepository.save(cliClient);
        log.info("Registered CLI OAuth2 client '{}' (PKCE public client, loopback redirect)", clientId);
    }

    private void registerSupersetClient() {
        if (properties.getSupersetClientId() == null || properties.getSupersetClientId().isBlank()) {
            log.info("No Superset client configured (kelta.auth.superset-client-id not set), skipping registration");
            return;
        }

        String clientId = properties.getSupersetClientId();
        String clientSecret = properties.getSupersetClientSecret();
        String redirectUri = properties.getSupersetRedirectUri();

        if (clientSecret == null || clientSecret.isBlank()) {
            log.warn("Superset client secret not set, skipping registration");
            return;
        }

        RegisteredClient existing = clientRepository.findByClientId(clientId);
        String registrationId = existing != null ? existing.getId() : UUID.randomUUID().toString();

        if (existing != null && existing.getRedirectUris().contains(redirectUri)) {
            log.info("Superset OAuth2 client '{}' already registered with correct redirect URI", clientId);
            return;
        }

        RegisteredClient supersetClient = RegisteredClient.withId(registrationId)
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode(clientSecret))
                .clientName("Apache Superset")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("email")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .refreshTokenTimeToLive(Duration.ofHours(8))
                        .reuseRefreshTokens(false)
                        .build())
                .build();

        clientRepository.save(supersetClient);
        if (existing == null) {
            log.info("Registered Superset OAuth2 client '{}' with redirect URI '{}'", clientId, redirectUri);
        } else {
            log.info("Updated Superset OAuth2 client '{}' redirect URI to '{}' (was '{}')",
                    clientId, redirectUri, existing.getRedirectUris());
        }
    }
}
