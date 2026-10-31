package io.kelta.testharness.scenarios;

import io.kelta.testharness.KeltaStack;
import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An external module installed by URL (module-platform part 9c,
 * {@code specs/module-platform/9-external-modules.md} §3.5), against a WireMock stub that serves
 * a v2 manifest and answers the lifecycle call.
 *
 * <p>The journey: {@code POST /api/modules/plan} fetches the manifest through the module-host
 * guard (the stub is allow-listed for module traffic only) → {@code POST /api/modules/install}
 * with the reviewed {@code planHash} provisions the module's collection, creates its service
 * principal, and sends {@code lifecycle installed} signed with the platform key and carrying the
 * principal's {@code Kelta-Access-Token} → the token reads what the module was granted and is
 * refused what it was not → the assertion verifies against the JWKS fetched anonymously through
 * the gateway → the uninstall plan and {@code DELETE} remove the collection and deactivate the
 * principal.
 *
 * <p>As in {@code ModuleServicePrincipalScenarioTest}, the stack's Cerbos is allow-all and its
 * gateway runs with {@code PERMISSIONS_ENABLED=false}: an ungranted collection is not provably
 * unreadable here. The refusal shown is the profile's own system permission
 * ({@code VIEW_SETUP}, not requested by the manifest), plus the generated profile carrying no
 * object grant beyond the manifest's; a real PDP's reading of that profile shape is
 * {@code CerbosGeneratedPolicyIT.moduleServicePrincipalReadsOnlyItsGrant}.
 */
@DisplayName("External module install scenario")
class ExternalModuleInstallScenarioTest extends ScenarioBase {

    private static final String SLUG = TenantFixture.ECOMMERCE_SLUG;
    private static final String BASE_URL = "http://" + KeltaStack.STUB_MODULE_HOST + ":8080";
    private static final String GRANTED = "products";

    private static final GenericContainer<?> STUB_MODULE =
            new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.9.1"))
                    .withNetwork(KeltaStack.NETWORK)
                    .withNetworkAliases(KeltaStack.STUB_MODULE_HOST)
                    .withExposedPorts(8080)
                    .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));

    private final ObjectMapper mapper = new ObjectMapper();
    private String adminToken;
    private String tenantId;

    @BeforeAll
    static void startStub() {
        STUB_MODULE.start();
    }

    @AfterAll
    static void stopStub() {
        STUB_MODULE.stop();
    }

    @BeforeEach
    void setUp() {
        adminToken = auth.loginAsAdmin(SLUG);
        tenantId = auth.extractTenantId(adminToken);
        stub().post().uri("/__admin/reset").retrieve().toBodilessEntity();
    }

    @Test
    @DisplayName("plan → install with planHash → its token reads its collections, is refused the rest → uninstall")
    void installUseAndUninstall() throws Exception {
        String suffix = Long.toHexString(System.nanoTime());
        String moduleId = "harness-stub-" + suffix;
        String owned = "harness_stub_items_" + suffix;
        serveManifest(manifest(moduleId, owned));

        // Plan: writes nothing, names the grant and the collection the module will own.
        JsonNode plan = admin("POST", "/" + SLUG + "/api/modules/plan", "{\"baseUrl\":\"" + BASE_URL + "\"}",
                HttpStatus.OK).get("data").get("attributes");
        assertThat(plan.get("mode").stringValue()).isEqualTo("INSTALL");
        assertThat(plan.get("blockers")).as("blockers: %s", plan.get("blockers")).isEmpty();
        assertThat(plan.get("grants").get("ownedCollections").get(0).stringValue()).isEqualTo(owned);
        assertThat(queryCount("SELECT count(*) FROM collection WHERE tenant_id = ? AND name = ?", tenantId, owned))
                .as("a plan writes nothing").isZero();

        // Install with the reviewed hash.
        JsonNode installed = admin("POST", "/" + SLUG + "/api/modules/install",
                "{\"baseUrl\":\"" + BASE_URL + "\",\"planHash\":\"" + plan.get("planHash").stringValue() + "\"}",
                HttpStatus.OK).get("data").get("attributes");
        assertThat(installed.get("status").stringValue()).as("install: %s", installed).isEqualTo("ACTIVE");
        assertThat(installed.get("baseUrl").stringValue()).isEqualTo(BASE_URL);
        assertThat(queryCount("SELECT count(*) FROM collection WHERE tenant_id = ? AND name = ? AND active = true",
                tenantId, owned)).as("the module's collection exists").isEqualTo(1);
        String serviceUserId = queryString("SELECT service_user_id FROM tenant_module "
                + "WHERE tenant_id = ? AND module_id = ?", tenantId, moduleId);
        assertThat(queryString("SELECT user_type FROM platform_user WHERE id = ?", serviceUserId))
                .isEqualTo("SERVICE");

        // The lifecycle call reached the module, signed, with the principal's token.
        JsonNode lifecycle = lifecycleRequests().getFirst();
        String accessToken = header(lifecycle, "Kelta-Access-Token");
        String assertion = header(lifecycle, "Authorization").substring("Bearer ".length());
        byte[] body = Base64.getDecoder().decode(lifecycle.get("bodyAsBase64").stringValue());
        assertThat(mapper.readTree(body).get("event").stringValue()).isEqualTo("installed");
        verifyAssertion(assertion, moduleId, body);

        // The token reads the granted collection and the one the module owns...
        waitForStatus(gatewayClientWithToken(accessToken), "/" + SLUG + "/api/" + owned, HttpStatus.OK, 20);
        assertThat(get(accessToken, "/" + SLUG + "/api/" + GRANTED).getStatusCode()).isEqualTo(HttpStatus.OK);
        // ...and is refused what the manifest did not request.
        assertThat(get(accessToken, "/" + SLUG + "/api/governor-limits").getStatusCode())
                .as("VIEW_SETUP was not requested").isEqualTo(HttpStatus.FORBIDDEN);
        String profileId = queryString("SELECT profile_id FROM platform_user WHERE id = ?", serviceUserId);
        assertThat(queryStrings("SELECT c.name FROM profile_object_permission p JOIN collection c "
                + "ON c.id = p.collection_id WHERE p.profile_id = ?", profileId))
                .as("object grants are exactly the manifest's plus the owned collection")
                .containsExactlyInAnyOrder(GRANTED, owned);

        // Uninstall: the plan removes the empty collection; the apply needs its hash.
        JsonNode uninstallPlan = admin("GET", "/" + SLUG + "/api/modules/" + moduleId + "/uninstall-plan?purgeData=true",
                null, HttpStatus.OK).get("data").get("attributes");
        List<String> decisions = new ArrayList<>();
        uninstallPlan.get("entries").forEach(e -> decisions.add(
                e.get("resourceType").stringValue() + ":" + e.get("decision").stringValue()));
        assertThat(decisions).as("uninstall plan").contains("COLLECTION:REMOVE");
        JsonNode stale = admin("DELETE", "/" + SLUG + "/api/modules/" + moduleId + "?purgeData=true&planHash=stale",
                null, HttpStatus.CONFLICT);
        assertThat(stale.get("errors").get(0).get("code").stringValue()).isEqualTo("PlanChanged");

        JsonNode removed = admin("DELETE", "/" + SLUG + "/api/modules/" + moduleId + "?purgeData=true&planHash="
                + uninstallPlan.get("planHash").stringValue(), null, HttpStatus.OK).get("data").get("attributes");
        assertThat(removed.get("failed")).as("uninstall: %s", removed).isEmpty();
        assertThat(queryCount("SELECT count(*) FROM collection WHERE tenant_id = ? AND name = ? AND active = true",
                tenantId, owned)).as("the module's collection is gone").isZero();
        assertThat(queryCount("SELECT count(*) FROM tenant_module WHERE tenant_id = ? AND module_id = ?", tenantId, moduleId))
                .isZero();
        assertThat(queryString("SELECT status FROM platform_user WHERE id = ?", serviceUserId)).isEqualTo("INACTIVE");
        assertThat(lifecycleRequests()).extracting(r -> eventOf(r)).contains("uninstalled");
    }

    @Test
    @DisplayName("a manifest that changed since the plan is 409 PlanChanged with the fresh plan; nothing installed")
    void staleHashIsRefused() throws Exception {
        String suffix = Long.toHexString(System.nanoTime());
        String moduleId = "harness-stub-" + suffix;
        serveManifest(manifest(moduleId, "harness_stub_items_" + suffix));
        String hash = admin("POST", "/" + SLUG + "/api/modules/plan", "{\"baseUrl\":\"" + BASE_URL + "\"}",
                HttpStatus.OK).get("data").get("attributes").get("planHash").stringValue();

        ObjectNode changed = manifest(moduleId, "harness_stub_items_" + suffix);
        ((ObjectNode) changed.get("permissions")).putArray("system").add("VIEW_SETUP");
        serveManifest(changed);

        JsonNode refused = admin("POST", "/" + SLUG + "/api/modules/install",
                "{\"baseUrl\":\"" + BASE_URL + "\",\"planHash\":\"" + hash + "\"}", HttpStatus.CONFLICT);
        assertThat(refused.get("errors").get(0).get("code").stringValue()).isEqualTo("PlanChanged");
        assertThat(refused.get("meta").get("plan").get("attributes").get("planHash").stringValue()).isNotEqualTo(hash);
        assertThat(queryCount("SELECT count(*) FROM tenant_module WHERE tenant_id = ? AND module_id = ?", tenantId, moduleId))
                .isZero();
        assertThat(lifecycleRequests()).isEmpty();
    }

    // ── the stub module ──────────────────────────────────────────────────────────────────

    private RestClient stub() {
        return RestClient.builder()
                .baseUrl("http://" + STUB_MODULE.getHost() + ":" + STUB_MODULE.getMappedPort(8080)).build();
    }

    private ObjectNode manifest(String moduleId, String collection) {
        return (ObjectNode) mapper.readTree("""
                {
                  "manifestVersion": 2, "protocolVersion": 1,
                  "id": "%1$s", "name": "Harness stub", "version": "1.0.0",
                  "description": "A WireMock-served external module",
                  "permissions": { "objects": [ { "collection": "%2$s", "read": true } ] },
                  "metadata": { "formatVersion": 2, "items": [
                    { "type": "COLLECTION", "data": { "name": "%3$s", "display_name": "Stub items",
                                                      "current_version": 1, "active": true } },
                    { "type": "FIELD", "data": { "collection_name": "%3$s", "name": "code", "display_name": "Code",
                                                 "type": "STRING" } }
                  ] },
                  "actions": [ { "key": "ping", "name": "Ping" } ]
                }
                """.formatted(moduleId, GRANTED, collection));
    }

    private void serveManifest(JsonNode manifest) {
        stub().post().uri("/__admin/reset").retrieve().toBodilessEntity();
        ObjectNode mapping = mapper.createObjectNode();
        mapping.putObject("request").put("method", "GET").put("url", "/.well-known/kelta-module.json");
        ObjectNode response = mapping.putObject("response");
        response.put("status", 200).put("body", manifest.toString());
        response.putObject("headers").put("Content-Type", "application/json");
        stub().post().uri("/__admin/mappings").contentType(MediaType.APPLICATION_JSON)
                .body(mapping.toString())
                .retrieve().toBodilessEntity();
        stub().post().uri("/__admin/mappings").contentType(MediaType.APPLICATION_JSON)
                .body("{\"request\":{\"method\":\"POST\",\"url\":\"/kelta/v1/lifecycle\"},"
                        + "\"response\":{\"status\":204}}")
                .retrieve().toBodilessEntity();
    }

    /** The lifecycle calls the stub received, oldest first. */
    private List<JsonNode> lifecycleRequests() {
        String found = stub().post().uri("/__admin/requests/find").contentType(MediaType.APPLICATION_JSON)
                .body("{\"method\":\"POST\",\"url\":\"/kelta/v1/lifecycle\"}")
                .retrieve().body(String.class);
        List<JsonNode> requests = new ArrayList<>();
        mapper.readTree(found).get("requests").forEach(requests::add);
        requests.sort((a, b) -> Long.compare(a.get("loggedDate").asLong(), b.get("loggedDate").asLong()));
        return requests;
    }

    private String eventOf(JsonNode request) {
        byte[] body = Base64.getDecoder().decode(request.get("bodyAsBase64").stringValue());
        return mapper.readTree(body).get("event").stringValue();
    }

    private static String header(JsonNode request, String name) {
        for (var entry : request.get("headers").properties()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                JsonNode value = entry.getValue();
                return value.isArray() ? value.get(0).stringValue() : value.stringValue();
            }
        }
        throw new AssertionError("no " + name + " header on " + request.get("headers"));
    }

    // ── the platform assertion (spec §3.2) ───────────────────────────────────────────────

    /** Verifies the ES256 assertion against the JWKS the gateway serves to anyone. */
    private void verifyAssertion(String jwt, String moduleId, byte[] body) throws Exception {
        String[] parts = jwt.split("\\.");
        JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.get("alg").stringValue()).isEqualTo("ES256");

        ResponseEntity<String> jwks = gatewayClient().get().uri("/api/modules/.well-known/jwks.json")
                .retrieve().onStatus(s -> true, (req, resp) -> {}).toEntity(String.class);
        assertThat(jwks.getStatusCode()).as("anonymous JWKS: %s", jwks.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode key = null;
        for (JsonNode candidate : mapper.readTree(jwks.getBody()).get("keys")) {
            assertThat(candidate.has("d")).as("the JWKS never carries a private key").isFalse();
            if (header.get("kid").stringValue().equals(candidate.get("kid").stringValue())) {
                key = candidate;
            }
        }
        assertThat(key).as("kid %s is published", header.get("kid")).isNotNull();

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(publicKey(key));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).as("signature").isTrue();

        assertThat(claims.get("iss").stringValue()).isEqualTo("http://kelta-gateway:8080/api/modules");
        JsonNode aud = claims.get("aud");
        assertThat(aud.isArray() ? aud.get(0).stringValue() : aud.stringValue()).isEqualTo(moduleId);
        assertThat(claims.get("tenant_id").stringValue()).isEqualTo(tenantId);
        assertThat(claims.get("tenant_slug").stringValue()).isEqualTo(SLUG);
        assertThat(claims.get("htm").stringValue()).isEqualTo("POST");
        assertThat(claims.get("htu").stringValue()).isEqualTo(BASE_URL + "/kelta/v1/lifecycle");
        assertThat(claims.get("body_sha256").stringValue()).isEqualTo(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(body)));
        long iat = claims.get("iat").asLong();
        assertThat(claims.get("exp").asLong() - iat).isEqualTo(60);
        assertThat(Math.abs(Instant.now().getEpochSecond() - iat)).isLessThan(600);
    }

    private static ECPublicKey publicKey(JsonNode jwk) throws Exception {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECPoint point = new ECPoint(
                new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("x").stringValue())),
                new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("y").stringValue())));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(point, parameters.getParameterSpec(ECParameterSpec.class)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private JsonNode admin(String method, String uri, String body, HttpStatus expected) {
        RestClient client = gatewayClientWithToken(adminToken);
        RestClient.RequestHeadersSpec<?> spec = switch (method) {
            case "POST" -> client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body);
            case "DELETE" -> client.delete().uri(uri);
            default -> client.get().uri(uri);
        };
        ResponseEntity<String> response = spec.retrieve().onStatus(s -> true, (req, resp) -> {})
                .toEntity(String.class);
        assertThat(response.getStatusCode()).as("%s %s: %s", method, uri, response.getBody()).isEqualTo(expected);
        return mapper.readTree(response.getBody());
    }

    private ResponseEntity<String> get(String token, String uri) {
        return gatewayClientWithToken(token).get().uri(uri).retrieve().onStatus(s -> true, (req, resp) -> {})
                .toEntity(String.class);
    }

    private long queryCount(String sql, Object... args) throws Exception {
        return Long.parseLong(queryString(sql, args));
    }

    private String queryString(String sql, Object... args) throws Exception {
        List<String> values = queryStrings(sql, args);
        assertThat(values).as("no row for %s", sql).isNotEmpty();
        return values.getFirst();
    }

    private List<String> queryStrings(String sql, Object... args) throws Exception {
        try (Connection db = openDbConnection(); PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<String> values = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
            return values;
        }
    }
}
