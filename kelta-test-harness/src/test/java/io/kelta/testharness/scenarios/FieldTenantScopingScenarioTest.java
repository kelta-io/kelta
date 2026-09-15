package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * KLT-206: {@code GET /api/fields} used to be declared {@code .tenantScoped(false)} with no
 * {@code tenant_id} column on the {@code field} table, so {@code injectTenantFilter} added no
 * predicate — any tenant could list (or filter-guess) every field on the platform, including
 * other tenants' field names and types.
 *
 * <p>Proves the fix against the real stack with two seeded tenants: {@code default} (tenant A,
 * no user collections) and {@code threadline-clothing} (tenant B, seeded by
 * {@code EcommerceSeedFixture} with real {@code customers}/{@code orders}/{@code products}
 * fields). Tenant A must never see tenant B's field rows, either in a list or by direct id.
 */
@DisplayName("Field Tenant Scoping Scenario")
class FieldTenantScopingScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("tenant A cannot list or fetch tenant B's fields; filter[collectionId][eq] still scopes correctly")
    @SuppressWarnings("unchecked")
    void fieldsAreScopedToTheCallingTenant() throws Exception {
        String tokenB = auth.loginAsAdmin(TenantFixture.ECOMMERCE_SLUG);
        String tenantB = auth.extractTenantId(tokenB);
        String slugB = tenants.slugForTenantId(tenantB);

        String tokenA = auth.loginAsAdmin(TenantFixture.DEFAULT_SLUG);
        String tenantA = auth.extractTenantId(tokenA);
        String slugA = tenants.slugForTenantId(tenantA);
        assertThat(tenantA).isNotEqualTo(tenantB);

        CollectionField bField;
        try (Connection admin = openDbConnection()) {
            bField = firstCollectionField(admin, tenantB);
        }
        assertThat(bField).as("ecommerce tenant has a seeded collection field").isNotNull();

        // ---- (1) as tenant A, an unfiltered list must not contain tenant B's field
        Map<String, Object> listAsA = gatewayClientWithToken(tokenA)
                .get()
                .uri("/" + slugA + "/api/fields?page[size]=200")
                .retrieve()
                .body(Map.class);
        assertThat(listAsA).containsKey("data");
        List<Map<String, Object>> dataAsA = (List<Map<String, Object>>) listAsA.get("data");
        assertThat(dataAsA)
                .as("tenant A's field list must not contain tenant B's field id")
                .extracting(row -> row.get("id"))
                .doesNotContain(bField.id());
        assertThat(dataAsA)
                .as("tenant A's field list must not contain tenant B's collectionId")
                .extracting(row -> ((Map<String, Object>) row.get("attributes")).get("collectionId"))
                .doesNotContain(bField.collectionId());

        // ---- (2) as tenant A, GET /api/fields/{B-field-id} -> 404 (no existence leak)
        HttpClientErrorException notFound = catchThrowableOfType(
                () -> gatewayClientWithToken(tokenA)
                        .get()
                        .uri("/" + slugA + "/api/fields/" + bField.id())
                        .retrieve()
                        .toBodilessEntity(),
                HttpClientErrorException.class);
        assertThat(notFound).as("cross-tenant field fetch by id must 404").isNotNull();
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // ---- (3) as tenant B, the same field is readable directly ...
        Map<String, Object> ownField = gatewayClientWithToken(tokenB)
                .get()
                .uri("/" + slugB + "/api/fields/" + bField.id())
                .retrieve()
                .body(Map.class);
        assertThat(ownField).containsKey("data");

        // ---- (4) ... and filter[collectionId][eq] still scopes to that collection's fields
        Map<String, Object> filtered = gatewayClientWithToken(tokenB)
                .get()
                .uri("/" + slugB + "/api/fields?filter[collectionId][eq]=" + bField.collectionId())
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> filteredData = (List<Map<String, Object>>) filtered.get("data");
        assertThat(filteredData).as("collectionId filter returns at least the seeded field").isNotEmpty();
        assertThat(filteredData)
                .extracting(row -> ((Map<String, Object>) row.get("attributes")).get("collectionId"))
                .allMatch(bField.collectionId()::equals);
    }

    private record CollectionField(String id, String collectionId) {}

    private CollectionField firstCollectionField(Connection conn, String tenantId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT f.id AS fid, f.collection_id AS cid
                FROM field f JOIN collection c ON f.collection_id = c.id
                WHERE c.tenant_id = ? AND f.active = true
                ORDER BY c.id, f.field_order LIMIT 1
                """)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new CollectionField(rs.getString("fid"), rs.getString("cid")) : null;
            }
        }
    }
}
