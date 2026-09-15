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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KLT-206: {@code fields} was declared {@code .tenantScoped(false)} with no
 * {@code tenant_id} column, so {@code GET /api/fields} returned every tenant's field
 * metadata regardless of caller. Verifies the fix with two real, seeded tenants
 * ({@link TenantFixture#DEFAULT_SLUG} and {@link TenantFixture#ECOMMERCE_SLUG}):
 * tenant A's field must be invisible to tenant B, both in the list and by ID, while
 * system fields (owned by the platform sentinel tenant) and {@code filter[collectionId][eq]}
 * keep working for everyone.
 */
@DisplayName("Field Tenant Isolation Scenario")
class FieldTenantIsolationScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("GET /api/fields is scoped to the caller's tenant plus system fields")
    @SuppressWarnings("unchecked")
    void fieldsListAndGetByIdAreTenantScoped() {
        String tokenA = auth.loginAsAdmin(TenantFixture.DEFAULT_SLUG);
        String tokenB = auth.loginAsAdmin(TenantFixture.ECOMMERCE_SLUG);
        RestClient clientA = gatewayClientWithToken(tokenA);
        RestClient clientB = gatewayClientWithToken(tokenB);

        // 1. Create a collection + field under tenant A only.
        String collectionNameA = "klt206a";
        String collectionIdA = createCollection(clientA, TenantFixture.DEFAULT_SLUG, collectionNameA);
        String fieldIdA = addField(clientA, TenantFixture.DEFAULT_SLUG, collectionIdA, "secretFromA");

        // 2. Create a collection + field under tenant B only.
        String collectionNameB = "klt206b";
        String collectionIdB = createCollection(clientB, TenantFixture.ECOMMERCE_SLUG, collectionNameB);
        String fieldIdB = addField(clientB, TenantFixture.ECOMMERCE_SLUG, collectionIdB, "secretFromB");

        // 3. As tenant B, list /api/fields: A's field must be absent, B's own field
        //    and at least one system field must be present.
        List<Map<String, Object>> fieldsSeenByB = listFields(clientB, TenantFixture.ECOMMERCE_SLUG);
        List<String> idsSeenByB = fieldsSeenByB.stream().map(f -> (String) f.get("id")).toList();

        assertThat(idsSeenByB)
                .as("tenant B must not see tenant A's field")
                .doesNotContain(fieldIdA);
        assertThat(idsSeenByB)
                .as("tenant B must see its own field")
                .contains(fieldIdB);
        assertThat(fieldsSeenByB.size())
                .as("tenant B must also see platform system fields, not just its own")
                .isGreaterThan(1);

        // 4. Symmetrically, tenant A must not see tenant B's field.
        List<Map<String, Object>> fieldsSeenByA = listFields(clientA, TenantFixture.DEFAULT_SLUG);
        List<String> idsSeenByA = fieldsSeenByA.stream().map(f -> (String) f.get("id")).toList();
        assertThat(idsSeenByA)
                .as("tenant A must not see tenant B's field")
                .doesNotContain(fieldIdB);
        assertThat(idsSeenByA)
                .as("tenant A must see its own field")
                .contains(fieldIdA);

        // 5. GET /api/fields/{id} for another tenant's field must 404, not leak the record.
        HttpStatusCode crossTenantGetStatus = clientB.get()
                .uri("/" + TenantFixture.ECOMMERCE_SLUG + "/api/fields/" + fieldIdA)
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toBodilessEntity()
                .getStatusCode();
        assertThat(crossTenantGetStatus).isEqualTo(HttpStatus.NOT_FOUND);

        // 6. Own-tenant get-by-id still works.
        HttpStatusCode ownGetStatus = clientA.get()
                .uri("/" + TenantFixture.DEFAULT_SLUG + "/api/fields/" + fieldIdA)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode();
        assertThat(ownGetStatus).isEqualTo(HttpStatus.OK);

        // 7. filter[collectionId][eq] keeps working and stays scoped to the caller.
        ResponseEntity<Map> filteredByA = clientA.get()
                .uri("/" + TenantFixture.DEFAULT_SLUG + "/api/fields?filter[collectionId][eq]=" + collectionIdA
                        + "&page[size]=200")
                .retrieve().toEntity(Map.class);
        assertThat(filteredByA.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> filteredDataA =
                (List<Map<String, Object>>) filteredByA.getBody().get("data");
        assertThat(filteredDataA.stream().map(f -> (String) f.get("id")))
                .as("filter[collectionId][eq] still returns exactly that collection's fields")
                .contains(fieldIdA)
                .doesNotContain(fieldIdB);
    }

    private String createCollection(RestClient client, String slug, String name) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "collections",
                "attributes", Map.of(
                        "name", name,
                        "displayName", name,
                        "tenantScoped", true)));
        ResponseEntity<Map> response = client.post().uri("/" + slug + "/api/collections")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("collection '%s' create should succeed", name).isTrue();
        return (String) ((Map<String, Object>) response.getBody().get("data")).get("id");
    }

    @SuppressWarnings("unchecked")
    private String addField(RestClient client, String slug, String collectionId, String fieldName) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "fields",
                "attributes", Map.of(
                        "collectionId", collectionId,
                        "name", fieldName,
                        "type", "STRING")));
        ResponseEntity<Map> response = client.post().uri("/" + slug + "/api/fields")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("field '%s' create should succeed", fieldName).isTrue();
        return (String) ((Map<String, Object>) response.getBody().get("data")).get("id");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listFields(RestClient client, String slug) {
        ResponseEntity<Map> response = client.get()
                .uri("/" + slug + "/api/fields?page[size]=200")
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }
}
