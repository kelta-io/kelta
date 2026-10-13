package io.kelta.worker.listener;

import io.kelta.worker.service.AuthSessionRevocationClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("UserSessionRevocationHook")
class UserSessionRevocationHookTest {

    private final AuthSessionRevocationClient client = mock(AuthSessionRevocationClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserSessionRevocationHook hook = new UserSessionRevocationHook(client, jdbc);

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
}
