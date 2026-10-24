package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.controller.PersonalAccessTokenController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("TenantAdminInviteService")
class TenantAdminInviteServiceTest {

    private static final String TENANT = "managed-tenant";
    private static final String SLUG = "acme";
    private static final String ACTOR = "ops@example.com";

    private JdbcTemplate jdbcTemplate;
    private UserInviteService userInviteService;
    private PersonalAccessTokenController patController;
    private TenantAdminInviteService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        userInviteService = mock(UserInviteService.class);
        patController = mock(PersonalAccessTokenController.class);
        service = new TenantAdminInviteService(jdbcTemplate, userInviteService, patController);
    }

    private void stubTenantAndAdmin() {
        when(jdbcTemplate.queryForList("SELECT slug FROM tenant WHERE id = ?", String.class, TENANT))
                .thenReturn(List.of(SLUG));
        when(jdbcTemplate.queryForList(contains("FROM platform_user"), eq(TENANT), eq(SLUG + "-admin")))
                .thenReturn(List.of(Map.of("id", "admin-user")));
    }

    @Test
    @DisplayName("sets the seeded admin's email and invites them, bound to the managed tenant")
    void invitesUnderManagedTenant() {
        stubTenantAndAdmin();
        List<String> bound = new ArrayList<>();
        when(userInviteService.inviteUser(TENANT, "admin-user")).thenAnswer(inv -> {
            bound.add(TenantContext.get());
            return "token";
        });

        var result = TenantContext.callWithTenant("caller-tenant",
                () -> service.inviteSeededAdmin(TENANT, "owner@example.com", ACTOR));

        assertThat(result).contains(new TenantAdminInviteService.AdminInvite("admin-user", "owner@example.com"));
        verify(jdbcTemplate).update(contains("UPDATE platform_user SET email"),
                eq("owner@example.com"), eq("admin-user"), eq(TENANT));
        assertThat(bound).containsExactly(TENANT);
        InOrder order = inOrder(userInviteService, patController);
        order.verify(userInviteService).inviteUser(TENANT, "admin-user");
        order.verify(patController).revokeBootstrapTokens(TENANT, ACTOR);
    }

    @Test
    @DisplayName("empty when the tenant does not exist")
    void emptyForUnknownTenant() {
        when(jdbcTemplate.queryForList("SELECT slug FROM tenant WHERE id = ?", String.class, TENANT))
                .thenReturn(List.of());

        assertThat(service.inviteSeededAdmin(TENANT, "owner@example.com", ACTOR)).isEmpty();
        verifyNoInteractions(userInviteService, patController);
    }

    @Test
    @DisplayName("empty when the tenant has no seeded admin")
    void emptyWithoutSeededAdmin() {
        when(jdbcTemplate.queryForList("SELECT slug FROM tenant WHERE id = ?", String.class, TENANT))
                .thenReturn(List.of(SLUG));
        when(jdbcTemplate.queryForList(contains("FROM platform_user"), eq(TENANT), eq(SLUG + "-admin")))
                .thenReturn(List.of());

        assertThat(service.inviteSeededAdmin(TENANT, "owner@example.com", ACTOR)).isEmpty();
        verify(jdbcTemplate, never()).update(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(userInviteService);
    }

    @Test
    @DisplayName("409 when another user in the tenant already has the email")
    void conflictOnDuplicateEmail() {
        stubTenantAndAdmin();
        when(jdbcTemplate.update(contains("UPDATE platform_user SET email"), anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("uq_user_tenant_email"));

        assertThatThrownBy(() -> service.inviteSeededAdmin(TENANT, "taken@example.com", ACTOR))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);
        verifyNoInteractions(userInviteService, patController);
    }

    @Test
    @DisplayName("normalizeEmail trims, lower-cases and rejects implausible addresses")
    void normalizeEmail() {
        assertThat(TenantAdminInviteService.normalizeEmail(" Owner@Example.COM ")).isEqualTo("owner@example.com");
        assertThat(TenantAdminInviteService.normalizeEmail(null)).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail(42)).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("")).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("no-at-sign")).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("@example.com")).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("owner@")).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("a@b@c.com")).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("own er@example.com")).isNull();
        assertThat(TenantAdminInviteService.normalizeEmail("a".repeat(250) + "@x.io")).isNull();
    }

    /**
     * The claim against a real (H2) {@code user_api_token} table and the real
     * {@link PersonalAccessTokenController}, so the revoke SQL itself is exercised: H2 has no RLS,
     * which makes the explicit {@code tenant_id} filter the only thing keeping the other tenant's
     * token alive.
     */
    @Nested
    @DisplayName("bootstrap-token revocation on claim")
    class BootstrapTokenRevocation {

        private static final String OTHER_TENANT = "other-tenant";

        private JdbcTemplate db;
        private StringRedisTemplate redis;
        private ValueOperations<String, String> valueOps;
        private PersonalAccessTokenController tokens;
        private TenantAdminInviteService claimService;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUpDatabase() {
            db = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build());
            db.execute("CREATE TABLE tenant (id VARCHAR(36) PRIMARY KEY, slug VARCHAR(100) NOT NULL)");
            db.execute("""
                    CREATE TABLE platform_user (
                        id VARCHAR(36) PRIMARY KEY,
                        tenant_id VARCHAR(36) NOT NULL,
                        username VARCHAR(100) NOT NULL,
                        email VARCHAR(254),
                        status VARCHAR(20) NOT NULL,
                        updated_at TIMESTAMP
                    )""");
            db.execute("""
                    CREATE TABLE user_api_token (
                        id VARCHAR(36) PRIMARY KEY,
                        user_id VARCHAR(36) NOT NULL,
                        tenant_id VARCHAR(36) NOT NULL,
                        name VARCHAR(200) NOT NULL,
                        token_hash VARCHAR(64) NOT NULL,
                        scopes VARCHAR(200),
                        expires_at TIMESTAMP,
                        revoked BOOLEAN DEFAULT FALSE NOT NULL,
                        revoked_at TIMESTAMP,
                        updated_at TIMESTAMP
                    )""");
            db.update("INSERT INTO tenant (id, slug) VALUES (?, ?)", TENANT, SLUG);
            db.update("INSERT INTO tenant (id, slug) VALUES (?, ?)", OTHER_TENANT, "other");
            insertUser("admin-user", TENANT, SLUG + "-admin");
            insertUser("staff-user", TENANT, "staff");
            insertUser("other-admin", OTHER_TENANT, "other-admin");

            // The seeded admin's bootstrap token, one targeted at another user of the same tenant,
            // the admin's own regular PAT, and a bootstrap token in a different tenant.
            insertToken("tok-admin-boot", "admin-user", TENANT, "bootstrap-ops@example.com-2026-10-24T01:00:00Z", "hash-admin-boot");
            insertToken("tok-staff-boot", "staff-user", TENANT, "bootstrap-ops@example.com-2026-10-24T02:00:00Z", "hash-staff-boot");
            insertToken("tok-admin-ci", "admin-user", TENANT, "ci-deploy", "hash-admin-ci");
            insertToken("tok-other-boot", "other-admin", OTHER_TENANT, "bootstrap-ops@example.com-2026-10-24T03:00:00Z", "hash-other-boot");

            redis = mock(StringRedisTemplate.class);
            valueOps = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(valueOps);
            tokens = new PersonalAccessTokenController(db, redis, mock(UserIdResolver.class));
            claimService = new TenantAdminInviteService(db, userInviteService, tokens);
        }

        private void insertUser(String id, String tenantId, String username) {
            db.update("INSERT INTO platform_user (id, tenant_id, username, email, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                    id, tenantId, username, username + "@seed.invalid");
        }

        private void insertToken(String id, String userId, String tenantId, String name, String hash) {
            db.update("INSERT INTO user_api_token (id, user_id, tenant_id, name, token_hash, scopes, expires_at) "
                            + "VALUES (?, ?, ?, ?, ?, '[\"api\"]', ?)",
                    id, userId, tenantId, name, hash, Timestamp.from(Instant.now().plus(12, ChronoUnit.HOURS)));
        }

        private boolean revoked(String tokenId) {
            return Boolean.TRUE.equals(db.queryForObject(
                    "SELECT revoked FROM user_api_token WHERE id = ?", Boolean.class, tokenId));
        }

        private Timestamp revokedAt(String tokenId) {
            return db.queryForObject("SELECT revoked_at FROM user_api_token WHERE id = ?", Timestamp.class, tokenId);
        }

        @Test
        @DisplayName("revokes every bootstrap token in the tenant; validate answers 404 and pat:revoked is written")
        void revokesTenantBootstrapTokens() {
            assertThat(tokens.validateToken("hash-admin-boot").getStatusCode()).isEqualTo(HttpStatus.OK);

            try (MockedStatic<SecurityAuditLogger> audit = mockStatic(SecurityAuditLogger.class)) {
                var result = claimService.inviteSeededAdmin(TENANT, "owner@example.com", ACTOR);

                assertThat(result).contains(new TenantAdminInviteService.AdminInvite("admin-user", "owner@example.com"));
                for (String tokenId : List.of("tok-admin-boot", "tok-staff-boot")) {
                    audit.verify(() -> SecurityAuditLogger.log(
                            SecurityAuditLogger.EventType.TENANT_BOOTSTRAP_TOKEN_REVOKED,
                            ACTOR, tokenId, TENANT, "success", "tenant claimed via admin-invite"));
                }
                audit.verify(() -> SecurityAuditLogger.log(
                        eq(SecurityAuditLogger.EventType.TENANT_BOOTSTRAP_TOKEN_REVOKED),
                        any(), eq("tok-admin-ci"), any(), any(), any()), never());
            }

            assertThat(revoked("tok-admin-boot")).isTrue();
            assertThat(revokedAt("tok-admin-boot")).isNotNull();
            assertThat(revoked("tok-staff-boot")).isTrue();
            assertThat(tokens.validateToken("hash-admin-boot").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(tokens.validateToken("hash-staff-boot").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(redis).delete("pat:hash-admin-boot");
            verify(valueOps).set("pat:revoked:hash-admin-boot", "revoked", Duration.ofDays(366));
            verify(redis).delete("pat:hash-staff-boot");
            verify(valueOps).set("pat:revoked:hash-staff-boot", "revoked", Duration.ofDays(366));
        }

        @Test
        @DisplayName("leaves the same user's regular PAT and other tenants' bootstrap tokens untouched")
        void leavesOtherTokensAlone() {
            claimService.inviteSeededAdmin(TENANT, "owner@example.com", ACTOR);

            assertThat(revoked("tok-admin-ci")).isFalse();
            assertThat(revokedAt("tok-admin-ci")).isNull();
            assertThat(revoked("tok-other-boot")).isFalse();
            assertThat(tokens.validateToken("hash-admin-ci").getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(tokens.validateToken("hash-other-boot").getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(redis, never()).delete("pat:hash-admin-ci");
            verify(valueOps, never()).set(eq("pat:revoked:hash-admin-ci"), anyString(), any(Duration.class));
            verify(redis, never()).delete("pat:hash-other-boot");
            verify(valueOps, never()).set(eq("pat:revoked:hash-other-boot"), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("a Redis outage is logged, does not fail the invite, and the DB revoke still happens")
        void redisFailureDoesNotFailInvite() {
            when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));

            var result = claimService.inviteSeededAdmin(TENANT, "owner@example.com", ACTOR);

            assertThat(result).isPresent();
            verify(userInviteService).inviteUser(TENANT, "admin-user");
            assertThat(revoked("tok-admin-boot")).isTrue();
            assertThat(revoked("tok-staff-boot")).isTrue();
            assertThat(revoked("tok-admin-ci")).isFalse();
            assertThat(tokens.validateToken("hash-admin-boot").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
