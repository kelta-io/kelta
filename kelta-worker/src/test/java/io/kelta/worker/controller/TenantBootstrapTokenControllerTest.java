package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.SecurityAuditLogger;
import io.kelta.worker.service.TenantAdminInviteService;
import io.kelta.worker.service.UserInviteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/tenants/{id}/bootstrap-token}. Runs the real {@link PersonalAccessTokenController}
 * mint and the real {@link TenantAdminInviteService} lookup over a mocked {@link JdbcTemplate}, so
 * the assertions see the actual {@code user_api_token} INSERT, the tenant-filtered user lookup and
 * the Redis cache TTL. The router stand-in is registered deliberately — the literal segments here
 * must win over {@code DynamicCollectionRouter}'s {@code POST /api/{parent}/{parentId}/{child}}.
 */
@DisplayName("TenantBootstrapTokenController")
class TenantBootstrapTokenControllerTest {

    private static final String CALLER_TENANT = "00000000-0000-0000-0000-000000000001";
    private static final String MANAGED_TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String MANAGED_SLUG = "acme";
    private static final String SEEDED_ADMIN = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_TENANT_USER = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PROFILE = "caller-profile";
    private static final String ACTOR = "ops@example.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    @RestController
    @RequestMapping("/api")
    static class NestedRouteStub {
        @PostMapping("/{parentName}/{parentId}/{childName}")
        String createChild(@PathVariable String parentName) {
            return "router";
        }
    }

    private JdbcTemplate jdbcTemplate;
    private ValueOperations<String, String> valueOps;
    private BootstrapRepository bootstrapRepository;
    private UserIdResolver userIdResolver;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        bootstrapRepository = mock(BootstrapRepository.class);
        userIdResolver = mock(UserIdResolver.class);
        CerbosPermissionResolver permissionResolver = mock(CerbosPermissionResolver.class);
        when(permissionResolver.getProfileId(any())).thenReturn(PROFILE);
        when(permissionResolver.getEmail(any())).thenReturn(ACTOR);

        PersonalAccessTokenController patController =
                new PersonalAccessTokenController(jdbcTemplate, redisTemplate, userIdResolver);
        TenantAdminInviteService tenantUsers =
                new TenantAdminInviteService(jdbcTemplate, mock(UserInviteService.class));

        mvc = MockMvcBuilders.standaloneSetup(
                new TenantBootstrapTokenController(tenantUsers, patController, permissionResolver, bootstrapRepository),
                patController,
                new NestedRouteStub()).build();

        when(jdbcTemplate.queryForList("SELECT slug FROM tenant WHERE id = ?", String.class, MANAGED_TENANT))
                .thenReturn(List.of(MANAGED_SLUG));
        when(jdbcTemplate.queryForList(contains("username = ?"), eq(MANAGED_TENANT), eq("acme-admin")))
                .thenReturn(List.of(Map.of("id", SEEDED_ADMIN, "status", "ACTIVE")));
        when(jdbcTemplate.queryForList(contains("SELECT email FROM platform_user"), eq(SEEDED_ADMIN), eq(MANAGED_TENANT)))
                .thenReturn(List.of(Map.of("email", "acme-admin@kelta.local")));
        when(jdbcTemplate.queryForList(contains("COUNT"), eq(SEEDED_ADMIN), eq(MANAGED_TENANT)))
                .thenReturn(List.of(Map.of("cnt", 0L)));
    }

    private void grant(String permission, boolean granted) {
        when(bootstrapRepository.findProfileSystemPermissions(PROFILE)).thenReturn(
                List.of(Map.of("permission_name", permission, "granted", granted)));
    }

    private ResultActions perform(RequestBuilder request) {
        return TenantContext.callWithTenant(CALLER_TENANT, () -> {
            try {
                return mvc.perform(request);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static RequestBuilder bootstrap(String tenantId, String body) {
        return post("/api/tenants/" + tenantId + "/bootstrap-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void verifyNoTokenWritten() {
        verify(jdbcTemplate, never()).update(contains("INSERT INTO user_api_token"), any(Object[].class));
        verifyNoInteractions(valueOps);
    }

    /** The {@code expires_at} bound into the {@code user_api_token} INSERT. */
    private Instant insertedExpiresAt() {
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(contains("INSERT INTO user_api_token"),
                args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(),
                args.capture());
        List<Object> values = args.getAllValues();
        assertThat(values.get(0)).as("user_id").isEqualTo(SEEDED_ADMIN);
        assertThat(values.get(1)).as("tenant_id").isEqualTo(MANAGED_TENANT);
        return ((Timestamp) values.get(6)).toInstant();
    }

    private Duration cachedTtl() {
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(startsWith("pat:"), anyString(), ttl.capture());
        return ttl.getValue();
    }

    @Test
    @DisplayName("403 without MANAGE_TENANTS: audited, and no token row is written")
    void forbiddenWithoutManageTenants() throws Exception {
        grant("MANAGE_USERS", true);

        try (MockedStatic<SecurityAuditLogger> audit = mockStatic(SecurityAuditLogger.class)) {
            perform(bootstrap(MANAGED_TENANT, "{}")).andExpect(status().isForbidden());

            audit.verify(() -> SecurityAuditLogger.log(SecurityAuditLogger.EventType.TENANT_BOOTSTRAP_TOKEN_ISSUED,
                    ACTOR, MANAGED_TENANT, MANAGED_TENANT, "failure", "MANAGE_TENANTS not granted"));
        }
        verifyNoTokenWritten();
    }

    @Test
    @DisplayName("403 when MANAGE_TENANTS is present but not granted")
    void forbiddenWhenNotGranted() throws Exception {
        grant("MANAGE_TENANTS", false);

        perform(bootstrap(MANAGED_TENANT, "{}")).andExpect(status().isForbidden());

        verifyNoTokenWritten();
    }

    @Test
    @DisplayName("the platform tenant is refused and no token is written")
    void platformTenantRefused() throws Exception {
        grant("MANAGE_TENANTS", true);

        perform(bootstrap(CALLER_TENANT, "{}")).andExpect(status().isForbidden());

        verifyNoTokenWritten();
        verify(jdbcTemplate, never()).queryForList(anyString(), eq(String.class), any(Object[].class));
    }

    @Test
    @DisplayName("an upper-cased platform tenant id is still the platform tenant")
    void platformTenantRefusedCaseInsensitively() throws Exception {
        grant("MANAGE_TENANTS", true);

        perform(bootstrap("00000000-0000-0000-0000-000000000001".toUpperCase(), "{}"))
                .andExpect(status().isForbidden());

        verifyNoTokenWritten();
    }

    @Test
    @DisplayName("a non-UUID id (e.g. the internal 'system' tenant) is 404 and writes no token")
    void nonUuidTenantRefused() throws Exception {
        grant("MANAGE_TENANTS", true);

        perform(bootstrap("system", "{}")).andExpect(status().isNotFound());

        verifyNoTokenWritten();
    }

    @Test
    @DisplayName("expiresIn defaults to 1 h; the cache TTL is no longer than the token's lifetime")
    void defaultsToOneHour() throws Exception {
        grant("MANAGE_TENANTS", true);
        Instant before = Instant.now();

        perform(bootstrap(MANAGED_TENANT, "{}")).andExpect(status().isOk());

        Instant expiresAt = insertedExpiresAt();
        assertThat(expiresAt).isBetween(before.plus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1)));
        assertThat(cachedTtl()).isLessThanOrEqualTo(Duration.ofHours(1)).isGreaterThan(Duration.ofMinutes(59));
    }

    @Test
    @DisplayName("a body-less request also defaults to 1 h")
    void bodylessDefaultsToOneHour() throws Exception {
        grant("MANAGE_TENANTS", true);
        Instant before = Instant.now();

        perform(post("/api/tenants/" + MANAGED_TENANT + "/bootstrap-token")).andExpect(status().isOk());

        assertThat(insertedExpiresAt()).isAfterOrEqualTo(before.plus(Duration.ofHours(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2h\"", "\"PT2H\"", "\"120m\""})
    @DisplayName("a 2 h request stores expires_at ≈ now + 2 h and caches for no longer")
    void twoHours(String expiresIn) throws Exception {
        grant("MANAGE_TENANTS", true);
        Instant before = Instant.now();

        perform(bootstrap(MANAGED_TENANT, "{\"expiresIn\":" + expiresIn + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").exists());

        Instant expiresAt = insertedExpiresAt();
        assertThat(expiresAt).isBetween(before.plus(Duration.ofHours(2)), Instant.now().plus(Duration.ofHours(2)));
        assertThat(cachedTtl()).isLessThanOrEqualTo(Duration.ofHours(2)).isGreaterThan(Duration.ofMinutes(119));
    }

    @Test
    @DisplayName("24 h is the maximum accepted lifetime")
    void twentyFourHoursAccepted() throws Exception {
        grant("MANAGE_TENANTS", true);

        perform(bootstrap(MANAGED_TENANT, "{\"expiresIn\":\"24h\"}")).andExpect(status().isOk());

        assertThat(cachedTtl()).isLessThanOrEqualTo(Duration.ofHours(24));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"25h\"", "\"PT24H1S\"", "\"1441m\"", "\"0h\"", "\"PT0S\"", "\"-1h\"", "\"PT-1H\"",
            "\"soon\"", "\"\"", "\"1d\"", "3600", "true"})
    @DisplayName("over 24 h, zero, negative or unparseable expiresIn is 400 and writes no token")
    void rejectsBadLifetimes(String expiresIn) throws Exception {
        grant("MANAGE_TENANTS", true);

        perform(bootstrap(MANAGED_TENANT, "{\"expiresIn\":" + expiresIn + "}")).andExpect(status().isBadRequest());

        verifyNoTokenWritten();
    }

    @Test
    @DisplayName("a userId of another tenant's user is 404: the lookup is filtered by the target tenant")
    void userOfAnotherTenantRejected() throws Exception {
        grant("MANAGE_TENANTS", true);
        when(jdbcTemplate.queryForList(contains("AND id = ?"), eq(MANAGED_TENANT), eq(OTHER_TENANT_USER)))
                .thenReturn(List.of());

        perform(bootstrap(MANAGED_TENANT, "{\"userId\":\"" + OTHER_TENANT_USER + "\"}"))
                .andExpect(status().isNotFound());

        verify(jdbcTemplate).queryForList(contains("WHERE tenant_id = ? AND id = ?"),
                eq(MANAGED_TENANT), eq(OTHER_TENANT_USER));
        verifyNoTokenWritten();
    }

    @Test
    @DisplayName("an email of another tenant's user is 404")
    void emailOfAnotherTenantRejected() throws Exception {
        grant("MANAGE_TENANTS", true);
        when(jdbcTemplate.queryForList(contains("LOWER(email) = ?"), eq(MANAGED_TENANT), eq("someone@other.example")))
                .thenReturn(List.of());

        perform(bootstrap(MANAGED_TENANT, "{\"email\":\"Someone@Other.example\"}")).andExpect(status().isNotFound());

        verifyNoTokenWritten();
    }

    @Test
    @DisplayName("a malformed userId is 400")
    void malformedUserIdRejected() throws Exception {
        grant("MANAGE_TENANTS", true);

        perform(bootstrap(MANAGED_TENANT, "{\"userId\":\"not-a-uuid\"}")).andExpect(status().isBadRequest());

        verifyNoTokenWritten();
    }

    @Test
    @DisplayName("a userId of the target tenant gets the token")
    void userOfTargetTenantAccepted() throws Exception {
        grant("MANAGE_TENANTS", true);
        String member = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        when(jdbcTemplate.queryForList(contains("AND id = ?"), eq(MANAGED_TENANT), eq(member)))
                .thenReturn(List.of(Map.of("id", member, "status", "ACTIVE")));
        when(jdbcTemplate.queryForList(contains("SELECT email FROM platform_user"), eq(member), eq(MANAGED_TENANT)))
                .thenReturn(List.of(Map.of("email", "member@acme.example")));
        when(jdbcTemplate.queryForList(contains("COUNT"), eq(member), eq(MANAGED_TENANT)))
                .thenReturn(List.of(Map.of("cnt", 0L)));

        perform(bootstrap(MANAGED_TENANT, "{\"userId\":\"" + member + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(member));
    }

    @Test
    @DisplayName("an inactive target user is 409 — the token would never validate")
    void inactiveUserRejected() throws Exception {
        grant("MANAGE_TENANTS", true);
        when(jdbcTemplate.queryForList(contains("username = ?"), eq(MANAGED_TENANT), eq("acme-admin")))
                .thenReturn(List.of(Map.of("id", SEEDED_ADMIN, "status", "INACTIVE")));

        perform(bootstrap(MANAGED_TENANT, "{}")).andExpect(status().isConflict());

        verifyNoTokenWritten();
    }

    @Test
    @DisplayName("an unknown tenant is 404")
    void unknownTenant() throws Exception {
        grant("MANAGE_TENANTS", true);
        String unknown = "22222222-2222-2222-2222-222222222222";
        when(jdbcTemplate.queryForList("SELECT slug FROM tenant WHERE id = ?", String.class, unknown))
                .thenReturn(List.of());

        perform(bootstrap(unknown, "{}")).andExpect(status().isNotFound());

        verifyNoTokenWritten();
    }

    @Test
    @DisplayName("omitted userId: minted for the seeded admin, in the target tenant's binding, named bootstrap-<actor>-<ts>")
    void mintsForSeededAdminUnderTargetTenant() throws Exception {
        grant("MANAGE_TENANTS", true);
        AtomicReference<String> boundTenant = new AtomicReference<>();
        when(jdbcTemplate.update(contains("INSERT INTO user_api_token"), any(Object[].class))).thenAnswer(inv -> {
            boundTenant.set(TenantContext.get());
            return 1;
        });

        MvcResult result = perform(bootstrap(MANAGED_TENANT, "{\"expiresIn\":\"30m\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(MANAGED_TENANT))
                .andExpect(jsonPath("$.userId").value(SEEDED_ADMIN))
                .andReturn();

        Map<?, ?> body = JSON.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat((String) body.get("token")).startsWith("klt_").hasSize(44);
        assertThat((String) body.get("name")).startsWith("bootstrap-" + ACTOR + "-");
        assertThat(body.get("tokenPrefix")).isEqualTo(((String) body.get("token")).substring(0, 8));
        assertThat(boundTenant.get()).as("mint runs under the target tenant").isEqualTo(MANAGED_TENANT);
        assertThat(insertedExpiresAt()).isBefore(Instant.now().plus(Duration.ofMinutes(31)));
    }

    @Test
    @DisplayName("the plaintext token is returned once; GET /api/me/tokens shows only its prefix")
    void tokenListShowsOnlyPrefix() throws Exception {
        grant("MANAGE_TENANTS", true);
        AtomicReference<Object[]> inserted = new AtomicReference<>();
        when(jdbcTemplate.update(contains("INSERT INTO user_api_token"), any(Object[].class))).thenAnswer(inv -> {
            inserted.set(inv.getArguments());
            return 1;
        });

        MvcResult minted = perform(bootstrap(MANAGED_TENANT, "{}")).andExpect(status().isOk()).andReturn();
        Map<?, ?> body = JSON.readValue(minted.getResponse().getContentAsString(), Map.class);
        String token = (String) body.get("token");

        Object[] row = inserted.get();
        assertThat(row).doesNotContain(token);
        assertThat(row[5]).as("stored as a SHA-256 hash").isEqualTo(PersonalAccessTokenController.sha256(token));

        when(userIdResolver.resolve("acme-admin@kelta.local", MANAGED_TENANT)).thenReturn(SEEDED_ADMIN);
        when(jdbcTemplate.queryForList(contains("FROM user_api_token WHERE user_id = ?"), eq(SEEDED_ADMIN), eq(MANAGED_TENANT)))
                .thenReturn(List.of(Map.of(
                        "id", "t-1", "name", row[3], "token_prefix", row[4], "scopes", "[\"api\"]",
                        "expires_at", row[7], "created_at", Timestamp.from(Instant.now()), "token_hash", row[5])));

        String list = TenantContext.callWithTenant(MANAGED_TENANT, () -> {
            try {
                return mvc.perform(get("/api/me/tokens").header("X-User-Id", "acme-admin@kelta.local"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data[0].tokenPrefix").value(body.get("tokenPrefix")))
                        .andExpect(jsonPath("$.data[0].token").doesNotExist())
                        .andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(list).doesNotContain(token).doesNotContain((String) row[5]);
    }

    @Test
    @DisplayName("success is audited once with actor, target tenant and target user, and no token material")
    void auditedOnceWithoutTokenMaterial() throws Exception {
        grant("MANAGE_TENANTS", true);

        try (MockedStatic<SecurityAuditLogger> audit = mockStatic(SecurityAuditLogger.class)) {
            MvcResult result = perform(bootstrap(MANAGED_TENANT, "{}")).andExpect(status().isOk()).andReturn();
            String token = (String) JSON.readValue(result.getResponse().getContentAsString(), Map.class).get("token");

            ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
            audit.verify(() -> SecurityAuditLogger.log(eq(SecurityAuditLogger.EventType.TENANT_BOOTSTRAP_TOKEN_ISSUED),
                    eq(ACTOR), eq(SEEDED_ADMIN), eq(MANAGED_TENANT), eq("success"), detail.capture()), times(1));
            audit.verify(() -> SecurityAuditLogger.log(any(), any(), any(), any(), any(), any()), times(1));
            assertThat(detail.getValue())
                    .contains("bootstrap-" + ACTOR)
                    .contains(CALLER_TENANT)
                    .doesNotContain(token)
                    .doesNotContain(PersonalAccessTokenController.sha256(token))
                    .doesNotContain("klt_");
        }
    }

    @Test
    @DisplayName("parseLifetime accepts ISO-8601 and m/h shorthand within (0, 24h]")
    void parseLifetime() throws Exception {
        assertThat(TenantBootstrapTokenController.parseLifetime(null)).isEqualTo(Duration.ofHours(1));
        assertThat(TenantBootstrapTokenController.parseLifetime("30m")).isEqualTo(Duration.ofMinutes(30));
        assertThat(TenantBootstrapTokenController.parseLifetime(" PT1H30M ")).isEqualTo(Duration.ofMinutes(90));
        assertThat(TenantBootstrapTokenController.parseLifetime("24h")).isEqualTo(Duration.ofHours(24));
        assertThat(TenantBootstrapTokenController.parseLifetime("24h1m")).isNull();
    }
}
