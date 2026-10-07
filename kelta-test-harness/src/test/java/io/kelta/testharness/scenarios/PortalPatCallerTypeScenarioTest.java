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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A portal member's personal access token acts as PORTAL (member-data-ownership slice 1).
 *
 * <p>A PAT's gateway principal is built from the token row, not from a JWT, so it carried no
 * {@code user_type} claim and every PAT — a portal member's included — was forwarded as
 * {@code X-User-Type: INTERNAL}. The gateway now takes the PAT owner's
 * {@code platform_user.user_type} from the worker identity lookup.
 *
 * <p>Observable on {@code GET /api/watches}: a PORTAL caller lists their own watches, while an
 * INTERNAL caller naming no member is asking for the tenant-wide support view and is refused
 * without {@code MANAGE_DATA}/{@code VIEW_ALL_DATA} — which the seeded Portal User profile never
 * holds. So the member's PAT getting a 200 own-list (not a 403) proves it was treated as PORTAL.
 */
@DisplayName("Portal PAT caller type scenario")
class PortalPatCallerTypeScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("a portal user's PAT calling a collection route is treated as PORTAL")
    @SuppressWarnings("unchecked")
    void portalUsersPatIsPortal() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String slug = tenants.slugForTenantId(tenantId);
        String email = "portal-pat-" + Long.toHexString(System.nanoTime()) + "@example.com";

        waitForStatus(gatewayClientWithToken(adminToken), "/" + slug + "/api/users", HttpStatus.OK, 20);

        ResponseEntity<Map> invited = gatewayClientWithToken(adminToken)
                .post().uri("/" + slug + "/api/admin/users/portal-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "firstName", "Portal", "lastName", "Member"))
                .retrieve().toEntity(Map.class);
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String memberId = (String) invited.getBody().get("userId");

        String pat = "klt_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection db = openDbConnection();
             PreparedStatement ps = db.prepareStatement(
                     "INSERT INTO user_api_token (user_id, tenant_id, name, token_prefix, token_hash, scopes, expires_at) "
                             + "VALUES (?, ?, ?, ?, ?, '[\"api\"]'::jsonb, ?)")) {
            ps.setString(1, memberId);
            ps.setString(2, tenantId);
            ps.setString(3, "portal-pat-scenario");
            ps.setString(4, pat.substring(0, 8));
            ps.setString(5, sha256(pat));
            ps.setTimestamp(6, Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)));
            ps.executeUpdate();
        }

        ResponseEntity<Map> watches = gatewayClientWithToken(pat)
                .get().uri("/" + slug + "/api/watches")
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toEntity(Map.class);

        assertThat(watches.getStatusCode())
                .as("an INTERNAL caller without support permissions is refused the tenant-wide view; "
                        + "the member's own PAT must be PORTAL and get their own list")
                .isEqualTo(HttpStatus.OK);
        assertThat((List<Object>) watches.getBody().get("data")).isEmpty();
        assertThat(watches.getBody()).doesNotContainKey("meta");
    }

    private static String sha256(String input) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
