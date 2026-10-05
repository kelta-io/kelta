package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Analytics authorization (app-surfacing slice 1) through the real stack
 * (gateway → worker → Postgres + RLS): the {@code VIEW_ANALYTICS} gate on
 * {@code ReportExecutionController} / {@code DashboardDataController}.
 *
 * <p>Covers what Mockito worker tests can't (the DB-constraint-test-gap lesson):
 * <ul>
 *   <li>the {@code VIEW_ANALYTICS} rows really exist per profile — seeded by
 *       {@code TenantProvisioningHook} for runtime-provisioned tenants (this harness tenant)
 *       and by the V162 backfill for pre-existing ones;</li>
 *   <li>a Standard User (granted VIEW_ANALYTICS, no MANAGE_REPORTS) can execute a real report
 *       end-to-end;</li>
 *   <li>an API_ACCESS-only custom profile (no VIEW_ANALYTICS row at all) is refused with 403 by
 *       the worker gate — harness Cerbos is dev allow-all, so what is proven is exactly the
 *       in-controller enforcement;</li>
 *   <li>MANAGE_REPORTS alone passes the gate (the admin authoring path keeps working).</li>
 * </ul>
 */
@DisplayName("Analytics Authorization Scenario")
class AnalyticsAuthzScenarioTest extends ScenarioBase {

    /** BCrypt hash of "password", for this scenario's own seeded users. */
    private static final String PASSWORD_HASH =
            "$2a$10$zAQaSHX1XSR1bwUL3pz9EOzecplsxInVizZc9HwLf7xPluSiE1EP6";

    @Test
    @DisplayName("VIEW_ANALYTICS gates report execution; MANAGE_REPORTS passes; no-permission profile is 403")
    @SuppressWarnings("unchecked")
    void viewAnalyticsGatesReportExecution() throws Exception {
        // The ecommerce fixture tenant owns the seeded `customers` collection the probe
        // report targets (Default Org does not have it) — same idiom as RecordMergeScenarioTest.
        String slug = TenantFixture.ECOMMERCE_SLUG;
        String adminToken = auth.loginAsAdmin(slug);
        String tenantId = auth.extractTenantId(adminToken);
        // Alphabetically the FIRST ecommerce-tenant scenario — it absorbs the whole
        // runtime-provisioning warm-up (slug cache + dynamic route propagation), observed
        // just over 30s in CI. Budget 2 minutes; later scenarios' waits then pass instantly.
        waitForStatus(gatewayClientWithToken(adminToken), "/" + slug + "/api/customers", HttpStatus.OK, 240);
        waitForStatus(gatewayClientWithToken(adminToken), "/" + slug + "/api/reports", HttpStatus.OK, 60);

        String suffix = Long.toHexString(System.nanoTime());
        String analystEmail = "analyst-" + suffix + "@example.com";
        String deniedEmail = "denied-" + suffix + "@example.com";

        try (Connection db = openDbConnection()) {
            String standardProfileId = profileIdByName(db, tenantId, "Standard User");
            assertThat(standardProfileId).as("tenant has the seeded Standard User profile").isNotNull();

            // Every seeded profile carries a VIEW_ANALYTICS row (hook for runtime tenants,
            // V162 backfill for pre-existing ones) — and Standard User's is granted.
            assertThat(countMissingViewAnalyticsRows(db, tenantId))
                    .as("every profile has a VIEW_ANALYTICS row").isZero();
            assertThat(isGranted(db, standardProfileId, "VIEW_ANALYTICS"))
                    .as("Standard User is granted VIEW_ANALYTICS").isTrue();
            assertThat(isGranted(db, standardProfileId, "MANAGE_REPORTS"))
                    .as("Standard User does NOT hold MANAGE_REPORTS (gate must pass on VIEW_ANALYTICS alone)")
                    .isFalse();

            String analystId = seedActiveUser(db, tenantId, analystEmail, standardProfileId);
            String deniedProfileId = null;
            String deniedId = null;
            String reportId = null;
            try {
                // API_ACCESS-only custom profile: no VIEW_ANALYTICS row exists for it at all,
                // mirroring a profile created after the V162 backfill.
                deniedProfileId = seedApiAccessOnlyProfile(db, tenantId, "No Analytics " + suffix);
                deniedId = seedActiveUser(db, tenantId, deniedEmail, deniedProfileId);

                // ---- admin (MANAGE_REPORTS holder) authors a real report -> proves the fallback
                String customersCollectionId = collectionIdByName(db, tenantId, "customers");
                assertThat(customersCollectionId).as("harness ecommerce collection exists").isNotNull();
                // columns is a JSON-typed field — the write path validates for real JSON
                // (a JSON-encoded string is rejected with "Invalid type, expected JSON").
                reportId = createReport(adminToken, slug, Map.of(
                        "name", "Authz Probe " + suffix,
                        "reportType", "TABULAR",
                        "primaryCollectionId", customersCollectionId,
                        "columns", java.util.List.of(Map.of(
                                "fieldName", "id", "label", "Id", "type", "string"))));

                ResponseEntity<Map> adminRun = executeReport(adminToken, slug, reportId);
                assertThat(adminRun.getStatusCode())
                        .as("MANAGE_REPORTS passes the gate").isEqualTo(HttpStatus.OK);

                // ---- Standard User (VIEW_ANALYTICS only) -> 200 end-to-end
                String analystToken = directLogin(analystEmail, slug);
                ResponseEntity<Map> analystRun = executeReport(analystToken, slug, reportId);
                assertThat(analystRun.getStatusCode())
                        .as("VIEW_ANALYTICS alone passes the gate").isEqualTo(HttpStatus.OK);

                // ---- API_ACCESS-only profile -> 403 from the worker gate, for reports AND dashboards
                String deniedToken = directLogin(deniedEmail, slug);
                String finalReportId = reportId;
                HttpClientErrorException reportDenied = catchThrowableOfType(
                        () -> executeReport(deniedToken, slug, finalReportId),
                        HttpClientErrorException.class);
                assertThat(reportDenied).as("report execute is refused").isNotNull();
                assertThat(reportDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

                HttpClientErrorException exportDenied = catchThrowableOfType(
                        () -> gatewayClientWithToken(deniedToken)
                                .get().uri("/" + slug + "/api/reports/" + finalReportId + "/export?format=csv")
                                .retrieve().toEntity(String.class),
                        HttpClientErrorException.class);
                assertThat(exportDenied).as("report export is refused").isNotNull();
                assertThat(exportDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

                HttpClientErrorException dashboardDenied = catchThrowableOfType(
                        () -> gatewayClientWithToken(deniedToken)
                                .post().uri("/" + slug + "/api/dashboards/" + UUID.randomUUID() + "/data")
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of())
                                .retrieve().toEntity(Map.class),
                        HttpClientErrorException.class);
                assertThat(dashboardDenied).as("dashboard data is refused").isNotNull();
                assertThat(dashboardDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

                // Granted caller on a random dashboard id gets 404 — proof the gate (not the
                // lookup) is what refused the denied caller above.
                HttpClientErrorException analystDashboard = catchThrowableOfType(
                        () -> gatewayClientWithToken(analystToken)
                                .post().uri("/" + slug + "/api/dashboards/" + UUID.randomUUID() + "/data")
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of())
                                .retrieve().toEntity(Map.class),
                        HttpClientErrorException.class);
                assertThat(analystDashboard).isNotNull();
                assertThat(analystDashboard.getStatusCode())
                        .as("granted caller passes the gate and reaches the lookup")
                        .isEqualTo(HttpStatus.NOT_FOUND);
            } finally {
                if (reportId != null) {
                    deleteRowById(db, "report", reportId);
                }
                deleteUserByEmail(db, tenantId, deniedEmail);
                deleteUserByEmail(db, tenantId, analystEmail);
                if (deniedProfileId != null) {
                    deleteProfileById(db, deniedProfileId);
                }
            }
        }
    }

    // ------------------------------------------------------------- Helpers

    private ResponseEntity<Map> executeReport(String token, String slug, String reportId) {
        return gatewayClientWithToken(token)
                .post().uri("/" + slug + "/api/reports/" + reportId + "/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve().toEntity(Map.class);
    }

    /** Creates a report via the generic JSON:API route and returns its id (asserts 201). */
    @SuppressWarnings("unchecked")
    private String createReport(String adminToken, String slug, Map<String, Object> attributes) {
        ResponseEntity<Map> created = gatewayClientWithToken(adminToken)
                .post().uri("/" + slug + "/api/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "reports", "attributes", attributes)))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode()).as("report create").isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = (Map<String, Object>) created.getBody().get("data");
        String id = (String) data.get("id");
        assertThat(id).as("created report has an id").isNotBlank();
        return id;
    }

    /**
     * Seeds a custom (non-system) profile holding ONLY a granted API_ACCESS row — deliberately
     * no VIEW_ANALYTICS row, mirroring a profile created after the V162 backfill ran.
     */
    private String seedApiAccessOnlyProfile(Connection db, String tenantId, String name)
            throws Exception {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO profile (id, tenant_id, name, description, is_system, created_at, updated_at)
                VALUES (?, ?, ?, 'Harness analytics-deny profile', FALSE, NOW(), NOW())
                """)) {
            ps.setString(1, id);
            ps.setString(2, tenantId);
            ps.setString(3, name);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO profile_system_permission (id, tenant_id, profile_id, permission_name, granted)
                VALUES (?, ?, ?, 'API_ACCESS', TRUE)
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, tenantId);
            ps.setString(3, id);
            ps.executeUpdate();
        }
        return id;
    }

    private int countMissingViewAnalyticsRows(Connection db, String tenantId) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT COUNT(*) FROM profile p
                WHERE p.tenant_id = ?
                  AND p.is_system = TRUE
                  AND NOT EXISTS (SELECT 1 FROM profile_system_permission x
                                  WHERE x.profile_id = p.id
                                    AND x.permission_name = 'VIEW_ANALYTICS')
                """)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private boolean isGranted(Connection db, String profileId, String permission) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT granted FROM profile_system_permission
                WHERE profile_id = ? AND permission_name = ?
                """)) {
            ps.setString(1, profileId);
            ps.setString(2, permission);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String directLogin(String email, String tenantSlug) {
        Map<String, Object> response = authClient().post()
                .uri("/auth/direct-login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "username", email,
                        "password", "password",
                        "tenantSlug", tenantSlug))
                .retrieve()
                .body(Map.class);
        assertThat(response).as("direct login for " + email).isNotNull();
        assertThat(response).containsKey("access_token");
        return (String) response.get("access_token");
    }

    /** Seeds an ACTIVE platform_user + user_credential so the user can direct-login. */
    private String seedActiveUser(Connection db, String tenantId, String email, String profileId)
            throws Exception {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO platform_user
                    (id, tenant_id, email, username, first_name, last_name, status, profile_id,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Harness', 'User', 'ACTIVE', ?, NOW(), NOW())
                """)) {
            ps.setString(1, id);
            ps.setString(2, tenantId);
            ps.setString(3, email);
            ps.setString(4, email);
            ps.setString(5, profileId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO user_credential (id, user_id, password_hash, force_change_on_login, created_at)
                VALUES (?, ?, ?, FALSE, NOW())
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, id);
            ps.setString(3, PASSWORD_HASH);
            ps.executeUpdate();
        }
        return id;
    }

    private void deleteUserByEmail(Connection db, String tenantId, String email) throws Exception {
        String id = null;
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM platform_user WHERE tenant_id = ? AND email = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    id = rs.getString(1);
                }
            }
        }
        if (id == null) {
            return;
        }
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM login_history WHERE user_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM platform_user WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private void deleteProfileById(Connection db, String profileId) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM profile_system_permission WHERE profile_id = ?")) {
            ps.setString(1, profileId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM profile WHERE id = ?")) {
            ps.setString(1, profileId);
            ps.executeUpdate();
        }
    }

    private String profileIdByName(Connection db, String tenantId, String name) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM profile WHERE tenant_id = ? AND name = ? LIMIT 1")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String collectionIdByName(Connection db, String tenantId, String name) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM collection WHERE tenant_id = ? AND name = ? LIMIT 1")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void deleteRowById(Connection db, String table, String id) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "DELETE FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }
}
