package io.kelta.worker.listener;

import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.controller.PersonalAccessTokenController;
import io.kelta.worker.service.AuthSessionRevocationClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("UserSessionRevocationHook")
class UserSessionRevocationHookTest {

    private final AuthSessionRevocationClient client = mock(AuthSessionRevocationClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final PersonalAccessTokenController tokens =
            new PersonalAccessTokenController(jdbc, redis, mock(UserIdResolver.class));
    private final UserSessionRevocationHook hook = new UserSessionRevocationHook(
            client, jdbc, tokens, mock(PlatformTransactionManager.class));

    private static final String REVOKE_ALL = "UPDATE user_api_token SET revoked = true";

    @Test
    @DisplayName("runs on the users collection")
    void collection() {
        assertThat(hook.getCollectionName()).isEqualTo("users");
    }

    @Test
    @DisplayName("a user leaving ACTIVE is signed out of kelta-auth")
    void deactivationSignsOut() {
        hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "ACTIVE"), "t-1");

        verify(client).revokeAfterCommit("t-1", "u-1");
    }

    @Test
    @DisplayName("other updates (still active, re-activation, status untouched) sign nobody out")
    void otherUpdates() {
        hook.afterUpdate("u-1", Map.of("status", "ACTIVE", "firstName", "Ann"), Map.of("status", "ACTIVE"), "t-1");
        hook.afterUpdate("u-1", Map.of("status", "ACTIVE"), Map.of("status", "INACTIVE"), "t-1");
        hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "INACTIVE"), "t-1");
        Map<String, Object> noStatus = new HashMap<>();
        noStatus.put("status", null);
        hook.afterUpdate("u-1", noStatus, Map.of("status", "ACTIVE"), "t-1");

        verifyNoInteractions(client, redis);
        verify(jdbc, never()).queryForList(contains("UPDATE user_api_token"), any(Object[].class));
    }

    @Test
    @DisplayName("a deleted user is signed out with the login names read before the delete")
    void deleteSignsOutWithNames() {
        when(jdbc.queryForList(contains("FROM platform_user"), eq("u-1"), eq("t-1")))
                .thenReturn(List.of(Map.of("email", "a@example.test", "username", "a")));

        hook.beforeDelete("u-1", "t-1");
        hook.afterDelete("u-1", "t-1");

        verify(client).revokeAfterCommit("t-1", "u-1", List.of("a@example.test", "a"));
    }

    @Test
    @DisplayName("a delete whose names could not be read still signs the user out by id")
    void deleteWithoutNames() {
        when(jdbc.queryForList(anyString(), eq("u-1"), eq("t-1"))).thenThrow(new IllegalStateException("db down"));

        assertThat(hook.beforeDelete("u-1", "t-1").isSuccess()).isTrue();
        hook.afterDelete("u-1", "t-1");

        verify(client).revokeAfterCommit("t-1", "u-1", List.of());
    }

    @Test
    @DisplayName("a user leaving ACTIVE has every live PAT revoked in the DB and in Redis")
    void deactivationRevokesTokens() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(jdbc.queryForList(contains(REVOKE_ALL), any(Timestamp.class), any(Timestamp.class), eq("u-1"), eq("t-1")))
                .thenReturn(List.of(Map.of("id", "tok-1", "token_hash", "h1"),
                        Map.of("id", "tok-2", "token_hash", "h2")));

        hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "ACTIVE"), "t-1");

        verify(jdbc).queryForList(contains("revoked_at = ?"), any(Timestamp.class), any(Timestamp.class),
                eq("u-1"), eq("t-1"));
        verify(redis).delete("pat:h1");
        verify(redis).delete("pat:h2");
        verify(valueOps).set("pat:revoked:h1", "revoked", Duration.ofDays(366));
        verify(valueOps).set("pat:revoked:h2", "revoked", Duration.ofDays(366));
    }

    @Test
    @DisplayName("a PAT that validated before deactivation is a 404 from the validate endpoint after it")
    void validatedTokenStopsValidatingAfterDeactivation() {
        when(redis.opsForValue()).thenReturn(valueOps);
        // Stands in for user_api_token: the validate query filters "t.revoked = false", the revoke sets it.
        AtomicBoolean revoked = new AtomicBoolean(false);
        Map<String, Object> row = Map.of(
                "id", "tok-1", "user_id", "u-1", "tenant_id", "t-1", "scopes", "[\"api\"]",
                "expires_at", Timestamp.from(Instant.now().plusSeconds(3600)),
                "email", "a@example.test", "status", "ACTIVE");
        when(jdbc.queryForList(contains("t.revoked = false"), eq("h1")))
                .thenAnswer(inv -> revoked.get() ? List.of() : List.of(row));
        when(jdbc.queryForList(contains(REVOKE_ALL), any(Timestamp.class), any(Timestamp.class), eq("u-1"), eq("t-1")))
                .thenAnswer(inv -> revoked.getAndSet(true) ? List.of()
                        : List.of(Map.of("id", "tok-1", "token_hash", "h1")));

        assertThat(tokens.validateToken("h1").getStatusCode()).isEqualTo(HttpStatus.OK);

        hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "ACTIVE"), "t-1");

        assertThat(tokens.validateToken("h1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(redis).delete("pat:h1");
        verify(valueOps).set("pat:revoked:h1", "revoked", Duration.ofDays(366));
    }

    @Test
    @DisplayName("a Redis failure never fails the user write; the DB revoke still happens")
    void redisFailureNeverThrows() {
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        when(jdbc.queryForList(contains(REVOKE_ALL), any(Timestamp.class), any(Timestamp.class), eq("u-1"), eq("t-1")))
                .thenReturn(List.of(Map.of("id", "tok-1", "token_hash", "h1")));

        assertThatCode(() -> hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "ACTIVE"), "t-1"))
                .doesNotThrowAnyException();

        verify(jdbc).queryForList(contains(REVOKE_ALL), any(Timestamp.class), any(Timestamp.class), eq("u-1"), eq("t-1"));
    }

    @Test
    @DisplayName("a DB failure while revoking never fails the user write")
    void dbFailureNeverThrows() {
        when(jdbc.queryForList(contains(REVOKE_ALL), any(Timestamp.class), any(Timestamp.class), eq("u-1"), eq("t-1")))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "ACTIVE"), "t-1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("inside a transaction the tokens are revoked only after it commits")
    void revokesAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "ACTIVE"), "t-1");

            verify(jdbc, never()).queryForList(contains(REVOKE_ALL), any(Object[].class));

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

            verify(jdbc).queryForList(contains(REVOKE_ALL), any(Timestamp.class), any(Timestamp.class), eq("u-1"), eq("t-1"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("a deleted user's tokens, read before the delete cascades them away, are evicted from Redis")
    void deleteEvictsTokensReadBeforeDelete() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(jdbc.queryForList(contains("FROM platform_user"), eq("u-1"), eq("t-1")))
                .thenReturn(List.of(Map.of("email", "a@example.test", "username", "a")));
        when(jdbc.queryForList(contains("SELECT id, token_hash FROM user_api_token"), eq("u-1"), eq("t-1")))
                .thenReturn(List.of(Map.of("id", "tok-1", "token_hash", "h1")));
        // ON DELETE CASCADE removed the rows: the UPDATE finds nothing.
        when(jdbc.queryForList(contains(REVOKE_ALL), any(Timestamp.class), any(Timestamp.class), eq("u-1"), eq("t-1"))).thenReturn(List.of());

        hook.beforeDelete("u-1", "t-1");
        hook.afterDelete("u-1", "t-1");

        verify(jdbc).queryForList(contains(REVOKE_ALL), any(Timestamp.class), any(Timestamp.class), eq("u-1"), eq("t-1"));
        verify(redis).delete("pat:h1");
        verify(valueOps).set("pat:revoked:h1", "revoked", Duration.ofDays(366));
    }

    @Test
    @DisplayName("a token revoked in the DB on delete is evicted once, not twice")
    void deleteDoesNotEvictTwice() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(jdbc.queryForList(contains("FROM platform_user"), eq("u-1"), eq("t-1")))
                .thenReturn(List.of(Map.of("email", "a@example.test")));
        when(jdbc.queryForList(contains("SELECT id, token_hash FROM user_api_token"), eq("u-1"), eq("t-1")))
                .thenReturn(List.of(Map.of("id", "tok-1", "token_hash", "h1")));
        when(jdbc.queryForList(contains(REVOKE_ALL), any(Timestamp.class), any(Timestamp.class), eq("u-1"), eq("t-1")))
                .thenReturn(List.of(Map.of("id", "tok-1", "token_hash", "h1")));

        hook.beforeDelete("u-1", "t-1");
        hook.afterDelete("u-1", "t-1");

        verify(redis).delete("pat:h1");
    }
}
