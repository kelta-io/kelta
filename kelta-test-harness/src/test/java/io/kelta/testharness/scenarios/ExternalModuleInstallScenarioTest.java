package io.kelta.testharness.scenarios;

import io.kelta.testharness.KeltaStack;
import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.AuthFixture;
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
import tools.jackson.databind.node.ArrayNode;
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
import java.util.HexFormat;
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
 *
 * <p>{@link #invokesTheModule()} covers the invocation layer (part 9d): a flow Task calls the
 * module's action, its before-save hook vetoes one write and stamps a field on another, its route
 * and webhook are proxied, its entitlements port answers the watch quota, and its schedule fires
 * through the worker scheduler. Disabling the module unregisters the hook and the route and fails
 * the flow step with {@code ModuleUnavailableException}.
 *
 * <p>{@link #deliversEventsAndPinsTheUiBundle()} covers part 9e: record changes on a subscribed
 * collection are POSTed to the module's {@code /kelta/v1/events}, a failed delivery is retried
 * with the same {@code eventId}, and one that fails every attempt lands in
 * {@code module_event_dead_letter}. The module's UI bundle is fetched and hashed at plan time,
 * pinned in the object store at install, and served from Kelta's copy (never the module's live
 * file); an upgrade that ships a different bundle says so in its plan and re-pins it.
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

    @Test
    @DisplayName("install → flow action → hook veto + field update → route → webhook → entitlements → schedule → disable")
    void invokesTheModule() throws Exception {
        String suffix = Long.toHexString(System.nanoTime());
        String moduleId = "harness-stub-" + suffix;
        String owned = "harness_stub_items_" + suffix;
        serveManifest(invocationManifest(manifest(moduleId, owned), owned));
        serveInvocationStubs();

        String hash = admin("POST", "/" + SLUG + "/api/modules/plan", "{\"baseUrl\":\"" + BASE_URL + "\"}",
                HttpStatus.OK).get("data").get("attributes").get("planHash").stringValue();
        JsonNode installed = admin("POST", "/" + SLUG + "/api/modules/install",
                "{\"baseUrl\":\"" + BASE_URL + "\",\"planHash\":\"" + hash + "\"}", HttpStatus.OK)
                .get("data").get("attributes");
        assertThat(installed.get("status").stringValue()).as("install: %s", installed).isEqualTo("ACTIVE");
        String installId = queryString("SELECT id FROM tenant_module WHERE tenant_id = ? AND module_id = ?",
                tenantId, moduleId);

        // A flow Task whose Resource is the module's action key calls POST /kelta/v1/actions/ping.
        String flowId = createFlow(moduleId + ":ping", suffix);
        String executionId = executeFlow(flowId);
        JsonNode execution = awaitExecution(executionId);
        assertThat(execution.get("status").stringValue()).as("execution: %s", execution).isEqualTo("COMPLETED");
        assertThat(execution.get("stateData").get("ping").get("pong").asBoolean()).as("execution: %s", execution)
                .isTrue();
        JsonNode actionCall = requests("POST", "/kelta/v1/actions/ping").stream()
                .filter(r -> header(r, "Idempotency-Key").equals(executionId + ":Ping"))
                .findFirst().orElseThrow(() -> new AssertionError("no action call for " + executionId));
        JsonNode actionBody = mapper.readTree(Base64.getDecoder().decode(actionCall.get("bodyAsBase64").stringValue()));
        assertThat(actionBody.get("input").get("greeting").stringValue()).isEqualTo("hi");
        assertThat(actionBody.get("execution").get("flowId").stringValue()).isEqualTo(flowId);
        assertThat(header(actionCall, "Authorization")).startsWith("Bearer ");

        // The before-save hook stamps a field on one write and vetoes another.
        waitForStatus(gatewayClientWithToken(adminToken), "/" + SLUG + "/api/" + owned, HttpStatus.OK, 20);
        ResponseEntity<String> stamped = createItem(owned, "OK1");
        assertThat(stamped.getStatusCode()).as("create: %s", stamped.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(mapper.readTree(stamped.getBody()).get("data").get("attributes").get("status").stringValue())
                .as("the module's fieldUpdates were applied").isEqualTo("STAMPED");
        ResponseEntity<String> vetoed = createItem(owned, "BLOCK");
        assertThat(vetoed.getStatusCode()).as("veto: %s", vetoed.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(vetoed.getBody()).contains("blocked by stub");
        List<String> codes = new ArrayList<>();
        mapper.readTree(get(adminToken, "/" + SLUG + "/api/" + owned).getBody()).get("data")
                .forEach(item -> codes.add(item.get("attributes").get("code").stringValue()));
        assertThat(codes).as("the vetoed record was not saved").containsExactly("OK1");

        // The route proxy forwards a declared route with its query, signed for the calling user.
        ResponseEntity<String> routed = get(adminToken, "/" + SLUG + "/api/modules/" + moduleId + "/x/report?page=2");
        assertThat(routed.getStatusCode()).as("route: %s", routed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(routed.getBody()).get("rows").asInt()).isEqualTo(3);
        JsonNode routeCall = requests("GET", "/kelta/v1/routes/report").getFirst();
        assertThat(routeCall.get("url").stringValue()).isEqualTo("/kelta/v1/routes/report?page=2");
        JsonNode routeClaims = claims(header(routeCall, "Authorization"));
        assertThat(routeClaims.get("htu").stringValue()).isEqualTo(BASE_URL + "/kelta/v1/routes/report?page=2");
        assertThat(routeClaims.get("act").get("sub").stringValue()).isEqualTo(queryString(
                "SELECT id FROM platform_user WHERE tenant_id = ? AND email = ?", tenantId,
                AuthFixture.adminUsername(SLUG)));
        assertThat(get(adminToken, "/" + SLUG + "/api/modules/" + moduleId + "/x/undeclared").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The webhook proxy forwards the raw bytes and only the allowlisted headers.
        byte[] payload = "{\"type\":\"invoice.paid\"}".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> hooked = gatewayClient().post()
                .uri("/api/modules/webhooks/" + tenantId + "/" + moduleId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1,v1=stub")
                .header("Cookie", "session=leak")
                .body(payload)
                .retrieve().onStatus(st -> true, (req, resp) -> {}).toEntity(String.class);
        assertThat(hooked.getStatusCode()).as("webhook: %s", hooked.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode webhookCall = requests("POST", "/kelta/v1/webhooks").getFirst();
        assertThat(Base64.getDecoder().decode(webhookCall.get("bodyAsBase64").stringValue())).isEqualTo(payload);
        assertThat(header(webhookCall, "stripe-signature")).isEqualTo("t=1,v1=stub");
        assertThat(hasHeader(webhookCall, "Cookie")).as("cookies are never forwarded").isFalse();

        // The entitlements port answers the watch quota: maxActiveWatches 0 refuses a new watch.
        ResponseEntity<String> watch = gatewayClientWithToken(adminToken).post().uri("/" + SLUG + "/api/watches")
                .contentType(MediaType.APPLICATION_JSON).body("{}")
                .retrieve().onStatus(st -> true, (req, resp) -> {}).toEntity(String.class);
        assertThat(watch.getStatusCode().value()).as("watch: %s", watch.getBody()).isEqualTo(422);
        assertThat(watch.getBody()).contains("plan limit (0/0)");
        assertThat(requests("POST", "/kelta/v1/services/entitlements/resolve")).isNotEmpty();

        // The manifest schedule is a MODULE_ACTION job the worker scheduler runs.
        String jobId = queryString("SELECT id FROM scheduled_job WHERE tenant_id = ? AND job_reference_id = ? "
                + "AND job_type = 'MODULE_ACTION'", tenantId, installId);
        awaitScheduledCall(jobId);

        // Disabled: the hook is gone (BLOCK saves), the route is gone, and the action refuses.
        admin("POST", "/" + SLUG + "/api/modules/" + moduleId + "/disable", "{}", HttpStatus.OK);
        assertThat(createItem(owned, "BLOCK").getStatusCode()).as("no hook while disabled")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(get(adminToken, "/" + SLUG + "/api/modules/" + moduleId + "/x/report").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode refused = awaitExecution(executeFlow(flowId));
        assertThat(refused.get("stateData").has("error")).as("execution: %s", refused).isTrue();
        assertThat(refused.get("stateData").get("error").get("Error").stringValue())
                .as("the flow error code a Catch matches").isEqualTo("ModuleUnavailableException");

        // Uninstall removes the module's jobs with it.
        JsonNode uninstallPlan = admin("GET", "/" + SLUG + "/api/modules/" + moduleId
                + "/uninstall-plan?purgeData=true", null, HttpStatus.OK).get("data").get("attributes");
        JsonNode removed = admin("DELETE", "/" + SLUG + "/api/modules/" + moduleId + "?purgeData=true&planHash="
                + uninstallPlan.get("planHash").stringValue(), null, HttpStatus.OK).get("data").get("attributes");
        assertThat(removed.get("failed")).as("uninstall: %s", removed).isEmpty();
        assertThat(queryCount("SELECT count(*) FROM scheduled_job WHERE job_reference_id = ?", installId)).isZero();
    }

    @Test
    @DisplayName("record events delivered, retried and dead-lettered; UI bundle pinned at install and re-pinned on upgrade")
    void deliversEventsAndPinsTheUiBundle() throws Exception {
        String suffix = Long.toHexString(System.nanoTime());
        String moduleId = "harness-stub-" + suffix;
        String owned = "harness_stub_items_" + suffix;
        byte[] bundleV1 = ("/* harness bundle v1 " + suffix + " */ export {}").getBytes(StandardCharsets.UTF_8);
        serveManifest(eventManifest(manifest(moduleId, owned), owned, bundleV1));
        serveEventStubs(bundleV1);

        // The plan fetched the bundle and shows the hash the admin approves.
        JsonNode plan = admin("POST", "/" + SLUG + "/api/modules/plan", "{\"baseUrl\":\"" + BASE_URL + "\"}",
                HttpStatus.OK).get("data").get("attributes");
        assertThat(plan.get("blockers")).as("blockers: %s", plan.get("blockers")).isEmpty();
        assertThat(plan.get("uiBundle").get("sha256").stringValue()).isEqualTo(sha256Hex(bundleV1));
        assertThat(plan.get("uiBundle").get("fetchedSha256").stringValue()).isEqualTo(sha256Hex(bundleV1));
        assertThat(plan.get("capabilities").get("events").get(0).get("collection").stringValue()).isEqualTo(owned);

        JsonNode installed = admin("POST", "/" + SLUG + "/api/modules/install",
                "{\"baseUrl\":\"" + BASE_URL + "\",\"planHash\":\"" + plan.get("planHash").stringValue() + "\"}",
                HttpStatus.OK).get("data").get("attributes");
        assertThat(installed.get("status").stringValue()).as("install: %s", installed).isEqualTo("ACTIVE");
        String installId = queryString("SELECT id FROM tenant_module WHERE tenant_id = ? AND module_id = ?",
                tenantId, moduleId);
        assertThat(queryString("SELECT ui_bundle_sha256 FROM tenant_module WHERE id = ?", installId))
                .isEqualTo(sha256Hex(bundleV1));

        // Kelta serves its pinned copy: the module swapping its live file changes nothing.
        stubBody("GET", "/ui/bundle.js", 1, "text/javascript", "/* swapped after approval */");
        int bundleFetches = requests("GET", "/ui/bundle.js").size();
        ResponseEntity<String> served = get(adminToken, "/" + SLUG + "/api/modules/" + moduleId + "/ui-bundle.js");
        assertThat(served.getStatusCode()).as("bundle: %s", served.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(served.getBody()).isEqualTo(new String(bundleV1, StandardCharsets.UTF_8));
        assertThat(served.getHeaders().getETag()).isEqualTo("\"" + sha256Hex(bundleV1) + "\"");
        assertThat(requests("GET", "/ui/bundle.js")).as("serving never fetches from the module")
                .hasSize(bundleFetches);

        // Delivered: one signed POST carrying the record as the module may see it.
        waitForStatus(gatewayClientWithToken(adminToken), "/" + SLUG + "/api/" + owned, HttpStatus.OK, 20);
        assertThat(createItem(owned, "OK1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode delivered = awaitEvents("OK1", 1).getFirst();
        JsonNode event = body(delivered);
        assertThat(event.get("type").stringValue()).isEqualTo("created");
        assertThat(event.get("collection").stringValue()).isEqualTo(owned);
        assertThat(event.get("tenantId").stringValue()).isEqualTo(tenantId);
        assertThat(event.get("eventId").stringValue()).isNotBlank();
        assertThat(claims(header(delivered, "Authorization")).get("htu").stringValue())
                .isEqualTo(BASE_URL + "/kelta/v1/events");

        // A 500 is retried with the same eventId, and the second attempt is taken.
        assertThat(createItem(owned, "RETRY").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<JsonNode> retried = awaitEvents("RETRY", 2);
        assertThat(body(retried.get(1)).get("eventId").stringValue())
                .isEqualTo(body(retried.get(0)).get("eventId").stringValue());

        // Failing every attempt (backoff 1s,1s: three attempts) dead-letters it with what was sent.
        assertThat(createItem(owned, "DEAD").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<JsonNode> dead = awaitEvents("DEAD", 3);
        String deadEventId = body(dead.getFirst()).get("eventId").stringValue();
        awaitCount("SELECT count(*) FROM module_event_dead_letter WHERE tenant_module_id = ? AND event_id = ?",
                installId, deadEventId);
        assertThat(queryString("SELECT attempts || ':' || last_status FROM module_event_dead_letter "
                + "WHERE tenant_module_id = ? AND event_id = ?", installId, deadEventId)).isEqualTo("3:500");
        assertThat(queryString("SELECT payload->'data'->>'code' FROM module_event_dead_letter "
                + "WHERE tenant_module_id = ? AND event_id = ?", installId, deadEventId)).isEqualTo("DEAD");
        assertThat(queryCount("SELECT count(*) FROM module_event_dead_letter WHERE tenant_module_id = ?", installId))
                .as("only the event that never got through").isEqualTo(1);

        // An upgrade that ships a different bundle says so in its plan, then re-pins it.
        byte[] bundleV2 = ("/* harness bundle v2 " + suffix + " */ export {}").getBytes(StandardCharsets.UTF_8);
        ObjectNode v2 = eventManifest(manifest(moduleId, owned), owned, bundleV2);
        v2.put("version", "1.1.0");
        serveManifest(v2);
        serveEventStubs(bundleV2);
        JsonNode upgradePlan = admin("POST", "/" + SLUG + "/api/modules/plan", "{\"baseUrl\":\"" + BASE_URL + "\"}",
                HttpStatus.OK).get("data").get("attributes");
        assertThat(upgradePlan.get("mode").stringValue()).isEqualTo("UPGRADE");
        JsonNode uiChange = upgradePlan.get("uiBundle");
        assertThat(uiChange.get("changed").asBoolean()).as("plan: %s", uiChange).isTrue();
        assertThat(uiChange.get("installedSha256").stringValue()).isEqualTo(sha256Hex(bundleV1));
        assertThat(uiChange.get("sha256").stringValue()).isEqualTo(sha256Hex(bundleV2));
        admin("POST", "/" + SLUG + "/api/modules/" + moduleId + "/upgrade",
                "{\"planHash\":\"" + upgradePlan.get("planHash").stringValue() + "\"}", HttpStatus.OK);
        assertThat(get(adminToken, "/" + SLUG + "/api/modules/" + moduleId + "/ui-bundle.js").getBody())
                .isEqualTo(new String(bundleV2, StandardCharsets.UTF_8));

        JsonNode uninstallPlan = admin("GET", "/" + SLUG + "/api/modules/" + moduleId
                + "/uninstall-plan?purgeData=true", null, HttpStatus.OK).get("data").get("attributes");
        admin("DELETE", "/" + SLUG + "/api/modules/" + moduleId + "?purgeData=true&planHash="
                + uninstallPlan.get("planHash").stringValue(), null, HttpStatus.OK);
        assertThat(queryCount("SELECT count(*) FROM module_event_dead_letter WHERE tenant_module_id = ?", installId))
                .as("dead letters go with their install").isZero();
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
        return requests("POST", "/kelta/v1/lifecycle");
    }

    /** The calls the stub received for {@code method} and {@code urlPath} (query ignored), oldest first. */
    private List<JsonNode> requests(String method, String urlPath) {
        ObjectNode pattern = mapper.createObjectNode().put("method", method).put("urlPath", urlPath);
        String found = stub().post().uri("/__admin/requests/find").contentType(MediaType.APPLICATION_JSON)
                .body(pattern.toString())
                .retrieve().body(String.class);
        List<JsonNode> requests = new ArrayList<>();
        mapper.readTree(found).get("requests").forEach(requests::add);
        requests.sort((a, b) -> Long.compare(a.get("loggedDate").asLong(), b.get("loggedDate").asLong()));
        return requests;
    }

    /**
     * {@link #manifest} plus everything the invocation layer serves: a {@code status} field for the
     * hook to stamp, the hook itself, the entitlements port (uncached, so every lookup reaches the
     * stub), a route, the webhook, and a schedule firing every second.
     */
    private ObjectNode invocationManifest(ObjectNode manifest, String owned) {
        ObjectNode field = mapper.createObjectNode();
        field.put("type", "FIELD").putObject("data").put("collection_name", owned).put("name", "status")
                .put("display_name", "Status").put("type", "STRING");
        ((ArrayNode) manifest.get("metadata").get("items")).add(field);
        ((ObjectNode) manifest.get("actions").get(0)).put("timeoutMs", 5000);
        manifest.putArray("hooks").addObject().put("collection", owned).put("timeoutMs", 3000)
                .putArray("events").add("beforeCreate");
        manifest.putArray("services").addObject().put("port", "entitlements").put("cacheTtlSeconds", 0);
        manifest.putArray("routes").addObject().put("path", "report").putArray("methods").add("GET");
        manifest.putArray("schedules").addObject().put("action", "ping").put("cron", "* * * * * *");
        manifest.putObject("webhook").put("enabled", true);
        return manifest;
    }

    /** The module's protocol endpoints; {@link #serveManifest} must have run first (it resets the stub). */
    private void serveInvocationStubs() {
        stubJson("POST", "/kelta/v1/actions/ping", 5, null, 200, "{\"success\":true,\"output\":{\"pong\":true}}");
        stubJson("POST", "/kelta/v1/hooks", 1, "{\"matchesJsonPath\":{\"expression\":\"$.record.code\","
                + "\"equalTo\":\"BLOCK\"}}", 200, "{\"allowed\":false,\"errors\":[{\"field\":\"code\","
                + "\"message\":\"blocked by stub\",\"code\":\"STUB_BLOCK\"}]}");
        stubJson("POST", "/kelta/v1/hooks", 5, null, 200,
                "{\"allowed\":true,\"fieldUpdates\":{\"status\":\"STAMPED\"}}");
        stubJson("GET", "/kelta/v1/routes/report", 5, null, 200, "{\"rows\":3}");
        stubJson("POST", "/kelta/v1/webhooks", 5, null, 202, "");
        stubJson("POST", "/kelta/v1/services/entitlements/resolve", 5, null, 200,
                "{\"planCode\":\"free\",\"subscriptionStatus\":\"active\",\"values\":{\"maxActiveWatches\":0}}");
        stubJson("GET", "/kelta/v1/health", 5, null, 200, "{\"status\":\"ok\"}");
    }

    private void stubJson(String method, String urlPath, int priority, String bodyPattern, int status, String body) {
        ObjectNode mapping = mapper.createObjectNode().put("priority", priority);
        ObjectNode request = mapping.putObject("request").put("method", method).put("urlPath", urlPath);
        if (bodyPattern != null) {
            request.putArray("bodyPatterns").add(mapper.readTree(bodyPattern));
        }
        ObjectNode response = mapping.putObject("response").put("status", status).put("body", body);
        response.putObject("headers").put("Content-Type", "application/json");
        stub().post().uri("/__admin/mappings").contentType(MediaType.APPLICATION_JSON)
                .body(mapping.toString())
                .retrieve().toBodilessEntity();
    }

    /** {@link #manifest} subscribed to {@code owned}'s record events, shipping {@code bundle} as its UI. */
    private ObjectNode eventManifest(ObjectNode manifest, String owned, byte[] bundle) throws Exception {
        manifest.putArray("events").addObject().put("collection", owned)
                .putArray("types").add("created").add("updated");
        manifest.putObject("ui").put("bundlePath", "/ui/bundle.js").put("sha256", sha256Hex(bundle));
        return manifest;
    }

    /**
     * The UI bundle and the events endpoint; {@link #serveManifest} must have run first. A record
     * whose code is {@code RETRY} is refused once, {@code DEAD} always; anything else is taken.
     */
    private void serveEventStubs(byte[] bundle) {
        stubBody("GET", "/ui/bundle.js", 5, "text/javascript", new String(bundle, StandardCharsets.UTF_8));
        String retry = "{\"matchesJsonPath\":{\"expression\":\"$.data.code\",\"equalTo\":\"RETRY\"}}";
        stubEvent(1, retry, "Started", "refused-once", 500);
        stubEvent(2, retry, "refused-once", null, 204);
        stubEvent(1, "{\"matchesJsonPath\":{\"expression\":\"$.data.code\",\"equalTo\":\"DEAD\"}}",
                null, null, 500);
        stubEvent(5, null, null, null, 204);
    }

    private void stubEvent(int priority, String bodyPattern, String requiredState, String newState, int status) {
        ObjectNode mapping = mapper.createObjectNode().put("priority", priority);
        if (requiredState != null) {
            mapping.put("scenarioName", "retry").put("requiredScenarioState", requiredState);
            if (newState != null) {
                mapping.put("newScenarioState", newState);
            }
        }
        ObjectNode request = mapping.putObject("request").put("method", "POST").put("urlPath", "/kelta/v1/events");
        if (bodyPattern != null) {
            request.putArray("bodyPatterns").add(mapper.readTree(bodyPattern));
        }
        mapping.putObject("response").put("status", status);
        stub().post().uri("/__admin/mappings").contentType(MediaType.APPLICATION_JSON)
                .body(mapping.toString())
                .retrieve().toBodilessEntity();
    }

    private void stubBody(String method, String urlPath, int priority, String contentType, String body) {
        ObjectNode mapping = mapper.createObjectNode().put("priority", priority);
        mapping.putObject("request").put("method", method).put("urlPath", urlPath);
        ObjectNode response = mapping.putObject("response").put("status", 200).put("body", body);
        response.putObject("headers").put("Content-Type", contentType);
        stub().post().uri("/__admin/mappings").contentType(MediaType.APPLICATION_JSON)
                .body(mapping.toString())
                .retrieve().toBodilessEntity();
    }

    /** Waits until the events endpoint received {@code count} deliveries of the record coded {@code code}. */
    private List<JsonNode> awaitEvents(String code, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        List<JsonNode> matching = List.of();
        while (System.currentTimeMillis() < deadline) {
            matching = requests("POST", "/kelta/v1/events").stream()
                    .filter(r -> code.equals(body(r).path("data").path("code").asString()))
                    .toList();
            if (matching.size() >= count) {
                return matching;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("expected " + count + " deliveries of " + code + ", got " + matching.size());
    }

    private void awaitCount(String sql, Object... args) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (queryCount(sql, args) > 0) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("no row for " + sql);
    }

    private JsonNode body(JsonNode request) {
        return mapper.readTree(Base64.getDecoder().decode(request.get("bodyAsBase64").stringValue()));
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Waits for the scheduler to run job {@code jobId}: its action call carries {@code <jobId>:<fireTime>}. */
    private void awaitScheduledCall(String jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            boolean fired = requests("POST", "/kelta/v1/actions/ping").stream()
                    .anyMatch(r -> hasHeader(r, "Idempotency-Key")
                            && header(r, "Idempotency-Key").startsWith(jobId + ":"));
            if (fired) {
                return;
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError("scheduled job " + jobId + " never called the module");
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

    private static boolean hasHeader(JsonNode request, String name) {
        for (var entry : request.get("headers").properties()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** The claims of a {@code Bearer} platform assertion, unverified ({@link #verifyAssertion} checks one). */
    private JsonNode claims(String authorization) {
        String jwt = authorization.substring("Bearer ".length());
        return mapper.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
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

    /**
     * An AUTOLAUNCHED flow of one Task, {@code Ping}, whose Resource is {@code resource}; a
     * {@code ModuleUnavailableException} is caught into {@code $.error} and ends at {@code Unavailable}.
     */
    private String createFlow(String resource, String suffix) {
        ObjectNode payload = mapper.createObjectNode();
        ObjectNode attributes = payload.putObject("data").put("type", "flows").putObject("attributes");
        attributes.put("name", "module-invoke-harness-" + suffix).put("flowType", "AUTOLAUNCHED").put("active", true);
        ObjectNode definition = attributes.putObject("definition").put("StartAt", "Ping");
        ObjectNode states = definition.putObject("States");
        ObjectNode ping = states.putObject("Ping").put("Type", "Task").put("Resource", resource)
                .put("ResultPath", "$.ping").put("Next", "Done");
        ObjectNode caught = ping.putArray("Catch").addObject().put("ResultPath", "$.error").put("Next", "Unavailable");
        caught.putArray("ErrorEquals").add("ModuleUnavailableException");
        states.putObject("Done").put("Type", "Succeed");
        states.putObject("Unavailable").put("Type", "Succeed");
        ResponseEntity<String> created = gatewayClientWithToken(adminToken).post().uri("/" + SLUG + "/api/flows")
                .contentType(MediaType.APPLICATION_JSON).body(payload.toString())
                .retrieve().onStatus(st -> true, (req, resp) -> {}).toEntity(String.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("flow: %s", created.getBody()).isTrue();
        return mapper.readTree(created.getBody()).get("data").get("id").stringValue();
    }

    private String executeFlow(String flowId) {
        ResponseEntity<String> executed = gatewayClientWithToken(adminToken).post()
                .uri("/" + SLUG + "/api/flows/" + flowId + "/execute")
                .contentType(MediaType.APPLICATION_JSON).body("{\"input\":{\"greeting\":\"hi\"}}")
                .retrieve().onStatus(st -> true, (req, resp) -> {}).toEntity(String.class);
        assertThat(executed.getStatusCode().is2xxSuccessful()).as("execute: %s", executed.getBody()).isTrue();
        return mapper.readTree(executed.getBody()).get("data").get("id").stringValue();
    }

    /** Polls an execution until it is COMPLETED, FAILED or CANCELLED and returns its attributes. */
    private JsonNode awaitExecution(String executionId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode attributes = null;
        while (System.currentTimeMillis() < deadline) {
            attributes = mapper.readTree(get(adminToken, "/" + SLUG + "/api/flows/executions/" + executionId)
                    .getBody()).get("data").get("attributes");
            String status = attributes.get("status").stringValue();
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                return attributes;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("execution " + executionId + " never finished: " + attributes);
    }

    private ResponseEntity<String> createItem(String collection, String code) {
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("data").put("type", collection).putObject("attributes").put("code", code);
        return gatewayClientWithToken(adminToken).post().uri("/" + SLUG + "/api/" + collection)
                .contentType(MediaType.APPLICATION_JSON).body(payload.toString())
                .retrieve().onStatus(st -> true, (req, resp) -> {}).toEntity(String.class);
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
