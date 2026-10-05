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
 * Approval actor identity (app-surfacing slice 2 hardening) through the real stack
 * (gateway header stamping → worker {@code UserIdResolver} → Postgres + RLS).
 *
 * <p>Covers what Mockito worker tests can't (the DB-constraint-test-gap lesson):
 * <ul>
 *   <li>the gateway stamps {@code X-User-Id} with the caller's <em>email</em>, and the
 *       worker's {@code UserIdResolver} translation to the {@code platform_user} UUID is
 *       what makes {@code assigned_to}/{@code submitted_by} comparisons work at all —
 *       proven end-to-end by a real submit + approve;</li>
 *   <li>a non-assignee cannot act on a step, and posting a body {@code userId} with the
 *       assignee's UUID no longer impersonates them (the spoof path is dead);</li>
 *   <li>{@code GET /api/me/identity} returns the caller's canonical UUID (the FE inbox
 *       filters depend on it — the JWT {@code sub} is not reliable client-side).</li>
 * </ul>
 */
@DisplayName("Approval Actor Identity Scenario")
class ApprovalActorScenarioTest extends ScenarioBase {

    /** BCrypt hash of "password", for this scenario's own seeded users. */
    private static final String PASSWORD_HASH =
            "$2a$10$zAQaSHX1XSR1bwUL3pz9EOzecplsxInVizZc9HwLf7xPluSiE1EP6";

    @Test
    @DisplayName("only the assigned approver can act; body-supplied identity is ignored")
    @SuppressWarnings("unchecked")
    void onlyAssignedApproverCanAct() throws Exception {
        // The ecommerce fixture tenant owns the seeded `customers` collection the probe
        // record lives in (Default Org does not have it) — same idiom as RecordMergeScenarioTest.
        String slug = TenantFixture.ECOMMERCE_SLUG;
        String adminToken = auth.loginAsAdmin(slug);
        String tenantId = auth.extractTenantId(adminToken);
        waitForStatus(gatewayClientWithToken(adminToken), "/" + slug + "/api/customers",
                HttpStatus.OK, 60);
        waitForStatus(gatewayClientWithToken(adminToken), "/" + slug + "/api/approval-processes",
                HttpStatus.OK, 60);

        String suffix = Long.toHexString(System.nanoTime());
        String approverEmail = "approver-" + suffix + "@example.com";
        String submitterEmail = "submitter-" + suffix + "@example.com";

        try (Connection db = openDbConnection()) {
            String standardProfileId = profileIdByName(db, tenantId, "Standard User");
            assertThat(standardProfileId).isNotNull();
            String customersCollectionId = collectionIdByName(db, tenantId, "customers");
            assertThat(customersCollectionId).as("harness ecommerce collection exists").isNotNull();

            String approverId = seedActiveUser(db, tenantId, approverEmail, standardProfileId);
            String submitterId = seedActiveUser(db, tenantId, submitterEmail, standardProfileId);
            String processId = null;
            String customerId = null;
            try {
                // ---- admin authors process + USER step assigned to the approver
                processId = createResource(adminToken, slug, "approval-processes", Map.of(
                        "collectionId", customersCollectionId,
                        "name", "Actor Probe " + suffix,
                        "active", true,
                        "recordEditability", "UNLOCKED",
                        "allowRecall", true));
                createResource(adminToken, slug, "approval-steps", Map.of(
                        "approvalProcessId", processId,
                        "stepNumber", 1,
                        "name", "Step 1",
                        "approverType", "USER",
                        "approverId", approverId));
                customerId = createResource(adminToken, slug, "customers", Map.of(
                        "email", "probe-" + suffix + "@example.com",
                        "first_name", "Probe",
                        "last_name", "Record"));

                String approverToken = directLogin(approverEmail, slug);
                String submitterToken = directLogin(submitterEmail, slug);

                // ---- /api/me/identity returns the canonical UUID (FE inbox depends on it)
                ResponseEntity<Map> identity = gatewayClientWithToken(approverToken)
                        .get().uri("/" + slug + "/api/me/identity")
                        .retrieve().toEntity(Map.class);
                assertThat(identity.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(identity.getBody().get("userId"))
                        .as("identity endpoint resolves email -> platform_user UUID")
                        .isEqualTo(approverId);

                // ---- submitter submits; submitted_by must be their UUID (header email resolved)
                ResponseEntity<Map> submitted = gatewayClientWithToken(submitterToken)
                        .post().uri("/" + slug + "/api/approvals/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("collectionId", customersCollectionId,
                                "recordId", customerId,
                                "processId", processId))
                        .retrieve().toEntity(Map.class);
                assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
                String instanceId = (String) submitted.getBody().get("instanceId");
                assertThat(instanceId).isNotBlank();
                assertThat(scalar(db,
                        "SELECT submitted_by FROM approval_instance WHERE id = ?", instanceId))
                        .as("submitted_by is the submitter's UUID, not their email")
                        .isEqualTo(submitterId);

                // ---- non-assignee cannot act (service finds no pending step for them)
                String finalInstance = instanceId;
                HttpClientErrorException bystanderDenied = catchThrowableOfType(
                        () -> approve(submitterToken, slug, finalInstance, Map.of()),
                        HttpClientErrorException.class);
                assertThat(bystanderDenied).isNotNull();
                assertThat(bystanderDenied.getStatusCode().is4xxClientError()).isTrue();
                assertThat(stepStatus(db, instanceId)).isEqualTo("PENDING");

                // ---- body userId spoofing the assignee's UUID is ignored (still refused)
                HttpClientErrorException spoofDenied = catchThrowableOfType(
                        () -> approve(submitterToken, slug, finalInstance,
                                Map.of("userId", approverId)),
                        HttpClientErrorException.class);
                assertThat(spoofDenied).as("body identity no longer impersonates").isNotNull();
                assertThat(spoofDenied.getStatusCode().is4xxClientError()).isTrue();
                assertThat(stepStatus(db, instanceId)).isEqualTo("PENDING");

                // ---- the real assignee approves (header email -> UUID -> assigned_to match)
                ResponseEntity<Map> approved = approve(approverToken, slug, instanceId,
                        Map.of("comments", "looks good"));
                assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(stepStatus(db, instanceId)).isEqualTo("APPROVED");
                assertThat(scalar(db,
                        "SELECT status FROM approval_instance WHERE id = ?", instanceId))
                        .isEqualTo("APPROVED");
            } finally {
                if (processId != null) {
                    // System-table FKs are plain (no ON DELETE CASCADE) — children first.
                    deleteByColumn(db,
                            "DELETE FROM approval_step_instance WHERE approval_instance_id IN "
                                    + "(SELECT id FROM approval_instance WHERE approval_process_id = ?)",
                            processId);
                    deleteByColumn(db,
                            "DELETE FROM approval_instance WHERE approval_process_id = ?", processId);
                    deleteByColumn(db,
                            "DELETE FROM approval_step WHERE approval_process_id = ?", processId);
                    deleteRowById(db, "approval_process", processId);
                }
                if (customerId != null) {
                    // User-collection tables live in the tenant's own schema (not public),
                    // so the raw harness connection can't reach them — delete via the API.
                    try {
                        gatewayClientWithToken(adminToken)
                                .delete().uri("/" + slug + "/api/customers/" + customerId)
                                .retrieve().toBodilessEntity();
                    } catch (Exception cleanupFailure) {
                        // Best-effort cleanup; the record is suffix-unique per run.
                    }
                }
                deleteUserByEmail(db, tenantId, approverEmail);
                deleteUserByEmail(db, tenantId, submitterEmail);
            }
        }
    }

    // ------------------------------------------------------------- Helpers

    private ResponseEntity<Map> approve(String token, String slug, String instanceId,
                                        Map<String, Object> body) {
        return gatewayClientWithToken(token)
                .post().uri("/" + slug + "/api/approvals/" + instanceId + "/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private String createResource(String token, String slug, String type,
                                  Map<String, Object> attributes) {
        ResponseEntity<Map> created = gatewayClientWithToken(token)
                .post().uri("/" + slug + "/api/" + type)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", type, "attributes", attributes)))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode()).as(type + " create").isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = (Map<String, Object>) created.getBody().get("data");
        String id = (String) data.get("id");
        assertThat(id).as("created " + type + " has an id").isNotBlank();
        return id;
    }

    private String stepStatus(Connection db, String instanceId) throws Exception {
        return scalar(db,
                "SELECT status FROM approval_step_instance WHERE approval_instance_id = ?",
                instanceId);
    }

    private String scalar(Connection db, String sql, String param) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
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
        String id;
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM platform_user WHERE tenant_id = ? AND email = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                id = rs.next() ? rs.getString(1) : null;
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

    private void deleteByColumn(Connection db, String sql, String param) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.executeUpdate();
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
