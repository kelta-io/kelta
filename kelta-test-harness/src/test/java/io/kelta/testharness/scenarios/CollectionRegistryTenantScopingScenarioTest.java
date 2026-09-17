package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KLT-259: {@code ConcurrentCollectionRegistry.get(name)} tries the tenant-scoped key
 * ({@code tenantId:name}) first, then falls back to the bare {@code name} key "for system
 * collections or legacy registrations". Nothing in the fallback branch actually checked that
 * the bare-name hit was a system collection — so if a custom collection were ever registered
 * under a null {@code tenantId} (a cold cache, a legacy path, a registration race), it would
 * become servable to every tenant.
 *
 * <p>Both tenants below own a collection literally named {@code orders} with different
 * fields — tenant B reuses the {@code threadline-clothing} fixture's seeded {@code orders}
 * ({@code name}, {@code status}, {@code order_date}, {@code subtotal}, {@code tax_amount},
 * {@code total_amount}, {@code customer}), tenant A gets a fresh {@code orders} collection of
 * its own with a field ({@code region_code}) that tenant B's does not have. Every resolution
 * path this task audited — {@code DashboardComponentValidator} (dry-run validate),
 * {@code ListViewConfigHook} (write-time validate), and {@code DashboardDataService} (widget
 * execution) — must see only tenant B's fields and tenant B's data when called as tenant B,
 * never tenant A's, regardless of which tenant registered first.
 */
@DisplayName("Collection Registry Tenant Scoping Scenario")
class CollectionRegistryTenantScopingScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("tenant B's `orders` resolution (dashboard component validate, list view, widget data) never sees tenant A's `orders`")
    @SuppressWarnings("unchecked")
    void ordersCollectionResolvesOnlyToTheCallingTenant() throws Exception {
        String slugB = TenantFixture.ECOMMERCE_SLUG;
        String tokenB = auth.loginAsAdmin(slugB);
        String tenantB = auth.extractTenantId(tokenB);

        String slugA = TenantFixture.DEFAULT_SLUG;
        String tokenA = auth.loginAsAdmin(slugA);
        String tenantA = auth.extractTenantId(tokenA);
        assertThat(tenantA).as("two distinct tenants").isNotEqualTo(tenantB);

        waitForStatus(gatewayClientWithToken(tokenB), "/" + slugB + "/api/orders", HttpStatus.OK, 240);

        String suffix = Long.toHexString(System.nanoTime());
        String aOnlyField = "region_code_" + suffix;
        String marker = "klt259-" + suffix;

        String aOrdersId = null;
        String dashboardId = null;
        String componentId = null;
        String listViewId = null;
        String aRecordId = null;
        String bRecordId = null;

        try {
            // ---- tenant A gets its own `orders` collection with a field B's doesn't have.
            aOrdersId = createCollection(tokenA, slugA, "orders", "Orders (tenant A)");
            addStringField(tokenA, slugA, aOrdersId, "name");
            addStringField(tokenA, slugA, aOrdersId, aOnlyField);
            waitForField(tokenA, slugA, aOrdersId, aOnlyField);

            String bOrdersId = collectionIdByName(tokenB, slugB, "orders");
            assertThat(bOrdersId).as("the ecommerce fixture's `orders` collection exists").isNotBlank();
            assertThat(bOrdersId).as("A and B's `orders` are different collections").isNotEqualTo(aOrdersId);

            aRecordId = createRecord(tokenA, slugA, "orders", Map.of("name", marker, aOnlyField, "US-WEST"));
            bRecordId = createRecord(tokenB, slugB, "orders", Map.of("name", marker, "subtotal", 111.11));

            // ---- (1) DashboardComponentValidator (dry-run /validate): B's context resolves
            //          B's `orders` only — B's own field passes, A's exclusive field is unknown.
            assertValidateAccepted(tokenB, slugB, "orders", List.of("subtotal"));
            assertValidateRejected(tokenB, slugB, "orders", List.of(aOnlyField), aOnlyField);

            // ---- (2) ListViewConfigHook (write-time validate through collectionId resolution):
            //          same proof, a different call site.
            listViewId = createListView(tokenB, slugB, bOrdersId, "subtotal", "KLT-259 valid " + suffix);
            assertThat(listViewId).isNotBlank();
            assertListViewRejected(tokenB, slugB, bOrdersId, aOnlyField, "KLT-259 invalid " + suffix);

            // ---- (3) DashboardDataService: B's widget queries B's table/fields only, and never
            //          returns tenant A's marked row or tenant A's exclusive field.
            dashboardId = createRecord(tokenB, slugB, "dashboards", Map.of(
                    "name", "KLT-259 " + suffix, "accessLevel", "PRIVATE", "columnCount", 3));
            componentId = createRecord(tokenB, slugB, "dashboard-components", Map.of(
                    "dashboardId", dashboardId,
                    "componentType", "table",
                    "title", "KLT-259 orders",
                    "columnPosition", 1,
                    "rowPosition", 1,
                    "sortOrder", 1,
                    "config", Map.of(
                            "collectionName", "orders",
                            "fields", List.of("name", "subtotal"),
                            "filters", List.of(Map.of("field", "name", "operator", "eq", "value", marker)))));

            Map<String, Object> widget = widgetData(tokenB, slugB, dashboardId, componentId);
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) ((Map<String, Object>) widget.get("data")).get("records");
            assertThat(rows)
                    .as("tenant B's widget returns exactly its own marked order, never tenant A's")
                    .hasSize(1);
            assertThat(rows.get(0).get("subtotal"))
                    .as("the record carries tenant B's field")
                    .isNotNull();
            assertThat(rows.get(0))
                    .as("tenant A's exclusive field never appears on a tenant B widget row")
                    .doesNotContainKey(aOnlyField);
        } finally {
            deleteQuietly(tokenB, slugB, "dashboard-components", componentId);
            deleteQuietly(tokenB, slugB, "dashboards", dashboardId);
            deleteQuietly(tokenB, slugB, "list-views", listViewId);
            deleteQuietly(tokenB, slugB, "orders", bRecordId);
            if (aOrdersId != null) {
                gatewayClientWithToken(tokenA).delete()
                        .uri("/" + slugA + "/api/collections/" + aOrdersId + "?force=true")
                        .retrieve()
                        .onStatus(s -> true, (req, resp) -> { })
                        .toBodilessEntity();
            }
        }
    }

    // ------------------------------------------------------------- Helpers

    @SuppressWarnings("unchecked")
    private String createCollection(String token, String slug, String name, String displayName) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "collections",
                "attributes", Map.of(
                        "name", name,
                        "displayName", displayName,
                        "tenantScoped", true)));
        ResponseEntity<Map> response = gatewayClientWithToken(token).post()
                .uri("/" + slug + "/api/collections")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode()).as("create collection '" + name + "'")
                .isEqualTo(HttpStatus.CREATED);
        String id = (String) ((Map<String, Object>) response.getBody().get("data")).get("id");
        assertThat(id).isNotBlank();
        waitForStatus(gatewayClientWithToken(token), "/" + slug + "/api/" + name, HttpStatus.OK, 40);
        return id;
    }

    private void addStringField(String token, String slug, String collectionId, String fieldName) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "fields",
                "attributes", Map.of(
                        "collectionId", collectionId,
                        "name", fieldName,
                        "type", "STRING")));
        ResponseEntity<Void> response = gatewayClientWithToken(token).post()
                .uri("/" + slug + "/api/fields")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity();
        assertThat(response.getStatusCode()).as("add field '" + fieldName + "'").isEqualTo(HttpStatus.CREATED);
    }

    /** Polls the fields list until {@code fieldName} is present (route + registry propagation). */
    @SuppressWarnings("unchecked")
    private void waitForField(String token, String slug, String collectionId, String fieldName) {
        for (int i = 0; i < 30; i++) {
            try {
                Map<String, Object> body = gatewayClientWithToken(token).get()
                        .uri("/" + slug + "/api/fields?filter[collectionId][eq]=" + collectionId)
                        .retrieve().body(Map.class);
                List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
                boolean present = data.stream().anyMatch(row ->
                        fieldName.equals(((Map<String, Object>) row.get("attributes")).get("name")));
                if (present) return;
            } catch (Exception ignored) {
                // not ready yet
            }
            sleep();
        }
        throw new AssertionError("Field '" + fieldName + "' never appeared on collection " + collectionId);
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private String collectionIdByName(String token, String slug, String name) {
        Map<String, Object> body = gatewayClientWithToken(token).get()
                .uri("/" + slug + "/api/collections?filter[name][eq]=" + name)
                .retrieve().body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        return data.isEmpty() ? null : (String) data.get(0).get("id");
    }

    /** Creates a record through the generic JSON:API route and returns its id (asserts 201). */
    @SuppressWarnings("unchecked")
    private String createRecord(String token, String slug, String collection, Map<String, Object> attributes) {
        ResponseEntity<Map> created = gatewayClientWithToken(token)
                .post().uri("/" + slug + "/api/" + collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", collection, "attributes", attributes)))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode()).as(collection + " create").isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = (Map<String, Object>) created.getBody().get("data");
        String id = (String) data.get("id");
        assertThat(id).as("created " + collection + " has an id").isNotBlank();
        return id;
    }

    private void deleteQuietly(String token, String slug, String collection, String id) {
        if (id == null) {
            return;
        }
        gatewayClientWithToken(token).delete()
                .uri("/" + slug + "/api/" + collection + "/" + id)
                .retrieve()
                .onStatus(s -> true, (req, resp) -> { })
                .toBodilessEntity();
    }

    /** POSTs a candidate dashboard-component to the dry-run validate endpoint and asserts it's accepted. */
    @SuppressWarnings("unchecked")
    private void assertValidateAccepted(String token, String slug, String collectionName, List<String> fields) {
        Map<String, Object> result = validateComponent(token, slug, collectionName, fields);
        assertThat((Boolean) result.get("valid"))
                .as("fields %s on '%s' should validate for the calling tenant", fields, collectionName)
                .isTrue();
    }

    /** Same, but asserts the given field is rejected as unknown on the calling tenant's collection. */
    @SuppressWarnings("unchecked")
    private void assertValidateRejected(String token, String slug, String collectionName, List<String> fields,
                                         String expectedUnknownField) {
        Map<String, Object> result = validateComponent(token, slug, collectionName, fields);
        assertThat((Boolean) result.get("valid"))
                .as("fields %s must NOT validate -- %s belongs to a different tenant's '%s'",
                        fields, expectedUnknownField, collectionName)
                .isFalse();
        List<Map<String, Object>> components = (List<Map<String, Object>>) result.get("components");
        List<Map<String, Object>> errors = (List<Map<String, Object>>) components.get(0).get("errors");
        assertThat(errors)
                .as("validation errors mention the unknown field")
                .anyMatch(e -> String.valueOf(e.get("message")).contains(expectedUnknownField));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validateComponent(String token, String slug, String collectionName,
                                                    List<String> fields) {
        Map<String, Object> body = Map.of("components", List.of(Map.of(
                "componentType", "table",
                "config", Map.of("collectionName", collectionName, "fields", fields))));
        ResponseEntity<Map> response = gatewayClientWithToken(token).post()
                .uri("/" + slug + "/api/dashboards/" + UUID.randomUUID() + "/validate")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private String createListView(String token, String slug, String collectionId, String sortField, String name) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "list-views",
                "attributes", Map.of(
                        "collectionId", collectionId,
                        "name", name,
                        "columns", List.of(sortField),
                        "sortField", sortField)));
        ResponseEntity<Map> response = gatewayClientWithToken(token).post()
                .uri("/" + slug + "/api/list-views")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode()).as("create list-view").isEqualTo(HttpStatus.CREATED);
        return (String) ((Map<String, Object>) response.getBody().get("data")).get("id");
    }

    private void assertListViewRejected(String token, String slug, String collectionId, String unknownField,
                                         String name) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "list-views",
                "attributes", Map.of(
                        "collectionId", collectionId,
                        "name", name,
                        "columns", List.of(unknownField))));
        HttpStatusCode status = gatewayClientWithToken(token).post()
                .uri("/" + slug + "/api/list-views")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve()
                .onStatus(s -> true, (req, resp) -> { })
                .toBodilessEntity()
                .getStatusCode();
        assertThat(status.isError())
                .as("a list-view column belonging to a different tenant's collection must be rejected")
                .isTrue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> widgetData(String token, String slug, String dashboardId, String componentId) {
        ResponseEntity<Map> response = gatewayClientWithToken(token)
                .post().uri("/" + slug + "/api/dashboards/" + dashboardId
                        + "/components/" + componentId + "/data")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> attributes =
                (Map<String, Object>) ((Map<String, Object>) response.getBody().get("data")).get("attributes");
        assertThat(attributes.get("error")).as("widget executed without error").isNull();
        return attributes;
    }
}
