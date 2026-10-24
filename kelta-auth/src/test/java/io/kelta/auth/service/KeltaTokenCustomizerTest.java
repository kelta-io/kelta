package io.kelta.auth.service;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.model.KeltaUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KeltaTokenCustomizerTest {

    private KeltaTokenCustomizer customizer;

    @Mock
    private JwtEncodingContext context;

    @Mock
    private Authentication authentication;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ConnectedAppTokenRecorder tokenRecorder;

    private AuthProperties authProperties;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        customizer = new KeltaTokenCustomizer(jdbcTemplate, tokenRecorder, authProperties);
    }

    @Test
    void customize_addsClaimsToIdToken() {
        KeltaUserDetails user = new KeltaUserDetails(
                "user-1", "admin@test.com", "tenant-1", "profile-1",
                "System Administrator", "Test Admin", "$2a$10$hash",
                true, false, false
        );

        when(context.getPrincipal()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(user);
        when(context.getTokenType()).thenReturn(new OAuth2TokenType("id_token"));

        Map<String, Object> claimsMap = new HashMap<>();
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer("http://localhost")
                .subject("admin@test.com")
                .issuedAt(Instant.now());

        // Use a real builder that captures claims
        when(context.getClaims()).thenReturn(claimsBuilder);

        customizer.customize(context);

        JwtClaimsSet claims = claimsBuilder.build();
        assertEquals("admin@test.com", claims.getClaim("email"));
        assertEquals("Test Admin", claims.getClaim("name"));
        assertEquals("tenant-1", claims.getClaim("tenant_id"));
        assertEquals("profile-1", claims.getClaim("profile_id"));
    }

    @Test
    void customize_enrichesAuthCodeAccessTokenWithConnectedAppClaims() {
        KeltaUserDetails user = new KeltaUserDetails(
                "user-1", "admin@test.com", "tenant-1", "profile-1",
                "System Administrator", "Test Admin", "$2a$10$hash",
                true, false, false
        );

        RegisteredClient registeredClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("third-party-app")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://third-party.com/callback")
                .build();

        when(context.getPrincipal()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(user);
        when(context.getTokenType()).thenReturn(new OAuth2TokenType("access_token"));
        when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.AUTHORIZATION_CODE);
        when(context.getRegisteredClient()).thenReturn(registeredClient);

        // Mock connected_app lookup
        Map<String, Object> appRow = new HashMap<>();
        appRow.put("id", "connected-app-1");
        appRow.put("tenant_id", "tenant-1");
        appRow.put("scopes", "[\"api\",\"read:records\"]");
        when(jdbcTemplate.queryForList(
                eq("SELECT id, tenant_id, scopes FROM connected_app WHERE client_id = ? AND active = true"),
                eq("third-party-app")
        )).thenReturn(List.of(appRow));

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer("http://localhost")
                .subject("admin@test.com")
                .issuedAt(Instant.now());
        when(context.getClaims()).thenReturn(claimsBuilder);

        customizer.customize(context);

        JwtClaimsSet claims = claimsBuilder.build();
        assertEquals("admin@test.com", claims.getClaim("email"));
        assertEquals("tenant-1", claims.getClaim("tenant_id"));
        assertEquals("connected-app-1", claims.getClaim("connected_app_id"));
        assertEquals("[\"api\",\"read:records\"]", claims.getClaim("app_scopes"));
        assertEquals("connected_app", claims.getClaim("auth_method"));
        assertNotNull(claims.getId(), "jti should be pinned for the recorded token");
        // The authorization_code token is recorded in the app's token list too.
        verify(tokenRecorder).recordIssuedToken(
                eq("connected-app-1"), eq("tenant-1"), eq("[\"api\",\"read:records\"]"),
                eq(claims.getId()), any(), eq("authorization_code"));
    }

    @Test
    void customize_clientCredentialsToken_enrichesClaimsSetsJtiAndRecordsToken() {
        RegisteredClient registeredClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("couchpicks-web")
                .clientSecret("{noop}secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("api")
                .build();

        when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.CLIENT_CREDENTIALS);
        when(context.getTokenType()).thenReturn(new OAuth2TokenType("access_token"));
        when(context.getRegisteredClient()).thenReturn(registeredClient);

        Map<String, Object> appRow = new HashMap<>();
        appRow.put("id", "connected-app-1");
        appRow.put("tenant_id", "tenant-1");
        appRow.put("scopes", "[\"api\"]");
        when(jdbcTemplate.queryForList(
                eq("SELECT id, tenant_id, scopes FROM connected_app WHERE client_id = ? AND active = true"),
                eq("couchpicks-web")
        )).thenReturn(List.of(appRow));

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer("http://localhost")
                .subject("couchpicks-web")
                .issuedAt(Instant.now());
        when(context.getClaims()).thenReturn(claimsBuilder);

        customizer.customize(context);

        JwtClaimsSet claims = claimsBuilder.build();
        assertEquals("tenant-1", claims.getClaim("tenant_id"));
        assertEquals("connected-app-1", claims.getClaim("connected_app_id"));
        assertEquals("api_key", claims.getClaim("auth_method"));
        assertEquals("[\"api\"]", claims.getClaim("app_scopes"));
        assertNotNull(claims.getId(), "jti should be pinned for the recorded token");

        verify(tokenRecorder).recordIssuedToken(
                eq("connected-app-1"), eq("tenant-1"), eq("[\"api\"]"),
                eq(claims.getId()), any());
        // client_credentials must not touch the user-principal path
        verify(context, never()).getPrincipal();
    }

    @Test
    void customize_clientCredentialsUnknownClient_refusesTenantlessToken() {
        RegisteredClient registeredClient = clientCredentialsClient("ghost-client", "api");
        stubClientCredentialsAccessToken(registeredClient);
        when(jdbcTemplate.queryForList(
                eq("SELECT id, tenant_id, scopes FROM connected_app WHERE client_id = ? AND active = true"),
                eq("ghost-client")
        )).thenReturn(List.of());

        assertThrows(OAuth2AuthenticationException.class, () -> customizer.customize(context));

        verify(context, never()).getClaims();
        verify(tokenRecorder, never()).recordIssuedToken(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void customize_clientCredentialsClientWithInternalScopeButNotConfigured_refusesTenantlessToken() {
        // Holding the "internal" scope is not enough: only a client InternalClientRegistrar
        // provisions from kelta.auth.internal-clients is platform-scoped.
        RegisteredClient registeredClient = clientCredentialsClient("rogue-internal", "internal");
        stubClientCredentialsAccessToken(registeredClient);
        when(jdbcTemplate.queryForList(anyString(), eq("rogue-internal"))).thenReturn(List.of());

        assertThrows(OAuth2AuthenticationException.class, () -> customizer.customize(context));
    }

    @Test
    void customize_clientCredentialsConnectedAppWithoutTenant_refusesToken() {
        RegisteredClient registeredClient = clientCredentialsClient("orphan-app", "api");
        stubClientCredentialsAccessToken(registeredClient);
        Map<String, Object> appRow = new HashMap<>();
        appRow.put("id", "connected-app-2");
        appRow.put("tenant_id", null);
        appRow.put("scopes", "[\"api\"]");
        when(jdbcTemplate.queryForList(anyString(), eq("orphan-app"))).thenReturn(List.of(appRow));

        assertThrows(OAuth2AuthenticationException.class, () -> customizer.customize(context));

        verify(tokenRecorder, never()).recordIssuedToken(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void customize_internalPlatformClient_keepsPlatformScopedTokenWithoutTenant() {
        AuthProperties.InternalClient internal = new AuthProperties.InternalClient();
        internal.setSecret("s3cret");
        authProperties.getInternalClients().put("gateway-internal", internal);

        RegisteredClient registeredClient = clientCredentialsClient("gateway-internal", "internal");
        stubClientCredentialsAccessToken(registeredClient);
        when(jdbcTemplate.queryForList(anyString(), eq("gateway-internal"))).thenReturn(List.of());

        assertDoesNotThrow(() -> customizer.customize(context));

        verify(context, never()).getClaims();
        verify(tokenRecorder, never()).recordIssuedToken(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void customize_accessTokenForNonKeltaUserPrincipal_refusesTenantlessToken() {
        User foreignUser = new User("someone@test.com", "", List.of());
        when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.AUTHORIZATION_CODE);
        when(context.getPrincipal()).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(foreignUser, null, List.of()));
        when(context.getTokenType()).thenReturn(new OAuth2TokenType("access_token"));

        assertThrows(OAuth2AuthenticationException.class, () -> customizer.customize(context));

        verify(context, never()).getClaims();
    }

    @Test
    void customize_accessTokenForKeltaUserWithoutTenant_refusesToken() {
        KeltaUserDetails user = new KeltaUserDetails(
                "user-1", "admin@test.com", null, "profile-1",
                "System Administrator", "Test Admin", "$2a$10$hash",
                true, false, false
        );
        when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.AUTHORIZATION_CODE);
        when(context.getPrincipal()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(user);
        when(context.getTokenType()).thenReturn(new OAuth2TokenType("access_token"));

        assertThrows(OAuth2AuthenticationException.class, () -> customizer.customize(context));
    }

    @Test
    void customize_accessTokenForKeltaUser_carriesTenantId() {
        KeltaUserDetails user = new KeltaUserDetails(
                "user-1", "admin@test.com", "tenant-1", "profile-1",
                "System Administrator", "Test Admin", "$2a$10$hash",
                true, false, false
        );
        when(context.getPrincipal()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(user);
        when(context.getTokenType()).thenReturn(new OAuth2TokenType("access_token"));
        when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.REFRESH_TOKEN);
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer("http://localhost")
                .subject("user-1")
                .issuedAt(Instant.now());
        when(context.getClaims()).thenReturn(claimsBuilder);

        customizer.customize(context);

        assertEquals("tenant-1", claimsBuilder.build().getClaim("tenant_id"));
    }

    private static RegisteredClient clientCredentialsClient(String clientId, String scope) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret("{noop}secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(scope)
                .build();
    }

    private void stubClientCredentialsAccessToken(RegisteredClient registeredClient) {
        when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.CLIENT_CREDENTIALS);
        when(context.getTokenType()).thenReturn(new OAuth2TokenType("access_token"));
        when(context.getRegisteredClient()).thenReturn(registeredClient);
    }

    @Test
    void customize_authCodeAccessTokenWithoutConnectedApp_usesNormalAuthMethod() {
        KeltaUserDetails user = new KeltaUserDetails(
                "user-1", "admin@test.com", "tenant-1", "profile-1",
                "System Administrator", "Test Admin", "$2a$10$hash",
                true, false, false
        );

        RegisteredClient registeredClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("kelta-platform")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:5173/auth/callback")
                .build();

        when(context.getPrincipal()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(user);
        when(context.getTokenType()).thenReturn(new OAuth2TokenType("access_token"));
        when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.AUTHORIZATION_CODE);
        when(context.getRegisteredClient()).thenReturn(registeredClient);

        // No connected app for platform client
        when(jdbcTemplate.queryForList(
                eq("SELECT id, tenant_id, scopes FROM connected_app WHERE client_id = ? AND active = true"),
                eq("kelta-platform")
        )).thenReturn(List.of());

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer("http://localhost")
                .subject("admin@test.com")
                .issuedAt(Instant.now());
        when(context.getClaims()).thenReturn(claimsBuilder);

        customizer.customize(context);

        JwtClaimsSet claims = claimsBuilder.build();
        assertEquals("internal", claims.getClaim("auth_method"));
        assertNull(claims.getClaim("connected_app_id"));
    }

    @Test
    void customize_principalWithErasedCredentials_stillReportsInternalAuthMethod() {
        KeltaUserDetails user = new KeltaUserDetails(
                "user-1", "admin@test.com", "tenant-1", "profile-1",
                "System Administrator", "Test Admin", "$2a$10$hash",
                true, false, false
        );
        user.eraseCredentials();

        assertEquals("internal", platformAccessTokenAuthMethod(user));
    }

    @Test
    void customize_principalWithoutLocalPassword_reportsSsoAuthMethod() {
        KeltaUserDetails user = new KeltaUserDetails(
                "user-1", "admin@test.com", "tenant-1", "profile-1",
                "System Administrator", "Test Admin", "",
                true, false, false
        );

        assertEquals("sso", platformAccessTokenAuthMethod(user));
    }

    /** Customizes an authorization_code access token for the platform client; returns auth_method. */
    private Object platformAccessTokenAuthMethod(KeltaUserDetails user) {
        RegisteredClient registeredClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("kelta-platform")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:5173/auth/callback")
                .build();

        when(context.getPrincipal()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(user);
        when(context.getTokenType()).thenReturn(new OAuth2TokenType("access_token"));
        when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.AUTHORIZATION_CODE);
        when(context.getRegisteredClient()).thenReturn(registeredClient);
        when(jdbcTemplate.queryForList(
                eq("SELECT id, tenant_id, scopes FROM connected_app WHERE client_id = ? AND active = true"),
                eq("kelta-platform")
        )).thenReturn(List.of());

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer("http://localhost")
                .subject("admin@test.com")
                .issuedAt(Instant.now());
        when(context.getClaims()).thenReturn(claimsBuilder);

        customizer.customize(context);
        return claimsBuilder.build().getClaim("auth_method");
    }
}
