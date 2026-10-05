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
 * Delegated administration (V157) through the real stack (gateway → worker → Postgres + RLS),
 * exercised as a genuinely <em>non-admin</em> caller: a delegated user is seeded directly into
 * {@code platform_user} / {@code user_credential} (a BCrypt hash of "password" of its own) and
 * logs in via the auth service's {@code /auth/direct-login}, so every request
 * carries a real Standard-User {@code X-User-Profile-Id} / {@code X-User-Email} stamped by the
 * gateway.
 *
 * <p>This covers the paths Mockito worker tests can't reach (the DB-constraint-test-gap lesson):
 * <ul>
 *   <li>{@code DelegatedAdminScopeController} (MANAGE_DELEGATED_ADMINS, in-controller — the
 *       {@code /api/admin/**} static route has only the blanket API_ACCESS check) persisting a
 *       real {@code delegated_admin_scope} JSONB row;</li>
 *   <li>{@code DelegatedAdminScopeValidationHook} rejecting privileged profiles against the real
 *       {@code profile_system_permission} table;</li>
 *   <li>{@code DelegatedUserAdminController} resolving the caller's effective scope fresh from
 *       Postgres and enforcing the profile-scoped read filter, field whitelist, self-edit
 *       rejection, and out-of-scope 404;</li>
 *   <li>{@code IdentityCollectionGuardHook} keeping the generic write routes
 *       ({@code POST /api/operations} atomic batch, dynamic {@code /api/delegated-admin-scopes})
 *       closed to a caller who <em>is</em> a delegated admin but holds no MANAGE_USERS /
 *       MANAGE_DELEGATED_ADMINS. (Harness Cerbos is dev allow-all, so what is proven here is
 *       exactly the worker-side enforcement.)</li>
 * </ul>
 */
@DisplayName("Delegated Administration Scenario")
class DelegatedAdminScenarioTest extends ScenarioBase {

    /** BCrypt hash of "password", for this scenario's own seeded users. */
    private static final String PASSWORD_HASH =
            "$2a$10$zAQaSHX1XSR1bwUL3pz9EOzecplsxInVizZc9HwLf7xPluSiE1EP6";

    private static final String SCOPES_URI = "/api/admin/delegated-admin-scopes";
    private static final String DELEGATED_URI = "/api/admin/delegated";

    // =====================================================================
    // 1 + 3 + 4 + 5: full delegated-administration flow
    // =====================================================================

    @Test
    @DisplayName("admin creates a scope; the delegated user sees, lists, creates and is fenced on updates")
    @SuppressWarnings("unchecked")
    void delegatedUserManagesOnlyInScopeUsers() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String slug = tenants.slugForTenantId(tenantId);
        waitForStatus(gatewayClientWithToken(adminToken), "/" + slug + SCOPES_URI, HttpStatus.OK, 20);

        String suffix = Long.toHexString(System.nanoTime());
        String delegateEmail = "delegate-" + suffix + "@example.com";
        String targetEmail = "target-" + suffix + "@example.com";
        String inviteeEmail = "invitee-" + suffix + "@example.com";

        try (Connection db = openDbConnection()) {
            String standardProfileId = profileIdByName(db, tenantId, "Standard User");
            String adminProfileId = profileIdByName(db, tenantId, "System Administrator");
            assertThat(standardProfileId).as("tenant has the seeded Standard User profile").isNotNull();
            assertThat(adminProfileId).as("tenant has the seeded System Administrator profile").isNotNull();

            String delegateId = seedActiveUser(db, tenantId, delegateEmail, standardProfileId);
            String targetId = seedActiveUser(db, tenantId, targetEmail, standardProfileId);
            String scopeId = null;
            try {
                // ---- (1) admin creates the delegated-admin scope -> 201 + real DB row
                scopeId = createScope(adminToken, slug, Map.of(
                        "name", "Support Desk " + suffix,
                        "description", "Harness delegated-administration scope",
                        "delegatedUserIds", List.of(delegateId),
                        "manageableProfileIds", List.of(standardProfileId),
                        "canCreateUsers", true,
                        "canDeactivateUsers", true,
                        "canResetPasswords", true));

                try (PreparedStatement ps = db.prepareStatement(
                        "SELECT can_create_users FROM delegated_admin_scope WHERE id = ? AND tenant_id = ?")) {
                    ps.setString(1, scopeId);
                    ps.setString(2, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).as("delegated_admin_scope row persisted").isTrue();
                        assertThat(rs.getBoolean(1)).as("canCreateUsers stored").isTrue();
                    }
                }

                // ---- (3) the delegated user logs in as themselves (real non-admin JWT)
                String delegatedToken = directLogin(delegateEmail, slug);

                ResponseEntity<Map> me = gatewayClientWithToken(delegatedToken)
                        .get().uri("/" + slug + DELEGATED_URI + "/me")
                        .retrieve().toEntity(Map.class);
                assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(me.getBody().get("delegated")).as("caller is delegated").isEqualTo(true);
                assertThat(me.getBody().get("canCreateUsers")).isEqualTo(true);
                List<Map<String, Object>> manageable =
                        (List<Map<String, Object>>) me.getBody().get("manageableProfiles");
                assertThat(manageable).extracting(p -> p.get("id"))
                        .as("scoped profile is listed").contains(standardProfileId);
                assertThat(manageable).extracting(p -> p.get("name")).contains("Standard User");

                // Reads are server-filtered to manageable profiles: the Standard-User target (and
                // the delegate themselves) are visible; the System Administrator admin is not.
                ResponseEntity<Map> listed = gatewayClientWithToken(delegatedToken)
                        .get().uri("/" + slug + DELEGATED_URI + "/users?limit=200")
                        .retrieve().toEntity(Map.class);
                List<Map<String, Object>> resources =
                        (List<Map<String, Object>>) listed.getBody().get("data");
                List<Object> emails = resources.stream()
                        .map(r -> ((Map<String, Object>) r.get("attributes")).get("email"))
                        .toList();
                assertThat(emails).as("in-scope users are listed").contains(targetEmail, delegateEmail);
                assertThat(emails).as("admin-profile users are filtered out")
                        .doesNotContain("admin@kelta.local");

                // ---- (4) delegated create -> 201 + PENDING_ACTIVATION row in platform_user
                ResponseEntity<Map> created = gatewayClientWithToken(delegatedToken)
                        .post().uri("/" + slug + DELEGATED_URI + "/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "email", inviteeEmail,
                                "firstName", "Invited",
                                "profileId", standardProfileId))
                        .retrieve().toEntity(Map.class);
                assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

                try (PreparedStatement ps = db.prepareStatement(
                        "SELECT status, profile_id FROM platform_user WHERE tenant_id = ? AND email = ?")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, inviteeEmail);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).as("created user persisted").isTrue();
                        assertThat(rs.getString("status")).isEqualTo("PENDING_ACTIVATION");
                        assertThat(rs.getString("profile_id")).isEqualTo(standardProfileId);
                    }
                }

                // ---- (5a) PATCH on a user whose profile is NOT manageable -> 404 (no existence leak)
                String adminUserId = userIdByEmail(db, tenantId, "admin@kelta.local");
                assertThat(adminUserId).isNotNull();
                HttpClientErrorException outOfScope = catchThrowableOfType(
                        () -> patchDelegatedUser(delegatedToken, slug, adminUserId,
                                Map.of("firstName", "Nope")),
                        HttpClientErrorException.class);
                assertThat(outOfScope).as("out-of-scope target reads as 404").isNotNull();
                assertThat(outOfScope.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

                // ---- (5b) PATCH with a non-whitelisted field (email) -> 400
                HttpClientErrorException emailRejected = catchThrowableOfType(
                        () -> patchDelegatedUser(delegatedToken, slug, targetId,
                                Map.of("email", "x@y.z")),
                        HttpClientErrorException.class);
                assertThat(emailRejected).as("email is immutable through delegation").isNotNull();
                assertThat(emailRejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

                // ---- (5c) PATCH on themselves -> 403 (their own profile IS manageable, still rejected)
                HttpClientErrorException selfEdit = catchThrowableOfType(
                        () -> patchDelegatedUser(delegatedToken, slug, delegateId,
                                Map.of("firstName", "Myself")),
                        HttpClientErrorException.class);
                assertThat(selfEdit).as("self-edit is always rejected").isNotNull();
                assertThat(selfEdit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            } finally {
                if (scopeId != null) {
                    deleteScopeById(db, scopeId);
                }
                deleteUserByEmail(db, tenantId, inviteeEmail);
                deleteUserByEmail(db, tenantId, targetEmail);
                deleteUserByEmail(db, tenantId, delegateEmail);
            }
        }
    }

    // =====================================================================
    // 2: scope-definition gate — no delegating admin-of-admins
    // =====================================================================

    @Test
    @DisplayName("a scope listing a privileged profile is rejected and never persisted")
    void rejectsScopeDelegatingPrivilegedProfiles() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String slug = tenants.slugForTenantId(tenantId);
        waitForStatus(gatewayClientWithToken(adminToken), "/" + slug + SCOPES_URI, HttpStatus.OK, 20);

        String name = "Privileged Scope " + Long.toHexString(System.nanoTime());

        try (Connection db = openDbConnection()) {
            String adminProfileId = profileIdByName(db, tenantId, "System Administrator");
            assertThat(adminProfileId).isNotNull();

            HttpClientErrorException rejected = catchThrowableOfType(
                    () -> gatewayClientWithToken(adminToken)
                            .post().uri("/" + slug + SCOPES_URI)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("data", Map.of(
                                    "type", "delegated-admin-scopes",
                                    "attributes", Map.of(
                                            "name", name,
                                            "manageableProfileIds", List.of(adminProfileId)))))
                            .retrieve().toEntity(Map.class),
                    HttpClientErrorException.class);

            assertThat(rejected)
                    .as("DelegatedAdminScopeValidationHook rejects privileged profiles")
                    .isNotNull();
            assertThat(rejected.getStatusCode().is4xxClientError()).isTrue();
            assertThat(rejected.getResponseBodyAsString()).contains("cannot be delegated");
            assertThat(countScopesByName(db, tenantId, name))
                    .as("rejected scope was never persisted").isZero();
        }
    }

    // =====================================================================
    // 6 + 7: generic write routes stay closed to delegated (non-admin) users
    // =====================================================================

    @Test
    @DisplayName("a delegated user cannot write identity collections via the generic routes")
    @SuppressWarnings("unchecked")
    void genericWriteRoutesStayClosedToDelegatedUsers() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String slug = tenants.slugForTenantId(tenantId);
        waitForStatus(gatewayClientWithToken(adminToken), "/" + slug + SCOPES_URI, HttpStatus.OK, 20);

        String suffix = Long.toHexString(System.nanoTime());
        String delegateEmail = "bypass-" + suffix + "@example.com";
        String intruderEmail = "intruder-" + suffix + "@example.com";
        String escalationName = "Escalation Scope " + suffix;

        try (Connection db = openDbConnection()) {
            String standardProfileId = profileIdByName(db, tenantId, "Standard User");
            assertThat(standardProfileId).isNotNull();

            String delegateId = seedActiveUser(db, tenantId, delegateEmail, standardProfileId);
            String scopeId = null;
            try {
                scopeId = createScope(adminToken, slug, Map.of(
                        "name", "Bypass Probe " + suffix,
                        "delegatedUserIds", List.of(delegateId),
                        "manageableProfileIds", List.of(standardProfileId),
                        "canCreateUsers", true));

                String delegatedToken = directLogin(delegateEmail, slug);

                // Sanity: the caller IS a delegated admin — the point is that scoped delegation
                // must not open the generic write routes.
                ResponseEntity<Map> me = gatewayClientWithToken(delegatedToken)
                        .get().uri("/" + slug + DELEGATED_URI + "/me")
                        .retrieve().toEntity(Map.class);
                assertThat(me.getBody().get("delegated")).isEqualTo(true);

                // ---- (6) JSON:API atomic operations create on `users` -> denied by
                // IdentityCollectionGuardHook (wrapped as an operation failure), nothing persisted.
                Map<String, Object> atomicBody = Map.of("atomic:operations", List.of(Map.of(
                        "op", "add",
                        "data", Map.of(
                                "type", "users",
                                "attributes", Map.of(
                                        "email", intruderEmail,
                                        "firstName", "Intruder",
                                        "profileId", standardProfileId)))));
                HttpClientErrorException atomicDenied = catchThrowableOfType(
                        () -> gatewayClientWithToken(delegatedToken)
                                .post().uri("/" + slug + "/api/operations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(atomicBody)
                                .retrieve().toEntity(Map.class),
                        HttpClientErrorException.class);
                assertThat(atomicDenied)
                        .as("atomic create on users is rejected for a delegated non-admin").isNotNull();
                assertThat(atomicDenied.getStatusCode().is4xxClientError()).isTrue();
                assertThat(userIdByEmail(db, tenantId, intruderEmail))
                        .as("no platform_user row was created (transaction rolled back)").isNull();

                // ---- (7) dynamic collection route write on delegated-admin-scopes -> denied
                // (self-service scope escalation must be impossible).
                HttpClientErrorException scopeDenied = catchThrowableOfType(
                        () -> gatewayClientWithToken(delegatedToken)
                                .post().uri("/" + slug + "/api/delegated-admin-scopes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("data", Map.of(
                                        "type", "delegated-admin-scopes",
                                        "attributes", Map.of(
                                                "name", escalationName,
                                                "delegatedUserIds", List.of(delegateId),
                                                "manageableProfileIds", List.of(standardProfileId),
                                                "canCreateUsers", true))))
                                .retrieve().toEntity(Map.class),
                        HttpClientErrorException.class);
                assertThat(scopeDenied)
                        .as("dynamic-route scope write is rejected for a delegated non-admin").isNotNull();
                assertThat(scopeDenied.getStatusCode().is4xxClientError()).isTrue();
                assertThat(countScopesByName(db, tenantId, escalationName))
                        .as("no delegated_admin_scope row was created").isZero();

                // The MANAGE_DELEGATED_ADMINS-gated admin controller refuses the same caller too.
                HttpClientErrorException adminRouteDenied = catchThrowableOfType(
                        () -> gatewayClientWithToken(delegatedToken)
                                .post().uri("/" + slug + SCOPES_URI)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("name", escalationName))
                                .retrieve().toEntity(Map.class),
                        HttpClientErrorException.class);
                assertThat(adminRouteDenied).isNotNull();
                assertThat(adminRouteDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            } finally {
                if (scopeId != null) {
                    deleteScopeById(db, scopeId);
                }
                deleteScopesByName(db, tenantId, escalationName); // in case the guard ever leaks
                deleteUserByEmail(db, tenantId, intruderEmail);   // in case the guard ever leaks
                deleteUserByEmail(db, tenantId, delegateEmail);
            }
        }
    }

    // ------------------------------------------------------------- Helpers

    /**
     * Creates a delegated-admin scope through the MANAGE_DELEGATED_ADMINS-gated admin route and
     * returns its id (asserts 201).
     */
    @SuppressWarnings("unchecked")
    private String createScope(String adminToken, String slug, Map<String, Object> attributes) {
        ResponseEntity<Map> created = gatewayClientWithToken(adminToken)
                .post().uri("/" + slug + SCOPES_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of(
                        "type", "delegated-admin-scopes",
                        "attributes", attributes)))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode()).as("scope create").isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = (Map<String, Object>) created.getBody().get("data");
        String id = (String) data.get("id");
        assertThat(id).as("created scope has an id").isNotBlank();
        return id;
    }

    private ResponseEntity<Map> patchDelegatedUser(String token, String slug, String userId,
                                                   Map<String, Object> attrs) {
        return gatewayClientWithToken(token)
                .patch().uri("/" + slug + DELEGATED_URI + "/users/" + userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(attrs)
                .retrieve().toEntity(Map.class);
    }

    /**
     * Logs in as an arbitrary seeded user via the auth service's direct-login endpoint (the
     * same mechanism {@code AuthFixture.loginAsAdmin} uses, but not limited to the admin user).
     */
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

    /**
     * Seeds an ACTIVE platform_user + user_credential (BCrypt of "password", no forced change)
     * so the user can direct-login.
     */
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

    /** Deletes a user by email, first clearing login_history (plain FK, no cascade). */
    private void deleteUserByEmail(Connection db, String tenantId, String email) throws Exception {
        String id = userIdByEmail(db, tenantId, email);
        if (id == null) {
            return;
        }
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM login_history WHERE user_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        // user_credential cascades on platform_user delete (fk ... ON DELETE CASCADE).
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM platform_user WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private String userIdByEmail(Connection db, String tenantId, String email) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM platform_user WHERE tenant_id = ? AND email = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
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

    private int countScopesByName(Connection db, String tenantId, String name) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT COUNT(*) FROM delegated_admin_scope WHERE tenant_id = ? AND name = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void deleteScopeById(Connection db, String scopeId) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM delegated_admin_scope WHERE id = ?")) {
            ps.setString(1, scopeId);
            ps.executeUpdate();
        }
    }

    private void deleteScopesByName(Connection db, String tenantId, String name) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM delegated_admin_scope WHERE tenant_id = ? AND name = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }
}
