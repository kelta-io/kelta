package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.AuthFixture;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A module service principal (external modules part 9b,
 * {@code specs/module-platform/9-external-modules.md} §3.3) calls the public API as its own
 * {@code SERVICE} user: kelta-auth issues its connected app's {@code client_credentials} token with
 * that user's identity and profile, the gateway resolves it to the user, and the worker
 * authorizes and audits the call as that user.
 *
 * <p>The principal is planted over SQL exactly as {@code ModuleServicePrincipalService} writes it
 * (that service has no endpoint until part 9c wires it into install): a SERVICE user under the
 * reserved {@code .invalid} domain, a system profile with {@code API_ACCESS} and create+read on
 * {@code products}, and a connected app whose {@code run_as_user_id} is the user.
 *
 * <p>What this scenario can and cannot show: the stack's Cerbos is dev allow-all and its gateway
 * runs with {@code PERMISSIONS_ENABLED=false}, so a collection the profile does not grant is still
 * readable here, and identity reaching the worker is shown by the {@code created_by} audit. The refusal it proves comes from the
 * profile's own system permissions ({@code GET /api/governor-limits} needs {@code VIEW_SETUP},
 * checked from {@code profile_system_permission}), and the token is confined to its tenant. That a
 * real PDP compiles the same profile shape to "read the granted collection, nothing else" is
 * {@code CerbosGeneratedPolicyIT.moduleServicePrincipalReadsOnlyItsGrant}.
 */
@DisplayName("Module service principal scenario")
class ModuleServicePrincipalScenarioTest extends ScenarioBase {

    private static final String SLUG = TenantFixture.ECOMMERCE_SLUG;
    private static final String COLLECTION = "products";
    private static final String CLIENT_SECRET = AuthFixture.PROVISIONED_ADMIN_PASSWORD;
    /** connected_app stores the bare BCrypt hash; kelta-auth adds the {@code {bcrypt}} prefix. */
    private static final String CLIENT_SECRET_HASH =
            AuthFixture.PROVISIONED_ADMIN_PASSWORD_HASH.substring("{bcrypt}".length());

    private final ObjectMapper mapper = new ObjectMapper();

    private String tenantId;
    private String adminToken;

    private record Principal(String userId, String profileId, String email, String clientId) {}

    @BeforeEach
    void setUp() {
        adminToken = auth.loginAsAdmin(SLUG);
        tenantId = auth.extractTenantId(adminToken);
        waitForStatus(gatewayClientWithToken(adminToken), "/" + SLUG + "/api/" + COLLECTION, HttpStatus.OK, 20);
    }

    @Test
    @DisplayName("its token carries the SERVICE user's identity and profile; sub stays the client id")
    void tokenCarriesTheServiceUsersIdentity() throws Exception {
        Principal principal = plantPrincipal(true);

        JsonNode claims = claims(token(principal.clientId()));

        assertThat(claims.get("sub").stringValue()).isEqualTo(principal.clientId());
        assertThat(claims.get("run_as_user_id").stringValue()).isEqualTo(principal.userId());
        assertThat(claims.get("user_type").stringValue()).isEqualTo("SERVICE");
        assertThat(claims.get("profile_id").stringValue()).isEqualTo(principal.profileId());
        assertThat(claims.get("email").stringValue()).isEqualTo(principal.email());
        assertThat(claims.get("tenant_id").stringValue()).isEqualTo(tenantId);
        assertThat(claims.get("auth_method").stringValue()).isEqualTo("api_key");
    }

    @Test
    @DisplayName("it reads and writes its granted collection, and the write is audited as the service user")
    @SuppressWarnings("unchecked")
    void readsAndWritesItsGrantedCollectionAsItself() throws Exception {
        Principal principal = plantPrincipal(true);
        String token = token(principal.clientId());

        ResponseEntity<Map> list = call(token, "GET", "/" + SLUG + "/api/" + COLLECTION, null);
        assertThat(list.getStatusCode()).as("list: %s", list.getBody()).isEqualTo(HttpStatus.OK);

        String sku = "msp-" + Long.toHexString(System.nanoTime());
        ResponseEntity<Map> created = call(token, "POST", "/" + SLUG + "/api/" + COLLECTION,
                Map.of("data", Map.of("type", COLLECTION,
                        "attributes", Map.of("name", "Module product", "sku", sku))));
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create: %s", created.getBody()).isTrue();
        Map<String, Object> attributes = (Map<String, Object>)
                ((Map<String, Object>) created.getBody().get("data")).get("attributes");
        assertThat(attributes.get("createdBy")).as("created_by audit: %s", attributes)
                .isEqualTo(principal.userId());
    }

    @Test
    @DisplayName("it is refused what its profile does not grant, and confined to its tenant")
    void isRefusedWhatItsProfileDoesNotGrant() throws Exception {
        Principal principal = plantPrincipal(true);
        String token = token(principal.clientId());

        ResponseEntity<Map> setup = call(token, "GET", "/" + SLUG + "/api/governor-limits", null);
        assertThat(setup.getStatusCode()).as("VIEW_SETUP is not granted: %s", setup.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> foreign = call(token, "GET", "/" + TenantFixture.DEFAULT_SLUG + "/api/users", null);
        assertThat(foreign.getStatusCode().is4xxClientError())
                .as("another tenant's route: %s", foreign.getStatusCode()).isTrue();
        assertThat(foreign.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The harness gateway runs with {@code PERMISSIONS_ENABLED=false}, so the {@code 403 User identity
     * not resolved} a plain app's token gets in a deployment is not reachable here;
     * {@code RouteAuthorizationFilterTest.connectedAppTokenShapes} covers it.
     */
    @Test
    @DisplayName("a connected app without run_as_user_id keeps the plain machine token, which has no identity")
    void plainConnectedAppKeepsThePlainToken() throws Exception {
        Principal principal = plantPrincipal(false);

        JsonNode claims = claims(token(principal.clientId()));

        assertThat(claims.get("sub").stringValue()).isEqualTo(principal.clientId());
        assertThat(claims.get("connected_app_id")).isNotNull();
        for (String identityClaim : new String[] {"run_as_user_id", "profile_id", "user_type", "email",
                "preferred_username"}) {
            assertThat(claims.has(identityClaim)).as(identityClaim).isFalse();
        }
    }

    @Test
    @DisplayName("an inactive service user gets no token")
    void inactiveServiceUserGetsNoToken() throws Exception {
        Principal principal = plantPrincipal(true);
        execute("UPDATE platform_user SET status = 'INACTIVE' WHERE id = ?", principal.userId());

        ResponseEntity<String> response = tokenResponse(principal.clientId());

        assertThat(response.getStatusCode().is2xxSuccessful()).as("token: %s", response.getBody()).isFalse();
        assertThat(response.getBody()).contains("access_denied");
    }

    @Test
    @DisplayName("it is not a seat, and cannot sign in even with a password")
    @SuppressWarnings("unchecked")
    void isNotASeatAndCannotSignIn() throws Exception {
        int seatsBefore = usersUsed();
        Principal principal = plantPrincipal(true);
        assertThat(usersUsed()).as("SERVICE users do not count against the users limit").isEqualTo(seatsBefore);

        execute("INSERT INTO user_credential (user_id, password_hash) VALUES (?, ?)",
                principal.userId(), AuthFixture.PROVISIONED_ADMIN_PASSWORD_HASH);
        ResponseEntity<Map> login = authClient().post().uri("/auth/direct-login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", principal.email(), "password", AuthFixture.PROVISIONED_ADMIN_PASSWORD,
                        "tenantSlug", SLUG))
                .retrieve().onStatus(s -> true, (req, resp) -> {}).toEntity(Map.class);
        assertThat(login.getStatusCode().is2xxSuccessful()).as("direct-login: %s", login.getBody()).isFalse();
        assertThat(login.getBody() == null || !login.getBody().containsKey("access_token")).isTrue();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    /** Plants a principal as ModuleServicePrincipalService writes it; {@code runAs=false} = a plain app. */
    private Principal plantPrincipal(boolean runAs) throws Exception {
        String suffix = Long.toHexString(System.nanoTime());
        String userId = UUID.randomUUID().toString();
        String profileId = UUID.randomUUID().toString();
        String appId = UUID.randomUUID().toString();
        String email = "harness-module." + suffix + "@service.kelta.invalid";
        String clientId = "kmod_harness" + suffix;
        String collectionId = queryString(
                "SELECT id FROM collection WHERE tenant_id = ? AND name = ? AND active = true", tenantId, COLLECTION);

        execute("INSERT INTO profile (id, tenant_id, name, description, is_system) VALUES (?, ?, ?, ?, TRUE)",
                profileId, tenantId, "Module: Harness (" + suffix + ")", "harness module profile");
        // One row per system permission, as the service writes them — only API_ACCESS granted.
        // The stack is shared: AnalyticsAuthzScenarioTest asserts every profile of this tenant
        // carries a VIEW_ANALYTICS row, so a single-row profile would fail it.
        execute("INSERT INTO profile_system_permission (id, tenant_id, profile_id, permission_name, granted) "
                        + "SELECT gen_random_uuid()::text, ?, ?, p.permission_name, p.permission_name = 'API_ACCESS' "
                        + "FROM (SELECT DISTINCT permission_name FROM profile_system_permission WHERE tenant_id = ?) p",
                tenantId, profileId, tenantId);
        execute("INSERT INTO profile_object_permission (id, tenant_id, profile_id, collection_id, can_create, "
                        + "can_read, can_edit, can_delete) VALUES (?, ?, ?, ?, TRUE, TRUE, FALSE, FALSE)",
                UUID.randomUUID().toString(), tenantId, profileId, collectionId);
        execute("INSERT INTO platform_user (id, tenant_id, email, first_name, status, profile_id, user_type) "
                + "VALUES (?, ?, ?, 'Harness Module', 'ACTIVE', ?, 'SERVICE')", userId, tenantId, email, profileId);
        execute("INSERT INTO connected_app (id, tenant_id, name, client_id, client_secret_hash, grant_types, "
                        + "require_pkce, consent_required, active, run_as_user_id, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, '[\"client_credentials\"]'::jsonb, FALSE, FALSE, TRUE, ?, ?)",
                appId, tenantId, "Module: Harness (" + suffix + ")", clientId, CLIENT_SECRET_HASH,
                runAs ? userId : null, userId);
        return new Principal(userId, profileId, email, clientId);
    }

    private ResponseEntity<String> tokenResponse(String clientId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        return authClient().post().uri("/oauth2/token")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().onStatus(s -> true, (req, resp) -> {}).toEntity(String.class);
    }

    private String token(String clientId) throws Exception {
        ResponseEntity<String> response = tokenResponse(clientId);
        assertThat(response.getStatusCode()).as("token: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("access_token").stringValue();
    }

    private JsonNode claims(String jwt) throws Exception {
        String payload = jwt.split("\\.")[1];
        return mapper.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> call(String token, String method, String uri, Object body) {
        var client = gatewayClientWithToken(token);
        var spec = "POST".equals(method)
                ? client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body)
                : client.get().uri(uri);
        return spec.retrieve().onStatus(s -> true, (req, resp) -> {}).toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private int usersUsed() {
        ResponseEntity<Map> limits = call(adminToken, "GET", "/" + SLUG + "/api/governor-limits", null);
        assertThat(limits.getStatusCode()).as("governor limits: %s", limits.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) limits.getBody().get("data");
        Map<String, Object> attributes = data.containsKey("attributes")
                ? (Map<String, Object>) data.get("attributes") : data;
        return ((Number) attributes.get("usersUsed")).intValue();
    }

    private void execute(String sql, Object... args) throws Exception {
        try (Connection db = openDbConnection(); PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
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
