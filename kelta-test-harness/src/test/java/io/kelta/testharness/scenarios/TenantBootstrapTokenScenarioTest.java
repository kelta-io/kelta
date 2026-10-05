package io.kelta.testharness.scenarios;

import io.kelta.testharness.KeltaStack;
import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-342: {@code POST /api/tenants/{id}/bootstrap-token} mints a short-lived PAT in the
 * <em>target</em> tenant for its seeded System Administrator.
 *
 * <ul>
 *   <li>The token reads {@code /api/me/identity} in the target tenant, and its token list shows
 *       only the prefix.</li>
 *   <li>It is refused on another tenant's URL and on the platform tenant's URL — the gateway's
 *       PAT tenant check (#1615) binds it to the tenant it was issued in.</li>
 *   <li>Once {@code expires_at} has passed it is refused. The scenario backdates the row and
 *       drops the Redis cache entry — the entry's TTL is the token's lifetime, so this is the
 *       state an expired token is in without waiting an hour.</li>
 *   <li>The platform tenant itself cannot be bootstrapped.</li>
 * </ul>
 */
@DisplayName("Tenant bootstrap token")
class TenantBootstrapTokenScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("a bootstrap token works only in its target tenant, and only until it expires")
    @SuppressWarnings("unchecked")
    void bootstrapTokenIsBoundToTargetTenantAndExpires() throws Exception {
        String slug = "plt342-" + UUID.randomUUID().toString().substring(0, 8);
        String tenantId = createTenant(slug);

        RestClient platformAdmin = gatewayClientWithToken(auth.loginAsAdmin());
        ResponseEntity<Map> minted = platformAdmin.post()
                .uri("/" + TenantFixture.DEFAULT_SLUG + "/api/tenants/" + tenantId + "/bootstrap-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("expiresIn", "1h"))
                .retrieve().toEntity(Map.class);

        assertThat(minted.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) minted.getBody().get("token");
        assertThat(token).startsWith("klt_");
        assertThat(minted.getBody()).containsEntry("tenantId", tenantId);

        RestClient bootstrap = gatewayClientWithToken(token);
        // The gateway learns a new tenant's slug asynchronously.
        waitForStatus(bootstrap, "/" + slug + "/api/me/identity", HttpStatus.OK, 60);

        Map<String, Object> identity = bootstrap.get()
                .uri("/" + slug + "/api/me/identity")
                .retrieve().body(Map.class);
        assertThat(identity).isNotNull();
        assertThat(String.valueOf(identity)).contains((String) minted.getBody().get("userId"));

        Map<String, Object> tokens = bootstrap.get()
                .uri("/" + slug + "/api/me/tokens")
                .retrieve().body(Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) tokens.get("data");
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("tokenPrefix")).isEqualTo(minted.getBody().get("tokenPrefix"));
            assertThat((String) row.get("name")).startsWith("bootstrap-");
        });
        assertThat(String.valueOf(tokens)).doesNotContain(token);

        assertThat(status(bootstrap, "/" + TenantFixture.ECOMMERCE_SLUG + "/api/me/identity"))
                .as("another tenant's URL").isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(status(bootstrap, "/" + TenantFixture.DEFAULT_SLUG + "/api/me/identity"))
                .as("the platform tenant's URL").isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(status(bootstrap, "/" + TenantFixture.DEFAULT_SLUG + "/api/tenants"))
                .as("the platform tenant's tenant API").isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

        String tokenHash = sha256(token);
        try (Connection db = openDbConnection()) {
            assertThat(expiresInSeconds(db, tokenHash)).as("stored lifetime ≈ 1 h").isBetween(3300L, 3600L);
            try (PreparedStatement ps = db.prepareStatement(
                    "UPDATE user_api_token SET expires_at = NOW() - INTERVAL '1 minute' WHERE token_hash = ?")) {
                ps.setString(1, tokenHash);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        }
        KeltaStack.REDIS.execInContainer("redis-cli", "DEL", "pat:" + tokenHash);

        assertThat(status(bootstrap, "/" + slug + "/api/me/identity"))
                .as("expired").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the platform tenant cannot be bootstrapped")
    void platformTenantRefused() {
        String platformTenantId = tenants.tenantIdForSlug(TenantFixture.DEFAULT_SLUG);
        RestClient platformAdmin = gatewayClientWithToken(auth.loginAsAdmin());

        HttpStatusCode refused = platformAdmin.post()
                .uri("/" + TenantFixture.DEFAULT_SLUG + "/api/tenants/" + platformTenantId + "/bootstrap-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .toBodilessEntity()
                .getStatusCode();

        assertThat(refused).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------

    private static HttpStatusCode status(RestClient client, String uri) {
        return client.get().uri(uri).retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .toBodilessEntity()
                .getStatusCode();
    }

    private static long expiresInSeconds(Connection db, String tokenHash) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT EXTRACT(EPOCH FROM (expires_at - NOW()))::bigint FROM user_api_token WHERE token_hash = ?")) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("token row exists").isTrue();
                return rs.getLong(1);
            }
        }
    }

    private String createTenant(String slug) {
        RestClient client = gatewayClientWithToken(auth.loginAsAdmin());
        ResponseEntity<Map> response = client.post()
                .uri("/" + TenantFixture.DEFAULT_SLUG + "/api/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "tenants",
                        "attributes", Map.of("slug", slug, "name", "PLT-342 " + slug))))
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        for (int i = 0; i < 60; i++) {
            String id = tenants.tenantIdForSlug(slug);
            if (id != null) {
                return id;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("Tenant " + slug + " never appeared in the slug-map");
    }

    private static String sha256(String input) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
