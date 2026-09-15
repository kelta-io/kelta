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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for "collections become undeletable once used" (concerns.md). A
 * collection is deleted as a generic record delete against the {@code collection} table;
 * before V181, 14 FKs referenced {@code collection(id)} with no ON DELETE action, so any
 * dependent row — including {@code field_history} rows written automatically on the first
 * record write, and metadata rows (a saved list view) with no delete API — turned the
 * delete into a permanent 409 REFERENCED_RECORD.
 *
 * <p>The first test exercises the real stack (gateway → worker → per-tenant Postgres):
 * create a collection, add a {@code trackHistory} field, write a record (→ a
 * {@code field_history} row), and create a {@code list_view} — two distinct FK blockers with
 * no delete API. Because those children exist, {@code CollectionDeletionGuardHook} blocks an
 * unforced delete (400); the test then deletes with {@code ?force=true} and asserts 204 and
 * that every dependent row cascaded away. This is the DB-constraint regression the "test-gap"
 * lesson demands: Mockito worker tests cannot exercise a real ON DELETE CASCADE.
 *
 * <p>The second test covers {@code record_version} (V182): V181's FK enumeration missed
 * {@code record_version.collection_id} (added later by record versioning #1266), so a
 * collection with collection-level history ({@code trackHistory=true} on the collection)
 * stayed undeletable. It needs its own collection because collection-level history supersedes
 * per-field {@code field_history} ({@code FieldHistoryHook}), so one collection can't produce
 * both rows.
 */
@DisplayName("Collection Deletion Scenario")
class CollectionDeletionScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("a used collection (field_history + list view) deletes end to end, cascading dependents")
    @SuppressWarnings("unchecked")
    void usedCollectionCanBeDeleted() throws Exception {
        String token = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(token);
        String slug = tenants.slugForTenantId(tenantId);
        RestClient client = gatewayClientWithToken(token);

        String collectionName = "deltest";

        // Collections route is live for the tenant.
        waitForStatus(client, "/" + slug + "/api/collections", HttpStatus.OK, 20);

        // 1. Create a user collection.
        Map<String, Object> collectionBody = Map.of("data", Map.of(
                "type", "collections",
                "attributes", Map.of(
                        "name", collectionName,
                        "displayName", "Deletion Test",
                        "tenantScoped", true)));
        ResponseEntity<Map> createdCollection = client.post().uri("/" + slug + "/api/collections")
                .contentType(MediaType.APPLICATION_JSON).body(collectionBody)
                .retrieve().toEntity(Map.class);
        assertThat(createdCollection.getStatusCode().is2xxSuccessful()).isTrue();
        String collectionId = (String) ((Map<String, Object>) createdCollection.getBody().get("data")).get("id");
        assertThat(collectionId).isNotBlank();

        // The dynamic route for the new collection propagates via NATS — wait for it.
        waitForStatus(client, "/" + slug + "/api/" + collectionName, HttpStatus.OK, 30);

        // 2. Add a history-tracked field so a record write produces a field_history row.
        addTrackedField(client, slug, collectionId, "title");
        waitForField(client, slug, collectionId, "title");

        // 3. Write a record — FieldHistoryHook inserts a field_history row (collection_id →
        //    collection(id)). field_history has no delete API, so this is what historically
        //    made the collection permanently undeletable.
        Map<String, Object> recordBody = Map.of("data", Map.of(
                "type", collectionName,
                "attributes", Map.of("title", "keep-me")));
        ResponseEntity<Map> createdRecord = client.post().uri("/" + slug + "/api/" + collectionName)
                .contentType(MediaType.APPLICATION_JSON).body(recordBody)
                .retrieve().toEntity(Map.class);
        assertThat(createdRecord.getStatusCode().is2xxSuccessful()).isTrue();

        // 4. Create a saved list view — a second FK blocker (list_view.collection_id) with
        //    no delete API of its own.
        Map<String, Object> listViewBody = Map.of("data", Map.of(
                "type", "list-views",
                "attributes", Map.of(
                        "collectionId", collectionId,
                        "name", "All " + collectionName,
                        "columns", List.of("title"))));
        ResponseEntity<Map> createdListView = client.post().uri("/" + slug + "/api/list-views")
                .contentType(MediaType.APPLICATION_JSON).body(listViewBody)
                .retrieve().toEntity(Map.class);
        assertThat(createdListView.getStatusCode().is2xxSuccessful()).isTrue();

        // Sanity: both blockers really exist before the delete (else the test proves nothing).
        assertThat(countByCollectionId("field_history", collectionId))
                .as("record write should have produced a field_history row").isPositive();
        assertThat(countByCollectionId("list_view", collectionId))
                .as("a list view should exist for the collection").isPositive();

        // 5a. An unforced delete is blocked by CollectionDeletionGuardHook: the V181 cascade
        //     would destroy these children, so the guard rejects it (400) and destroys nothing.
        HttpStatusCode blockedStatus = client.delete()
                .uri("/" + slug + "/api/collections/" + collectionId)
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toBodilessEntity()
                .getStatusCode();
        assertThat(blockedStatus)
                .as("unforced delete of a used collection should be blocked by the guard")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countById("collection", collectionId))
                .as("blocked delete must not have removed the collection").isPositive();

        // 5b. Delete with ?force=true. Before V181 this returned 409 REFERENCED_RECORD forever;
        //     now the guard lets it through and the child rows cascade away.
        HttpStatusCode deleteStatus = client.delete()
                .uri("/" + slug + "/api/collections/" + collectionId + "?force=true")
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toBodilessEntity()
                .getStatusCode();
        assertThat(deleteStatus)
                .as("forced delete of a used collection should return 204, not 409 REFERENCED_RECORD")
                .isEqualTo(HttpStatus.NO_CONTENT);

        // 6. The collection row and every cascaded dependent are gone.
        assertThat(countById("collection", collectionId)).as("collection row removed").isZero();
        assertThat(countByCollectionId("field_history", collectionId))
                .as("field_history rows cascaded").isZero();
        assertThat(countByCollectionId("list_view", collectionId))
                .as("list_view rows cascaded").isZero();
        assertThat(countByCollectionId("field", collectionId))
                .as("field rows cascaded (existing fk_field_collection)").isZero();
    }

    @Test
    @DisplayName("a used history-tracked collection (record_version) force-deletes end to end, cascading versions")
    @SuppressWarnings("unchecked")
    void versionedCollectionCanBeForceDeleted() throws Exception {
        String token = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(token);
        String slug = tenants.slugForTenantId(tenantId);
        RestClient client = gatewayClientWithToken(token);

        String collectionName = "delvertest";

        waitForStatus(client, "/" + slug + "/api/collections", HttpStatus.OK, 20);

        // 1. Create a collection with collection-level history enabled. RecordVersionHook then
        //    mints a record_version snapshot on every record write (record_version.collection_id
        //    → collection(id)) — the FK V181 missed and V182 gives ON DELETE CASCADE.
        Map<String, Object> collectionBody = Map.of("data", Map.of(
                "type", "collections",
                "attributes", Map.of(
                        "name", collectionName,
                        "displayName", "Versioned Deletion Test",
                        "tenantScoped", true,
                        "trackHistory", true)));
        ResponseEntity<Map> createdCollection = client.post().uri("/" + slug + "/api/collections")
                .contentType(MediaType.APPLICATION_JSON).body(collectionBody)
                .retrieve().toEntity(Map.class);
        assertThat(createdCollection.getStatusCode().is2xxSuccessful()).isTrue();
        String collectionId = (String) ((Map<String, Object>) createdCollection.getBody().get("data")).get("id");
        assertThat(collectionId).isNotBlank();

        waitForStatus(client, "/" + slug + "/api/" + collectionName, HttpStatus.OK, 30);

        // 2. A field to carry a value, then a record write — RecordVersionHook inserts a
        //    record_version row (collection-level history supersedes field_history, so no
        //    field_history row is written here).
        addTrackedField(client, slug, collectionId, "title");
        waitForField(client, slug, collectionId, "title");

        Map<String, Object> recordBody = Map.of("data", Map.of(
                "type", collectionName,
                "attributes", Map.of("title", "keep-me")));
        ResponseEntity<Map> createdRecord = client.post().uri("/" + slug + "/api/" + collectionName)
                .contentType(MediaType.APPLICATION_JSON).body(recordBody)
                .retrieve().toEntity(Map.class);
        assertThat(createdRecord.getStatusCode().is2xxSuccessful()).isTrue();

        // Sanity: the record_version blocker really exists before the delete.
        assertThat(countByCollectionId("record_version", collectionId))
                .as("a record write on a history-tracked collection should produce a record_version row")
                .isPositive();

        // 3. Force-delete (the record_version rows make the guard block an unforced delete).
        HttpStatusCode deleteStatus = client.delete()
                .uri("/" + slug + "/api/collections/" + collectionId + "?force=true")
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toBodilessEntity()
                .getStatusCode();
        assertThat(deleteStatus)
                .as("forced delete of a history-tracked collection should return 204, not 409 REFERENCED_RECORD")
                .isEqualTo(HttpStatus.NO_CONTENT);

        // 4. The collection row and every cascaded record_version are gone.
        assertThat(countById("collection", collectionId)).as("collection row removed").isZero();
        assertThat(countByCollectionId("record_version", collectionId))
                .as("record_version rows cascaded (V182)").isZero();
    }

    /** Adds a STRING field with {@code trackHistory=true} so writes emit field_history rows. */
    private void addTrackedField(RestClient client, String slug, String collectionId, String fieldName) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "fields",
                "attributes", Map.of(
                        "collectionId", collectionId,
                        "name", fieldName,
                        "type", "STRING",
                        "trackHistory", true)));
        ResponseEntity<Map> response = client.post().uri("/" + slug + "/api/fields")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("field '%s' create should succeed", fieldName).isTrue();
    }

    /** Polls the fields list until {@code fieldName} is present (route + registry propagation). */
    @SuppressWarnings("unchecked")
    private void waitForField(RestClient client, String slug, String collectionId, String fieldName) {
        for (int i = 0; i < 30; i++) {
            try {
                ResponseEntity<Map> fields = client.get()
                        .uri("/" + slug + "/api/fields?filter[collectionId][eq]=" + collectionId)
                        .retrieve().toEntity(Map.class);
                List<Map<String, Object>> data = (List<Map<String, Object>>) fields.getBody().get("data");
                boolean present = data != null && data.stream().anyMatch(f -> {
                    Map<String, Object> a = (Map<String, Object>) f.get("attributes");
                    return a != null && fieldName.equals(a.get("name"));
                });
                if (present) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // not ready yet
            }
            sleep();
        }
        throw new AssertionError("Field '" + fieldName + "' expected present but timed out");
    }

    /** Counts rows in a system (public-schema) table by their {@code collection_id} column. */
    private int countByCollectionId(String table, String collectionId) throws Exception {
        return count("SELECT count(*) FROM " + table + " WHERE collection_id = ?", collectionId);
    }

    /** Counts rows in a table by primary key {@code id}. */
    private int countById(String table, String id) throws Exception {
        return count("SELECT count(*) FROM " + table + " WHERE id = ?", id);
    }

    private int count(String sql, String param) throws Exception {
        try (Connection admin = openDbConnection();
             PreparedStatement ps = admin.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
