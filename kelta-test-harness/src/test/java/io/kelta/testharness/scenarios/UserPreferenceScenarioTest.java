package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Owner guard on {@code user-ui-preferences} (app-data-entry slice 1) through the real
 * stack. The collection rides the generic dynamic route, so what is proven here is exactly
 * the {@code UserPreferenceGuardHook} enforcement on the V163 table under real RLS:
 * self-writes succeed; creating, updating, or deleting another user's row is rejected and
 * persists nothing.
 */
@DisplayName("User Preference Owner Guard Scenario")
class UserPreferenceScenarioTest extends ScenarioBase {

    /** BCrypt hash of "password", for this scenario's own seeded users. */
    private static final String PASSWORD_HASH =
            "$2a$10$zAQaSHX1XSR1bwUL3pz9EOzecplsxInVizZc9HwLf7xPluSiE1EP6";

    private static final String URI = "/api/user-ui-preferences";

    @Test
    @DisplayName("preferences are writable only by their owner")
    @SuppressWarnings("unchecked")
    void preferencesAreOwnerScoped() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String slug = tenants.slugForTenantId(tenantId);
        waitForStatus(gatewayClientWithToken(adminToken), "/" + slug + URI, HttpStatus.OK, 60);

        String suffix = Long.toHexString(System.nanoTime());
        String aliceEmail = "alice-" + suffix + "@example.com";
        String bobEmail = "bob-" + suffix + "@example.com";

        try (Connection db = openDbConnection()) {
            String standardProfileId = profileIdByName(db, tenantId, "Standard User");
            assertThat(standardProfileId).isNotNull();
            String aliceId = seedActiveUser(db, tenantId, aliceEmail, standardProfileId);
            String bobId = seedActiveUser(db, tenantId, bobEmail, standardProfileId);
            String aliceRowId = null;
            try {
                String aliceToken = directLogin(aliceEmail, slug);
                String bobToken = directLogin(bobEmail, slug);

                // ---- Alice creates her own preference row -> 201
                ResponseEntity<Map> created = gatewayClientWithToken(aliceToken)
                        .post().uri("/" + slug + URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("data", Map.of(
                                "type", "user-ui-preferences",
                                "attributes", Map.of(
                                        "userId", aliceId,
                                        "prefType", "list-view",
                                        "prefKey", "probe-" + suffix,
                                        "value", List.of(Map.of("id", "v1", "name", "Mine"))))))
                        .retrieve().toEntity(Map.class);
                assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                aliceRowId = (String) ((Map<String, Object>) created.getBody().get("data")).get("id");
                assertThat(aliceRowId).isNotBlank();

                // ---- Alice updates her own row -> 2xx
                ResponseEntity<Map> updated = gatewayClientWithToken(aliceToken)
                        .patch().uri("/" + slug + URI + "/" + aliceRowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("data", Map.of(
                                "type", "user-ui-preferences",
                                "attributes", Map.of("value", List.of()))))
                        .retrieve().toEntity(Map.class);
                assertThat(updated.getStatusCode().is2xxSuccessful()).isTrue();

                // ---- Bob cannot create a row owned by Alice
                String finalRow = aliceRowId;
                HttpClientErrorException createDenied = catchThrowableOfType(
                        () -> gatewayClientWithToken(bobToken)
                                .post().uri("/" + slug + URI)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("data", Map.of(
                                        "type", "user-ui-preferences",
                                        "attributes", Map.of(
                                                "userId", aliceId,
                                                "prefType", "list-view",
                                                "prefKey", "hijack-" + suffix,
                                                "value", List.of()))))
                                .retrieve().toEntity(Map.class),
                        HttpClientErrorException.class);
                assertThat(createDenied).as("cross-user create rejected").isNotNull();
                assertThat(createDenied.getStatusCode().is4xxClientError()).isTrue();

                // ---- Bob cannot update or delete Alice's row; the row is untouched
                HttpClientErrorException updateDenied = catchThrowableOfType(
                        () -> gatewayClientWithToken(bobToken)
                                .patch().uri("/" + slug + URI + "/" + finalRow)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("data", Map.of(
                                        "type", "user-ui-preferences",
                                        "attributes", Map.of("value", List.of(Map.of("stolen", true))))))
                                .retrieve().toEntity(Map.class),
                        HttpClientErrorException.class);
                assertThat(updateDenied).as("cross-user update rejected").isNotNull();
                assertThat(updateDenied.getStatusCode().is4xxClientError()).isTrue();

                HttpClientErrorException deleteDenied = catchThrowableOfType(
                        () -> gatewayClientWithToken(bobToken)
                                .delete().uri("/" + slug + URI + "/" + finalRow)
                                .retrieve().toBodilessEntity(),
                        HttpClientErrorException.class);
                assertThat(deleteDenied).as("cross-user delete rejected").isNotNull();
                assertThat(deleteDenied.getStatusCode().is4xxClientError()).isTrue();

                assertThat(scalar(db,
                        "SELECT user_id FROM user_ui_preference WHERE id = ?", finalRow))
                        .as("Alice's row survives Bob's attempts").isEqualTo(aliceId);

                // ---- Alice deletes her own row -> 2xx
                ResponseEntity<Void> deleted = gatewayClientWithToken(aliceToken)
                        .delete().uri("/" + slug + URI + "/" + finalRow)
                        .retrieve().toBodilessEntity();
                assertThat(deleted.getStatusCode().is2xxSuccessful()).isTrue();
                aliceRowId = null;
            } finally {
                if (aliceRowId != null) {
                    deleteRowById(db, "user_ui_preference", aliceRowId);
                }
                deleteUserByEmail(db, tenantId, aliceEmail);
                deleteUserByEmail(db, tenantId, bobEmail);
            }
        }
    }

    // ------------------------------------------------------------- Helpers

    private String scalar(Connection db, String sql, String param) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String directLogin(String email, String tenantSlug) {
        Map<String, Object> response = authClient().post()
                .uri("/auth/direct-login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "username", email,
                        "password", "password",
                        "tenantSlug", tenantSlug))
                .retrieve()
                .body(Map.class);
        assertThat(response).as("direct login for " + email).isNotNull();
        assertThat(response).containsKey("access_token");
        return (String) response.get("access_token");
    }

    /** Seeds an ACTIVE platform_user + user_credential so the user can direct-login. */
    private String seedActiveUser(Connection db, String tenantId, String email, String profileId)
            throws Exception {
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
                VALUES (?, ?, ?, FALSE, NOW())
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, id);
            ps.setString(3, PASSWORD_HASH);
            ps.executeUpdate();
        }
        return id;
    }

    private void deleteUserByEmail(Connection db, String tenantId, String email) throws Exception {
        String id;
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM platform_user WHERE tenant_id = ? AND email = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                id = rs.next() ? rs.getString(1) : null;
            }
        }
        if (id == null) {
            return;
        }
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM login_history WHERE user_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM user_ui_preference WHERE user_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM platform_user WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private void deleteRowById(Connection db, String table, String id) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private String profileIdByName(Connection db, String tenantId, String name) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM profile WHERE tenant_id = ? AND name = ? LIMIT 1")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
