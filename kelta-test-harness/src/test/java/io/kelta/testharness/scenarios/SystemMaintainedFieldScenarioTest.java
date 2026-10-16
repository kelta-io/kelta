package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * System-maintained fields (KLT-609) through the real stack. A field flagged
 * {@code systemMaintained} is written by a {@code RECORD_TRIGGERED} flow's {@code UPDATE_RECORD}
 * step — record event over NATS → {@code FlowEventListener} → {@code FlowEngine} →
 * {@code DefaultQueryEngine.update} with no caller bound — and that write lands. A direct API
 * PATCH that changes the value is refused with 400 and leaves the flow's value in place; a PATCH
 * re-sending the stored value, or touching only other fields, still succeeds.
 *
 * <p>The collection and the flow get unique names and are deleted in {@code finally}: every
 * scenario shares one tenant.
 */
@DisplayName("System-maintained field scenario")
class SystemMaintainedFieldScenarioTest extends ScenarioBase {

    private static final int FLOW_VALUE = 7;
    private static final long FLOW_TIMEOUT_MS = 30_000;

    @Test
    @DisplayName("a record-triggered flow writes the field; a direct PATCH changing it is a 400")
    @SuppressWarnings("unchecked")
    void flowWritesAndApiCannotChangeSystemMaintainedField() throws Exception {
        String token = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(token);
        String slug = tenants.slugForTenantId(tenantId);
        RestClient admin = gatewayClientWithToken(token);
        waitForStatus(admin, "/" + slug + "/api/collections", HttpStatus.OK, 20);
        waitForStatus(admin, "/" + slug + "/api/flows", HttpStatus.OK, 20);

        String suffix = Long.toHexString(System.nanoTime());
        String collection = "harness_sysmaint_" + suffix;
        String collectionId = null;
        String flowId = null;
        try {
            collectionId = createCollection(admin, slug, collection);
            createField(admin, slug, Map.of("collectionId", collectionId, "name", "title", "type", "STRING"));
            createField(admin, slug, Map.of("collectionId", collectionId, "name", "tally", "type", "INTEGER",
                    "systemMaintained", true));
            waitForStatus(admin, "/" + slug + "/api/" + collection, HttpStatus.OK, 30);

            // ---- The flow: on CREATED, UPDATE_RECORD sets tally on the triggering record.
            flowId = createFlow(admin, slug, "sysmaint-harness-" + suffix, collection);

            // ---- Create records until the flow's write lands. The flow-trigger cache is dropped
            // by a NATS config event, so a record created in the instant before that arrives
            // is not seen by the flow; a fresh record on the next attempt is.
            String recordId = null;
            Object flowWritten = null;
            for (int attempt = 0; attempt < 3 && flowWritten == null; attempt++) {
                recordId = createRecord(admin, slug, collection, "inspection-" + attempt);
                flowWritten = pollTally(admin, slug, collection, recordId);
            }
            assertThat(flowWritten)
                    .as("the RECORD_TRIGGERED flow's UPDATE_RECORD step wrote the system-maintained field")
                    .isNotNull();
            assertThat(((Number) flowWritten).intValue()).isEqualTo(FLOW_VALUE);

            // ---- A direct PATCH that changes it is refused, naming the collection and field.
            ResponseEntity<String> changed = patch(admin, slug, collection, recordId,
                    Map.of("tally", FLOW_VALUE + 1));
            assertThat(changed.getStatusCode()).as("PATCH changing tally: %s", changed.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(changed.getBody()).contains("system-maintained").contains("tally").contains(collection);
            assertThat(((Number) readTally(admin, slug, collection, recordId)).intValue())
                    .as("the refused PATCH left the flow's value in place").isEqualTo(FLOW_VALUE);

            // ---- Re-sending the stored value, or not touching it, is accepted.
            ResponseEntity<String> unchanged = patch(admin, slug, collection, recordId,
                    Map.of("title", "renamed", "tally", FLOW_VALUE));
            assertThat(unchanged.getStatusCode()).as("PATCH with the unchanged value: %s", unchanged.getBody())
                    .isEqualTo(HttpStatus.OK);
            ResponseEntity<String> other = patch(admin, slug, collection, recordId, Map.of("title", "again"));
            assertThat(other.getStatusCode()).as("PATCH of another field: %s", other.getBody())
                    .isEqualTo(HttpStatus.OK);
        } finally {
            if (flowId != null) {
                admin.delete().uri("/" + slug + "/api/flows/" + flowId)
                        .retrieve().onStatus(st -> true, (req, resp) -> {}).toBodilessEntity();
            }
            if (collectionId != null) {
                admin.delete().uri("/" + slug + "/api/collections/" + collectionId + "?force=true")
                        .retrieve().onStatus(st -> true, (req, resp) -> {}).toBodilessEntity();
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

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
    private String createFlow(RestClient admin, String slug, String name, String collection) {
        Map<String, Object> definition = Map.of(
                "Comment", "Harness system-maintained scenario",
                "StartAt", "SetTally",
                "States", Map.of(
                        "SetTally", Map.of(
                                "Type", "Task",
                                "Resource", "UPDATE_RECORD",
                                "Parameters", Map.of(
                                        "targetCollectionName", collection,
                                        "recordId", "${$.record.id}",
                                        "updates", List.of(Map.of("field", "tally", "value", FLOW_VALUE))),
                                "End", true)));
        ResponseEntity<Map> created = admin.post().uri("/" + slug + "/api/flows")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "flows", "attributes", Map.of(
                        "name", name,
                        "flowType", "RECORD_TRIGGERED",
                        "active", true,
                        "triggerConfig", Map.of("collection", collection, "events", List.of("CREATED")),
                        "definition", definition))))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create flow: %s", created.getBody()).isTrue();
        return (String) ((Map<String, Object>) created.getBody().get("data")).get("id");
    }

    @SuppressWarnings("unchecked")
    private String createRecord(RestClient admin, String slug, String collection, String title) {
        ResponseEntity<Map> created = admin.post().uri("/" + slug + "/api/" + collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", collection, "attributes", Map.of("title", title))))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create record: %s", created.getBody()).isTrue();
        return (String) ((Map<String, Object>) created.getBody().get("data")).get("id");
    }

    private Object pollTally(RestClient admin, String slug, String collection, String recordId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + FLOW_TIMEOUT_MS / 3;
        while (System.currentTimeMillis() < deadline) {
            Object tally = readTally(admin, slug, collection, recordId);
            if (tally != null) {
                return tally;
            }
            Thread.sleep(500);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object readTally(RestClient admin, String slug, String collection, String recordId) {
        Map<String, Object> body = admin.get().uri("/" + slug + "/api/" + collection + "/" + recordId)
                .retrieve().body(Map.class);
        Map<String, Object> attributes = (Map<String, Object>) ((Map<String, Object>) body.get("data"))
                .get("attributes");
        return attributes.get("tally");
    }

    private ResponseEntity<String> patch(RestClient admin, String slug, String collection, String recordId,
                                         Map<String, Object> attributes) {
        return admin.patch().uri("/" + slug + "/api/" + collection + "/" + recordId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", collection, "id", recordId, "attributes", attributes)))
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toEntity(String.class);
    }
}
