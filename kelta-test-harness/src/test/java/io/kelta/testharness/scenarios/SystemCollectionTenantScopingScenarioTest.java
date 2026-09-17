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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-260: tenant scoping of tenant-scoped system collections is enforced at the storage layer
 * ({@code PhysicalTableStorageAdapter.query()}), not only by {@code DynamicCollectionRouter}'s
 * JSON:API injection — so every direct {@code queryEngine.executeQuery} caller inherits it.
 *
 * <p>Two cross-tenant leaks were observed in production on 2026-09-16 and are what this scenario
 * pins down against the real stack:
 * <ul>
 *   <li>a {@code metric} widget on {@code users} / {@code userType = PORTAL} returned 14 for a
 *       tenant that has 6 portal users — the other 8 belonged to another tenant; a {@code table}
 *       widget and a report on {@code users} listed every tenant's email the same way;</li>
 *   <li>{@code GET /api/pages/home/render} served <em>another tenant's</em> home page — config,
 *       data-source definitions and deep links included — because the lookup filtered only on
 *       slug/published/active and the first match won regardless of tenant.</li>
 * </ul>
 *
 * <p>Both tenants get a published, active page on the same slug, tenant A's created first, so a
 * regression that drops the tenant predicate reproduces the original bug exactly: B's render
 * returns A's page. {@code collections} must still list the platform's shared system rows, which
 * is the thing a blanket {@code tenant_id = ?} would break.
 */
@DisplayName("System Collection Tenant Scoping Scenario")
class SystemCollectionTenantScopingScenarioTest extends ScenarioBase {

    private static final String SHARED_PAGE_SLUG = "home";

    @Test
    @DisplayName("dashboards, reports and page render read only the calling tenant's system rows")
    @SuppressWarnings("unchecked")
    void systemCollectionReadsAreScopedToTheCallingTenant() throws Exception {
        String slugB = TenantFixture.ECOMMERCE_SLUG;
        String tokenB = auth.loginAsAdmin(slugB);
        String tenantB = auth.extractTenantId(tokenB);

        String slugA = TenantFixture.DEFAULT_SLUG;
        String tokenA = auth.loginAsAdmin(slugA);
        String tenantA = auth.extractTenantId(tokenA);
        assertThat(tenantA).as("two distinct tenants").isNotEqualTo(tenantB);

        waitForStatus(gatewayClientWithToken(tokenB), "/" + slugB + "/api/reports", HttpStatus.OK, 240);

        String suffix = Long.toHexString(System.nanoTime());
        String emailA = "plt260-a-" + suffix + "@example.com";
        String emailB = "plt260-b-" + suffix + "@example.com";

        String dashboardId = null;
        String metricId = null;
        String tableId = null;
        String reportId = null;

        try (Connection db = openDbConnection()) {
            String usersCollectionId = systemCollectionId(db, "users");
            assertThat(usersCollectionId).as("the `users` system collection row exists").isNotNull();

            try {
                // ---- both tenants hold portal users; A gets more, so an unscoped read is obvious
                seedPortalUsers(db, tenantA, emailA, 4);
                seedPortalUsers(db, tenantB, emailB, 2);
                long portalUsersB = countPortalUsers(db, tenantB);
                long portalUsersEverywhere = countPortalUsers(db, null);
                assertThat(portalUsersEverywhere)
                        .as("the leak is only visible when other tenants have portal users too")
                        .isGreaterThan(portalUsersB);

                // ---- both tenants publish a page on the same slug, A's first (it used to win)
                seedPublishedPage(db, tenantA, "PLT-260 Tenant A Home");
                seedPublishedPage(db, tenantB, "PLT-260 Tenant B Home");

                dashboardId = createRecord(tokenB, slugB, "dashboards", Map.of(
                        "name", "PLT-260 " + suffix,
                        "accessLevel", "PRIVATE",
                        "columnCount", 3));

                Map<String, Object> portalFilter = Map.of(
                        "filters", List.of(Map.of("field", "userType", "operator", "eq", "value", "PORTAL")));

                metricId = createRecord(tokenB, slugB, "dashboard-components", Map.of(
                        "dashboardId", dashboardId,
                        "componentType", "metric",
                        "title", "Portal users",
                        "columnPosition", 1,
                        "rowPosition", 1,
                        "sortOrder", 1,
                        "config", Map.of(
                                "collectionName", "users",
                                "aggregateFunction", "COUNT",
                                "filters", portalFilter.get("filters"))));

                tableId = createRecord(tokenB, slugB, "dashboard-components", Map.of(
                        "dashboardId", dashboardId,
                        "componentType", "table",
                        "title", "Portal user list",
                        "columnPosition", 1,
                        "rowPosition", 2,
                        "sortOrder", 2,
                        "config", Map.of(
                                "collectionName", "users",
                                "fields", List.of("email", "userType"),
                                "filters", portalFilter.get("filters"))));

                reportId = createRecord(tokenB, slugB, "reports", Map.of(
                        "name", "PLT-260 portal users " + suffix,
                        "reportType", "TABULAR",
                        "primaryCollectionId", usersCollectionId,
                        "columns", List.of(
                                Map.of("fieldName", "email", "label", "Email", "type", "string")),
                        "filters", List.of(
                                Map.of("field", "userType", "operator", "eq", "value", "PORTAL"))));

                // ---- (1) metric widget: COUNT is tenant B's, not the platform's
                Map<String, Object> metric = widgetData(tokenB, slugB, dashboardId, metricId);
                assertThat(metric).as("metric widget executed").containsKey("data");
                assertThat(numberOf(((Map<String, Object>) metric.get("data")).get("value")))
                        .as("a portal-user metric must count only the calling tenant's rows")
                        .isEqualTo(portalUsersB);

                // ---- (2) table widget: no other tenant's email is listed
                Map<String, Object> table = widgetData(tokenB, slugB, dashboardId, tableId);
                List<Map<String, Object>> tableRows =
                        (List<Map<String, Object>>) ((Map<String, Object>) table.get("data")).get("records");
                assertThat(tableRows).as("tenant B's portal users are listed").isNotEmpty();
                assertThat(tableRows).extracting(row -> row.get("email"))
                        .as("a table widget on `users` must not list another tenant's emails")
                        .allMatch(email -> !String.valueOf(email).contains("plt260-a-"));

                // ---- (3) report on `users`: same, through ReportExecutionService
                ResponseEntity<Map> report = gatewayClientWithToken(tokenB)
                        .post().uri("/" + slugB + "/api/reports/" + reportId + "/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of())
                        .retrieve().toEntity(Map.class);
                assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
                Map<String, Object> reportAttrs =
                        (Map<String, Object>) ((Map<String, Object>) report.getBody().get("data")).get("attributes");
                List<Map<String, Object>> reportRows =
                        (List<Map<String, Object>>) reportAttrs.get("records");
                assertThat(reportRows).as("tenant B's portal users are reported").isNotEmpty();
                assertThat(reportRows).extracting(row -> row.get("email"))
                        .as("a report on `users` must not list another tenant's emails")
                        .allMatch(email -> !String.valueOf(email).contains("plt260-a-"));
                Map<String, Object> reportMeta = (Map<String, Object>) report.getBody().get("meta");
                assertThat(numberOf(reportMeta.get("totalCount")))
                        .as("the report's totalCount is scoped too")
                        .isEqualTo(portalUsersB);

                // ---- (4) page render: B's own page on the shared slug, never A's
                ResponseEntity<Map> rendered = gatewayClientWithToken(tokenB)
                        .get().uri("/" + slugB + "/api/pages/" + SHARED_PAGE_SLUG + "/render")
                        .retrieve().toEntity(Map.class);
                assertThat(rendered.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(rendered.getBody().get("title"))
                        .as("render must serve the calling tenant's page, not whichever sorts first")
                        .isEqualTo("PLT-260 Tenant B Home");

                // ---- (5) `collections` still shares the platform's system rows with every tenant
                Map<String, Object> collections = gatewayClientWithToken(tokenB)
                        .get().uri("/" + slugB + "/api/collections?page[size]=200")
                        .retrieve().body(Map.class);
                List<Map<String, Object>> collectionRows =
                        (List<Map<String, Object>>) collections.get("data");
                assertThat(collectionRows).extracting(SystemCollectionTenantScopingScenarioTest::nameOf)
                        .as("system collections stay visible to the tenants that use them")
                        .contains("users", "collections");
            } finally {
                deleteRowById(db, "dashboard_component", metricId);
                deleteRowById(db, "dashboard_component", tableId);
                deleteRowById(db, "dashboard", dashboardId);
                deleteRowById(db, "report", reportId);
                deletePublishedPage(db, tenantA);
                deletePublishedPage(db, tenantB);
                deletePortalUsers(db, emailA);
                deletePortalUsers(db, emailB);
            }
        }
    }

    // ------------------------------------------------------------- Helpers

    @SuppressWarnings("unchecked")
    private static String nameOf(Map<String, Object> row) {
        Map<String, Object> attributes = (Map<String, Object>) row.get("attributes");
        return attributes == null ? null : (String) attributes.get("name");
    }

    private static long numberOf(Object value) {
        return value instanceof Number n ? n.longValue() : -1L;
    }

    /** Creates a record through the generic JSON:API route and returns its id (asserts 201). */
    @SuppressWarnings("unchecked")
    private String createRecord(String token, String slug, String collection,
                                Map<String, Object> attributes) {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> widgetData(String token, String slug, String dashboardId,
                                            String componentId) {
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

    /** The shared platform row for a system collection — the same id in every tenant. */
    private String systemCollectionId(Connection db, String name) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM collection WHERE name = ? AND system_collection = TRUE LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Seeds {@code count} PORTAL users whose emails share {@code emailStem} as a prefix. */
    private List<String> seedPortalUsers(Connection db, String tenantId, String emailStem, int count)
            throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = UUID.randomUUID().toString();
            String email = i + "-" + emailStem;
            try (PreparedStatement ps = db.prepareStatement("""
                    INSERT INTO platform_user
                        (id, tenant_id, email, username, first_name, last_name, status, user_type,
                         created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'Harness', 'Portal', 'ACTIVE', 'PORTAL', NOW(), NOW())
                    """)) {
                ps.setString(1, id);
                ps.setString(2, tenantId);
                ps.setString(3, email);
                ps.setString(4, email);
                ps.executeUpdate();
            }
            ids.add(id);
        }
        return ids;
    }

    private long countPortalUsers(Connection db, String tenantId) throws Exception {
        String sql = tenantId == null
                ? "SELECT COUNT(*) FROM platform_user WHERE user_type = 'PORTAL'"
                : "SELECT COUNT(*) FROM platform_user WHERE user_type = 'PORTAL' AND tenant_id = ?";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            if (tenantId != null) {
                ps.setString(1, tenantId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Seeds a published, active page on {@link #SHARED_PAGE_SLUG}. Both tenants get one; tenant
     * A's is created first, so the unscoped {@code ORDER BY created_at, id} lookup returns A's —
     * which is exactly the production symptom.
     */
    private void seedPublishedPage(Connection db, String tenantId, String title) throws Exception {
        deletePublishedPage(db, tenantId);
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO ui_page
                    (id, tenant_id, name, path, slug, title, config, active, published,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, '{"components": []}'::jsonb, TRUE, TRUE, NOW(), NOW())
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, tenantId);
            ps.setString(3, title);
            ps.setString(4, "/" + SHARED_PAGE_SLUG);
            ps.setString(5, SHARED_PAGE_SLUG);
            ps.setString(6, title);
            ps.executeUpdate();
        }
    }

    private void deletePublishedPage(Connection db, String tenantId) throws Exception {
        // Both the slug and the path are unique per tenant, so clear either collision.
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM ui_page WHERE tenant_id = ? AND (slug = ? OR path = ?)")) {
            ps.setString(1, tenantId);
            ps.setString(2, SHARED_PAGE_SLUG);
            ps.setString(3, "/" + SHARED_PAGE_SLUG);
            ps.executeUpdate();
        }
    }

    private void deletePortalUsers(Connection db, String emailStem) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM platform_user WHERE email LIKE ?")) {
            ps.setString(1, "%" + emailStem);
            ps.executeUpdate();
        }
    }

    private void deleteRowById(Connection db, String table, String id) throws Exception {
        if (id == null) {
            return;
        }
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }
}
