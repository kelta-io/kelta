package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sub-resource routes ({@code /api/{parent}/{parentId}/{child}/{childId}}) and get-by-id on the
 * shared metadata collections, against the real stack.
 *
 * <ul>
 *   <li>A child record is reachable — read, updated or deleted — only under the parent it
 *       references; under any other parent of the same tenant it is a 404 and nothing is
 *       written.</li>
 *   <li>A collection created directly in the platform tenant (the harness {@code default} tenant)
 *       that is not a system collection is not visible to another tenant by id, by name, or as a
 *       child, nor are its fields — the same rule the list path applies. A genuine system
 *       collection stays visible to every tenant.</li>
 * </ul>
 */
@DisplayName("Child Resource Scoping Scenario")
class ChildResourceScopingScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("a child record is reachable only under the parent it references")
    @SuppressWarnings("unchecked")
    void childRecordIsScopedToItsParent() {
        String slug = TenantFixture.ECOMMERCE_SLUG;
        String token = auth.loginAsAdmin(slug);
        RestClient client = gatewayClientWithToken(token);
        String base = "/" + slug + "/api";

        waitForStatus(client, base + "/orders", HttpStatus.OK, 40);

        String suffix = Long.toHexString(System.nanoTime());
        String ownerId = createRecord(client, base + "/customers", "customers",
                Map.of("email", "child-scope-owner-" + suffix + "@example.com", "first_name", "Owner"));
        String otherId = createRecord(client, base + "/customers", "customers",
                Map.of("email", "child-scope-other-" + suffix + "@example.com", "first_name", "Other"));
        String orderId = createRecord(client, base + "/orders", "orders",
                Map.of("customer", ownerId, "status", "pending"));

        // Under its own parent the child is served.
        assertThat(status(client.get().uri(base + "/customers/" + ownerId + "/orders/" + orderId)))
                .isEqualTo(HttpStatus.OK);

        // Under another parent of the same tenant every verb answers like a missing child...
        String wrongPath = base + "/customers/" + otherId + "/orders/" + orderId;
        assertThat(status(client.get().uri(wrongPath))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(client.patch().uri(wrongPath).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "orders", "attributes", Map.of("status", "moved"))))))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(client.put().uri(wrongPath).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "orders", "attributes", Map.of("status", "moved"))))))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(client.delete().uri(wrongPath))).isEqualTo(HttpStatus.NOT_FOUND);

        // ...and nothing was written: the order still exists, unchanged.
        Map<String, Object> order = client.get().uri(base + "/orders/" + orderId).retrieve().body(Map.class);
        Map<String, Object> attributes = (Map<String, Object>) ((Map<String, Object>) order.get("data"))
                .get("attributes");
        assertThat(attributes.get("status")).isEqualTo("pending");
    }

    @Test
    @DisplayName("a platform-tenant non-system collection and its fields are not visible to another tenant by id")
    @SuppressWarnings("unchecked")
    void platformNonSystemCollectionIsNotVisibleToOtherTenants() throws Exception {
        String platformToken = auth.loginAsAdmin(TenantFixture.DEFAULT_SLUG);
        RestClient platform = gatewayClientWithToken(platformToken);
        String platformBase = "/" + TenantFixture.DEFAULT_SLUG + "/api";

        String otherToken = auth.loginAsAdmin(TenantFixture.ECOMMERCE_SLUG);
        RestClient other = gatewayClientWithToken(otherToken);
        String otherBase = "/" + TenantFixture.ECOMMERCE_SLUG + "/api";

        String name = "childscope" + Long.toHexString(System.nanoTime());
        String collectionId = createRecord(platform, platformBase + "/collections", "collections",
                Map.of("name", name, "displayName", "Child scope", "tenantScoped", true));
        try {
            String fieldId = createRecord(platform, platformBase + "/fields", "fields",
                    Map.of("collectionId", collectionId, "name", "label", "type", "STRING"));

            // The platform tenant reads its own rows.
            assertThat(status(platform.get().uri(platformBase + "/collections/" + collectionId)))
                    .isEqualTo(HttpStatus.OK);
            assertThat(status(platform.get().uri(platformBase + "/fields/" + fieldId)))
                    .isEqualTo(HttpStatus.OK);

            // Another tenant sees none of them — by id, by name, or as a child.
            assertThat(status(other.get().uri(otherBase + "/collections/" + collectionId)))
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(status(other.get().uri(otherBase + "/collections/" + name)))
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(status(other.get().uri(otherBase + "/fields/" + fieldId)))
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(status(other.get().uri(otherBase + "/collections/" + collectionId + "/fields/" + fieldId)))
                    .isEqualTo(HttpStatus.NOT_FOUND);

            // A genuine system collection stays visible to every tenant.
            String usersCollectionId;
            try (Connection db = openDbConnection()) {
                usersCollectionId = systemCollectionId(db, "users");
            }
            assertThat(usersCollectionId).isNotNull();
            assertThat(status(other.get().uri(otherBase + "/collections/" + usersCollectionId)))
                    .isEqualTo(HttpStatus.OK);
        } finally {
            platform.delete().uri(platformBase + "/collections/" + collectionId + "?force=true")
                    .retrieve().onStatus(s -> true, (req, resp) -> { }).toBodilessEntity();
        }
    }

    @SuppressWarnings("unchecked")
    private static String createRecord(RestClient client, String uri, String type, Map<String, Object> attributes) {
        ResponseEntity<Map> created = client.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", type, "attributes", attributes)))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create %s", type).isTrue();
        return (String) ((Map<String, Object>) created.getBody().get("data")).get("id");
    }

    private static HttpStatusCode status(RestClient.RequestHeadersSpec<?> request) {
        return request.retrieve().onStatus(s -> true, (req, resp) -> { }).toBodilessEntity().getStatusCode();
    }

    private static String systemCollectionId(Connection db, String name) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM collection WHERE name = ? AND system_collection = true LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
