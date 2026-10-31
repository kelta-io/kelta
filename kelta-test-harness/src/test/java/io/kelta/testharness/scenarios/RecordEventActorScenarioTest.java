package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who a record-triggered flow starts as, through the real stack (gateway identity stamping →
 * worker {@code CallerContext} → {@code DefaultQueryEngine} record event over NATS →
 * {@code FlowEventListener} → {@code FlowActorResolver} → {@code flow_execution.started_by}).
 *
 * <p>User A creates a record; user B updates it, then deletes it. The run each write starts must
 * name the user who made that write — A for the create, B for the update and the delete. Before
 * the fix the event named the record's {@code createdBy}, so every run was A's, and A became the
 * initiator external modules act for ({@code act}) and the user who may read the run's status.
 *
 * <p>The collection, the flows and user B get unique names and are deleted in {@code finally}:
 * every scenario shares one tenant.
 */
@DisplayName("Record event actor scenario")
class RecordEventActorScenarioTest extends ScenarioBase {

    /** BCrypt hash of "password", for this scenario's own seeded user. */
    private static final String PASSWORD_HASH =
            "$2a$10$zAQaSHX1XSR1bwUL3pz9EOzecplsxInVizZc9HwLf7xPluSiE1EP6";
    private static final long RUN_TIMEOUT_MS = 30_000;

    @Test
    @DisplayName("an update or delete by a second user starts the record-triggered run as that user")
    void runStartsAsTheUserWhoWrote() throws Exception {
        String tokenA = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(tokenA);
        String slug = tenants.slugForTenantId(tenantId);
        RestClient userA = gatewayClientWithToken(tokenA);
        waitForStatus(userA, "/" + slug + "/api/collections", HttpStatus.OK, 20);
        waitForStatus(userA, "/" + slug + "/api/flows", HttpStatus.OK, 20);
        String userAId = identity(userA, slug);

        String suffix = Long.toHexString(System.nanoTime());
        String collection = "harness_actor_" + suffix;
        String emailB = "actor-b-" + suffix + "@example.com";
        String collectionId = null;
        List<String> flowIds = new ArrayList<>();

        try (Connection db = openDbConnection()) {
            try {
                // ---- user B: same profile as A, so the only difference is who they are.
                String userBId = seedActiveUser(db, tenantId, emailB, profileIdOf(db, userAId));
                RestClient userB = gatewayClientWithToken(directLogin(emailB, slug));

                collectionId = createCollection(userA, slug, collection);
                createField(userA, slug, Map.of("collectionId", collectionId, "name", "title", "type", "STRING"));
                waitForStatus(userA, "/" + slug + "/api/" + collection, HttpStatus.OK, 30);

                // ---- One flow per change type. CREATED last: once it fires, the tenant's trigger
                // cache was loaded after every flow existed.
                String onUpdated = createFlow(userA, slug, "actor-updated-" + suffix, collection, "UPDATED");
                flowIds.add(onUpdated);
                String onDeleted = createFlow(userA, slug, "actor-deleted-" + suffix, collection, "DELETED");
                flowIds.add(onDeleted);
                String onCreated = createFlow(userA, slug, "actor-created-" + suffix, collection, "CREATED");
                flowIds.add(onCreated);

                // ---- A creates records until the CREATED flow sees one (the trigger cache is
                // dropped by a NATS config event, which can trail the flow's creation).
                String recordId = null;
                String createdRunBy = null;
                for (int attempt = 0; attempt < 3 && createdRunBy == null; attempt++) {
                    recordId = createRecord(userA, slug, collection, "record-" + attempt);
                    createdRunBy = pollStartedBy(db, onCreated, recordId, RUN_TIMEOUT_MS / 3);
                }
                assertThat(createdRunBy).as("A's create starts the CREATED run as A").isEqualTo(userAId);

                // ---- B updates A's record.
                ResponseEntity<String> patched = userB.patch().uri("/" + slug + "/api/" + collection + "/" + recordId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("data", Map.of("type", collection, "id", recordId,
                                "attributes", Map.of("title", "renamed by B"))))
                        .retrieve().onStatus(s -> true, (req, resp) -> {}).toEntity(String.class);
                assertThat(patched.getStatusCode()).as("B's PATCH: %s", patched.getBody()).isEqualTo(HttpStatus.OK);
                assertThat(pollStartedBy(db, onUpdated, recordId, RUN_TIMEOUT_MS))
                        .as("B's update starts the UPDATED run as B, not the record's creator A")
                        .isEqualTo(userBId);

                // ---- B deletes A's record.
                ResponseEntity<Void> deleted = userB.delete().uri("/" + slug + "/api/" + collection + "/" + recordId)
                        .retrieve().onStatus(s -> true, (req, resp) -> {}).toBodilessEntity();
                assertThat(deleted.getStatusCode()).as("B's DELETE").isEqualTo(HttpStatus.NO_CONTENT);
                assertThat(pollStartedBy(db, onDeleted, recordId, RUN_TIMEOUT_MS))
                        .as("B's delete starts the DELETED run as B, not the record's creator A")
                        .isEqualTo(userBId);
            } finally {
                for (String flowId : flowIds) {
                    userA.delete().uri("/" + slug + "/api/flows/" + flowId)
                            .retrieve().onStatus(st -> true, (req, resp) -> {}).toBodilessEntity();
                }
                if (collectionId != null) {
                    userA.delete().uri("/" + slug + "/api/collections/" + collectionId + "?force=true")
                            .retrieve().onStatus(st -> true, (req, resp) -> {}).toBodilessEntity();
                }
                deleteUserByEmail(db, tenantId, emailB);
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    /** The {@code started_by} of the flow's run for the record, once it exists; null on timeout. */
    private String pollStartedBy(Connection db, String flowId, String recordId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (PreparedStatement ps = db.prepareStatement(
                    "SELECT started_by FROM flow_execution WHERE flow_id = ? AND trigger_record_id = ?")) {
                ps.setString(1, flowId);
                ps.setString(2, recordId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String identity(RestClient client, String slug) {
        Map<String, Object> body = client.get().uri("/" + slug + "/api/me/identity").retrieve().body(Map.class);
        assertThat(body).as("identity").isNotNull();
        return (String) body.get("userId");
    }

    private String profileIdOf(Connection db, String userId) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("SELECT profile_id FROM platform_user WHERE id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("platform_user %s exists", userId).isTrue();
                return rs.getString(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String createCollection(RestClient admin, String slug, String name) {
        ResponseEntity<Map> created = admin.post().uri("/" + slug + "/api/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "collections",
                        "attributes", Map.of("name", name, "displayName", name, "tenantScoped", true))))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create collection %s: %s", name, created.getBody())
                .isTrue();
        return (String) ((Map<String, Object>) created.getBody().get("data")).get("id");
    }

    private void createField(RestClient admin, String slug, Map<String, Object> attributes) {
        ResponseEntity<Map> created = admin.post().uri("/" + slug + "/api/fields")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "fields", "attributes", attributes)))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create field %s: %s", attributes, created.getBody())
                .isTrue();
    }

    @SuppressWarnings("unchecked")
    private String createFlow(RestClient admin, String slug, String name, String collection, String event) {
        Map<String, Object> definition = Map.of(
                "Comment", "Harness record event actor scenario",
                "StartAt", "Noted",
                "States", Map.of("Noted", Map.of("Type", "Pass", "End", true)));
        ResponseEntity<Map> created = admin.post().uri("/" + slug + "/api/flows")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "flows", "attributes", Map.of(
                        "name", name,
                        "flowType", "RECORD_TRIGGERED",
                        "active", true,
                        "triggerConfig", Map.of("collection", collection, "events", List.of(event)),
                        "definition", definition))))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create flow %s: %s", name, created.getBody())
                .isTrue();
        return (String) ((Map<String, Object>) created.getBody().get("data")).get("id");
    }

    @SuppressWarnings("unchecked")
    private String createRecord(RestClient client, String slug, String collection, String title) {
        ResponseEntity<Map> created = client.post().uri("/" + slug + "/api/" + collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", collection, "attributes", Map.of("title", title))))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create record: %s", created.getBody()).isTrue();
        return (String) ((Map<String, Object>) created.getBody().get("data")).get("id");
    }

    @SuppressWarnings("unchecked")
    private String directLogin(String email, String tenantSlug) {
        Map<String, Object> response = authClient().post()
                .uri("/auth/direct-login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", email, "password", "password", "tenantSlug", tenantSlug))
                .retrieve()
                .body(Map.class);
        assertThat(response).as("direct login for " + email).isNotNull().containsKey("access_token");
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
                VALUES (?, ?, ?, ?, 'Harness', 'Actor', 'ACTIVE', ?, NOW(), NOW())
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
        for (String sql : List.of(
                "DELETE FROM login_history WHERE user_id = ?",
                "DELETE FROM user_credential WHERE user_id = ?",
                "DELETE FROM platform_user WHERE id = ?")) {
            try (PreparedStatement ps = db.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        }
    }
}
