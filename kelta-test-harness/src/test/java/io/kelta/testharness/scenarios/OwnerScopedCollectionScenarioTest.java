package io.kelta.testharness.scenarios;

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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner-scoped collections (member data ownership slice 2) against real Postgres: two portal
 * members on a collection with {@code ownerScope=PORTAL} each see only their own rows on every
 * QueryEngine read path, cannot change each other's rows, and a staff caller with
 * {@code VIEW_ALL_DATA} sees everything once the scope is {@code ALL}.
 *
 * <p>The owner predicate is SQL in {@code PhysicalTableStorageAdapter} and the write guard is
 * {@code OwnerScopeGuardHook}; neither is reachable from a Mockito test against a real table, so
 * this is the regression guard for the column mapping, the paging totals and the 404 contract
 * (testing.md → Real-DB guard).
 *
 * <p>Record calls go to the worker directly with the identity headers the gateway would stamp —
 * {@code X-User-Id} (the member's UUID), {@code X-User-Type} and {@code X-User-Profile-Id} — so
 * the scenario exercises {@code CallerContextFilter} exactly as production does, independent of
 * login flows. Metadata (collections, fields) goes through the gateway as the admin.
 */
@DisplayName("Owner-scoped collection scenario")
class OwnerScopedCollectionScenarioTest extends ScenarioBase {

    private record Caller(String userId, String userType, String profileId) {}

    @Test
    @DisplayName("portal members see and change only their own rows; VIEW_ALL_DATA staff see all under ALL")
    @SuppressWarnings("unchecked")
    void ownerScopingAcrossReadAndWritePaths() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String slug = tenants.slugForTenantId(tenantId);
        RestClient admin = gatewayClientWithToken(adminToken);
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L, 36);
        String folders = "oscfolders" + suffix;
        String items = "oscitems" + suffix;

        waitForStatus(admin, "/" + slug + "/api/collections", HttpStatus.OK, 20);

        // ── Schema: folders (unowned) ← items (owned by createdBy, LOOKUP to folders) ──────
        String foldersId = createCollection(admin, slug, folders);
        waitForStatus(admin, "/" + slug + "/api/" + folders, HttpStatus.OK, 30);
        createField(admin, slug, Map.of("collectionId", foldersId, "name", "title", "type", "STRING"), null);

        String itemsId = createCollection(admin, slug, items);
        waitForStatus(admin, "/" + slug + "/api/" + items, HttpStatus.OK, 30);
        createField(admin, slug, Map.of("collectionId", itemsId, "name", "title", "type", "STRING"), null);
        createField(admin, slug, Map.of("collectionId", itemsId, "name", "folder", "type", "LOOKUP",
                "relationshipName", "folder", "referenceTarget", folders), foldersId);
        waitForField(admin, slug, itemsId, "folder");

        patchCollection(admin, slug, itemsId, Map.of("ownerField", "createdBy", "ownerScope", "PORTAL"));

        // ── Callers ─────────────────────────────────────────────────────────────────────
        Caller alice = new Caller(invitePortalMember(admin, slug, "alice", suffix), "PORTAL", null);
        Caller bob = new Caller(invitePortalMember(admin, slug, "bob", suffix), "PORTAL", null);
        Caller staff = new Caller(AuthFixture.adminUsername(TenantFixture.DEFAULT_SLUG), "INTERNAL",
                profileGranting(tenantId, "VIEW_ALL_DATA"));

        // ── Data: one shared folder; Bob's row first so the newest row overall is Alice's ──
        String folderId = id(call(null, tenantId, slug, HttpMethod.POST, "/api/" + folders,
                Map.of("data", Map.of("type", folders, "attributes", Map.of("title", "Shared")))));
        String bobItem = id(createItem(bob, tenantId, slug, items, "bob-1", folderId));
        String aliceItem = id(createItem(alice, tenantId, slug, items, "alice-1", folderId));
        id(createItem(alice, tenantId, slug, items, "alice-2", folderId));

        // The ownership settings reach the worker through the collection.changed refresh.
        awaitList(alice, tenantId, slug, items, body -> totalCount(body) == 2);

        // ── list: own rows only, with a correct SQL total ───────────────────────────────
        Map<String, Object> aliceList = call(alice, tenantId, slug, HttpMethod.GET, "/api/" + items).getBody();
        assertThat(totalCount(aliceList)).isEqualTo(2);
        assertThat(titles(aliceList)).containsExactlyInAnyOrder("alice-1", "alice-2");
        Map<String, Object> bobList = call(bob, tenantId, slug, HttpMethod.GET, "/api/" + items).getBody();
        assertThat(totalCount(bobList)).isEqualTo(1);
        assertThat(titles(bobList)).containsExactly("bob-1");

        // ── get: own row 200, foreign row 404 (never 403) ───────────────────────────────
        assertThat(call(alice, tenantId, slug, HttpMethod.GET, "/api/" + items + "/" + aliceItem)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(bob, tenantId, slug, HttpMethod.GET, "/api/" + items + "/" + aliceItem)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // ── aggregate ───────────────────────────────────────────────────────────────────
        Map<String, Object> aliceAgg = call(alice, tenantId, slug, HttpMethod.GET,
                "/api/" + items + "/aggregate?groupBy=title").getBody();
        assertThat(((Number) aliceAgg.get("totalCount")).intValue()).isEqualTo(2);
        Map<String, Object> bobAgg = call(bob, tenantId, slug, HttpMethod.GET,
                "/api/" + items + "/aggregate?groupBy=title").getBody();
        assertThat(((Number) bobAgg.get("totalCount")).intValue()).isEqualTo(1);

        // ── /latest: Alice wrote the newest row overall, Bob still gets his own ─────────
        Map<String, Object> bobLatest = call(bob, tenantId, slug, HttpMethod.GET,
                "/api/" + items + "/latest?fields=title").getBody();
        assertThat(((Map<String, Object>) bobLatest.get("record")).get("title")).isEqualTo("bob-1");

        // ── ?include= of the owned child collection through the shared parent ───────────
        Map<String, Object> aliceFolder = call(alice, tenantId, slug, HttpMethod.GET,
                "/api/" + folders + "/" + folderId + "?include=" + items).getBody();
        List<Map<String, Object>> included = (List<Map<String, Object>>) aliceFolder.get("included");
        assertThat(included).as("only Alice's items are included").isNotNull();
        assertThat(included.stream().filter(r -> items.equals(r.get("type")))
                .map(r -> ((Map<String, Object>) r.get("attributes")).get("title")))
                .containsExactlyInAnyOrder("alice-1", "alice-2");

        // ── cross-owner writes: 404, and the row is untouched ───────────────────────────
        assertThat(call(bob, tenantId, slug, HttpMethod.PATCH, "/api/" + items + "/" + aliceItem,
                Map.of("data", Map.of("type", items, "id", aliceItem,
                        "attributes", Map.of("title", "hijacked")))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(bob, tenantId, slug, HttpMethod.DELETE, "/api/" + items + "/" + aliceItem)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> stillThere = call(alice, tenantId, slug, HttpMethod.GET,
                "/api/" + items + "/" + aliceItem).getBody();
        assertThat(((Map<String, Object>) ((Map<String, Object>) stillThere.get("data")).get("attributes"))
                .get("title")).isEqualTo("alice-1");

        // The owner may still change their own row.
        assertThat(call(alice, tenantId, slug, HttpMethod.PATCH, "/api/" + items + "/" + aliceItem,
                Map.of("data", Map.of("type", items, "id", aliceItem,
                        "attributes", Map.of("title", "alice-1b")))).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // ── ALL: VIEW_ALL_DATA staff see every row; members stay scoped ─────────────────
        patchCollection(admin, slug, itemsId, Map.of("ownerScope", "ALL"));
        awaitList(staff, tenantId, slug, items, body -> totalCount(body) == 3);
        assertThat(totalCount(call(alice, tenantId, slug, HttpMethod.GET, "/api/" + items).getBody()))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("ownership validation: a LOOKUP to users is a valid owner field, a text field is a 400")
    void ownerFieldValidationAgainstRealFields() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String slug = tenants.slugForTenantId(tenantId);
        RestClient admin = gatewayClientWithToken(adminToken);
        String name = "oscvalid" + Long.toString(System.nanoTime() % 1_000_000_000L, 36);

        waitForStatus(admin, "/" + slug + "/api/collections", HttpStatus.OK, 20);
        String collectionId = createCollection(admin, slug, name);
        waitForStatus(admin, "/" + slug + "/api/" + name, HttpStatus.OK, 30);
        createField(admin, slug, Map.of("collectionId", collectionId, "name", "title", "type", "STRING"), null);
        createField(admin, slug, Map.of("collectionId", collectionId, "name", "member", "type", "LOOKUP",
                "relationshipName", "member", "referenceTarget", "users"), systemCollectionId("users"));
        waitForField(admin, slug, collectionId, "member");

        ResponseEntity<Map> textOwner = patch(admin, slug, collectionId,
                Map.of("ownerField", "title", "ownerScope", "PORTAL"));
        assertThat(textOwner.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> noOwner = patch(admin, slug, collectionId, Map.of("ownerScope", "PORTAL"));
        assertThat(noOwner.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> lookupOwner = patch(admin, slug, collectionId,
                Map.of("ownerField", "member", "ownerScope", "PORTAL", "ownerScopeReads", false));
        assertThat(lookupOwner.getStatusCode().is2xxSuccessful()).isTrue();

        try (Connection db = openDbConnection();
             PreparedStatement ps = db.prepareStatement(
                     "SELECT owner_field, owner_scope, owner_scope_reads FROM collection WHERE id = ?")) {
            ps.setString(1, collectionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("owner_field")).isEqualTo("member");
                assertThat(rs.getString("owner_scope")).isEqualTo("PORTAL");
                assertThat(rs.getBoolean("owner_scope_reads")).isFalse();
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
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
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
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private void patchCollection(RestClient admin, String slug, String collectionId, Map<String, Object> attrs) {
        ResponseEntity<Map> patched = patch(admin, slug, collectionId, attrs);
        assertThat(patched.getStatusCode().is2xxSuccessful())
                .as("collection ownership PATCH: %s", patched.getBody()).isTrue();
    }

    private ResponseEntity<Map> patch(RestClient admin, String slug, String collectionId, Map<String, Object> attrs) {
        return admin.patch().uri("/" + slug + "/api/collections/" + collectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "collections", "id", collectionId, "attributes", attrs)))
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toEntity(Map.class);
    }

    private String invitePortalMember(RestClient admin, String slug, String name, String suffix) {
        ResponseEntity<Map> invited = admin.post().uri("/" + slug + "/api/admin/users/portal-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "osc-" + name + "-" + suffix + "@example.com",
                        "firstName", name, "lastName", "Member"))
                .retrieve().toEntity(Map.class);
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) invited.getBody().get("userId");
    }

    /** A profile in the tenant whose system permissions grant {@code permission}. */
    private String profileGranting(String tenantId, String permission) throws Exception {
        try (Connection db = openDbConnection();
             PreparedStatement ps = db.prepareStatement("""
                     SELECT p.id FROM profile p
                     JOIN profile_system_permission psp ON psp.profile_id = p.id
                     WHERE p.tenant_id = ? AND psp.permission_name = ? AND psp.granted = true
                     LIMIT 1
                     """)) {
            ps.setString(1, tenantId);
            ps.setString(2, permission);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("the tenant has a profile granting %s", permission).isTrue();
                return rs.getString(1);
            }
        }
    }

    private String systemCollectionId(String name) throws Exception {
        try (Connection db = openDbConnection();
             PreparedStatement ps = db.prepareStatement(
                     "SELECT id FROM collection WHERE name = ? AND system_collection = true LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("system collection %s is seeded", name).isTrue();
                return rs.getString(1);
            }
        }
    }

    private ResponseEntity<Map> createItem(Caller caller, String tenantId, String slug, String items,
                                           String title, String folderId) {
        ResponseEntity<Map> created = call(caller, tenantId, slug, HttpMethod.POST, "/api/" + items,
                Map.of("data", Map.of("type", items,
                        "attributes", Map.of("title", title, "folder", folderId))));
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("create %s as %s: %s", title, caller.userId(), created.getBody()).isTrue();
        return created;
    }

    private ResponseEntity<Map> call(Caller caller, String tenantId, String slug, HttpMethod method, String uri) {
        return call(caller, tenantId, slug, method, uri, null);
    }

    /** A worker request carrying the identity headers the gateway stamps; never throws on 4xx. */
    private ResponseEntity<Map> call(Caller caller, String tenantId, String slug, HttpMethod method,
                                     String uri, Object body) {
        RestClient.RequestBodySpec spec = workerClient().method(method).uri(uri)
                .header("X-Tenant-ID", tenantId)
                .header("X-Tenant-Slug", slug);
        if (caller != null) {
            spec = spec.header("X-User-Id", caller.userId()).header("X-User-Type", caller.userType());
            if (caller.profileId() != null) {
                spec = spec.header("X-User-Profile-Id", caller.profileId());
            }
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().onStatus(HttpStatusCode::isError, (req, resp) -> {}).toEntity(Map.class);
    }

    /** Polls the caller's list until {@code done} holds — collection.changed refresh timing. */
    private void awaitList(Caller caller, String tenantId, String slug, String collection,
                           Predicate<Map<String, Object>> done) throws InterruptedException {
        Map<String, Object> last = null;
        for (int i = 0; i < 30; i++) {
            ResponseEntity<Map> list = call(caller, tenantId, slug, HttpMethod.GET, "/api/" + collection);
            if (list.getStatusCode().is2xxSuccessful()) {
                last = list.getBody();
                if (done.test(last)) {
                    return;
                }
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("owner scoping never took effect for " + caller + "; last list: " + last);
    }

    @SuppressWarnings("unchecked")
    private static long totalCount(Map<String, Object> listBody) {
        return ((Number) ((Map<String, Object>) listBody.get("meta")).get("totalCount")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> titles(Map<String, Object> listBody) {
        return ((List<Map<String, Object>>) listBody.get("data")).stream()
                .map(r -> ((Map<String, Object>) r.get("attributes")).get("title"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static String id(ResponseEntity<Map> created) {
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create: %s", created.getBody()).isTrue();
        return (String) ((Map<String, Object>) created.getBody().get("data")).get("id");
    }

    /** Polls the fields list until the field has propagated. */
    @SuppressWarnings("unchecked")
    private void waitForField(RestClient client, String slug, String collectionId, String fieldName)
            throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            try {
                ResponseEntity<Map> fields = client.get()
                        .uri("/" + slug + "/api/fields?filter[collectionId][eq]=" + collectionId)
                        .retrieve().toEntity(Map.class);
                List<Map<String, Object>> data = (List<Map<String, Object>>) fields.getBody().get("data");
                if (data != null && data.stream().anyMatch(f ->
                        fieldName.equals(((Map<String, Object>) f.get("attributes")).get("name")))) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // not ready yet
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Field '" + fieldName + "' never propagated");
    }
}
