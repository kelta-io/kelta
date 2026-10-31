package io.kelta.testharness.scenarios;

import com.github.dockerjava.api.exception.NotFoundException;
import io.kelta.testharness.KeltaStack;
import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.AuthFixture;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real {@code kelta-billing} external module (module-platform part 9f, the Rust service in
 * {@code kelta-modules/billing}) installed by URL into the ecommerce tenant, with a WireMock
 * container standing in for Stripe.
 *
 * <p>The journey: {@code POST /api/modules/plan} shows what the module asks for (its five
 * collections, the {@code stripe} credential, {@code VIEW_ALL_DATA}) → install with the reviewed
 * {@code planHash} → a flow Task runs {@code kelta-billing:list-plans} → the declared routes serve
 * {@code me} and start a checkout (the module reads the {@code stripe} credential over its
 * {@code Kelta-Access-Token} and calls the Stripe stub) → the wildcard before-save hook refuses a
 * create past the plan limit → a rule change reaches the module as a record event and invalidates
 * its rule cache → the platform's watch quota asks the module's entitlements port (free: refused)
 * → a Stripe-signed webhook through the platform proxy upgrades the member, and an unsigned one is
 * refused → the quota and {@code me} read the upgrade → uninstall removes the hook.
 *
 * <p>It also records the module-side latency of {@code POST /kelta/v1/services/entitlements/resolve}
 * (the {@code kelta_module::access} debug log, with the module's entitlement cache off so every
 * call reads the platform) and prints {@code GATE0 ...} lines — the part 9f Gate 0 numbers.
 *
 * <p>The image is {@code kelta-billing-harness}: CI's integration job builds it from
 * {@code kelta-modules/Dockerfile}; locally, {@code docker build -f kelta-modules/Dockerfile -t
 * kelta-billing-harness .} first, or the scenario is skipped (never in CI, where
 * {@code KELTA_HARNESS_PREBUILT_IMAGES=true} makes a missing image a failure).
 *
 * <p>As in {@code ExternalModuleInstallScenarioTest}, the stack's Cerbos is allow-all and its
 * gateway runs with {@code PERMISSIONS_ENABLED=false}, so the module principal reading an
 * arbitrary collection here does not prove what {@code VIEW_ALL_DATA} grants under a real PDP.
 */
@DisplayName("Billing module (Rust) scenario")
class BillingModuleScenarioTest extends ScenarioBase {

    private static final Logger log = LoggerFactory.getLogger(BillingModuleScenarioTest.class);

    private static final String SLUG = TenantFixture.ECOMMERCE_SLUG;
    private static final String MODULE_ID = "kelta-billing";
    private static final String IMAGE = "kelta-billing-harness";
    private static final String BASE_URL = "http://" + KeltaStack.BILLING_MODULE_HOST + ":8080";
    private static final String STRIPE_HOST = "stripe-stub";
    /** Placeholder secrets only: a hyphen keeps them from looking like real provider keys. */
    private static final String SECRET_KEY = "sk_test_harness-billing";
    private static final String WEBHOOK_SECRET = "whsec_harness-billing";
    private static final String RETURN_ORIGIN = "https://app.example.test";
    private static final String PRO_PRICE = "price_harness_pro";
    private static final String CHECKOUT_URL = "https://checkout.stripe.test/c/pay/cs_harness";
    private static final List<String> COLLECTIONS = List.of("billing_customers", "billing_entitlement_rules",
            "billing_passes", "billing_plans", "billing_subscriptions");
    private static final int RESOLVE_SAMPLES = 120;

    private static final GenericContainer<?> STRIPE =
            new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.9.1"))
                    .withNetwork(KeltaStack.NETWORK)
                    .withNetworkAliases(STRIPE_HOST)
                    .withExposedPorts(8080)
                    .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));

    private static final GenericContainer<?> BILLING = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withNetwork(KeltaStack.NETWORK)
            .withNetworkAliases(KeltaStack.BILLING_MODULE_HOST)
            // The worker's issuer is EXTERNAL_BASE_URL + /api/modules; the JWKS is served there.
            .withEnv("KELTA_MODULE_ISSUER", "http://kelta-gateway:8080/api/modules")
            .withEnv("KELTA_MODULE_BASE_URLS", BASE_URL)
            .withEnv("KELTA_PLATFORM_URL", "http://kelta-gateway:8080")
            .withEnv("STRIPE_API_BASE", "http://" + STRIPE_HOST + ":8080/v1")
            // The default, spelled out: every resolve reads the platform (the latency recorded).
            .withEnv("BILLING_ENTITLEMENTS_CACHE_SECONDS", "0")
            .withEnv("RUST_LOG", "info,kelta_module=debug")
            .withExposedPorts(8080)
            .withLogConsumer(new org.testcontainers.containers.output.Slf4jLogConsumer(
                    LoggerFactory.getLogger("kelta-billing-container")))
            .waitingFor(Wait.forHttp("/.well-known/kelta-module.json").forStatusCode(200));

    private final ObjectMapper mapper = new ObjectMapper();
    private String adminToken;
    private String tenantId;

    @BeforeAll
    static void startContainers() {
        boolean present;
        try {
            DockerClientFactory.instance().client().inspectImageCmd(IMAGE).exec();
            present = true;
        } catch (NotFoundException e) {
            present = false;
        }
        if (!present && "true".equalsIgnoreCase(System.getenv("KELTA_HARNESS_PREBUILT_IMAGES"))) {
            throw new IllegalStateException("Image " + IMAGE + " is missing: the integration job builds it from "
                    + "kelta-modules/Dockerfile before the harness runs");
        }
        Assumptions.assumeTrue(present, "Image " + IMAGE + " not built: "
                + "docker build -f kelta-modules/Dockerfile -t " + IMAGE + " .");
        STRIPE.start();
        BILLING.start();
    }

    @AfterAll
    static void stopContainers() {
        BILLING.stop();
        STRIPE.stop();
    }

    @BeforeEach
    void setUp() {
        adminToken = auth.loginAsAdmin(SLUG);
        tenantId = auth.extractTenantId(adminToken);
        stripe().post().uri("/__admin/reset").retrieve().toBodilessEntity();
        stubCheckout();
    }

    @Test
    @DisplayName("plan → install → action → routes + checkout → hook → event → entitlements → signed webhook → uninstall")
    void billingEndToEnd() throws Exception {
        String suffix = Long.toHexString(System.nanoTime());
        String userId = queryString("SELECT id FROM platform_user WHERE tenant_id = ? AND email = ?", tenantId,
                AuthFixture.adminUsername(SLUG));
        String credentialId = createRecord("credentials", Map.of("name", "stripe", "type", "stripe",
                "secretKey", SECRET_KEY, "webhookSecret", WEBHOOK_SECRET, "allowedReturnOrigins", RETURN_ORIGIN));

        // ── Consent: the plan names what the module asks for, then the reviewed hash installs it.
        JsonNode plan = admin("POST", "/" + SLUG + "/api/modules/plan", "{\"baseUrl\":\"" + BASE_URL + "\"}",
                HttpStatus.OK).get("data").get("attributes");
        assertThat(plan.get("mode").stringValue()).isEqualTo("INSTALL");
        assertThat(plan.get("blockers")).as("blockers: %s", plan.get("blockers")).isEmpty();
        JsonNode grants = plan.get("grants");
        assertThat(strings(grants.get("ownedCollections"))).containsExactlyElementsOf(COLLECTIONS);
        assertThat(strings(grants.get("credentials"))).containsExactly("stripe");
        assertThat(strings(grants.get("system"))).containsExactly("VIEW_ALL_DATA");
        assertThat(plan.get("uiBundle").get("fetchedSha256").stringValue())
                .isEqualTo(plan.get("uiBundle").get("sha256").stringValue());
        JsonNode installed = admin("POST", "/" + SLUG + "/api/modules/install",
                "{\"baseUrl\":\"" + BASE_URL + "\",\"planHash\":\"" + plan.get("planHash").stringValue() + "\"}",
                HttpStatus.OK).get("data").get("attributes");
        assertThat(installed.get("status").stringValue()).as("install: %s", installed).isEqualTo("ACTIVE");
        awaitModuleLog("POST", "/kelta/v1/lifecycle", 1);
        assertThat(moduleLog("POST", "/kelta/v1/lifecycle").getFirst().get("status").asInt())
                .as("the module verified the signed lifecycle call").isEqualTo(204);

        waitForStatus(gatewayClientWithToken(adminToken), "/" + SLUG + "/api/billing_plans", HttpStatus.OK, 40);
        createRecord("billing_plans", Map.of("code", "free", "name", "Free", "kind", "DEFAULT", "active", true,
                "sortOrder", 0, "entitlements", Map.of("maxActiveWatches", 0, "maxNotes", 1)));
        String proId = createRecord("billing_plans", Map.of("code", "pro", "name", "Pro", "kind", "SUBSCRIPTION",
                "stripePriceId", PRO_PRICE, "active", true, "sortOrder", 1,
                "entitlements", Map.of("maxActiveWatches", 5, "maxNotes", 10)));

        // ── Action: a flow Task whose Resource is the module's action key.
        String flowId = createFlow(MODULE_ID + ":list-plans", suffix);
        JsonNode execution = awaitExecution(executeFlow(flowId));
        assertThat(execution.get("status").stringValue()).as("execution: %s", execution).isEqualTo("COMPLETED");
        List<String> codes = new ArrayList<>();
        execution.get("stateData").get("plans").get("data").forEach(p -> codes.add(p.get("code").stringValue()));
        assertThat(codes).containsExactly("free", "pro");

        // ── Routes, signed for the calling member: me, then a checkout the module starts with Stripe.
        JsonNode me = route("GET", "me", null, HttpStatus.OK);
        assertThat(me.get("plan").get("code").stringValue()).isEqualTo("free");
        assertThat(me.get("entitlements").get("maxActiveWatches").asInt()).isZero();

        JsonNode checkout = route("POST", "checkout-sessions", "{\"planCode\":\"pro\",\"successUrl\":\""
                + RETURN_ORIGIN + "/ok\",\"cancelUrl\":\"" + RETURN_ORIGIN + "/cancel\"}", HttpStatus.OK);
        assertThat(checkout.get("url").stringValue()).isEqualTo(CHECKOUT_URL);
        JsonNode stripeCall = stripeRequests("/v1/checkout/sessions").getFirst();
        assertThat(header(stripeCall, "Authorization")).isEqualTo("Bearer " + SECRET_KEY);
        assertThat(header(stripeCall, "Idempotency-Key")).isNotBlank();
        String form = stripeCall.get("body").stringValue();
        assertThat(form).contains("client_reference_id=" + userId, "mode=subscription",
                "line_items%5B0%5D%5Bprice%5D=" + PRO_PRICE);
        JsonNode refused = route("POST", "checkout-sessions", "{\"planCode\":\"pro\",\"successUrl\":"
                + "\"https://elsewhere.example.test/ok\",\"cancelUrl\":\"" + RETURN_ORIGIN + "/c\"}",
                HttpStatus.BAD_REQUEST);
        assertThat(refused.get("error").stringValue()).isEqualTo("Return URL is not allowed");
        assertThat(stripeRequests("/v1/checkout/sessions")).as("a refused return URL never reaches Stripe")
                .hasSize(1);

        // ── Hook: the wildcard before-create hook enforces an entitlement rule on any collection.
        // The module caches a tenant's rules (60 s) and drops them on a rule's record event, so a new
        // rule applies once its event has arrived: wait for it (the two plan events came first).
        String notes = createNotesCollection(suffix);
        awaitModuleLog("POST", "/kelta/v1/events", 2);
        long ruleEvents = moduleLog("POST", "/kelta/v1/events").size();
        String ruleId = createRecord("billing_entitlement_rules", Map.of("collectionName", notes,
                "limitKey", "maxNotes", "appliesTo", "ALL", "active", true));
        awaitModuleLog("POST", "/kelta/v1/events", ruleEvents + 1);
        assertThat(createNote(notes, "N1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> overLimit = createNote(notes, "N2");
        assertThat(overLimit.getStatusCode()).as("over limit: %s", overLimit.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(overLimit.getBody()).contains("plan limit (1/1)");

        // ── Event: the module caches a tenant's rules for 60 s; the rule's record event invalidates
        // them, so a changed refusal message shows on the next create, not a minute later.
        long eventsBefore = moduleLog("POST", "/kelta/v1/events").size();
        patchRecord("billing_entitlement_rules", ruleId, Map.of("message", "harness: notes are capped " + suffix));
        awaitModuleLog("POST", "/kelta/v1/events", eventsBefore + 1);
        ResponseEntity<String> changed = awaitNoteRefused(notes, "harness: notes are capped " + suffix);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // ── Entitlements through the platform: the watch quota asks the module's port. Each POST
        // is one resolve; the free plan's 0 refuses it before anything is written.
        long resolvesBefore = moduleLog("POST", "/kelta/v1/services/entitlements/resolve").size();
        List<Long> roundTrips = new ArrayList<>();
        for (int i = 0; i < RESOLVE_SAMPLES; i++) {
            long started = System.nanoTime();
            ResponseEntity<String> watch = postJson(adminToken, "/" + SLUG + "/api/watches", "{}");
            roundTrips.add((System.nanoTime() - started) / 1_000);
            assertThat(watch.getStatusCode().value()).as("watch: %s", watch.getBody()).isEqualTo(422);
            if (i == 0) {
                assertThat(watch.getBody()).contains("plan limit (0/0)");
            }
        }
        List<JsonNode> resolves = moduleLog("POST", "/kelta/v1/services/entitlements/resolve");
        List<Long> moduleSide = new ArrayList<>();
        resolves.subList((int) resolvesBefore, resolves.size())
                .forEach(r -> moduleSide.add(r.get("elapsed_us").asLong()));
        assertThat(moduleSide).as("one resolve per watch create").hasSizeGreaterThanOrEqualTo(RESOLVE_SAMPLES);
        assertThat(resolves.getLast().get("status").asInt()).isEqualTo(200);
        report("entitlements.resolve, module side (uncached; 3 platform reads)", moduleSide);
        report("POST /api/watches round trip (gateway → worker → module resolve)", roundTrips);

        // ── Webhook: a Stripe-signed subscription event through the platform's proxy upgrades the member.
        long periodEnd = Instant.now().plusSeconds(30L * 86_400).getEpochSecond();
        String event = """
                {"id":"evt_harness_%1$s","type":"customer.subscription.created","data":{"object":{
                  "id":"sub_harness_%1$s","customer":"cus_harness_%1$s","status":"active",
                  "metadata":{"userId":"%2$s"},"cancel_at_period_end":false,"current_period_end":%3$d,
                  "items":{"data":[{"price":{"id":"%4$s"}}]}}}}""".formatted(suffix, userId, periodEnd, PRO_PRICE);
        ResponseEntity<String> unsigned = webhook(event, "t=" + Instant.now().getEpochSecond() + ",v1=00");
        assertThat(unsigned.getStatusCode()).as("unsigned: %s", unsigned.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The proxy answers with the module's status only (it goes back to Stripe, which retries a non-2xx).
        ResponseEntity<String> accepted = webhook(event, stripeSignature(event));
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode subscription = mapper.readTree(get(adminToken, "/" + SLUG
                + "/api/billing_subscriptions?filter[userId][eq]=" + userId).getBody()).get("data").get(0)
                .get("attributes");
        assertThat(subscription.get("status").stringValue()).isEqualTo("active");
        assertThat(subscription.get("planId").stringValue()).isEqualTo(proId);

        JsonNode upgraded = route("GET", "me", null, HttpStatus.OK);
        assertThat(upgraded.get("plan").get("code").stringValue()).isEqualTo("pro");
        assertThat(upgraded.get("subscription").get("status").stringValue()).isEqualTo("active");
        assertThat(upgraded.get("entitlements").get("maxActiveWatches").asInt()).isEqualTo(5);
        ResponseEntity<String> allowed = postJson(adminToken, "/" + SLUG + "/api/watches", "{}");
        assertThat(allowed.getStatusCode().value()).as("the quota passes on pro: %s", allowed.getBody())
                .isNotEqualTo(422);
        assertThat(allowed.getBody()).doesNotContain("plan limit");

        // ── Uninstall: the plan removes the module's collections; the principal is deactivated.
        JsonNode uninstallPlan = admin("GET", "/" + SLUG + "/api/modules/" + MODULE_ID
                + "/uninstall-plan?purgeData=true", null, HttpStatus.OK).get("data").get("attributes");
        JsonNode removed = admin("DELETE", "/" + SLUG + "/api/modules/" + MODULE_ID + "?purgeData=true&planHash="
                + uninstallPlan.get("planHash").stringValue(), null, HttpStatus.OK).get("data").get("attributes");
        assertThat(removed.get("failed")).as("uninstall: %s", removed).isEmpty();
        assertThat(queryCount("SELECT count(*) FROM tenant_module WHERE tenant_id = ? AND module_id = ?",
                tenantId, MODULE_ID)).isZero();
        assertThat(createNote(notes, "AFTER").getStatusCode()).as("no hook once uninstalled")
                .isEqualTo(HttpStatus.CREATED);
        gatewayClientWithToken(adminToken).delete().uri("/" + SLUG + "/api/credentials/" + credentialId).retrieve()
                .onStatus(st -> true, (req, resp) -> {}).toBodilessEntity();
    }

    // ── platform calls ───────────────────────────────────────────────────────────────────

    private JsonNode route(String method, String path, String body, HttpStatus expected) {
        String uri = "/" + SLUG + "/api/modules/" + MODULE_ID + "/x/" + path;
        ResponseEntity<String> response = "POST".equals(method) ? postJson(adminToken, uri, body)
                : get(adminToken, uri);
        assertThat(response.getStatusCode()).as("%s %s: %s", method, path, response.getBody()).isEqualTo(expected);
        return mapper.readTree(response.getBody());
    }

    private ResponseEntity<String> webhook(String body, String signature) {
        return gatewayClient().post().uri("/api/modules/webhooks/" + tenantId + "/" + MODULE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signature)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .retrieve().onStatus(st -> true, (req, resp) -> {}).toEntity(String.class);
    }

    /** Stripe's scheme: {@code t=<ts>,v1=hex(HMAC-SHA256(secret, "<ts>.<body>"))}. */
    private static String stripeSignature(String body) throws Exception {
        long ts = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8));
        return "t=" + ts + ",v1=" + HexFormat.of().formatHex(digest);
    }

    private String createRecord(String collection, Map<String, Object> attributes) {
        RestClient client = gatewayClientWithToken(adminToken);
        waitForStatus(client, "/" + SLUG + "/api/" + collection, HttpStatus.OK, 60);
        ResponseEntity<String> created = client.post().uri("/" + SLUG + "/api/" + collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", collection, "attributes", attributes)))
                .retrieve().onStatus(st -> true, (req, resp) -> {}).toEntity(String.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create %s: %s", collection, created.getBody())
                .isTrue();
        return mapper.readTree(created.getBody()).get("data").get("id").stringValue();
    }

    private void patchRecord(String collection, String id, Map<String, Object> attributes) {
        ResponseEntity<String> patched = gatewayClientWithToken(adminToken).patch()
                .uri("/" + SLUG + "/api/" + collection + "/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", Map.of("type", collection, "id", id, "attributes", attributes)))
                .retrieve().onStatus(st -> true, (req, resp) -> {}).toEntity(String.class);
        assertThat(patched.getStatusCode()).as("patch %s: %s", collection, patched.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** A user collection with one {@code code} field, owned by no module. */
    private String createNotesCollection(String suffix) {
        String name = "harness_billing_notes_" + suffix;
        String collectionId = createRecord("collections", Map.of("name", name, "displayName", "Billing notes",
                "tenantScoped", true));
        createRecord("fields", Map.of("collectionId", collectionId, "name", "code", "type", "STRING"));
        waitForStatus(gatewayClientWithToken(adminToken), "/" + SLUG + "/api/" + name, HttpStatus.OK, 40);
        return name;
    }

    private ResponseEntity<String> createNote(String collection, String code) {
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("data").put("type", collection).putObject("attributes").put("code", code);
        return postJson(adminToken, "/" + SLUG + "/api/" + collection, payload.toString());
    }

    /** Creates notes until one is refused with {@code message}: the module re-read its rules. */
    private ResponseEntity<String> awaitNoteRefused(String collection, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        ResponseEntity<String> last = null;
        while (System.currentTimeMillis() < deadline) {
            last = createNote(collection, "T" + System.nanoTime());
            if (last.getStatusCode() == HttpStatus.BAD_REQUEST && last.getBody().contains(message)) {
                return last;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the module never applied the changed rule (its cache lasts 60 s): " + last);
    }

    /**
     * An AUTOLAUNCHED flow of one Task, {@code ListPlans}, whose Resource is {@code resource},
     * writing its output to {@code $.plans}.
     */
    private String createFlow(String resource, String suffix) {
        ObjectNode payload = mapper.createObjectNode();
        ObjectNode attributes = payload.putObject("data").put("type", "flows").putObject("attributes");
        attributes.put("name", "billing-module-harness-" + suffix).put("flowType", "AUTOLAUNCHED").put("active", true);
        ObjectNode definition = attributes.putObject("definition").put("StartAt", "ListPlans");
        ObjectNode states = definition.putObject("States");
        states.putObject("ListPlans").put("Type", "Task").put("Resource", resource).put("ResultPath", "$.plans")
                .put("Next", "Done");
        states.putObject("Done").put("Type", "Succeed");
        ResponseEntity<String> created = postJson(adminToken, "/" + SLUG + "/api/flows", payload.toString());
        assertThat(created.getStatusCode().is2xxSuccessful()).as("flow: %s", created.getBody()).isTrue();
        return mapper.readTree(created.getBody()).get("data").get("id").stringValue();
    }

    private String executeFlow(String flowId) {
        ResponseEntity<String> executed = postJson(adminToken, "/" + SLUG + "/api/flows/" + flowId + "/execute",
                "{\"input\":{}}");
        assertThat(executed.getStatusCode().is2xxSuccessful()).as("execute: %s", executed.getBody()).isTrue();
        return mapper.readTree(executed.getBody()).get("data").get("id").stringValue();
    }

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

    private ResponseEntity<String> postJson(String token, String uri, String body) {
        return gatewayClientWithToken(token).post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(s -> true, (req, resp) -> {}).toEntity(String.class);
    }

    // ── the module's access log and the Stripe stub ───────────────────────────────────────

    /** The module's {@code kelta_module::access} lines for one method and path, in order. */
    private List<JsonNode> moduleLog(String method, String path) {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : BILLING.getLogs().split("\n")) {
            if (!line.startsWith("{") || !line.contains("\"kelta_module::access\"")) {
                continue;
            }
            JsonNode fields = mapper.readTree(line).get("fields");
            if (fields != null && method.equals(fields.get("method").stringValue())
                    && path.equals(fields.get("path").stringValue())) {
                lines.add(fields);
            }
        }
        return lines;
    }

    private void awaitModuleLog(String method, String path, long count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (moduleLog(method, path).size() >= count) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the module never logged " + count + " × " + method + " " + path);
    }

    /** Logs and prints {@code GATE0 <what> p50=… p99=… n=…} in milliseconds (nearest rank). */
    private static void report(String what, List<Long> micros) {
        List<Long> sorted = new ArrayList<>(micros);
        Collections.sort(sorted);
        String line = "GATE0 %s p50=%.2fms p99=%.2fms max=%.2fms n=%d".formatted(what,
                percentile(sorted, 50) / 1000.0, percentile(sorted, 99) / 1000.0, sorted.getLast() / 1000.0,
                sorted.size());
        log.info(line);
        System.out.println(line);
    }

    private static long percentile(List<Long> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    private RestClient stripe() {
        return RestClient.builder().baseUrl("http://" + STRIPE.getHost() + ":" + STRIPE.getMappedPort(8080)).build();
    }

    private void stubCheckout() {
        ObjectNode mapping = mapper.createObjectNode();
        mapping.putObject("request").put("method", "POST").put("urlPath", "/v1/checkout/sessions");
        mapping.putObject("response").put("status", 200).put("body",
                        "{\"id\":\"cs_harness\",\"object\":\"checkout.session\",\"url\":\"" + CHECKOUT_URL + "\"}")
                .putObject("headers").put("Content-Type", "application/json");
        stripe().post().uri("/__admin/mappings").contentType(MediaType.APPLICATION_JSON).body(mapping.toString())
                .retrieve().toBodilessEntity();
    }

    private List<JsonNode> stripeRequests(String urlPath) {
        JsonNode body = mapper.readTree(stripe().get().uri("/__admin/requests").retrieve().body(String.class));
        List<JsonNode> matched = new ArrayList<>();
        body.get("requests").forEach(r -> {
            JsonNode request = r.get("request");
            if (request.get("url").stringValue().startsWith(urlPath)) {
                matched.add(request);
            }
        });
        return matched;
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

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.stringValue()));
        return values;
    }

    private long queryCount(String sql, Object... args) throws Exception {
        return Long.parseLong(queryString(sql, args));
    }

    private String queryString(String sql, Object... args) throws Exception {
        try (Connection db = openDbConnection(); PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("no row for %s", sql).isTrue();
                return rs.getString(1);
            }
        }
    }
}
