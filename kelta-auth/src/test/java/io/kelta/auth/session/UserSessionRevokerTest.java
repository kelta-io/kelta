package io.kelta.auth.session;

import io.kelta.auth.model.KeltaUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("UserSessionRevoker")
class UserSessionRevokerTest {

    @SuppressWarnings("unchecked")
    private final FindByIndexNameSessionRepository<MapSession> sessions = mock(FindByIndexNameSessionRepository.class);
    private final OAuth2AuthorizationService authorizations = mock(OAuth2AuthorizationService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserSessionRevoker revoker = new UserSessionRevoker(sessions, authorizations, jdbc);

    private static KeltaUserDetails user(String tenantId, String userId) {
        return new KeltaUserDetails(userId, "user@example.test", tenantId, "p", "Standard", "User",
                null, true, false, false);
    }

    private static OAuth2Authorization authorizationOf(Object principal) {
        OAuth2Authorization authorization = mock(OAuth2Authorization.class);
        when(authorization.getAttribute(Principal.class.getName())).thenReturn(principal);
        return authorization;
    }

    @Test
    @DisplayName("an authorization belongs to the user only when its stored principal has the same tenant and user id")
    void belongsToMatchesTenantAndUser() {
        KeltaUserDetails mine = user("t1", "u1");
        assertThat(UserSessionRevoker.belongsTo(authorizationOf(
                UsernamePasswordAuthenticationToken.authenticated(mine, null, List.of())), "t1", "u1")).isTrue();
        assertThat(UserSessionRevoker.belongsTo(authorizationOf(
                UsernamePasswordAuthenticationToken.authenticated(user("t2", "u2"), null, List.of())), "t1", "u1"))
                .as("same email, other tenant").isFalse();
        assertThat(UserSessionRevoker.belongsTo(authorizationOf(
                UsernamePasswordAuthenticationToken.authenticated("user@example.test", null, List.of())), "t1", "u1"))
                .as("not a platform user").isFalse();
        assertThat(UserSessionRevoker.belongsTo(authorizationOf(null), "t1", "u1")).isFalse();
    }

    @Test
    @DisplayName("ends every other session in the user's index and keeps the current one")
    void endsOtherSessions() {
        when(jdbc.queryForList(contains("FROM platform_user"), eq("u1")))
                .thenReturn(List.of(Map.of("tenant_id", "t1", "email", "user@example.test")));
        when(sessions.findByIndexNameAndIndexValue(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "t1:u1"))
                .thenReturn(Map.of("current", new MapSession("current"), "other", new MapSession("other")));

        UserSessionRevoker.Result result = revoker.signOutElsewhere("u1", "current");

        assertThat(result.sessionsEnded()).isEqualTo(1);
        verify(sessions).deleteById("other");
        verify(sessions, never()).deleteById("current");
    }

    @Test
    @DisplayName("an unknown user, a missing id or a failing store never fails the password change")
    void neverThrows() {
        assertThat(revoker.signOutElsewhere(null, "s")).isEqualTo(new UserSessionRevoker.Result(0, 0));
        when(jdbc.queryForList(contains("FROM platform_user"), eq("gone"))).thenReturn(List.of());
        assertThat(revoker.signOutElsewhere("gone", "s")).isEqualTo(new UserSessionRevoker.Result(0, 0));

        when(jdbc.queryForList(contains("FROM platform_user"), eq("u1")))
                .thenReturn(List.of(Map.of("tenant_id", "t1", "email", "user@example.test")));
        when(sessions.findByIndexNameAndIndexValue(anyString(), anyString()))
                .thenThrow(new IllegalStateException("redis down"));
        when(jdbc.queryForList(contains("FROM oauth2_authorization"), eq(String.class), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThat(revoker.signOutElsewhere("u1", "s")).isEqualTo(new UserSessionRevoker.Result(0, 0));
    }

    @Test
    @DisplayName("signOutEverywhere ends every session of the user, the current one included, and revokes only that user's authorizations")
    void signsOutEverywhere() {
        when(jdbc.queryForList(contains("FROM platform_user"), eq("u1")))
                .thenReturn(List.of(Map.of("tenant_id", "t1", "email", "user@example.test")));
        when(sessions.findByIndexNameAndIndexValue(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "t1:u1"))
                .thenReturn(Map.of("a", new MapSession("a"), "b", new MapSession("b")));
        when(jdbc.queryForList(contains("FROM oauth2_authorization"), eq(String.class),
                eq("user@example.test"), eq("user@example.test"))).thenReturn(List.of("mine", "theirs"));
        OAuth2Authorization mine = authorizationOf(UsernamePasswordAuthenticationToken.authenticated(
                user("t1", "u1"), null, List.of()));
        OAuth2Authorization theirs = authorizationOf(UsernamePasswordAuthenticationToken.authenticated(
                user("t2", "u2"), null, List.of()));
        when(authorizations.findById("mine")).thenReturn(mine);
        when(authorizations.findById("theirs")).thenReturn(theirs);

        UserSessionRevoker.Result result = revoker.signOutEverywhere("t1", "u1", List.of());

        assertThat(result).isEqualTo(new UserSessionRevoker.Result(2, 1));
        verify(sessions).deleteById("a");
        verify(sessions).deleteById("b");
        verify(authorizations).remove(mine);
        verify(authorizations, never()).remove(theirs);
    }

    @Test
    @DisplayName("signOutEverywhere leaves a user of another tenant alone")
    void signOutEverywhereChecksTheTenant() {
        when(jdbc.queryForList(contains("FROM platform_user"), eq("u1")))
                .thenReturn(List.of(Map.of("tenant_id", "t1", "email", "user@example.test")));

        assertThat(revoker.signOutEverywhere("t2", "u1", List.of("user@example.test")))
                .isEqualTo(new UserSessionRevoker.Result(0, 0));
        verify(sessions, never()).findByIndexNameAndIndexValue(anyString(), anyString());
        verify(authorizations, never()).remove(any());
    }

    @Test
    @DisplayName("signOutEverywhere for a removed user finds authorizations by the login names it was given")
    void signOutEverywhereForARemovedUser() {
        when(jdbc.queryForList(contains("FROM platform_user"), eq("u1"))).thenReturn(List.of());
        when(sessions.findByIndexNameAndIndexValue(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "t1:u1"))
                .thenReturn(Map.of());
        when(jdbc.queryForList(contains("FROM oauth2_authorization"), eq(String.class),
                eq("gone@example.test"), eq("gone"))).thenReturn(List.of("mine"));
        OAuth2Authorization mine = authorizationOf(UsernamePasswordAuthenticationToken.authenticated(
                user("t1", "u1"), null, List.of()));
        when(authorizations.findById("mine")).thenReturn(mine);

        assertThat(revoker.signOutEverywhere("t1", "u1", List.of("gone@example.test", "gone")).authorizationsRevoked())
                .isEqualTo(1);
        verify(authorizations).remove(mine);
    }

    @Test
    @DisplayName("signOutEverywhere for an anonymized user also finds authorizations by the names it was given")
    void signOutEverywhereForAnAnonymizedUser() {
        when(jdbc.queryForList(contains("FROM platform_user"), eq("u1")))
                .thenReturn(List.of(Map.of("tenant_id", "t1", "email", "deleted+u1@invalid")));
        when(sessions.findByIndexNameAndIndexValue(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "t1:u1"))
                .thenReturn(Map.of());
        when(jdbc.queryForList(contains("FROM oauth2_authorization"), eq(String.class),
                eq("deleted+u1@invalid"), eq("old@example.test"))).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM oauth2_authorization"), eq(String.class),
                eq("old"), eq("old"))).thenReturn(List.of("mine"));
        OAuth2Authorization mine = authorizationOf(UsernamePasswordAuthenticationToken.authenticated(
                user("t1", "u1"), null, List.of()));
        when(authorizations.findById("mine")).thenReturn(mine);

        assertThat(revoker.signOutEverywhere("t1", "u1", List.of("old@example.test", "old")).authorizationsRevoked())
                .isEqualTo(1);
        verify(authorizations).remove(mine);
    }

    @Test
    @DisplayName("signOutEverywhere without a tenant or user id does nothing")
    void signOutEverywhereNeedsBothIds() {
        assertThat(revoker.signOutEverywhere(null, "u1", List.of())).isEqualTo(new UserSessionRevoker.Result(0, 0));
        assertThat(revoker.signOutEverywhere("t1", " ", List.of())).isEqualTo(new UserSessionRevoker.Result(0, 0));
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }
}
