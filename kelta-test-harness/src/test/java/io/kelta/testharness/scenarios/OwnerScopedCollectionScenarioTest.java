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
        createField(admin, slug, Map.of("collectionId", foldersId, "name", "title", "type", "STRING"), null);

        String itemsId = createCollection(admin, slug, items);
        createField(admin, slug, Map.of("collectionId", itemsId, "name", "title", "type", "STRING"), null);
        createField(admin, slug, Map.of("collectionId", itemsId, "name", "folder", "type", "LOOKUP",
                "relationshipName", "folder", "referenceTarget", folders), foldersId);

        // A field create refreshes the serving pod before it answers (FieldConfigEventPublisher,
        // #910) and an ownership PATCH does the same (CollectionConfigEventPublisher), so the
        // single harness worker enforces each change as soon as the request returns — no polling.
        patchCollection(admin, slug, itemsId, Map.of("ownerField", "createdBy", "ownerScope", "PORTAL"));

        // ── Callers ─────────────────────────────────────────────────────────────────────
        Caller alice = new Caller(invitePortalMember(admin, slug, "alice", suffix), "PORTAL", null);
        Caller bob = new Caller(invitePortalMember(admin, slug, "bob", suffix), "PORTAL", null);
        String adminUser = AuthFixture.adminUsername(TenantFixture.DEFAULT_SLUG);
        Caller staff = new Caller(adminUser, "INTERNAL", profileGranting(tenantId, "VIEW_ALL_DATA"));
        Caller staffWithoutGrant = new Caller(adminUser, "INTERNAL", null);

        // ── Data: one shared folder; Bob's row first so the newest row overall is Alice's ──
        String folderId = id(call(null, tenantId, slug, HttpMethod.POST, "/api/" + folders,
                Map.of("data", Map.of("type", folders, "attributes", Map.of("title", "Shared")))));
        String bobItem = id(createItem(bob, tenantId, slug, items, "bob-1", folderId));
        String aliceItem = id(createItem(alice, tenantId, slug, items, "alice-1", folderId));
        id(createItem(alice, tenantId, slug, items, "alice-2", folderId));

        // ── list: own rows only, with a correct SQL total ───────────────────────────────
        Map<String, Object> aliceList = list(alice, tenantId, slug, items);
        assertThat(totalCount(aliceList)).as("alice list: %s", aliceList).isEqualTo(2);
        assertThat(titles(aliceList)).as("alice list: %s", aliceList)
                .containsExactlyInAnyOrder("alice-1", "alice-2");
        Map<String, Object> bobList = list(bob, tenantId, slug, items);
        assertThat(totalCount(bobList)).as("bob list: %s", bobList).isEqualTo(1);
        assertThat(titles(bobList)).as("bob list: %s", bobList).containsExactly("bob-1");

        // ── get: own row 200, foreign row 404 (never 403) ───────────────────────────────
        assertStatus(call(alice, tenantId, slug, HttpMethod.GET, "/api/" + items + "/" + aliceItem),
                HttpStatus.OK, "alice reads her own row");
        assertStatus(call(bob, tenantId, slug, HttpMethod.GET, "/api/" + items + "/" + aliceItem),
                HttpStatus.NOT_FOUND, "bob reads alice's row");

        // ── aggregate ───────────────────────────────────────────────────────────────────
        Map<String, Object> aliceAgg = call(alice, tenantId, slug, HttpMethod.GET,
                "/api/" + items + "/aggregate?groupBy=title").getBody();
        assertThat(((Number) aliceAgg.get("totalCount")).intValue()).as("alice aggregate: %s", aliceAgg)
                .isEqualTo(2);
        Map<String, Object> bobAgg = call(bob, tenantId, slug, HttpMethod.GET,
                "/api/" + items + "/aggregate?groupBy=title").getBody();
        assertThat(((Number) bobAgg.get("totalCount")).intValue()).as("bob aggregate: %s", bobAgg)
                .isEqualTo(1);

        // ── /latest: Alice wrote the newest row overall, Bob still gets his own ─────────
        Map<String, Object> bobLatest = call(bob, tenantId, slug, HttpMethod.GET,
                "/api/" + items + "/latest?fields=title").getBody();
        assertThat(((Map<String, Object>) bobLatest.get("record")).get("title")).as("bob latest: %s", bobLatest)
                .isEqualTo("bob-1");

        // ── ?include= of the owned child collection through the shared parent ───────────
        Map<String, Object> aliceFolder = call(alice, tenantId, slug, HttpMethod.GET,
                "/api/" + folders + "/" + folderId + "?include=" + items).getBody();
        List<Map<String, Object>> included = (List<Map<String, Object>>) aliceFolder.get("included");
        assertThat(included).as("only Alice's items are included: %s", aliceFolder).isNotNull();
        assertThat(included.stream().filter(r -> items.equals(r.get("type")))
                .map(r -> ((Map<String, Object>) r.get("attributes")).get("title")))
                .as("included: %s", included)
                .containsExactlyInAnyOrder("alice-1", "alice-2");

        // ── cross-owner writes: 404, and the row is untouched ───────────────────────────
        assertStatus(call(bob, tenantId, slug, HttpMethod.PATCH, "/api/" + items + "/" + aliceItem,
                Map.of("data", Map.of("type", items, "id", aliceItem,
                        "attributes", Map.of("title", "hijacked")))),
                HttpStatus.NOT_FOUND, "bob patches alice's row");
        assertStatus(call(bob, tenantId, slug, HttpMethod.DELETE, "/api/" + items + "/" + aliceItem),
                HttpStatus.NOT_FOUND, "bob deletes alice's row");
        Map<String, Object> stillThere = call(alice, tenantId, slug, HttpMethod.GET,
                "/api/" + items + "/" + aliceItem).getBody();
        assertThat(((Map<String, Object>) ((Map<String, Object>) stillThere.get("data")).get("attributes"))
                .get("title")).as("alice's row after bob's attempts: %s", stillThere).isEqualTo("alice-1");

        // The owner may still change their own row.
        assertStatus(call(alice, tenantId, slug, HttpMethod.PATCH, "/api/" + items + "/" + aliceItem,
                Map.of("data", Map.of("type", items, "id", aliceItem,
                        "attributes", Map.of("title", "alice-1b")))),
                HttpStatus.OK, "alice patches her own row");

        // ── ALL: VIEW_ALL_DATA staff see every row; members stay scoped ─────────────────
        Map<String, Object> staffUnderPortal = list(staffWithoutGrant, tenantId, slug, items);
        assertThat(totalCount(staffUnderPortal)).as("PORTAL scope leaves staff unscoped: %s", staffUnderPortal)
                .isEqualTo(3);
        patchCollection(admin, slug, itemsId, Map.of("ownerScope", "ALL"));
        // Staff without VIEW_ALL_DATA own none of the rows: an empty list proves ALL is live.
        Map<String, Object> ungranted = list(staffWithoutGrant, tenantId, slug, items);
        assertThat(totalCount(ungranted)).as("ALL scopes staff without VIEW_ALL_DATA: %s", ungranted)
                .isEqualTo(0);
        Map<String, Object> granted = list(staff, tenantId, slug, items);
        assertThat(totalCount(granted)).as("VIEW_ALL_DATA staff see every row: %s", granted).isEqualTo(3);
        Map<String, Object> aliceUnderAll = list(alice, tenantId, slug, items);
        assertThat(totalCount(aliceUnderAll)).as("alice under ALL: %s", aliceUnderAll).isEqualTo(2);
    }

    /**
     * The shape of a public-read, owner-write collection (member data ownership slice 4 — what
     * replaced the bespoke per-collection owner-guard hooks): {@code ownerField=createdBy},
     * {@code ownerScope=ALL}, {@code ownerScopeReads=false}. Every member reads every row; only
     * the author, or staff holding {@code MODIFY_ALL_DATA}, may change one.
     */
    @Test
    @DisplayName("createdBy / ALL / unscoped reads: members list everyone's rows but change only their own")
    @SuppressWarnings("unchecked")
    void publicReadOwnerWriteCollection() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String slug = tenants.slugForTenantId(tenantId);
        RestClient admin = gatewayClientWithToken(adminToken);
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L, 36);
        String reports = "oscreports" + suffix;

        waitForStatus(admin, "/" + slug + "/api/collections", HttpStatus.OK, 20);
        String reportsId = createCollection(admin, slug, reports);
        try {
            createField(admin, slug, Map.of("collectionId", reportsId, "name", "title", "type", "STRING"), null);
            patchCollection(admin, slug, reportsId,
                    Map.of("ownerField", "createdBy", "ownerScope", "ALL", "ownerScopeReads", false));

            Caller alice = new Caller(invitePortalMember(admin, slug, "ralice", suffix), "PORTAL", null);
            Caller bob = new Caller(invitePortalMember(admin, slug, "rbob", suffix), "PORTAL", null);
            String adminUser = AuthFixture.adminUsername(TenantFixture.DEFAULT_SLUG);
            Caller staffWithoutGrant = new Caller(adminUser, "INTERNAL", null);
            Caller modifyAll = new Caller(adminUser, "INTERNAL", profileGranting(tenantId, "MODIFY_ALL_DATA"));

            String aliceReport = id(createReport(alice, tenantId, slug, reports, "alice-1"));
            id(createReport(alice, tenantId, slug, reports, "alice-2"));
            id(createReport(bob, tenantId, slug, reports, "bob-1"));

            // ── reads are unscoped: Bob's list holds Alice's rows, and he can open one ──────
            Map<String, Object> bobList = list(bob, tenantId, slug, reports);
            assertThat(totalCount(bobList)).as("bob list: %s", bobList).isEqualTo(3);
            assertThat(titles(bobList)).as("bob list: %s", bobList)
                    .containsExactlyInAnyOrder("alice-1", "alice-2", "bob-1");
            assertStatus(call(bob, tenantId, slug, HttpMethod.GET, "/api/" + reports + "/" + aliceReport),
                    HttpStatus.OK, "bob reads alice's row");

            // ── writes are owner-only: Bob's PATCH and DELETE of Alice's row are 404 ────────
            assertStatus(call(bob, tenantId, slug, HttpMethod.PATCH, "/api/" + reports + "/" + aliceReport,
                    Map.of("data", Map.of("type", reports, "id", aliceReport,
                            "attributes", Map.of("title", "hijacked")))),
                    HttpStatus.NOT_FOUND, "bob patches alice's row");
            assertStatus(call(bob, tenantId, slug, HttpMethod.DELETE, "/api/" + reports + "/" + aliceReport),
                    HttpStatus.NOT_FOUND, "bob deletes alice's row");
            // Under ALL, staff without MODIFY_ALL_DATA are owner-scoped for writes too.
            assertStatus(call(staffWithoutGrant, tenantId, slug, HttpMethod.PATCH,
                    "/api/" + reports + "/" + aliceReport,
                    Map.of("data", Map.of("type", reports, "id", aliceReport,
                            "attributes", Map.of("title", "staff-edit")))),
                    HttpStatus.NOT_FOUND, "staff without MODIFY_ALL_DATA patch alice's row");
            Map<String, Object> untouched = call(alice, tenantId, slug, HttpMethod.GET,
                    "/api/" + reports + "/" + aliceReport).getBody();
            assertThat(((Map<String, Object>) ((Map<String, Object>) untouched.get("data")).get("attributes"))
                    .get("title")).as("alice's row after the refused writes: %s", untouched).isEqualTo("alice-1");

            // ── the author, and MODIFY_ALL_DATA staff, may change it ────────────────────────
            assertStatus(call(alice, tenantId, slug, HttpMethod.PATCH, "/api/" + reports + "/" + aliceReport,
                    Map.of("data", Map.of("type", reports, "id", aliceReport,
                            "attributes", Map.of("title", "alice-1b")))),
                    HttpStatus.OK, "alice patches her own row");
            assertStatus(call(modifyAll, tenantId, slug, HttpMethod.PATCH, "/api/" + reports + "/" + aliceReport,
                    Map.of("data", Map.of("type", reports, "id", aliceReport,
                            "attributes", Map.of("title", "moderated")))),
                    HttpStatus.OK, "MODIFY_ALL_DATA staff patch alice's row");
            assertStatus(call(alice, tenantId, slug, HttpMethod.DELETE, "/api/" + reports + "/" + aliceReport),
                    HttpStatus.NO_CONTENT, "alice deletes her own row");
        } finally {
            admin.delete().uri("/" + slug + "/api/collections/" + reportsId + "?force=true")
                    .retrieve().onStatus(st -> true, (req, resp) -> {}).toBodilessEntity();
        }
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
        createField(admin, slug, Map.of("collectionId", collectionId, "name", "title", "type", "STRING"), null);
        createField(admin, slug, Map.of("collectionId", collectionId, "name", "member", "type", "LOOKUP",
                "relationshipName", "member", "referenceTarget", "users"), systemCollectionId("users"));

        assertStatus(patch(admin, slug, collectionId, Map.of("ownerField", "title", "ownerScope", "PORTAL")),
                HttpStatus.BAD_REQUEST, "a text owner field");
        assertStatus(patch(admin, slug, collectionId, Map.of("ownerScope", "PORTAL")),
                HttpStatus.BAD_REQUEST, "a scope without an owner field");
        patchCollection(admin, slug, collectionId,
                Map.of("ownerField", "member", "ownerScope", "PORTAL", "ownerScopeReads", false));

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
        assertThat(invited.getStatusCode()).as("invite %s: %s", name, invited.getBody()).isEqualTo(HttpStatus.CREATED);
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

    private ResponseEntity<Map> createReport(Caller caller, String tenantId, String slug, String reports,
                                             String title) {
        ResponseEntity<Map> created = call(caller, tenantId, slug, HttpMethod.POST, "/api/" + reports,
                Map.of("data", Map.of("type", reports, "attributes", Map.of("title", title))));
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

    /** A list read that must succeed; the body is in the failure message otherwise. */
    private Map<String, Object> list(Caller caller, String tenantId, String slug, String collection) {
        ResponseEntity<Map> response = call(caller, tenantId, slug, HttpMethod.GET, "/api/" + collection);
        assertStatus(response, HttpStatus.OK, "list " + collection + " as " + caller);
        return response.getBody();
    }

    private static void assertStatus(ResponseEntity<Map> response, HttpStatus expected, String what) {
        assertThat(response.getStatusCode()).as("%s: %s", what, response.getBody()).isEqualTo(expected);
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
}
