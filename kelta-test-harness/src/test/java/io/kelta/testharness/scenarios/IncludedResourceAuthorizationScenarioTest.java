package io.kelta.testharness.scenarios;

import io.kelta.testharness.KeltaStack;
import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.AuthFixture;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.images.builder.Transferable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Included resources ({@code ?include=}) get the same record read authorization as a direct read
 * of the included collection: {@code CerbosRecordAuthorizationAdvice} keeps an {@code included[]}
 * resource only when the caller could read that record through {@code GET /api/<type>/<id>}, and a
 * primary record the caller may not read brings no included resources with it.
 *
 * <p>The harness Cerbos is dev allow-all, so this scenario adds a <em>tenant-scoped</em>
 * {@code record} policy to the running PDP (its disk store watches {@code /policies}) that denies
 * {@code read} on one whole collection (the collection-wide path, as for a profile without read on
 * it) and on one record of another collection by attribute (the per-record path). The per-record
 * path is only taken for a collection that carries an enabled custom rule ({@code RecordRuleIndex}),
 * so the scenario plants one and publishes the policy-changed event that evicts the index — the
 * same event a real policy sync emits. Every other decision falls through to the root allow-all
 * policy, and the scoped file is neutralized afterwards.
 *
 * <p>Record calls go to the worker directly with the identity headers the gateway stamps
 * ({@code X-User-Email}, {@code X-User-Profile-Id}, {@code X-Cerbos-Scope}, …), which is what
 * makes the record advice run.
 */
@DisplayName("Included resources respect read authorization")
class IncludedResourceAuthorizationScenarioTest extends ScenarioBase {

    private static final String POLICY_DIR = "/policies/";

    @Test
    @DisplayName("included[] keeps only records the caller may read directly")
    @SuppressWarnings("unchecked")
    void includedResourcesRespectReadAuthorization() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String slug = tenants.slugForTenantId(tenantId);
        RestClient admin = gatewayClientWithToken(adminToken);
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L, 36);
        String targets = "iratarget" + suffix;  // per-record decision (title = "restricted" denied)
        String locked = "iralocked" + suffix;   // collection-wide decision (read denied)
        String sources = "irasource" + suffix;  // primary: lookups to both
        String policyFile = POLICY_DIR + "harness_included_authz_" + suffix + ".yaml";

        waitForStatus(admin, "/" + slug + "/api/collections", HttpStatus.OK, 20);

        String targetsId = createCollection(admin, slug, targets);
        String lockedId = createCollection(admin, slug, locked);
        String sourcesId = createCollection(admin, slug, sources);
        String ruleId = UUID.randomUUID().toString();
        try {
            createField(admin, slug, Map.of("collectionId", targetsId, "name", "title", "type", "STRING"), null);
            createField(admin, slug, Map.of("collectionId", lockedId, "name", "title", "type", "STRING"), null);
            createField(admin, slug, Map.of("collectionId", sourcesId, "name", "title", "type", "STRING"), null);
            createField(admin, slug, Map.of("collectionId", sourcesId, "name", "target", "type", "LOOKUP",
                    "relationshipName", "target", "referenceTarget", targets), targetsId);
            createField(admin, slug, Map.of("collectionId", sourcesId, "name", "locked", "type", "LOOKUP",
                    "relationshipName", "locked", "referenceTarget", locked), lockedId);

            String openTarget = create(tenantId, slug, targets, Map.of("title", "open"));
            String restrictedTarget = create(tenantId, slug, targets, Map.of("title", "restricted"));
            String lockedRow = create(tenantId, slug, locked, Map.of("title", "locked-1"));
            String openSource = create(tenantId, slug, sources,
                    Map.of("title", "s-open", "target", openTarget, "locked", lockedRow));
            String restrictedSource = create(tenantId, slug, sources,
                    Map.of("title", "s-restricted", "target", restrictedTarget));

            String profileId = anyProfile(tenantId);
            Caller caller = new Caller(AuthFixture.adminUsername(TenantFixture.DEFAULT_SLUG), profileId, tenantId, slug);
            String include = "?include=target,locked";

            // ── Baseline (allow-all): every referenced record is included ────────────────────
            Map<String, Object> before = get(caller, "/api/" + sources + include);
            assertThat(includedIds(before)).as("baseline included: %s", before)
                    .contains(openTarget, restrictedTarget, lockedRow);

            // ── Deny: whole `locked` collection, and `targets` rows titled "restricted" ───────
            // The PDP must have reloaded before the worker's caches are evicted, or the first read
            // re-caches the old allow.
            KeltaStack.CERBOS.copyFileToContainer(Transferable.of(scopedRecordPolicy(tenantId, targets, locked)),
                    policyFile);
            awaitPdpRead(tenantId, locked, "EFFECT_DENY");
            plantCustomRule(ruleId, tenantId, profileId, targetsId);
            natsPublish("kelta.cerbos.policies.changed." + tenantId,
                    "{\"tenantId\":\"" + tenantId + "\"}");

            // Direct reads are the reference.
            awaitBody(caller, "/api/" + locked + "/" + lockedRow, body -> body.get("data") == null,
                    "direct read of the locked record is denied");
            awaitBody(caller, "/api/" + targets, body -> ids(body).contains(openTarget)
                            && !ids(body).contains(restrictedTarget),
                    "direct list of targets omits the restricted record");

            // ── List with includes: only directly readable records are included ──────────────
            Map<String, Object> list = get(caller, "/api/" + sources + include);
            assertThat(ids(list)).as("primary rows stay: %s", list)
                    .containsExactlyInAnyOrder(openSource, restrictedSource);
            assertThat(includedIds(list)).as("included after deny: %s", list)
                    .contains(openTarget)
                    .doesNotContain(restrictedTarget, lockedRow);

            // ── Single record with includes ──────────────────────────────────────────────────
            Map<String, Object> one = get(caller, "/api/" + sources + "/" + openSource + include);
            assertThat(includedIds(one)).as("single-record included: %s", one)
                    .containsExactly(openTarget);
            Map<String, Object> other = get(caller, "/api/" + sources + "/" + restrictedSource + include);
            assertThat(includedIds(other)).as("single-record included (restricted target): %s", other)
                    .isEmpty();

            // ── A primary the caller may not read brings nothing along ───────────────────────
            Map<String, Object> deniedPrimary = get(caller, "/api/" + locked + "/" + lockedRow + "?include=" + sources);
            assertThat(deniedPrimary.get("data")).as("denied primary: %s", deniedPrimary).isNull();
            assertThat(includedIds(deniedPrimary)).as("denied primary included: %s", deniedPrimary).isEmpty();
        } finally {
            // A scoped policy whose only rule never matches leaves every decision to the root
            // allow-all policy (the distroless Cerbos image has no `rm`).
            KeltaStack.CERBOS.copyFileToContainer(Transferable.of(neutralScopedRecordPolicy(tenantId)), policyFile);
            awaitPdpRead(tenantId, locked, "EFFECT_ALLOW");
            try (Connection db = openDbConnection();
                 PreparedStatement ps = db.prepareStatement("DELETE FROM profile_custom_rules WHERE id = ?")) {
                ps.setString(1, ruleId);
                ps.executeUpdate();
            }
            natsPublish("kelta.cerbos.policies.changed." + tenantId, "{\"tenantId\":\"" + tenantId + "\"}");
            for (String id : List.of(sourcesId, targetsId, lockedId)) {
                admin.delete().uri("/" + slug + "/api/collections/" + id + "?force=true")
                        .retrieve().onStatus(st -> true, (req, resp) -> {}).toBodilessEntity();
            }
        }
    }

    // ── policy fixtures ──────────────────────────────────────────────────────────────────

    private static String scopedRecordPolicy(String tenantId, String targets, String locked) {
        return """
                apiVersion: api.cerbos.dev/v1
                resourcePolicy:
                  version: "default"
                  resource: "record"
                  scope: "%s"
                  rules:
                    - actions: ["read"]
                      effect: EFFECT_DENY
                      roles: ["*"]
                      condition:
                        match:
                          expr: R.attr.collectionId == "%s"
                    - actions: ["read"]
                      effect: EFFECT_DENY
                      roles: ["*"]
                      condition:
                        match:
                          expr: R.attr.collectionId == "%s" && has(R.attr.title) && R.attr.title == "restricted"
                """.formatted(tenantId, locked, targets);
    }

    private static String neutralScopedRecordPolicy(String tenantId) {
        return """
                apiVersion: api.cerbos.dev/v1
                resourcePolicy:
                  version: "default"
                  resource: "record"
                  scope: "%s"
                  rules:
                    - actions: ["read"]
                      effect: EFFECT_DENY
                      roles: ["*"]
                      condition:
                        match:
                          expr: "false"
                """.formatted(tenantId);
    }

    /**
     * Waits until the harness PDP answers a collection-wide {@code read} on {@code collection} in
     * the tenant's scope with {@code effect} — its disk store reloads a changed file shortly
     * after the write.
     */
    private static void awaitPdpRead(String tenantId, String collection, String effect)
            throws InterruptedException {
        String request = "{\"principal\":{\"id\":\"probe\",\"roles\":[\"user\"]},"
                + "\"resources\":[{\"actions\":[\"read\"],\"resource\":{\"kind\":\"record\","
                + "\"id\":\"probe\",\"scope\":\"" + tenantId + "\","
                + "\"attr\":{\"collectionId\":\"" + collection + "\"}}}]}";
        String last = null;
        for (int i = 0; i < 60; i++) {
            try {
                last = RestClient.create().post()
                        .uri("http://" + KeltaStack.CERBOS.getHost() + ":" + KeltaStack.CERBOS.getMappedPort(3592)
                                + "/api/check/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve().body(String.class);
                if (last != null && last.contains("\"read\":\"" + effect + "\"")) {
                    return;
                }
            } catch (Exception e) {
                last = e.toString();
            }
            Thread.sleep(250);
        }
        throw new AssertionError("PDP did not answer " + effect + " for " + collection + ": " + last);
    }

    /** An enabled custom rule on {@code collectionId} puts it on the per-record check path. */
    private void plantCustomRule(String ruleId, String tenantId, String profileId, String collectionId)
            throws Exception {
        try (Connection db = openDbConnection();
             PreparedStatement ps = db.prepareStatement("""
                     INSERT INTO profile_custom_rules
                         (id, tenant_id, profile_id, collection_id, action, effect, condition_type, condition_json, enabled)
                     VALUES (?, ?, ?, ?, 'read', 'DENY', 'CEL', '{}'::jsonb, true)
                     """)) {
            ps.setString(1, ruleId);
            ps.setString(2, tenantId);
            ps.setString(3, profileId);
            ps.setString(4, collectionId);
            ps.executeUpdate();
        }
    }

    /**
     * Publishes one core NATS message (the stream on {@code kelta.cerbos.>} stores it for the
     * worker's consumer). The harness has no NATS client dependency, and the protocol for a single
     * publish is three lines.
     */
    private static void natsPublish(String subject, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket(KeltaStack.NATS.getHost(), KeltaStack.NATS.getMappedPort(4222))) {
            socket.setSoTimeout(5_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            String info = in.readLine();
            assertThat(info).as("NATS greeting").startsWith("INFO");
            out.write(("CONNECT {\"verbose\":false,\"pedantic\":false}\r\nPUB " + subject + " " + body.length + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.write("\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                assertThat(line).as("NATS publish reply").doesNotStartWith("-ERR");
                if (line.startsWith("PONG")) {
                    return;
                }
            }
            throw new AssertionError("NATS closed the connection before PONG");
        }
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────────────

    private record Caller(String email, String profileId, String tenantId, String slug) {}

    private ResponseEntity<Map> call(Caller caller, String uri) {
        return workerClient().method(HttpMethod.GET).uri(uri)
                .header("X-Tenant-ID", caller.tenantId())
                .header("X-Tenant-Slug", caller.slug())
                .header("X-User-Id", caller.email())
                .header("X-User-Type", "INTERNAL")
                .header("X-User-Email", caller.email())
                .header("X-User-Profile-Id", caller.profileId())
                .header("X-Cerbos-Scope", caller.tenantId())
                .retrieve().onStatus(HttpStatusCode::isError, (req, resp) -> {}).toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(Caller caller, String uri) {
        ResponseEntity<Map> response = call(caller, uri);
        assertThat(response.getStatusCode()).as("GET %s: %s", uri, response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private void awaitBody(Caller caller, String uri, Predicate<Map<String, Object>> ready, String what)
            throws InterruptedException {
        Map<String, Object> last = null;
        for (int i = 0; i < 60; i++) {
            ResponseEntity<Map> response = call(caller, uri);
            if (response.getStatusCode().is2xxSuccessful()) {
                last = response.getBody();
                if (last != null && ready.test(last)) {
                    return;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError(what + " — last body: " + last);
    }

    private String create(String tenantId, String slug, String collection, Map<String, Object> attributes) {
        ResponseEntity<Map> created = workerClient().post().uri("/api/" + collection)
                .header("X-Tenant-ID", tenantId)
                .header("X-Tenant-Slug", slug)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", collection, "attributes", attributes)))
                .retrieve().onStatus(HttpStatusCode::isError, (req, resp) -> {}).toEntity(Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("create %s %s: %s", collection, attributes, created.getBody()).isTrue();
        return (String) ((Map<String, Object>) created.getBody().get("data")).get("id");
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

    private void createField(RestClient admin, String slug, Map<String, Object> attributes,
                             String referenceCollectionId) {
        Map<String, Object> data = referenceCollectionId == null
                ? Map.of("type", "fields", "attributes", attributes)
                : Map.of("type", "fields", "attributes", attributes,
                        "relationships", Map.of("referenceCollectionId", Map.of(
                                "data", Map.of("type", "collections", "id", referenceCollectionId))));
        ResponseEntity<Map> created = admin.post().uri("/" + slug + "/api/fields")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("data", data))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create field %s: %s", attributes, created.getBody())
                .isTrue();
    }

    private String anyProfile(String tenantId) throws Exception {
        try (Connection db = openDbConnection();
             PreparedStatement ps = db.prepareStatement("SELECT id FROM profile WHERE tenant_id = ? ORDER BY id LIMIT 1")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("the tenant has a profile").isTrue();
                return rs.getString(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> body) {
        Object data = body.get("data");
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(r -> (String) ((Map<String, Object>) r).get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> includedIds(Map<String, Object> body) {
        Object included = body.get("included");
        if (!(included instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(r -> (String) ((Map<String, Object>) r).get("id")).toList();
    }
}
