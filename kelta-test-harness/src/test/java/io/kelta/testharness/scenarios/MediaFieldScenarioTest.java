package io.kelta.testharness.scenarios;

import io.kelta.testharness.KeltaStack;
import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.AuthFixture;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Media fields ({@code fieldTypeConfig.media}) against real Postgres: a collection configured
 * purely through metadata mints upload URLs, and its download redirect answers only for a key a
 * record references — through the QueryEngine, so the SQL filter on the media field, RLS and the
 * owner-scope predicate are all real here, none of which a Mockito test can reach.
 *
 * <p>The harness runs no object store: presigning is a local HMAC, so the worker is pointed at an
 * unreachable media endpoint and the scenario asserts on the redirect's {@code Location} without
 * following it. Record calls go to the worker with gateway-style identity headers, as
 * {@code OwnerScopedCollectionScenarioTest} does.
 */
@DisplayName("Media field scenario")
class MediaFieldScenarioTest extends ScenarioBase {

    private record Caller(String userId, String userType) {}

    @Test
    @DisplayName("upload-url → record → download redirect, scoped to referenced keys, tenant and owner")
    @SuppressWarnings("unchecked")
    void mediaFieldLifecycle() {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String slug = tenants.slugForTenantId(tenantId);
        RestClient admin = gatewayClientWithToken(adminToken);
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L, 36);
        String photos = "mediaphotos" + suffix;

        waitForStatus(admin, "/" + slug + "/api/collections", HttpStatus.OK, 20);
        String collectionId = createCollection(admin, slug, photos);
        try {
            createField(admin, slug, Map.of("collectionId", collectionId, "name", "title", "type", "STRING"));
            createField(admin, slug, Map.of("collectionId", collectionId, "name", "photoKey", "type", "STRING",
                    "fieldTypeConfig", Map.of("media", Map.of("enabled", true, "maxFileSize", 2048))));

            Caller staff = new Caller(AuthFixture.adminUsername(TenantFixture.DEFAULT_SLUG), "INTERNAL");

            // ── upload-url: key is <tenant>/<collection>/<uuid>/<sanitized name> ─────────────
            ResponseEntity<Map> minted = call(staff, tenantId, slug, HttpMethod.POST, "/api/" + photos + "/upload-url",
                    Map.of("fileName", "front door.jpg", "contentType", "image/jpeg", "fileSize", 1024));
            assertThat(minted.getStatusCode()).as("upload-url: %s", minted.getBody()).isEqualTo(HttpStatus.OK);
            Map<String, Object> attrs = (Map<String, Object>) ((Map<String, Object>) minted.getBody().get("data"))
                    .get("attributes");
            String storageKey = (String) attrs.get("storageKey");
            assertThat(storageKey).startsWith(tenantId + "/" + photos + "/").endsWith("/front_door.jpg");
            assertThat((String) attrs.get("uploadUrl")).contains("harness-media").contains("X-Amz-Signature");

            // The field's own 2048-byte limit applies; a non-image type is refused.
            assertThat(call(staff, tenantId, slug, HttpMethod.POST, "/api/" + photos + "/upload-url",
                    Map.of("fileName", "big.jpg", "contentType", "image/jpeg", "fileSize", 4096)).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(call(staff, tenantId, slug, HttpMethod.POST, "/api/" + photos + "/upload-url",
                    Map.of("fileName", "a.pdf", "contentType", "application/pdf", "fileSize", 10)).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            // ── download: nothing references the key yet → 404 ────────────────────────────────
            String downloadPath = "/api/" + photos + "/download/" + storageKey;
            assertThat(call(staff, tenantId, slug, HttpMethod.GET, downloadPath, null).getStatusCode())
                    .as("unreferenced key").isEqualTo(HttpStatus.NOT_FOUND);

            // ── once a record holds the key, the download redirects to a presigned GET ───────
            ResponseEntity<Map> created = call(staff, tenantId, slug, HttpMethod.POST, "/api/" + photos,
                    Map.of("data", Map.of("type", photos,
                            "attributes", Map.of("title", "Front door", "photoKey", storageKey))));
            assertThat(created.getStatusCode().is2xxSuccessful()).as("create: %s", created.getBody()).isTrue();

            ResponseEntity<Map> redirect = call(staff, tenantId, slug, HttpMethod.GET, downloadPath, null);
            assertThat(redirect.getStatusCode()).as("referenced key").isEqualTo(HttpStatus.FOUND);
            assertThat(redirect.getHeaders().getFirst(HttpHeaders.LOCATION))
                    .startsWith("http://media-store.invalid:3900/harness-media/")
                    .contains("X-Amz-Signature");
            assertThat(redirect.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, max-age=60");

            // ── another tenant's key layout, or a guessed object id: 404 ─────────────────────
            String otherTenant = UUID.randomUUID().toString();
            assertThat(call(staff, tenantId, slug, HttpMethod.GET, "/api/" + photos + "/download/" + otherTenant
                    + storageKey.substring(tenantId.length()), null).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(call(staff, tenantId, slug, HttpMethod.GET, "/api/" + photos + "/download/" + tenantId + "/"
                    + photos + "/" + UUID.randomUUID() + "/front_door.jpg", null).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            // ── owner-scoped reads hide the record, and with it the media, from other members ─
            patchCollection(admin, slug, collectionId, Map.of("ownerField", "createdBy", "ownerScope", "PORTAL"));
            Caller member = new Caller(invitePortalMember(admin, slug, suffix), "PORTAL");
            assertThat(call(member, tenantId, slug, HttpMethod.GET, downloadPath, null).getStatusCode())
                    .as("portal member, record owned by staff").isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(call(staff, tenantId, slug, HttpMethod.GET, downloadPath, null).getStatusCode())
                    .as("staff are unscoped under PORTAL").isEqualTo(HttpStatus.FOUND);
        } finally {
            admin.delete().uri("/" + slug + "/api/collections/" + collectionId + "?force=true")
                    .retrieve().onStatus(st -> true, (req, resp) -> {}).toBodilessEntity();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    /** Worker client that never follows redirects, so a 302 is observed rather than chased. */
    private static RestClient nonRedirectingWorkerClient() {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return RestClient.builder()
                .baseUrl(KeltaStack.workerBaseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(http))
                .build();
    }

    /** A worker request carrying the identity headers the gateway stamps; never throws on 4xx. */
    private static ResponseEntity<Map> call(Caller caller, String tenantId, String slug, HttpMethod method,
                                            String uri, Object body) {
        RestClient.RequestBodySpec spec = nonRedirectingWorkerClient().method(method).uri(uri)
                .header("X-Tenant-ID", tenantId)
                .header("X-Tenant-Slug", slug)
                .header("X-User-Id", caller.userId())
                .header("X-User-Type", caller.userType());
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().onStatus(HttpStatusCode::isError, (req, resp) -> {}).toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static String createCollection(RestClient admin, String slug, String name) {
        ResponseEntity<Map> created = admin.post().uri("/" + slug + "/api/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "collections",
                        "attributes", Map.of("name", name, "displayName", name, "tenantScoped", true))))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create collection %s: %s", name, created.getBody())
                .isTrue();
        return (String) ((Map<String, Object>) created.getBody().get("data")).get("id");
    }

    private static void createField(RestClient admin, String slug, Map<String, Object> attributes) {
        ResponseEntity<Map> created = admin.post().uri("/" + slug + "/api/fields")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "fields", "attributes", attributes)))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create field %s: %s", attributes, created.getBody())
                .isTrue();
    }

    private static void patchCollection(RestClient admin, String slug, String collectionId, Map<String, Object> attrs) {
        ResponseEntity<Map> patched = admin.patch().uri("/" + slug + "/api/collections/" + collectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", "collections", "id", collectionId, "attributes", attrs)))
                .retrieve()
                .onStatus(s -> true, (req, resp) -> {})
                .toEntity(Map.class);
        assertThat(patched.getStatusCode().is2xxSuccessful()).as("collection PATCH: %s", patched.getBody()).isTrue();
    }

    private static String invitePortalMember(RestClient admin, String slug, String suffix) {
        ResponseEntity<Map> invited = admin.post().uri("/" + slug + "/api/admin/users/portal-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "media-member-" + suffix + "@example.com",
                        "firstName", "Media", "lastName", "Member"))
                .retrieve().toEntity(Map.class);
        assertThat(invited.getStatusCode()).as("invite: %s", invited.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) invited.getBody().get("userId");
    }
}
