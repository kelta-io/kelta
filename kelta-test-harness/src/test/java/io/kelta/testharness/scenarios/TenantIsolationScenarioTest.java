package io.kelta.testharness.scenarios;

import io.kelta.testharness.KeltaStack;
import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that tenant data and routing are fully isolated.
 *
 * <p>The gateway uses the tenant slug in the URL path to identify the target tenant
 * and enforces that the JWT's {@code tenant_id} claim matches. Requests to a slug
 * belonging to a different tenant must be rejected (401/403), and requests to an
 * entirely unknown slug must return 404.
 *
 * <p>Fixture tenants:
 * <ul>
 *   <li>{@code default} (id: 00000000-0000-0000-0000-000000000001) — Flyway baseline</li>
 *   <li>{@code threadline-clothing} — provisioned at startup by {@code EcommerceSeedFixture}
 *       via the admin API, with customers/orders/products collections</li>
 * </ul>
 * Each tenant has an admin account (password {@code password}); see {@code AuthFixture}.
 *
 * <p>The stack runs against a NOBYPASSRLS application role ({@code KeltaStack.APP_ROLE}), so
 * the isolation the gateway enforces above is backed by row-level security underneath: the
 * last two tests here assert that directly, on the same connection the services use.
 */
@DisplayName("Tenant Isolation Scenario")
class TenantIsolationScenarioTest extends ScenarioBase {

    /**
     * Confirms that both seeded tenants are present in the worker's slug-map, proving
     * that Flyway migrations ran fully and both tenants are ACTIVE.
     */
    @Test
    @DisplayName("both seeded tenants are registered in the slug-map")
    void bothSeededTenantsExist() {
        String defaultId    = tenants.tenantIdForSlug(TenantFixture.DEFAULT_SLUG);
        String ecommerceId  = tenants.tenantIdForSlug(TenantFixture.ECOMMERCE_SLUG);

        assertThat(defaultId).isNotNull().matches("[0-9a-f-]{36}");
        assertThat(ecommerceId).isNotNull().matches("[0-9a-f-]{36}");
        assertThat(defaultId).isNotEqualTo(ecommerceId);
    }

    /**
     * A token scoped to one tenant must be rejected when used against a different
     * tenant's slug endpoint.
     *
     * <p>We login once (direct-login returns the first DB match for the user, which
     * may be either tenant). We then determine which tenant the token belongs to and
     * attempt to access the OTHER tenant's slug. The gateway should reject with 401
     * or 403 because the JWT's {@code tenant_id} does not match the slug's tenant.
     */
    @Test
    @DisplayName("token for tenant A is rejected on tenant B slug")
    void tokenForTenantARejectedOnTenantBSlug() {
        String token    = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(token);
        String mySlug   = tenants.slugForTenantId(tenantId);
        assertThat(mySlug).isNotNull();

        // Determine the OTHER seeded tenant's slug
        String otherSlug = TenantFixture.DEFAULT_SLUG.equals(mySlug)
                ? TenantFixture.ECOMMERCE_SLUG
                : TenantFixture.DEFAULT_SLUG;

        // Using my token against the other tenant's slug should be rejected
        assertThatThrownBy(() ->
                gatewayClientWithToken(token)
                        .get()
                        .uri("/" + otherSlug + "/api/collections")
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOfSatisfying(HttpClientErrorException.class, ex -> {
            HttpStatusCode status = ex.getStatusCode();
            // Gateway must reject with 401 (unauthenticated for wrong tenant) or 403 (forbidden)
            assertThat(status.value()).isIn(
                    HttpStatus.UNAUTHORIZED.value(),
                    HttpStatus.FORBIDDEN.value()
            );
        });
    }

    /**
     * An unauthenticated request to any tenant slug endpoint must be rejected with 401.
     * Verifies that tenant isolation applies even without a token.
     */
    @Test
    @DisplayName("unauthenticated request is rejected on any tenant slug")
    void unauthenticatedRequestRejectedOnBothSlugs() {
        for (String slug : List.of(TenantFixture.DEFAULT_SLUG, TenantFixture.ECOMMERCE_SLUG)) {
            assertThatThrownBy(() ->
                    gatewayClient()
                            .get()
                            .uri("/" + slug + "/api/collections")
                            .retrieve()
                            .toBodilessEntity()
            ).isInstanceOfSatisfying(HttpClientErrorException.class, ex ->
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
            );
        }
    }

    /**
     * A request to a completely unknown slug must return 404 — not 401 or 500.
     * This ensures the gateway fails fast for non-existent tenants without leaking
     * information about valid slug existence via auth errors.
     */
    @Test
    @DisplayName("unknown slug returns 404 regardless of authentication")
    void unknownSlugReturns404() {
        String token = auth.loginAsAdmin();

        // Without token
        HttpStatusCode unauthStatus = gatewayClient()
                .get()
                .uri("/does-not-exist-xyz/api/collections")
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toBodilessEntity()
                .getStatusCode();

        // With a valid token
        HttpStatusCode authStatus = gatewayClientWithToken(token)
                .get()
                .uri("/does-not-exist-xyz/api/collections")
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toBodilessEntity()
                .getStatusCode();

        assertThat(unauthStatus).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(authStatus).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Verifies that a token scoped to a tenant can successfully access that same
     * tenant's data — confirming isolation is one-directional (not overly restrictive).
     */
    @Test
    @DisplayName("token grants access to its own tenant slug")
    void tokenGrantsAccessToOwnTenantSlug() {
        String token    = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(token);
        String mySlug   = tenants.slugForTenantId(tenantId);
        assertThat(mySlug).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> body = gatewayClientWithToken(token)
                .get()
                .uri("/" + mySlug + "/api/collections")
                .retrieve()
                .body(Map.class);

        assertThat(body).containsKey("data");
    }

    // ── database-layer isolation (the services' own role) ────────────────────

    /**
     * The premise every RLS assertion in this class rests on. A superuser — which is what
     * the harness used to hand the services — never evaluates a policy, so before this the
     * whole stack could have had no RLS at all and every test here would still have passed.
     */
    @Test
    @DisplayName("the services connect as a role that row-level security applies to")
    void servicesRunWithoutBypassRls() throws Exception {
        try (Connection conn = openAppDbConnection(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1))
                        .as("%s must be NOBYPASSRLS and not a superuser, or the policies are decoration",
                                KeltaStack.appDbUsername())
                        .isFalse();
            }
        }
    }

    /**
     * Reads {@code platform_user} directly, as the services do, with each tenant bound in
     * turn: the rows a tenant can see must be its own. A query that forgets its
     * {@code WHERE tenant_id = ?} is contained by the database rather than by the caller.
     */
    @Test
    @DisplayName("bound to tenant A, the application role cannot read tenant B's rows")
    void databaseDeniesCrossTenantReads() throws Exception {
        String defaultId   = tenants.tenantIdForSlug(TenantFixture.DEFAULT_SLUG);
        String ecommerceId = tenants.tenantIdForSlug(TenantFixture.ECOMMERCE_SLUG);

        assertThat(tenantIdsVisibleTo(defaultId)).containsExactly(defaultId);
        assertThat(tenantIdsVisibleTo(ecommerceId)).containsExactly(ecommerceId);

        // The platform session (no tenant bound) still sees both — that is what Flyway and
        // the cross-tenant bootstrap paths run as.
        assertThat(tenantIdsVisibleTo("")).contains(defaultId, ecommerceId);
    }

    /** Distinct platform_user.tenant_id values visible with {@code tenantId} bound. */
    private List<String> tenantIdsVisibleTo(String tenantId) throws Exception {
        try (Connection conn = openAppDbConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL app.current_tenant_id = '" + tenantId.replace("'", "''") + "'");
                List<String> seen = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(
                        "SELECT DISTINCT tenant_id FROM platform_user ORDER BY 1")) {
                    while (rs.next()) {
                        seen.add(rs.getString(1));
                    }
                }
                return seen;
            } finally {
                conn.rollback();
            }
        }
    }
}
