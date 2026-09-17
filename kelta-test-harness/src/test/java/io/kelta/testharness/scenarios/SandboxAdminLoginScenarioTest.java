package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * KLT-252: the one-time admin credential a sandbox prints could never log in —
 * {@code hardenSandboxAdmin} wrote a bare bcrypt hash (kelta-auth's
 * {@code DelegatingPasswordEncoder} requires the {@code {bcrypt}} id prefix) and
 * left {@code force_change_on_login = true} with no API path to clear it.
 *
 * <p>Proves against the real stack (worker → per-tenant Postgres → kelta-auth) that
 * the printed credential authenticates immediately, and that a separate account
 * still stuck with {@code force_change_on_login = true} gets a distinguishable
 * {@code credentials_expired} response instead of a generic {@code invalid_credentials}.
 */
@DisplayName("Sandbox Admin Login Scenario")
class SandboxAdminLoginScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("printed sandbox admin credential logs in via direct-login with a token scoped to the sandbox tenant")
    @SuppressWarnings("unchecked")
    void printedCredentialLogsIn() throws Exception {
        String parentToken = auth.loginAsAdmin();
        String parentTenantId = auth.extractTenantId(parentToken);
        String parentSlug = tenants.slugForTenantId(parentTenantId);
        RestClient client = gatewayClientWithToken(parentToken);
        String base = "/" + parentSlug;

        waitForStatus(client, base + "/api/environments", HttpStatus.OK, 20);

        Map<String, Object> envBody = Map.of("data", Map.of(
                "type", "environments",
                "attributes", Map.of("name", "adminlogin", "type", "SANDBOX")));
        ResponseEntity<Map> created = client.post().uri(base + "/api/environments")
                .contentType(MediaType.APPLICATION_JSON).body(envBody)
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> envAttrs = (Map<String, Object>)
                ((Map<String, Object>) created.getBody().get("data")).get("attributes");

        String sandboxSlug = (String) envAttrs.get("sandboxSlug");
        String adminUsername = (String) envAttrs.get("adminUsername");
        String adminPassword = (String) envAttrs.get("adminInitialPassword");
        assertThat(sandboxSlug).isEqualTo(parentSlug + "--adminlogin");
        assertThat(adminUsername).isEqualTo(sandboxSlug + "-admin");
        assertThat(adminPassword).as("one-time sandbox admin credential is returned exactly once").isNotBlank();

        String sandboxTenantId;
        try (Connection db = openDbConnection()) {
            sandboxTenantId = queryString(db,
                    "SELECT id FROM tenant WHERE parent_tenant_id = ? AND slug = ?",
                    parentTenantId, sandboxSlug);
            assertThat(sandboxTenantId).as("sandbox tenant row exists").isNotNull();

            String hash = queryString(db,
                    "SELECT password_hash FROM user_credential uc "
                            + "JOIN platform_user pu ON uc.user_id = pu.id "
                            + "WHERE pu.tenant_id = ? AND pu.username = ?",
                    sandboxTenantId, adminUsername);
            assertThat(hash).as("stored hash is DelegatingPasswordEncoder-compatible").startsWith("{bcrypt}");

            Boolean forceChange = queryBoolean(db,
                    "SELECT force_change_on_login FROM user_credential uc "
                            + "JOIN platform_user pu ON uc.user_id = pu.id "
                            + "WHERE pu.tenant_id = ? AND pu.username = ?",
                    sandboxTenantId, adminUsername);
            assertThat(forceChange).as("printed password is already one-time — nothing to force-change").isFalse();
        }

        Map<String, Object> loginResponse = authClient().post()
                .uri("/auth/direct-login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "username", adminUsername,
                        "password", adminPassword,
                        "tenantSlug", sandboxSlug))
                .retrieve()
                .body(Map.class);

        assertThat(loginResponse).containsKey("access_token");
        String token = (String) loginResponse.get("access_token");
        assertThat(auth.extractTenantId(token))
                .as("issued token is scoped to the sandbox tenant, not the parent")
                .isEqualTo(sandboxTenantId);
    }

    @Test
    @DisplayName("direct-login for a force_change_on_login account returns credentials_expired, not invalid_credentials")
    @SuppressWarnings("unchecked")
    void forceChangeAccountGetsDistinctError() throws Exception {
        String parentToken = auth.loginAsAdmin();
        String parentTenantId = auth.extractTenantId(parentToken);
        String parentSlug = tenants.slugForTenantId(parentTenantId);
        RestClient client = gatewayClientWithToken(parentToken);
        String base = "/" + parentSlug;

        waitForStatus(client, base + "/api/environments", HttpStatus.OK, 20);

        Map<String, Object> envBody = Map.of("data", Map.of(
                "type", "environments",
                "attributes", Map.of("name", "forcechange", "type", "SANDBOX")));
        ResponseEntity<Map> created = client.post().uri(base + "/api/environments")
                .contentType(MediaType.APPLICATION_JSON).body(envBody)
                .retrieve().toEntity(Map.class);
        Map<String, Object> envAttrs = (Map<String, Object>)
                ((Map<String, Object>) created.getBody().get("data")).get("attributes");

        String sandboxSlug = (String) envAttrs.get("sandboxSlug");
        String adminUsername = (String) envAttrs.get("adminUsername");
        String adminPassword = (String) envAttrs.get("adminInitialPassword");

        // Flip this sandbox-only admin back to force-change, so the assertion
        // exercises the credentials_expired path without touching any other
        // tenant's admin (the default-tenant admin is shared across scenarios).
        try (Connection db = openDbConnection();
             PreparedStatement ps = db.prepareStatement(
                     "UPDATE user_credential SET force_change_on_login = true "
                             + "WHERE user_id = (SELECT id FROM platform_user WHERE username = ?)")) {
            ps.setString(1, adminUsername);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        HttpClientErrorException expired = catchThrowableOfType(
                () -> authClient().post()
                        .uri("/auth/direct-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "username", adminUsername,
                                "password", adminPassword,
                                "tenantSlug", sandboxSlug))
                        .retrieve()
                        .toEntity(Map.class),
                HttpClientErrorException.class);

        assertThat(expired).as("must reject with a client error, distinct from invalid_credentials").isNotNull();
        assertThat(expired.getStatusCode().is4xxClientError()).isTrue();
        String body = expired.getResponseBodyAsString();
        assertThat(body).contains("credentials_expired");
        assertThat(body).as("caller needs the force-change URL to recover").contains("force_change_url");
    }

    private String queryString(Connection db, String sql, String... params) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private Boolean queryBoolean(Connection db, String sql, String... params) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBoolean(1) : null;
            }
        }
    }
}
