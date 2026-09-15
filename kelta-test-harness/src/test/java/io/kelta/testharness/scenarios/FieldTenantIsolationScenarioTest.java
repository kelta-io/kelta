package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies GET /api/fields is scoped to the caller's tenant (KLT-206).
 *
 * <p>Before this fix, {@code fields} was declared {@code .tenantScoped(false)} and the
 * {@code field} table carried no {@code tenant_id} column, so any tenant could list (and
 * fetch by id) every other tenant's field metadata. Uses the {@code default} tenant and the
 * {@code threadline-clothing} fixture tenant (seeded by {@link
 * io.kelta.testharness.fixtures.EcommerceSeedFixture} with a {@code products} collection).
 */
@DisplayName("Field Tenant Isolation Scenario")
class FieldTenantIsolationScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("a tenant cannot see another tenant's fields, but system-collection fields stay visible")
    void fieldsAreTenantScoped() {
        String defaultToken = auth.loginAsAdmin(TenantFixture.DEFAULT_SLUG);
        String ecommerceToken = auth.loginAsAdmin(TenantFixture.ECOMMERCE_SLUG);

        // Resolve one of the ecommerce tenant's own field ids ("products.sku").
        String productsCollectionId =
                collectionId(ecommerceToken, TenantFixture.ECOMMERCE_SLUG, "products");
        String otherTenantFieldId =
                fieldId(ecommerceToken, TenantFixture.ECOMMERCE_SLUG, productsCollectionId, "sku");
        assertThat(otherTenantFieldId).as("ecommerce tenant's 'products.sku' field id").isNotBlank();

        // As the default tenant, list fields with a large page and confirm the other
        // tenant's field is absent.
        List<Map<String, Object>> defaultTenantFields = listFields(defaultToken, TenantFixture.DEFAULT_SLUG);
        assertThat(defaultTenantFields).isNotEmpty();
        assertThat(defaultTenantFields.stream().map(r -> r.get("id")))
                .as("default tenant's field list must not contain another tenant's field")
                .doesNotContain(otherTenantFieldId);

        // filter[collectionId][eq] against the platform's own "fields" system collection
        // must still work, and must return rows — proving system-collection fields (owned
        // by the platform sentinel tenant) remain visible to an ordinary tenant.
        String fieldsSystemCollectionId = collectionId(defaultToken, TenantFixture.DEFAULT_SLUG, "fields");
        List<Map<String, Object>> systemFields =
                fieldsForCollection(defaultToken, TenantFixture.DEFAULT_SLUG, fieldsSystemCollectionId);
        assertThat(systemFields)
                .as("system-collection ('fields') fields must remain visible to every tenant")
                .isNotEmpty();

        // GET /api/fields/{id} for the other tenant's field must 404.
        HttpStatusCode status = gatewayClientWithToken(defaultToken)
                .get()
                .uri("/" + TenantFixture.DEFAULT_SLUG + "/api/fields/" + otherTenantFieldId)
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toBodilessEntity()
                .getStatusCode();
        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listFields(String token, String slug) {
        Map<String, Object> body = gatewayClientWithToken(token)
                .get()
                .uri("/" + slug + "/api/fields?page[size]=200")
                .retrieve()
                .body(Map.class);
        assertThat(body).containsKey("data");
        return (List<Map<String, Object>>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fieldsForCollection(String token, String slug, String collectionId) {
        Map<String, Object> body = gatewayClientWithToken(token)
                .get()
                .uri("/" + slug + "/api/fields?filter[collectionId][eq]=" + collectionId)
                .retrieve()
                .body(Map.class);
        assertThat(body).containsKey("data");
        return (List<Map<String, Object>>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private String collectionId(String token, String slug, String name) {
        Map<String, Object> body = gatewayClientWithToken(token)
                .get()
                .uri("/" + slug + "/api/collections?filter[name][eq]=" + name)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertThat(data).as("collection '" + name + "' should exist for " + slug).isNotEmpty();
        return (String) data.get(0).get("id");
    }

    @SuppressWarnings("unchecked")
    private String fieldId(String token, String slug, String collectionId, String fieldName) {
        List<Map<String, Object>> data = fieldsForCollection(token, slug, collectionId);
        return data.stream()
                .filter(r -> {
                    Map<String, Object> attrs = (Map<String, Object>) r.get("attributes");
                    return attrs != null && fieldName.equals(attrs.get("name"));
                })
                .map(r -> (String) r.get("id"))
                .findFirst()
                .orElse(null);
    }
}
