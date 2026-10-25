package io.kelta.worker.listener;

import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.controller.PersonalAccessTokenController;
import io.kelta.worker.service.AuthSessionRevocationClient;
import io.kelta.worker.service.SecurityAuditLogger;
import io.kelta.worker.service.UserAccessRevocationMarker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("UserSessionRevocationHook")
class UserSessionRevocationHookTest {

    private final AuthSessionRevocationClient client = mock(AuthSessionRevocationClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PersonalAccessTokenController tokens =
            new PersonalAccessTokenController(jdbc, redis, mock(UserIdResolver.class));
    private final UserAccessRevocationMarker marker = new UserAccessRevocationMarker(redis, Duration.ofHours(2));
    private final UserSessionRevocationHook hook = new UserSessionRevocationHook(client, jdbc, tokens, marker);

    private static final String LIVE_TOKENS = "SELECT id, token_hash FROM user_api_token WHERE tenant_id = ?";
    private static final String REVOKE = "SET revoked = true";

    /** Asserts {@code user:revoked:t-1:u-1} was written with a revocation time in [from, now] and a 2 h TTL. */
    private void assertMarkerWrittenSince(long from) {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("user:revoked:t-1:u-1"), value.capture(), eq(Duration.ofHours(2)));
        assertThat(Long.parseLong(value.getValue())).isBetween(from, Instant.now().getEpochSecond());
    }

    @SafeVarargs
    private void userHoldsTokens(Map<String, Object>... rows) {
        when(jdbc.queryForList(contains(LIVE_TOKENS), eq("t-1"), eq("u-1"))).thenReturn(List.of(rows));
    }

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

        verifyNoInteractions(client);
        verify(jdbc, never()).queryForList(contains(LIVE_TOKENS), any(Object[].class));
        verifyNoInteractions(redis);
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
    @DisplayName("a user leaving ACTIVE has every live PAT revoked in the database and in Redis")
    void deactivationRevokesEveryPat() {
        userHoldsTokens(Map.of("id", "tok-1", "token_hash", "hash-1"), Map.of("id", "tok-2", "token_hash", "hash-2"));
        when(jdbc.update(contains(REVOKE), any(), any(), anyString(), eq("t-1"))).thenReturn(1);
        when(redis.opsForValue()).thenReturn(valueOps);

        try (MockedStatic<SecurityAuditLogger> audit = mockStatic(SecurityAuditLogger.class)) {
            hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "ACTIVE"), "t-1");

            audit.verify(() -> SecurityAuditLogger.log(SecurityAuditLogger.EventType.PAT_REVOKED,
                    "system", "tok-1", "t-1", "success", "user status INACTIVE"));
            audit.verify(() -> SecurityAuditLogger.log(SecurityAuditLogger.EventType.PAT_REVOKED,
                    "system", "tok-2", "t-1", "success", "user status INACTIVE"));
        }
        verify(jdbc).update(contains(REVOKE), any(), any(), eq("tok-1"), eq("t-1"));
        verify(jdbc).update(contains(REVOKE), any(), any(), eq("tok-2"), eq("t-1"));
        verify(redis).delete("pat:hash-1");
        verify(redis).delete("pat:hash-2");
        verify(valueOps).set("pat:revoked:hash-1", "revoked", Duration.ofDays(366));
        verify(valueOps).set("pat:revoked:hash-2", "revoked", Duration.ofDays(366));
    }

    @Test
    @DisplayName("a Redis outage never fails the user write; the database revoke still lands")
    void redisFailureDoesNotFailTheWrite() {
        userHoldsTokens(Map.of("id", "tok-1", "token_hash", "hash-1"));
        when(jdbc.update(contains(REVOKE), any(), any(), anyString(), eq("t-1"))).thenReturn(1);
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertThatCode(() -> hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "ACTIVE"), "t-1"))
                .doesNotThrowAnyException();

        verify(jdbc).update(contains(REVOKE), any(), any(), eq("tok-1"), eq("t-1"));
        verify(client).revokeAfterCommit("t-1", "u-1");
    }

    @Test
    @DisplayName("a database failure while revoking PATs never fails the user write")
    void databaseFailureDoesNotFailTheWrite() {
        when(jdbc.queryForList(contains(LIVE_TOKENS), eq("t-1"), eq("u-1")))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "ACTIVE"), "t-1"))
                .doesNotThrowAnyException();
        verify(client).revokeAfterCommit("t-1", "u-1");
    }

    @Test
    @DisplayName("a PAT that validated before deactivation is refused after it, and its revocation key is written")
    void deactivatedUsersPatNoLongerValidates() {
        AtomicBoolean revoked = new AtomicBoolean(false);
        when(jdbc.queryForList(contains("t.revoked = false"), eq("hash-1"))).thenAnswer(inv -> revoked.get()
                ? List.of()
                : List.of(Map.of("id", "tok-1", "user_id", "u-1", "tenant_id", "t-1", "scopes", "[\"api\"]",
                        "expires_at", Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                        "email", "a@example.test", "status", "ACTIVE")));
        userHoldsTokens(Map.of("id", "tok-1", "token_hash", "hash-1"));
        when(jdbc.update(contains(REVOKE), any(), any(), eq("tok-1"), eq("t-1"))).thenAnswer(inv -> {
            revoked.set(true);
            return 1;
        });
        when(redis.opsForValue()).thenReturn(valueOps);

        assertThat(tokens.validateToken("hash-1").getStatusCode()).isEqualTo(HttpStatus.OK);

        hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "ACTIVE"), "t-1");

        assertThat(tokens.validateToken("hash-1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(redis).delete("pat:hash-1");
        verify(valueOps).set("pat:revoked:hash-1", "revoked", Duration.ofDays(366));
    }

    @Test
    @DisplayName("a deleted user's PATs, read before the cascade removes them, are revoked in Redis")
    void deleteRevokesPatsReadBeforeTheDelete() {
        userHoldsTokens(Map.of("id", "tok-1", "token_hash", "hash-1"));
        when(redis.opsForValue()).thenReturn(valueOps);

        hook.beforeDelete("u-1", "t-1");
        verifyNoInteractions(redis);
        try (MockedStatic<SecurityAuditLogger> audit = mockStatic(SecurityAuditLogger.class)) {
            hook.afterDelete("u-1", "t-1");

            audit.verify(() -> SecurityAuditLogger.log(SecurityAuditLogger.EventType.PAT_REVOKED,
                    "system", "tok-1", "t-1", "success", "user deleted"));
        }

        verify(redis).delete("pat:hash-1");
        verify(valueOps).set("pat:revoked:hash-1", "revoked", Duration.ofDays(366));
    }

    @Test
    @DisplayName("a delete whose Redis revoke fails still completes")
    void deleteWithRedisDown() {
        userHoldsTokens(Map.of("id", "tok-1", "token_hash", "hash-1"));
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("redis down"));

        hook.beforeDelete("u-1", "t-1");
        assertThatCode(() -> hook.afterDelete("u-1", "t-1")).doesNotThrowAnyException();
        verify(client).revokeAfterCommit("t-1", "u-1", List.of());
    }

    @Test
    @DisplayName("a user leaving ACTIVE gets a user:revoked marker (epoch seconds, 2 h TTL) so the gateway rejects their access tokens")
    void deactivationWritesAccessRevocationMarker() {
        when(redis.opsForValue()).thenReturn(valueOps);
        long before = Instant.now().getEpochSecond();

        hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "ACTIVE"), "t-1");

        assertMarkerWrittenSince(before);
    }

    @Test
    @DisplayName("a deleted user gets the same user:revoked marker")
    void deleteWritesAccessRevocationMarker() {
        when(redis.opsForValue()).thenReturn(valueOps);
        long before = Instant.now().getEpochSecond();

        hook.beforeDelete("u-1", "t-1");
        hook.afterDelete("u-1", "t-1");

        assertMarkerWrittenSince(before);
    }

    @Test
    @DisplayName("updates that do not leave ACTIVE write no marker")
    void otherUpdatesWriteNoMarker() {
        hook.afterUpdate("u-1", Map.of("status", "ACTIVE"), Map.of("status", "INACTIVE"), "t-1");

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("a Redis failure while writing the marker never fails the user write")
    void markerFailureDoesNotFailTheWrite() {
        when(redis.opsForValue()).thenReturn(valueOps);
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> hook.afterUpdate("u-1", Map.of("status", "INACTIVE"), Map.of("status", "ACTIVE"), "t-1"))
                .doesNotThrowAnyException();
        hook.beforeDelete("u-1", "t-1");
        assertThatCode(() -> hook.afterDelete("u-1", "t-1")).doesNotThrowAnyException();

        verify(client).revokeAfterCommit("t-1", "u-1");
        verify(client).revokeAfterCommit("t-1", "u-1", List.of());
    }
}
