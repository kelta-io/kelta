package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway's system-collection response cache is varied by caller: a read answered from it is
 * the answer the worker gives <em>that</em> caller.
 *
 * <p>{@code /api/tenants} is the probe because its answer differs by caller without any Cerbos
 * policy setup (the stack's PDP is allow-all): a platform admin lists every tenant, while an
 * anonymous lookup is narrowed by the gateway to the URL's own tenant and its name and slug. Each
 * test uses its own page size, so the two never share an entry left by the other.
 */
@DisplayName("System-collection response cache varies by caller")
class SystemCollectionResponseCacheScenarioTest extends ScenarioBase {

    private static final String TENANTS = "/" + TenantFixture.DEFAULT_SLUG + "/api/tenants?page[size]=";

    @Test
    @DisplayName("an anonymous tenant lookup after a platform admin's listing gets its own narrowed answer")
    void anonymousLookupAfterAdminListing() {
        String uri = TENANTS + 197;
        String ownTenant = tenants.tenantIdForSlug(TenantFixture.DEFAULT_SLUG);
        String otherTenant = tenants.tenantIdForSlug(TenantFixture.ECOMMERCE_SLUG);

        List<Map<String, Object>> adminRows = rows(gatewayClientWithToken(auth.loginAsAdmin()), uri);
        assertThat(adminRows).extracting(row -> row.get("id")).contains(ownTenant, otherTenant);

        List<Map<String, Object>> anonymousRows = rows(gatewayClient(), uri);

        assertThat(anonymousRows).extracting(row -> row.get("id")).containsExactly(ownTenant);
        // fields[tenants]=name,slug — the record's system audit fields always come along.
        assertThat(attributes(anonymousRows.getFirst()).keySet())
                .isSubsetOf("name", "slug", "createdAt", "createdBy", "updatedAt", "updatedBy");
    }

    @Test
    @DisplayName("a platform admin's listing after an anonymous lookup is not the narrowed answer")
    void adminListingAfterAnonymousLookup() {
        String uri = TENANTS + 196;
        String ownTenant = tenants.tenantIdForSlug(TenantFixture.DEFAULT_SLUG);
        String otherTenant = tenants.tenantIdForSlug(TenantFixture.ECOMMERCE_SLUG);

        assertThat(rows(gatewayClient(), uri)).extracting(row -> row.get("id")).containsExactly(ownTenant);

        List<Map<String, Object>> adminRows = rows(gatewayClientWithToken(auth.loginAsAdmin()), uri);

        assertThat(adminRows).extracting(row -> row.get("id")).contains(ownTenant, otherTenant);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(RestClient client, String uri) {
        Map<String, Object> body = client.get().uri(uri).retrieve().body(Map.class);
        assertThat(body).isNotNull();
        return (List<Map<String, Object>>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> row) {
        return (Map<String, Object>) row.get("attributes");
    }
}
