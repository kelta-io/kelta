package io.kelta.auth.service;

import io.kelta.runtime.context.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConnectedAppRegisteredClientRepositoryTest {

    private RegisteredClientRepository delegate;
    private JdbcTemplate jdbcTemplate;
    private ConnectedAppRegisteredClientRepository repository;

    private static final String SELECT_BY_CLIENT_ID =
            "SELECT id, client_id, client_secret_hash, name, redirect_uris, scopes, "
            + "grant_types, require_pkce, consent_required "
            + "FROM connected_app WHERE client_id = ? AND active = true";

    @BeforeEach
    void setUp() {
        delegate = mock(RegisteredClientRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new ConnectedAppRegisteredClientRepository(delegate, jdbcTemplate);
    }

    private Map<String, Object> appRow(String id, String clientId, String grantTypes,
                                       boolean requirePkce) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("client_id", clientId);
        row.put("client_secret_hash", "bcrypthash");
        row.put("name", "My App");
        row.put("redirect_uris", "[\"https://app.example.com/callback\"]");
        row.put("scopes", "[\"api\"]");
        row.put("grant_types", grantTypes);
        row.put("require_pkce", requirePkce);
        row.put("consent_required", false);
        return row;
    }

    @Test
    void buildsClientCredentialsClientFromConnectedApp() {
        when(jdbcTemplate.queryForList(anyString(), eq("klt_cc")))
                .thenReturn(List.of(appRow("app-1", "klt_cc", "[\"client_credentials\"]", false)));

        RegisteredClient client = repository.findByClientId("klt_cc");

        assertThat(client).isNotNull();
        assertThat(client.getId()).isEqualTo("app-1"); // stable id = connected_app id
        assertThat(client.getClientId()).isEqualTo("klt_cc");
        assertThat(client.getClientSecret()).isEqualTo("{bcrypt}bcrypthash");
        assertThat(client.getAuthorizationGrantTypes())
                .contains(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(client.getClientAuthenticationMethods())
                .contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        verifyNoInteractions(delegate);
    }

    @Test
    void buildsAuthorizationCodePkceClientFromConnectedApp() {
        when(jdbcTemplate.queryForList(anyString(), eq("klt_ac")))
                .thenReturn(List.of(appRow("app-2", "klt_ac", "[\"authorization_code\"]", true)));

        RegisteredClient client = repository.findByClientId("klt_ac");

        assertThat(client.getAuthorizationGrantTypes())
                .contains(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
        // Public (PKCE) client: no secret, NONE auth method, requireProofKey on.
        assertThat(client.getClientAuthenticationMethods()).contains(ClientAuthenticationMethod.NONE);
        assertThat(client.getClientSecret()).isNull();
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getRedirectUris()).contains("https://app.example.com/callback");
    }

    @Test
    void delegatesForNonConnectedAppClientId() {
        when(jdbcTemplate.queryForList(anyString(), eq("kelta-platform")))
                .thenReturn(List.of());
        RegisteredClient platform = mock(RegisteredClient.class);
        when(delegate.findByClientId("kelta-platform")).thenReturn(platform);

        RegisteredClient client = repository.findByClientId("kelta-platform");

        assertThat(client).isSameAs(platform);
        verify(delegate).findByClientId("kelta-platform");
    }

    @Test
    void cachesBuiltClientAcrossLookups() {
        when(jdbcTemplate.queryForList(anyString(), eq("klt_cc")))
                .thenReturn(List.of(appRow("app-1", "klt_cc", "[\"client_credentials\"]", false)));

        repository.findByClientId("klt_cc");
        // Second lookup by the SAME clientId within the TTL: no second DB hit.
        repository.findByClientId("klt_cc");
        // findById by the connected_app id is served from the same cached build.
        RegisteredClient byId = repository.findById("app-1");

        assertThat(byId.getClientId()).isEqualTo("klt_cc");
        verify(jdbcTemplate, times(1)).queryForList(anyString(), eq("klt_cc"));
    }

    @Test
    void saveDelegates() {
        RegisteredClient rc = mock(RegisteredClient.class);
        repository.save(rc);
        verify(delegate).save(rc);
    }

    @Test
    void fallsBackToDelegateWhenConnectedAppQueryFails() {
        when(jdbcTemplate.queryForList(anyString(), eq("klt_x")))
                .thenThrow(new RuntimeException("table not migrated"));
        RegisteredClient other = mock(RegisteredClient.class);
        when(delegate.findByClientId("klt_x")).thenReturn(other);

        assertThat(repository.findByClientId("klt_x")).isSameAs(other);
        verify(delegate).findByClientId("klt_x");
        verify(delegate, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cacheIsScopedToTheTenantTheLookupRanUnder() {
        // The query carries no tenant predicate — row-level security on connected_app is
        // what scopes it, so what one tenant's /authorize resolved must not be served to
        // the next tenant's request from cache.
        when(jdbcTemplate.queryForList(anyString(), eq("klt_shared")))
                .thenReturn(List.of(appRow("app-a", "klt_shared", "[\"client_credentials\"]", false)))
                .thenReturn(List.of(appRow("app-b", "klt_shared", "[\"client_credentials\"]", false)));

        RegisteredClient forA = TenantContext.callWithTenant(
                "tenant-a", () -> repository.findByClientId("klt_shared"));
        RegisteredClient forB = TenantContext.callWithTenant(
                "tenant-b", () -> repository.findByClientId("klt_shared"));

        assertThat(forA.getId()).isEqualTo("app-a");
        assertThat(forB.getId()).isEqualTo("app-b");
        verify(jdbcTemplate, times(2)).queryForList(anyString(), eq("klt_shared"));

        // Within one tenant the cache still spares the database.
        RegisteredClient againForA = TenantContext.callWithTenant(
                "tenant-a", () -> repository.findByClientId("klt_shared"));
        assertThat(againForA.getId()).isEqualTo("app-a");
        verify(jdbcTemplate, times(2)).queryForList(anyString(), eq("klt_shared"));
    }
}
