package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record write path against the real stack: a body {@code tenantId} naming a tenant other
 * than the request's is refused with {@code 400 systemMaintained} on {@code tenantId}, on a
 * tenant-scoped system collection and on a user collection alike, and nothing is written; a
 * collection create cannot set {@code systemCollection: true}.
 *
 * <p>The caller is the ecommerce fixture tenant (it has the {@code products} user collection);
 * the foreign id is the default fixture tenant's.
 */
@DisplayName("Write Tenant Scope Scenario")
class WriteTenantScopeScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("a body tenantId naming another tenant is refused and nothing is written")
    @SuppressWarnings("unchecked")
    void foreignBodyTenantIdIsRefused() throws Exception {
        String slug = TenantFixture.ECOMMERCE_SLUG;
        String token = auth.loginAsAdmin(slug);
        String callerTenant = auth.extractTenantId(token);
        String otherTenant = tenants.tenantIdForSlug(TenantFixture.DEFAULT_SLUG);
        assertThat(otherTenant).as("two distinct tenants").isNotEqualTo(callerTenant);

        waitForStatus(gatewayClientWithToken(token), "/" + slug + "/api/products", HttpStatus.OK, 240);

        String suffix = Long.toHexString(System.nanoTime());
        String menuName = "wts-menu-" + suffix;
        String productName = "wts-product-" + suffix;

        ResponseEntity<Map> menu = post(token, "/" + slug + "/api/ui-menus", Map.of("data", Map.of(
                "type", "ui-menus",
                "attributes", Map.of("name", menuName, "tenantId", otherTenant))));
        assertRefusedOnTenantId(menu);

        ResponseEntity<Map> product = post(token, "/" + slug + "/api/products", Map.of("data", Map.of(
                "type", "products",
                "attributes", Map.of("name", productName, "tenantId", otherTenant))));
        assertRefusedOnTenantId(product);

        try (Connection db = openDbConnection()) {
            assertThat(countRows(db, "SELECT COUNT(*) FROM ui_menu WHERE name = ?", menuName))
                    .as("no menu row in any tenant").isZero();
        }

        // The caller's own tenant id is accepted.
        ResponseEntity<Map> ownMenu = post(token, "/" + slug + "/api/ui-menus", Map.of("data", Map.of(
                "type", "ui-menus",
                "attributes", Map.of("name", menuName, "tenantId", callerTenant))));
        assertThat(ownMenu.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String menuId = (String) ((Map<String, Object>) ownMenu.getBody().get("data")).get("id");
        try (Connection db = openDbConnection()) {
            assertThat(countRows(db, "SELECT COUNT(*) FROM ui_menu WHERE id::text = ? AND tenant_id::text = ?",
                    menuId, callerTenant)).isEqualTo(1);
            try (PreparedStatement ps = db.prepareStatement("DELETE FROM ui_menu WHERE id::text = ?")) {
                ps.setString(1, menuId);
                ps.executeUpdate();
            }
        }
    }

    @Test
    @DisplayName("a collection create cannot set systemCollection: true")
    void systemCollectionFlagIsRefused() throws Exception {
        String slug = TenantFixture.ECOMMERCE_SLUG;
        String token = auth.loginAsAdmin(slug);
        waitForStatus(gatewayClientWithToken(token), "/" + slug + "/api/collections", HttpStatus.OK, 240);

        String name = "wts_flag_" + Long.toHexString(System.nanoTime());
        ResponseEntity<Map> created = post(token, "/" + slug + "/api/collections", Map.of("data", Map.of(
                "type", "collections",
                "attributes", Map.of("name", name, "displayName", "Flag", "systemCollection", true))));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> error = firstError(created);
        assertThat(error.get("code")).isEqualTo("systemMaintained");
        assertThat(error.get("source")).isEqualTo(Map.of("pointer", "/data/attributes/systemCollection"));
        try (Connection db = openDbConnection()) {
            assertThat(countRows(db, "SELECT COUNT(*) FROM collection WHERE name = ?", name)).isZero();
        }
    }

    private void assertRefusedOnTenantId(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> error = firstError(response);
        assertThat(error.get("code")).isEqualTo("systemMaintained");
        assertThat(error.get("source")).isEqualTo(Map.of("pointer", "/data/attributes/tenantId"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstError(ResponseEntity<Map> response) {
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.getBody().get("errors");
        assertThat(errors).isNotEmpty();
        return errors.getFirst();
    }

    private ResponseEntity<Map> post(String token, String uri, Map<String, Object> body) {
        return gatewayClientWithToken(token)
                .post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toEntity(Map.class);
    }

    private static long countRows(Connection db, String sql, String... params) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
