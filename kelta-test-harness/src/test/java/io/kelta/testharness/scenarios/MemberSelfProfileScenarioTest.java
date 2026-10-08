package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Member self-profile (member-data-ownership slice 3) through the real stack (gateway → worker →
 * Postgres + RLS), as a genuine PORTAL caller holding no {@code MANAGE_USERS}: a portal member is
 * invited, given a PAT of their own (the gateway forwards a PAT as its owner's user type and
 * profile), and then
 * <ul>
 *   <li>reads and updates their own timezone through {@code /api/me/profile};</li>
 *   <li>cannot change their {@code profileId} through {@code /api/me/profile}
 *       ({@code FIELD_NOT_EDITABLE}), the generic {@code PATCH /api/users/{id}} or a
 *       {@code POST /api/operations} batch — both of which {@code IdentityCollectionGuardHook}
 *       still closes, since the {@code SelfProfileWriteContext} is bound only by the self-profile
 *       controller. (Harness Cerbos is dev allow-all, so what is proven here is exactly the
 *       worker-side enforcement.)</li>
 * </ul>
 */
@DisplayName("Member self-profile scenario")
class MemberSelfProfileScenarioTest extends ScenarioBase {

    private static final String PROFILE_URI = "/api/me/profile";

    @Test
    @DisplayName("a PORTAL member updates their timezone and cannot change profileId by any route")
    @SuppressWarnings("unchecked")
    void portalMemberEditsOnlyOwnAllowListedFields() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String slug = tenants.slugForTenantId(tenantId);
        String email = "self-profile-" + Long.toHexString(System.nanoTime()) + "@example.com";

        waitForStatus(gatewayClientWithToken(adminToken), "/" + slug + "/api/users", HttpStatus.OK, 20);

        ResponseEntity<Map> invited = gatewayClientWithToken(adminToken)
                .post().uri("/" + slug + "/api/admin/users/portal-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "firstName", "Portal", "lastName", "Member"))
                .retrieve().toEntity(Map.class);
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String memberId = (String) invited.getBody().get("userId");
        String pat = issuePat(tenantId, memberId);

        try (Connection db = openDbConnection()) {
            String originalProfileId = column(db, memberId, "profile_id");
            String adminProfileId = profileIdByName(db, tenantId, "System Administrator");
            assertThat(originalProfileId).as("portal member has a profile").isNotNull();
            assertThat(adminProfileId).as("tenant has the seeded admin profile").isNotNull()
                    .isNotEqualTo(originalProfileId);

            // ---- GET: own record, PORTAL
            ResponseEntity<Map> read = gatewayClientWithToken(pat)
                    .get().uri("/" + slug + PROFILE_URI)
                    .retrieve().toEntity(Map.class);
            assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> data = (Map<String, Object>) read.getBody().get("data");
            assertThat(data.get("id")).isEqualTo(memberId);
            Map<String, Object> attrs = (Map<String, Object>) data.get("attributes");
            assertThat(attrs.get("email")).isEqualTo(email);
            assertThat(attrs.get("userType")).isEqualTo("PORTAL");
            assertThat(attrs).doesNotContainKey("profileId");

            // ---- PATCH timezone: 200 and persisted
            ResponseEntity<Map> updated = patch(pat, "/" + slug + PROFILE_URI,
                    Map.of("data", Map.of("type", "users",
                            "attributes", Map.of("timezone", "Europe/Lisbon"))));
            assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(((Map<String, Object>) ((Map<String, Object>) updated.getBody().get("data"))
                    .get("attributes")).get("timezone")).isEqualTo("Europe/Lisbon");
            assertThat(column(db, memberId, "timezone")).as("timezone persisted").isEqualTo("Europe/Lisbon");

            // ---- PATCH profileId through /api/me/profile: 400 FIELD_NOT_EDITABLE
            ResponseEntity<Map> selfEscalation = patch(pat, "/" + slug + PROFILE_URI,
                    Map.of("data", Map.of("type", "users",
                            "attributes", Map.of("profileId", adminProfileId))));
            assertThat(selfEscalation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            Map<String, Object> error =
                    ((List<Map<String, Object>>) selfEscalation.getBody().get("errors")).get(0);
            assertThat(error.get("code")).isEqualTo("FIELD_NOT_EDITABLE");
            assertThat(((Map<String, Object>) error.get("source")).get("pointer"))
                    .isEqualTo("/data/attributes/profileId");

            // ---- PATCH profileId through the generic users route: rejected by the guard
            ResponseEntity<Map> routeEscalation = patch(pat, "/" + slug + "/api/users/" + memberId,
                    Map.of("data", Map.of("type", "users", "id", memberId,
                            "attributes", Map.of("profileId", adminProfileId))));
            assertThat(routeEscalation.getStatusCode().is4xxClientError())
                    .as("generic users route is closed to a member (got %s)", routeEscalation.getStatusCode())
                    .isTrue();

            // ---- profileId through an atomic operations batch: rejected by the guard
            Map<String, Object> atomicBody = Map.of("atomic:operations", List.of(Map.of(
                    "op", "update",
                    "ref", Map.of("type", "users", "id", memberId),
                    "data", Map.of("type", "users", "id", memberId,
                            "attributes", Map.of("profileId", adminProfileId)))));
            ResponseEntity<Map> atomicEscalation = gatewayClientWithToken(pat)
                    .post().uri("/" + slug + "/api/operations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(atomicBody)
                    .retrieve()
                    .onStatus(s -> true, (req, resp) -> {})
                    .toEntity(Map.class);
            assertThat(atomicEscalation.getStatusCode().is4xxClientError())
                    .as("atomic users update is closed to a member (got %s)", atomicEscalation.getStatusCode())
                    .isTrue();

            assertThat(column(db, memberId, "profile_id"))
                    .as("profile_id never changed").isEqualTo(originalProfileId);
            assertThat(column(db, memberId, "timezone"))
                    .as("the rejected writes left the timezone alone").isEqualTo("Europe/Lisbon");
        }
    }

    private ResponseEntity<Map> patch(String token, String uri, Map<String, Object> body) {
        return gatewayClientWithToken(token)
                .patch().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toEntity(Map.class);
    }

    private String issuePat(String tenantId, String userId) throws Exception {
        String pat = "klt_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection db = openDbConnection();
             PreparedStatement ps = db.prepareStatement(
                     "INSERT INTO user_api_token (user_id, tenant_id, name, token_prefix, token_hash, scopes, expires_at) "
                             + "VALUES (?, ?, ?, ?, ?, '[\"api\"]'::jsonb, ?)")) {
            ps.setString(1, userId);
            ps.setString(2, tenantId);
            ps.setString(3, "self-profile-scenario");
            ps.setString(4, pat.substring(0, 8));
            ps.setString(5, sha256(pat));
            ps.setTimestamp(6, Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)));
            ps.executeUpdate();
        }
        return pat;
    }

    /** Reads one {@code platform_user} column; {@code column} is a literal from this class. */
    private String column(Connection db, String userId, String column) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT " + column + " FROM platform_user WHERE id = ?")) {
            ps.setString(1, userId);
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

    private static String sha256(String input) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
