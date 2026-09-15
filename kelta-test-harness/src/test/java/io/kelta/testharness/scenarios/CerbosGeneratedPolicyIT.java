package io.kelta.testharness.scenarios;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pushes the worker's <em>golden</em> generated Cerbos policies into a real Cerbos PDP and
 * asserts an allow/deny matrix against them.
 *
 * <p>Why this exists: the CI stack's Cerbos runs static allow-all disk policies
 * ({@code docker/cerbos/policies}), so nothing in CI ever evaluates what
 * {@code CerbosPolicyGenerator} actually emits. A policy that the unit tests are happy with
 * but Cerbos rejects (or evaluates differently) would only surface in production as a failed
 * {@code AddOrUpdatePolicy} or a silent deny. The golden files under
 * {@code kelta-worker/src/test/resources/cerbos/golden} are pinned to the generator by
 * {@code CerbosPolicyGeneratorTest} and consumed verbatim here, so the two halves cannot
 * drift apart.
 *
 * <p>The fixture (see {@code CerbosPolicyGeneratorTest.GoldenFixtureTests}): tenant
 * {@code tenant-golden}; collections {@code col-a/accounts}, {@code col-b/bookings},
 * {@code col-c/contacts}; profiles
 * <ul>
 *   <li>{@code admin-profile} — VIEW_ALL_DATA + MODIFY_ALL_DATA, full CRUD on col-a</li>
 *   <li>{@code editor-profile} — read+edit col-a, read col-b, nothing on col-c; a custom
 *       ABAC rule denies {@code edit} on accounts when {@code R.attr.status == "locked"}</li>
 *   <li>{@code viewer-profile} — VIEW_ALL_DATA only</li>
 *   <li>{@code nobody-profile} — no grants</li>
 * </ul>
 *
 * <p>Runs a standalone Cerbos (sqlite in-memory store + admin API) pinned to the production
 * image version; it does not need the full {@code KeltaStack}.
 */
@DisplayName("Generated Cerbos policies evaluate correctly on a real PDP")
class CerbosGeneratedPolicyIT {

    private static final String CERBOS_IMAGE = "ghcr.io/cerbos/cerbos:0.42.0";
    private static final String TENANT = "tenant-golden";
    private static final String ADMIN_USER = "cerbos";
    private static final String ADMIN_PASSWORD = "cerbosAdmin";
    // bcrypt("cerbosAdmin") — test-only credential for the throwaway container
    private static final String ADMIN_PASSWORD_HASH =
            "$2y$05$9spStCKjVOpxOdn0NZO1d.oMAWklpC3pp8LcW6nzzN.81E6kfkqgy";
    private static final List<String> CRUD = List.of("create", "read", "edit", "delete");

    private static GenericContainer<?> cerbos;
    private static RestClient client;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void startCerbosAndPushGoldenPolicies() throws IOException {
        Path config = Files.createTempFile("cerbos-golden-", ".yaml");
        Files.writeString(config, """
                server:
                  httpListenAddr: ":3592"
                  grpcListenAddr: ":3593"
                  adminAPI:
                    enabled: true
                    adminCredentials:
                      username: %s
                      passwordHash: %s
                storage:
                  driver: sqlite3
                  sqlite3:
                    dsn: ":memory:?_fk=true"
                telemetry:
                  disabled: true
                """.formatted(ADMIN_USER,
                Base64.getEncoder().encodeToString(ADMIN_PASSWORD_HASH.getBytes(StandardCharsets.UTF_8))));

        cerbos = new GenericContainer<>(DockerImageName.parse(CERBOS_IMAGE))
                .withCopyFileToContainer(MountableFile.forHostPath(config), "/config/config.yaml")
                .withCommand("server", "--config=/config/config.yaml")
                .withExposedPorts(3592)
                .waitingFor(Wait.forHttp("/_cerbos/health").forPort(3592)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        cerbos.start();

        client = RestClient.builder()
                .baseUrl("http://" + cerbos.getHost() + ":" + cerbos.getMappedPort(3592))
                .build();

        // Base (unscoped) policies must land before the scoped ones can compile.
        List<JsonNode> policies = new ArrayList<>();
        for (String file : List.of("base_collection.json", "base_record.json",
                "derived_roles.json", "collection.json", "record.json")) {
            policies.add(mapper.readTree(Files.readString(goldenDir().resolve(file))));
        }
        String response = client.post().uri("/admin/policy")
                .headers(h -> h.setBasicAuth(ADMIN_USER, ADMIN_PASSWORD))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("policies", policies))
                .retrieve()
                .body(String.class);
        assertThat(response).as("Cerbos rejected the golden policies").contains("success");
    }

    @AfterAll
    static void stopCerbos() {
        if (cerbos != null) {
            cerbos.stop();
        }
    }

    // ----- collection policy (UUID-keyed, what the gateway checks) -----

    @Test
    @DisplayName("MODIFY_ALL_DATA + VIEW_ALL_DATA grant full CRUD on every collection")
    void adminHasFullCrudEverywhere() {
        assertThat(check("collection", "admin-profile", TENANT, "col-a")).isEqualTo("AAAA");
        assertThat(check("collection", "admin-profile", TENANT, "col-c")).isEqualTo("AAAA");
    }

    @Test
    @DisplayName("object permissions grant exactly the configured actions per collection")
    void editorGetsExactlyItsObjectPermissions() {
        assertThat(check("collection", "editor-profile", TENANT, "col-a")).isEqualTo(".AA.");
        assertThat(check("collection", "editor-profile", TENANT, "col-b")).isEqualTo(".A..");
        assertThat(check("collection", "editor-profile", TENANT, "col-c")).isEqualTo("....");
    }

    @Test
    @DisplayName("VIEW_ALL_DATA alone grants read everywhere and nothing else")
    void viewerReadsEverywhereOnly() {
        assertThat(check("collection", "viewer-profile", TENANT, "col-c")).isEqualTo(".A..");
    }

    @Test
    @DisplayName("no grants, unknown profile, empty profile and foreign tenant are all denied")
    void denyPaths() {
        assertThat(check("collection", "nobody-profile", TENANT, "col-a")).isEqualTo("....");
        assertThat(check("collection", "unknown-profile", TENANT, "col-a")).isEqualTo("....");
        assertThat(check("collection", "", TENANT, "col-a")).isEqualTo("....");
        assertThat(check("collection", "editor-profile", "other-tenant", "col-a")).isEqualTo("....");
    }

    // ----- record policy (name-keyed, what the worker record advice checks) -----

    @Test
    @DisplayName("record policy keys on the collection NAME, not the UUID")
    void recordPolicyIsNameKeyed() {
        assertThat(check("record", "editor-profile", TENANT, "accounts")).isEqualTo(".AA.");
        assertThat(check("record", "editor-profile", TENANT, "col-a")).isEqualTo("....");
    }

    @Test
    @DisplayName("custom ABAC deny rule overrides the CRUD grant for the matching profile only")
    void customAbacDenyApplies() {
        assertThat(check("record", "editor-profile", TENANT, "accounts", Map.of("status", "open")))
                .isEqualTo(".AA.");
        assertThat(check("record", "editor-profile", TENANT, "accounts", Map.of("status", "locked")))
                .isEqualTo(".A..");
        assertThat(check("record", "admin-profile", TENANT, "accounts", Map.of("status", "locked")))
                .isEqualTo("AAAA");
    }

    // ----- helpers -----

    private static String check(String kind, String profileId, String tenantId, String collectionId) {
        return check(kind, profileId, tenantId, collectionId, Map.of());
    }

    /**
     * Returns a 4-char string over {@code create,read,edit,delete}: {@code A} for allow,
     * {@code .} for deny.
     */
    private static String check(String kind, String profileId, String tenantId, String collectionId,
                                Map<String, Object> extraResourceAttrs) {
        Map<String, Object> resourceAttrs = new LinkedHashMap<>();
        resourceAttrs.put("collectionId", collectionId);
        resourceAttrs.putAll(extraResourceAttrs);

        Map<String, Object> request = Map.of(
                "principal", Map.of(
                        "id", "user@example.test",
                        "roles", List.of("user"),
                        "scope", TENANT,
                        "attr", Map.of("profileId", profileId, "tenantId", tenantId)),
                "resources", List.of(Map.of(
                        "actions", CRUD,
                        "resource", Map.of(
                                "kind", kind,
                                "id", "resource-1",
                                "scope", TENANT,
                                "attr", resourceAttrs))));

        JsonNode response = client.post().uri("/api/check/resources")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        JsonNode actions = response.path("results").path(0).path("actions");
        StringBuilder sb = new StringBuilder();
        for (String action : CRUD) {
            sb.append("EFFECT_ALLOW".equals(actions.path(action).asText()) ? 'A' : '.');
        }
        return sb.toString();
    }

    private static Path goldenDir() {
        try (var in = CerbosGeneratedPolicyIT.class.getResourceAsStream("/harness.properties")) {
            if (in == null) throw new IllegalStateException("harness.properties not found on classpath");
            Properties props = new Properties();
            props.load(in);
            return Path.of(props.getProperty("harness.basedir")).getParent()
                    .resolve("kelta-worker/src/test/resources/cerbos/golden");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load harness.properties", e);
        }
    }
}
