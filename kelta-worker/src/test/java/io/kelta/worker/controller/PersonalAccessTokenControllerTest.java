package io.kelta.worker.controller;

import io.kelta.runtime.router.UserIdResolver;
import io.kelta.runtime.storage.UniqueConstraintViolationException;
import io.kelta.worker.service.SecurityAuditLogger;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalAccessTokenController Tests")
@WithTenant("tenant-1")
class PersonalAccessTokenControllerTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private UserIdResolver userIdResolver;

    private PersonalAccessTokenController controller;

    @BeforeEach
    void setUp() {
        controller = new PersonalAccessTokenController(jdbcTemplate, redisTemplate, userIdResolver);
        // Default: resolve email to UUID
        lenient().when(userIdResolver.resolve(eq("user-1"), eq("tenant-1"))).thenReturn("user-1");
    }

    @Nested
    @DisplayName("GET /api/me/tokens")
    class ListTokens {
        @Test
        void shouldListUserTokens() {
            when(jdbcTemplate.queryForList(contains("user_api_token"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(
                            Map.of("id", "tok-1", "name", "Test Token", "token_prefix", "klt_ABCD")));

            var response = controller.listTokens("user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void shouldReturn400WhenNoUserContext() {
            var response = controller.listTokens(null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @WithTenant(none = true)
        void shouldReturn400WhenNoTenantContext() {
            var response = controller.listTokens("user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void shouldIncludeUsageCountFromRedis() {
            when(jdbcTemplate.queryForList(contains("user_api_token"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(
                            Map.of("id", "tok-1", "name", "Test Token", "token_prefix", "klt_ABCD",
                                    "token_hash", "hash-abc")));
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("pat-usage:hash-abc")).thenReturn("42");

            var response = controller.listTokens("user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tokens = (List<Map<String, Object>>) body.get("data");
            assertThat(tokens).hasSize(1);
            assertThat(tokens.get(0).get("requestCount")).isEqualTo(42L);
            assertThat(tokens.get(0)).doesNotContainKey("tokenHash");
        }

        @Test
        void shouldDefaultUsageCountToZeroWhenRedisHasNoEntry() {
            when(jdbcTemplate.queryForList(contains("user_api_token"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(
                            Map.of("id", "tok-1", "name", "Test Token", "token_prefix", "klt_ABCD",
                                    "token_hash", "hash-abc")));
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("pat-usage:hash-abc")).thenReturn(null);

            var response = controller.listTokens("user-1");

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tokens = (List<Map<String, Object>>) body.get("data");
            assertThat(tokens.get(0).get("requestCount")).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("POST /api/me/tokens")
    class CreateToken {
        @Test
        void shouldCreateTokenSuccessfully() {
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("email", "user@test.com")));
            when(jdbcTemplate.queryForList(contains("COUNT"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("cnt", 0L)));

            var response = controller.createToken("user-1",
                    Map.of("name", "My Token", "expiresInDays", 90));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsKey("token");
            String token = (String) body.get("token");
            assertThat(token).startsWith("klt_");
            assertThat(token).hasSize(44); // "klt_" + 40 chars
        }

        @Test
        void shouldRefuseAServiceUserWith403AndMintNothing() {
            when(userIdResolver.resolve("svc-1", "tenant-1")).thenReturn("svc-1");
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("svc-1"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("email", "billing.ab12@service.kelta.invalid", "user_type", "SERVICE")));

            var response = controller.createToken("svc-1", Map.of("name", "Escape hatch", "expiresInDays", 365));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(jdbcTemplate, never()).update(contains("INSERT INTO user_api_token"), any(Object[].class));
        }

        @Test
        void shouldThrowUniqueViolationWhenNameAlreadyExists() {
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("email", "user@test.com")));
            when(jdbcTemplate.queryForList(contains("COUNT"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("cnt", 0L)));
            when(jdbcTemplate.update(contains("INSERT INTO user_api_token"), any(Object[].class)))
                    .thenThrow(new DuplicateKeyException(
                            "duplicate key value violates unique constraint \"uq_user_api_token_name_per_user\""));

            assertThatThrownBy(() -> controller.createToken("user-1",
                    Map.of("name", "My Token", "expiresInDays", 90)))
                    .isInstanceOf(UniqueConstraintViolationException.class)
                    .hasMessageContaining("name");
        }

        @Test
        void shouldReturn400WhenNameMissing() {
            var response = controller.createToken("user-1", Map.of());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void shouldReturn400WhenExpirationTooLong() {
            var response = controller.createToken("user-1",
                    Map.of("name", "Token", "expiresInDays", 999));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void shouldReturn400WhenMaxTokensReached() {
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("email", "user@test.com")));
            when(jdbcTemplate.queryForList(contains("COUNT"), eq("user-1"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("cnt", 10L)));

            var response = controller.createToken("user-1",
                    Map.of("name", "Token", "expiresInDays", 90));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("DELETE /api/me/tokens/{tokenId}")
    class RevokeToken {
        @Test
        void shouldRevokeTokenSuccessfully() {
            when(jdbcTemplate.queryForList(contains("FROM user_api_token WHERE tenant_id = ?"),
                    eq("tenant-1"), eq("tok-1"), eq("user-1")))
                    .thenReturn(List.of(Map.of("id", "tok-1", "token_hash", "abc123")));
            when(jdbcTemplate.update(contains("revoked = true"), any(), any(), eq("tok-1"), eq("tenant-1")))
                    .thenReturn(1);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            try (MockedStatic<SecurityAuditLogger> audit = mockStatic(SecurityAuditLogger.class)) {
                var response = controller.revokeToken("tok-1", "user-1");

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                audit.verify(() -> SecurityAuditLogger.log(SecurityAuditLogger.EventType.PAT_REVOKED,
                        "user-1", "tok-1", "tenant-1", "success", null));
            }
            verify(redisTemplate).delete("pat:abc123");
            verify(valueOps).set(eq("pat:revoked:abc123"), eq("revoked"), eq(Duration.ofDays(366)));
        }

        @Test
        void shouldReturn404ForNonExistentToken() {
            when(jdbcTemplate.queryForList(contains("FROM user_api_token WHERE tenant_id = ?"),
                    eq("tenant-1"), eq("tok-1"), eq("user-1")))
                    .thenReturn(List.of());

            var response = controller.revokeToken("tok-1", "user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(jdbcTemplate, never()).update(contains("revoked = true"), any(Object[].class));
        }

        @Test
        void shouldReturn404WhenTokenRevokedConcurrently() {
            when(jdbcTemplate.queryForList(contains("FROM user_api_token WHERE tenant_id = ?"),
                    eq("tenant-1"), eq("tok-1"), eq("user-1")))
                    .thenReturn(List.of(Map.of("id", "tok-1", "token_hash", "abc123")));
            when(jdbcTemplate.update(contains("revoked = true"), any(), any(), eq("tok-1"), eq("tenant-1")))
                    .thenReturn(0);

            var response = controller.revokeToken("tok-1", "user-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verifyNoInteractions(redisTemplate);
        }

        @Test
        void shouldReturn400WhenNoUserContext() {
            var response = controller.revokeToken("tok-1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("revokeAllUserTokens / revokeDeletedTokens")
    class RevokeAllUserTokens {
        @Test
        void shouldRevokeEveryLiveTokenOfTheUserInTheTenant() {
            when(jdbcTemplate.queryForList(contains("WHERE tenant_id = ? AND revoked = false AND (user_id = ?)"),
                    eq("tenant-1"), eq("user-1")))
                    .thenReturn(List.of(Map.of("id", "tok-1", "token_hash", "h1"),
                            Map.of("id", "tok-2", "token_hash", "h2")));
            when(jdbcTemplate.update(contains("revoked = true"), any(), any(), anyString(), eq("tenant-1")))
                    .thenReturn(1);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            List<String> revoked = controller.revokeAllUserTokens("tenant-1", "user-1", "system",
                    SecurityAuditLogger.EventType.PAT_REVOKED, "user status INACTIVE");

            assertThat(revoked).containsExactly("tok-1", "tok-2");
            verify(valueOps).set("pat:revoked:h1", "revoked", Duration.ofDays(366));
            verify(valueOps).set("pat:revoked:h2", "revoked", Duration.ofDays(366));
        }

        @Test
        void shouldBlockDeletedTokensInRedisWithoutTouchingTheDatabase() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            controller.revokeDeletedTokens("tenant-1",
                    List.of(new PersonalAccessTokenController.LiveToken("tok-1", "h1")), "system",
                    SecurityAuditLogger.EventType.PAT_REVOKED, "user deleted");

            verify(redisTemplate).delete("pat:h1");
            verify(valueOps).set("pat:revoked:h1", "revoked", Duration.ofDays(366));
            verifyNoInteractions(jdbcTemplate);
        }
    }

    @Nested
    @DisplayName("GET /api/me/tokens/validate/{tokenHash}")
    class ValidateToken {
        private Map<String, Object> row(Instant expiresAt) {
            return Map.of("id", "tok-1", "user_id", "user-1", "tenant_id", "tenant-1",
                    "scopes", "[\"api\"]", "expires_at", Timestamp.from(expiresAt),
                    "email", "user@test.com", "status", "ACTIVE");
        }

        @Test
        void shouldReturnMetadataForLiveToken() {
            when(jdbcTemplate.queryForList(contains("FROM user_api_token"), eq("hash-live")))
                    .thenReturn(List.of(row(Instant.now().plus(1, ChronoUnit.HOURS))));
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var response = controller.validateToken("hash-live");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void shouldReturn404ForExpiredBootstrapToken() {
            when(jdbcTemplate.queryForList(contains("FROM user_api_token"), eq("hash-expired")))
                    .thenReturn(List.of(row(Instant.now().minus(1, ChronoUnit.MINUTES))));

            var response = controller.validateToken("hash-expired");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verifyNoInteractions(redisTemplate);
        }

        @Test
        void shouldReturn404ForRevokedToken() {
            // The lookup only matches live rows; a revoked token's row is filtered out by SQL.
            when(jdbcTemplate.queryForList(contains("t.revoked = false"), eq("hash-revoked")))
                    .thenReturn(List.of());

            var response = controller.validateToken("hash-revoked");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verifyNoInteractions(redisTemplate);
        }
    }

    @Test
    void sha256ShouldProduceConsistentHash() {
        String hash1 = PersonalAccessTokenController.sha256("test_token");
        String hash2 = PersonalAccessTokenController.sha256("test_token");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 produces 64 hex chars
    }
}
