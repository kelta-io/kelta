package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KLT-626: {@code POST /api/environments} with {@code cloneMetadata=false} creates an
 * <em>empty</em> sandbox — the platform's system defaults and a working admin, none of the
 * parent's metadata. Without the option the parent's metadata is still cloned.
 *
 * <p>The parent gets one custom collection, one global picklist and one flow. The empty
 * sandbox is ACTIVE at once, says {@code clone_mode=EMPTY}, holds none of the three, serves
 * the system collections to its admin (who signs in with the printed one-time password),
 * and refuses a refresh with 409. A default sandbox becomes ACTIVE with all three cloned
 * and says {@code clone_mode=CLONED}. Isolation: the sandbox tenant id differs from the
 * parent's, and bound to the sandbox (as the NOBYPASSRLS app role) no parent row is
 * readable; the parent's collection is not reachable through the sandbox's API.
 *
 * <p>Shares the harness tenant with every other scenario, so every name is unique and the
 * parent's fixtures and both sandboxes are removed in {@code finally} — a leftover
 * collection would be cloned into later sandboxes (KLT-378, KLT-471).
 */
@DisplayName("Sandbox Empty Create Scenario")
class SandboxEmptyCreateScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("cloneMetadata=false creates a sandbox with system defaults only; the default still clones")
    @SuppressWarnings("unchecked")
    void emptySandboxHasNoParentMetadata() throws Exception {
        String token = auth.loginAsAdmin();
        String parentTenantId = auth.extractTenantId(token);
        String parentSlug = tenants.slugForTenantId(parentTenantId);
        RestClient client = gatewayClientWithToken(token);
        String base = "/" + parentSlug;

        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String collectionName = "sbxempty" + suffix;
        String picklistName = "sbxempty_regions_" + suffix;
        String flowName = "sbxempty flow " + suffix;

        waitForStatus(client, base + "/api/collections", HttpStatus.OK, 20);
        waitForStatus(client, base + "/api/environments", HttpStatus.OK, 20);

        String collectionId = null;
        String picklistId = null;
        String flowId = null;
        List<String> envIds = new ArrayList<>();
        try {
            collectionId = createCollection(client, base, collectionName);
            picklistId = createResource(client, base, "global-picklists", Map.of("name", picklistName));
            flowId = createResource(client, base, "flows", Map.of(
                    "name", flowName,
                    "flowType", "AUTOLAUNCHED",
                    "active", false,
                    "definition", Map.of(
                            "StartAt", "Done",
                            "States", Map.of("Done", Map.of("Type", "Succeed")))));

            // ---- Empty sandbox: ACTIVE at once, nothing of the parent's.
            Map<String, Object> empty = createSandbox(client, base, "empty" + suffix, false);
            String emptyEnvId = (String) empty.get("id");
            envIds.add(emptyEnvId);
            Map<String, Object> emptyAttrs = (Map<String, Object>) empty.get("attributes");
            assertThat(emptyAttrs)
                    .containsEntry("status", "ACTIVE")
                    .containsEntry("clone_mode", "EMPTY");
            String emptySlug = (String) emptyAttrs.get("sandboxSlug");
            String emptyTenantId = (String) emptyAttrs.get("sandbox_tenant_id");
            assertThat(emptyTenantId).as("sandbox is its own tenant").isNotBlank().isNotEqualTo(parentTenantId);

            Map<String, Object> listed = environmentAttributes(client, base, emptyEnvId);
            assertThat(listed).containsEntry("clone_mode", "EMPTY").containsEntry("status", "ACTIVE");

            try (Connection db = openDbConnection()) {
                assertThat(queryString(db, "SELECT config->>'cloneMode' FROM environment WHERE id = ?", emptyEnvId))
                        .isEqualTo("EMPTY");
                assertThat(countByName(db, "collection", emptyTenantId, collectionName)).isZero();
                assertThat(countByName(db, "global_picklist", emptyTenantId, picklistName)).isZero();
                assertThat(countByName(db, "flow", emptyTenantId, flowName)).isZero();
                assertThat(queryInt(db,
                        "SELECT COUNT(*) FROM collection WHERE tenant_id = ? AND system_collection = false",
                        emptyTenantId))
                        .as("no custom collection at all in the empty sandbox").isZero();
            }

            // The admin signs in with the printed one-time password and sees the system collections.
            String sandboxToken = directLogin((String) emptyAttrs.get("adminUsername"),
                    (String) emptyAttrs.get("adminInitialPassword"), emptySlug);
            assertThat(auth.extractTenantId(sandboxToken)).isEqualTo(emptyTenantId);
            RestClient sandboxClient = gatewayClientWithToken(sandboxToken);
            waitForStatus(sandboxClient, "/" + emptySlug + "/api/collections", HttpStatus.OK, 20);
            Map<String, Object> collections = sandboxClient.get()
                    .uri("/" + emptySlug + "/api/collections?page[size]=200")
                    .retrieve().body(Map.class);
            List<String> collectionNames = ((List<Map<String, Object>>) collections.get("data")).stream()
                    .map(row -> (String) ((Map<String, Object>) row.get("attributes")).get("name"))
                    .toList();
            assertThat(collectionNames).contains("users", "collections").doesNotContain(collectionName);

            // The parent's collection is not reachable through the sandbox.
            HttpStatusCode parentCollectionStatus = sandboxClient.get()
                    .uri("/" + emptySlug + "/api/" + collectionName)
                    .retrieve().onStatus(s -> true, (req, resp) -> {})
                    .toBodilessEntity().getStatusCode();
            assertThat(parentCollectionStatus.is2xxSuccessful())
                    .as("parent collection must not resolve in the sandbox (got %s)", parentCollectionStatus)
                    .isFalse();

            // RLS: bound to the sandbox, the app role reads none of the parent's rows.
            for (String table : List.of("collection", "global_picklist", "flow")) {
                assertThat(countVisibleTo(emptyTenantId, table, parentTenantId))
                        .as("no parent %s row is readable from the sandbox", table).isZero();
                assertThat(countVisibleTo(parentTenantId, table, parentTenantId))
                        .as("sanity: the parent reads its own %s rows", table).isPositive();
            }

            // Refresh would clone the parent into it — refused.
            ResponseEntity<Map> refresh = client.post().uri(base + "/api/environments/" + emptyEnvId + "/refresh")
                    .retrieve().onStatus(s -> true, (req, resp) -> {}).toEntity(Map.class);
            assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refresh.getBody().toString()).contains("EMPTY_SANDBOX_REFRESH");

            // ---- Default sandbox: clones all three.
            Map<String, Object> cloned = createSandbox(client, base, "cloned" + suffix, null);
            String clonedEnvId = (String) cloned.get("id");
            envIds.add(clonedEnvId);
            Map<String, Object> clonedAttrs = (Map<String, Object>) cloned.get("attributes");
            assertThat(clonedAttrs).containsEntry("clone_mode", "CLONED");
            String clonedTenantId = (String) clonedAttrs.get("sandbox_tenant_id");
            waitForEnvironmentActive(client, base, clonedEnvId, 240);

            try (Connection db = openDbConnection()) {
                assertThat(countByName(db, "collection", clonedTenantId, collectionName)).isEqualTo(1);
                assertThat(countByName(db, "global_picklist", clonedTenantId, picklistName)).isEqualTo(1);
                assertThat(countByName(db, "flow", clonedTenantId, flowName)).isEqualTo(1);
            }
        } finally {
            for (String envId : envIds) {
                deleteQuietly(client, base + "/api/environments/" + envId);
            }
            if (flowId != null) {
                deleteQuietly(client, base + "/api/flows/" + flowId);
            }
            if (picklistId != null) {
                deleteQuietly(client, base + "/api/global-picklists/" + picklistId);
            }
            if (collectionId != null) {
                deleteQuietly(client, base + "/api/collections/" + collectionId + "?force=true");
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> createSandbox(RestClient client, String base, String name, Boolean cloneMetadata) {
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("name", name);
        attributes.put("type", "SANDBOX");
        if (cloneMetadata != null) {
            attributes.put("cloneMetadata", cloneMetadata);
        }
        ResponseEntity<Map> created = client.post().uri(base + "/api/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "environments", "attributes", attributes)))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return (Map<String, Object>) created.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private String createCollection(RestClient client, String base, String name) {
        String id = createResource(client, base, "collections", Map.of(
                "name", name, "displayName", name, "tenantScoped", true));
        waitForStatus(client, base + "/api/" + name, HttpStatus.OK, 30);
        return id;
    }

    @SuppressWarnings("unchecked")
    private String createResource(RestClient client, String base, String type, Map<String, Object> attributes) {
        ResponseEntity<Map> response = client.post().uri(base + "/api/" + type)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", type, "attributes", attributes)))
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("create %s: %s", type, response.getBody()).isTrue();
        String id = (String) ((Map<String, Object>) response.getBody().get("data")).get("id");
        assertThat(id).isNotBlank();
        return id;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> environmentAttributes(RestClient client, String base, String envId) {
        Map<String, Object> body = client.get().uri(base + "/api/environments/" + envId).retrieve().body(Map.class);
        return (Map<String, Object>) ((Map<String, Object>) body.get("data")).get("attributes");
    }

    @SuppressWarnings("unchecked")
    private String directLogin(String username, String password, String tenantSlug) {
        Map<String, Object> response = authClient().post().uri("/auth/direct-login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", username, "password", password, "tenantSlug", tenantSlug))
                .retrieve().body(Map.class);
        assertThat(response).containsKey("access_token");
        return (String) response.get("access_token");
    }

    /** Polls the environment until ACTIVE; fails fast on FAILED. ~0.5s per attempt. */
    private void waitForEnvironmentActive(RestClient client, String base, String envId, int maxAttempts) {
        String lastStatus = "unknown";
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Map<String, Object> attrs = environmentAttributes(client, base, envId);
                lastStatus = String.valueOf(attrs.get("status"));
                if ("ACTIVE".equals(lastStatus)) {
                    return;
                }
                if ("FAILED".equals(lastStatus)) {
                    throw new AssertionError("Sandbox clone FAILED for environment " + envId
                            + " — config=" + attrs.get("config"));
                }
            } catch (AssertionError e) {
                throw e;
            } catch (RuntimeException ignored) {
                // not ready yet
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Environment " + envId + " did not become ACTIVE (last status: " + lastStatus + ")");
    }

    /**
     * Rows of {@code table} owned by {@code ownerTenantId} that the app role sees when bound to
     * {@code boundTenantId}. System collections are left out: the harness parent is the default
     * tenant, which owns them, and V200's {@code system_rows_read} policy shows them to every tenant.
     */
    private int countVisibleTo(String boundTenantId, String table, String ownerTenantId) throws SQLException {
        try (Connection conn = openAppDbConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL app.current_tenant_id = '" + boundTenantId.replace("'", "''") + "'");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table
                        + " WHERE tenant_id = '" + ownerTenantId.replace("'", "''") + "'"
                        + ("collection".equals(table) ? " AND system_collection = false" : ""))) {
                    rs.next();
                    return rs.getInt(1);
                }
            } finally {
                conn.rollback();
            }
        }
    }

    private int countByName(Connection db, String table, String tenantId, String name) throws SQLException {
        return queryInt(db, "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ? AND name = ?", tenantId, name);
    }

    private void deleteQuietly(RestClient client, String uri) {
        try {
            client.delete().uri(uri).retrieve().onStatus(s -> true, (req, resp) -> {}).toBodilessEntity();
        } catch (RuntimeException ignored) {
            // best-effort cleanup
        }
    }

    private String queryString(Connection db, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private int queryInt(Connection db, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
