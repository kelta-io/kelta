package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.AuthFixture;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * PLT-337: a runtime-provisioned tenant's seeded admin has no well-known password.
 *
 * <ul>
 *   <li>A tenant created without {@code adminEmail} gets {@code <slug>-admin@kelta.local} with
 *       an unusable credential — direct-login with the old default {@code password} fails.</li>
 *   <li>{@code adminEmail} on create (transient, never a tenant attribute) and
 *       {@code POST /api/tenants/{id}/admin-invite} both point the admin at a real address and
 *       store an invite token.</li>
 *   <li>V204 invalidates credentials still on the old default hash with
 *       {@code force_change_on_login = true}, and leaves changed or deliberate ones alone. The
 *       migration already ran when the stack started, so the scenario plants both cases and
 *       re-executes the migration file itself.</li>
 * </ul>
 */
@DisplayName("Tenant admin provisioning (no default password)")
class TenantAdminProvisioningScenarioTest extends ScenarioBase {

    /** The pre-PLT-337 seeded hash: bare BCrypt of "password". */
    private static final String OLD_DEFAULT_HASH =
            "$2a$10$zAQaSHX1XSR1bwUL3pz9EOzecplsxInVizZc9HwLf7xPluSiE1EP6";
    private static final String MIGRATION = "V204__invalidate_default_tenant_admin_passwords.sql";

    @Test
    @DisplayName("a fresh tenant created without adminEmail cannot be logged into as <slug>-admin with 'password'")
    void freshTenantAdminHasNoUsablePassword() throws Exception {
        String slug = uniqueSlug("noinvite");
        String tenantId = createTenant(slug, null);

        try (Connection db = openDbConnection()) {
            Map<String, Object> cred = adminCredential(db, tenantId, slug);
            assertThat(cred.get("email")).isEqualTo(slug + "-admin@kelta.local");
            assertThat(cred.get("password_hash")).as("no known credential is seeded").isEqualTo("");
            assertThat(cred.get("reset_token")).as("nobody was invited").isNull();
        }

        HttpClientErrorException rejected = directLoginError(slug + "-admin@kelta.local", "password", slug);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rejected.getResponseBodyAsString()).contains("invalid_credentials");
    }

    @Test
    @DisplayName("adminEmail on create seeds the admin with that email and invites them; it is not persisted on the tenant")
    @SuppressWarnings("unchecked")
    void adminEmailOnCreateInvites() throws Exception {
        String slug = uniqueSlug("oncreate");
        String email = "owner+" + slug + "@example.com";
        String tenantId = createTenant(slug, email);

        try (Connection db = openDbConnection()) {
            Map<String, Object> cred = adminCredential(db, tenantId, slug);
            assertThat(cred.get("email")).isEqualTo(email);
            assertThat(cred.get("password_hash")).isEqualTo("");
            assertThat(cred.get("reset_token")).as("invite token stored").isNotNull();
        }

        RestClient client = gatewayClientWithToken(auth.loginAsAdmin());
        Map<String, Object> tenant = client.get()
                .uri("/" + TenantFixture.DEFAULT_SLUG + "/api/tenants/" + tenantId)
                .retrieve().body(Map.class);
        Map<String, Object> attributes = (Map<String, Object>) ((Map<String, Object>) tenant.get("data")).get("attributes");
        assertThat(attributes).doesNotContainKey("adminEmail");
    }

    @Test
    @DisplayName("POST /api/tenants/{id}/admin-invite sets the seeded admin's email and stores an invite")
    @SuppressWarnings("unchecked")
    void adminInviteEndpoint() throws Exception {
        String slug = uniqueSlug("later");
        String tenantId = createTenant(slug, null);
        String email = "claimer+" + slug + "@example.com";

        RestClient client = gatewayClientWithToken(auth.loginAsAdmin());
        ResponseEntity<Map> response = client.post()
                .uri("/" + TenantFixture.DEFAULT_SLUG + "/api/tenants/" + tenantId + "/admin-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "INVITED").containsEntry("email", email);
        try (Connection db = openDbConnection()) {
            Map<String, Object> cred = adminCredential(db, tenantId, slug);
            assertThat(cred.get("email")).isEqualTo(email);
            assertThat(cred.get("reset_token")).isNotNull();
        }
    }

    @Test
    @DisplayName("V204 invalidates an unclaimed default-hash credential and leaves a changed or deliberate one working")
    void migrationInvalidatesOnlyUnclaimedDefaults() throws Exception {
        String defaultTenantId = tenants.tenantIdForSlug(TenantFixture.DEFAULT_SLUG);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String unclaimed = "v204-unclaimed-" + suffix + "@example.com";
        String changed = "v204-changed-" + suffix + "@example.com";
        String deliberate = "v204-deliberate-" + suffix + "@example.com";

        try (Connection db = openDbConnection()) {
            String profileId = standardProfile(db, defaultTenantId);
            seedUser(db, defaultTenantId, unclaimed, profileId, OLD_DEFAULT_HASH, true);
            seedUser(db, defaultTenantId, changed, profileId, AuthFixture.PROVISIONED_ADMIN_PASSWORD_HASH, false);
            seedUser(db, defaultTenantId, deliberate, profileId, OLD_DEFAULT_HASH, false);
        }

        // Before: the old default still matches — only the force-change flag stops it.
        assertThat(directLoginError(unclaimed, "password", TenantFixture.DEFAULT_SLUG).getResponseBodyAsString())
                .contains("credentials_expired");

        try (Connection db = openDbConnection(); Statement st = db.createStatement()) {
            st.execute(Files.readString(migrationFile()));
        }

        try (Connection db = openDbConnection()) {
            assertThat(hashOf(db, unclaimed)).isEqualTo("");
            assertThat(hashOf(db, changed)).isEqualTo(AuthFixture.PROVISIONED_ADMIN_PASSWORD_HASH);
            assertThat(hashOf(db, deliberate)).isEqualTo(OLD_DEFAULT_HASH);
        }

        HttpClientErrorException invalidated = directLoginError(unclaimed, "password", TenantFixture.DEFAULT_SLUG);
        assertThat(invalidated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(invalidated.getResponseBodyAsString()).contains("invalid_credentials");

        assertThat(directLogin(changed, AuthFixture.PROVISIONED_ADMIN_PASSWORD, TenantFixture.DEFAULT_SLUG))
                .as("a changed password still logs in").containsKey("access_token");
    }

    // ------------------------------------------------------------------

    private static String uniqueSlug(String prefix) {
        return "plt337-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createTenant(String slug, String adminEmail) {
        Map<String, Object> attributes = new HashMap<>(Map.of("slug", slug, "name", "PLT-337 " + slug));
        if (adminEmail != null) {
            attributes.put("adminEmail", adminEmail);
        }
        RestClient client = gatewayClientWithToken(auth.loginAsAdmin());
        ResponseEntity<Map> response = client.post()
                .uri("/" + TenantFixture.DEFAULT_SLUG + "/api/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "tenants", "attributes", attributes)))
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        for (int i = 0; i < 60; i++) {
            String id = tenants.tenantIdForSlug(slug);
            if (id != null) {
                return id;
            }
            sleep();
        }
        throw new AssertionError("Tenant " + slug + " never appeared in the slug-map");
    }

    private Map<String, Object> adminCredential(Connection db, String tenantId, String slug) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT pu.email, uc.password_hash, uc.reset_token, uc.force_change_on_login
                FROM platform_user pu JOIN user_credential uc ON uc.user_id = pu.id
                WHERE pu.tenant_id = ? AND pu.username = ?
                """)) {
            ps.setString(1, tenantId);
            ps.setString(2, slug + "-admin");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("seeded admin exists for " + slug).isTrue();
                Map<String, Object> row = new HashMap<>();
                row.put("email", rs.getString("email"));
                row.put("password_hash", rs.getString("password_hash"));
                row.put("reset_token", rs.getString("reset_token"));
                assertThat(rs.getBoolean("force_change_on_login")).isTrue();
                return row;
            }
        }
    }

    private String standardProfile(Connection db, String tenantId) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM profile WHERE tenant_id = ? AND name = 'Standard User' LIMIT 1")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("Standard User profile in the default tenant").isTrue();
                return rs.getString(1);
            }
        }
    }

    private void seedUser(Connection db, String tenantId, String email, String profileId,
                          String hash, boolean forceChange) throws Exception {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO platform_user
                    (id, tenant_id, email, username, first_name, last_name, status, profile_id,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Harness', 'User', 'ACTIVE', ?, NOW(), NOW())
                """)) {
            ps.setString(1, id);
            ps.setString(2, tenantId);
            ps.setString(3, email);
            ps.setString(4, email);
            ps.setString(5, profileId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO user_credential (id, user_id, password_hash, force_change_on_login, created_at)
                VALUES (?, ?, ?, ?, NOW())
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, id);
            ps.setString(3, hash);
            ps.setBoolean(4, forceChange);
            ps.executeUpdate();
        }
    }

    private String hashOf(Connection db, String email) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT uc.password_hash FROM user_credential uc JOIN platform_user pu ON pu.id = uc.user_id "
                        + "WHERE pu.email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> directLogin(String username, String password, String tenantSlug) {
        return authClient().post()
                .uri("/auth/direct-login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", username, "password", password, "tenantSlug", tenantSlug))
                .retrieve()
                .body(Map.class);
    }

    private HttpClientErrorException directLoginError(String username, String password, String tenantSlug) {
        HttpClientErrorException e = catchThrowableOfType(
                () -> directLogin(username, password, tenantSlug), HttpClientErrorException.class);
        assertThat(e).as("direct-login for " + username + " must be rejected").isNotNull();
        return e;
    }

    private static Path migrationFile() {
        try (InputStream in = TenantAdminProvisioningScenarioTest.class.getResourceAsStream("/harness.properties")) {
            if (in == null) throw new IllegalStateException("harness.properties not found on classpath");
            Properties props = new Properties();
            props.load(in);
            return Path.of(props.getProperty("harness.basedir")).getParent()
                    .resolve("kelta-worker/src/main/resources/db/migration").resolve(MIGRATION);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load harness.properties", e);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
