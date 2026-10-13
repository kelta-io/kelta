package io.kelta.worker.controller;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.CoreSystemCollections;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.DelegatedAdminScopeRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.UserInviteService;
import io.kelta.worker.service.delegated.DelegatedAdminService;
import io.kelta.worker.service.delegated.DelegatedAdminService.EffectiveDelegatedScope;
import io.kelta.worker.util.WithTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DelegatedUserAdminController Tests")
@WithTenant("tenant-1")
class DelegatedUserAdminControllerTest {

    private static final String CALLER_EMAIL = "delegate@t.io";
    private static final String TENANT = "tenant-1";
    private static final String CALLER_ID = "caller-1";

    @Mock private DelegatedAdminService delegatedAdminService;
    @Mock private DelegatedAdminScopeRepository scopeRepository;
    @Mock private QueryEngine queryEngine;
    @Mock private CollectionRegistry collectionRegistry;
    @Mock private CerbosPermissionResolver permissionResolver;
    @Mock private UserInviteService userInviteService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private io.kelta.worker.service.AuthSessionRevocationClient authSessions;
    @Mock private HttpServletRequest request;

    private final CollectionDefinition usersDef = CoreSystemCollections.users();

    private DelegatedUserAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new DelegatedUserAdminController(delegatedAdminService, scopeRepository,
                queryEngine, collectionRegistry, permissionResolver, userInviteService, jdbcTemplate, authSessions);
        lenient().when(collectionRegistry.get("users")).thenReturn(usersDef);
    }

    // ------------------------------------------------------------- fixtures

    private static EffectiveDelegatedScope fullScope() {
        return new EffectiveDelegatedScope(true, true, true, true,
                Set.of("profile-a", "profile-b"));
    }

    private void stubCaller(EffectiveDelegatedScope scope) {
        when(permissionResolver.getEmail(request)).thenReturn(CALLER_EMAIL);
        when(delegatedAdminService.effectiveScope(CALLER_EMAIL, TENANT)).thenReturn(scope);
        lenient().when(delegatedAdminService.resolveUserId(CALLER_EMAIL, TENANT))
                .thenReturn(Optional.of(CALLER_ID));
    }

    private static Map<String, Object> userRow(String id, String profileId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("tenantId", TENANT);
        row.put("email", "target@t.io");
        row.put("profileId", profileId);
        row.put("status", "ACTIVE");
        return row;
    }

    private void stubTarget(String id, String profileId) {
        when(queryEngine.getById(eq(usersDef), eq(id)))
                .thenReturn(Optional.of(userRow(id, profileId)));
    }

    // ----------------------------------------------------------------- /me

    @Nested
    @DisplayName("GET /me")
    class Me {

        @Test
        @DisplayName("returns delegated=false shape for NONE without requiring delegation")
        void noneScopeReturnsNotDelegated() {
            when(permissionResolver.getEmail(request)).thenReturn(CALLER_EMAIL);
            when(delegatedAdminService.effectiveScope(CALLER_EMAIL, TENANT))
                    .thenReturn(EffectiveDelegatedScope.NONE);
            when(scopeRepository.findProfileNames(Set.of(), TENANT)).thenReturn(Map.of());

            ResponseEntity<Map<String, Object>> response = controller.me(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("delegated", false)
                    .containsEntry("canCreateUsers", false)
                    .containsEntry("canDeactivateUsers", false)
                    .containsEntry("canResetPasswords", false)
                    .containsEntry("manageableProfiles", List.of());
        }

        @Test
        @DisplayName("returns capabilities plus named profiles")
        void delegatedScopeReturnsCapabilitiesAndNames() {
            EffectiveDelegatedScope scope = new EffectiveDelegatedScope(true, true, false, true,
                    Set.of("profile-a", "profile-b"));
            when(permissionResolver.getEmail(request)).thenReturn(CALLER_EMAIL);
            when(delegatedAdminService.effectiveScope(CALLER_EMAIL, TENANT)).thenReturn(scope);
            when(scopeRepository.findProfileNames(Set.of("profile-a", "profile-b"), TENANT))
                    .thenReturn(Map.of("profile-a", "Sales", "profile-b", "Support"));

            ResponseEntity<Map<String, Object>> response = controller.me(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = response.getBody();
            assertThat(body)
                    .containsEntry("delegated", true)
                    .containsEntry("canCreateUsers", true)
                    .containsEntry("canDeactivateUsers", false)
                    .containsEntry("canResetPasswords", true);
            assertThat(body.get("manageableProfiles")).isEqualTo(List.of(
                    Map.of("id", "profile-a", "name", "Sales"),
                    Map.of("id", "profile-b", "name", "Support")));
        }
    }

    // ---------------------------------------------------------- GET /users

    @Nested
    @DisplayName("GET /users")
    class ListUsers {

        @Test
        @DisplayName("403 when the caller is not a delegated admin")
        void rejectsNonDelegatedCaller() {
            stubCaller(EffectiveDelegatedScope.NONE);

            assertThatThrownBy(() -> controller.listUsers(request, 50, 1))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
            verifyNoInteractions(queryEngine);
        }

        @Test
        @DisplayName("empty manageable set short-circuits to an empty collection without querying")
        void emptyManageableProfilesReturnsEmptyCollection() {
            stubCaller(new EffectiveDelegatedScope(true, false, false, false, Set.of()));

            ResponseEntity<Map<String, Object>> response = controller.listUsers(request, 50, 1);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("data", List.of());
            verifyNoInteractions(queryEngine);
        }

        @Test
        @DisplayName("queries users with an IN filter on profileId scoped to the manageable set")
        void queriesWithProfileIdInFilter() {
            stubCaller(fullScope());
            when(queryEngine.executeQuery(eq(usersDef), any(QueryRequest.class)))
                    .thenReturn(QueryResult.of(List.of(userRow("user-2", "profile-a")), 1,
                            new Pagination(1, 50)));

            ResponseEntity<Map<String, Object>> response = controller.listUsers(request, 50, 1);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryEngine).executeQuery(eq(usersDef), captor.capture());
            List<FilterCondition> filters = captor.getValue().filters();
            assertThat(filters).hasSize(1);
            FilterCondition condition = filters.get(0);
            assertThat(condition.fieldName()).isEqualTo("profileId");
            assertThat(condition.operator()).isEqualTo(FilterOperator.IN);
            @SuppressWarnings("unchecked")
            Collection<String> filterValues = (Collection<String>) condition.value();
            assertThat(filterValues).containsExactlyInAnyOrder("profile-a", "profile-b");
        }
    }

    // --------------------------------------------------------- POST /users

    @Nested
    @DisplayName("POST /users")
    class CreateUser {

        @Test
        @DisplayName("403 when the scope does not allow creating users")
        void rejectsWithoutCanCreateUsers() {
            stubCaller(new EffectiveDelegatedScope(true, false, true, true,
                    Set.of("profile-a")));

            assertThatThrownBy(() -> controller.createUser(request,
                    Map.of("email", "new@t.io", "profileId", "profile-a")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
            verifyNoInteractions(queryEngine, userInviteService);
        }

        @Test
        @DisplayName("400 when profileId is missing")
        void rejectsMissingProfileId() {
            stubCaller(fullScope());

            assertThatThrownBy(() -> controller.createUser(request, Map.of("email", "new@t.io")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
            verifyNoInteractions(queryEngine, userInviteService);
        }

        @Test
        @DisplayName("403 when the requested profile is outside the delegated scope")
        void rejectsOutOfScopeProfile() {
            stubCaller(fullScope());

            assertThatThrownBy(() -> controller.createUser(request,
                    Map.of("email", "new@t.io", "profileId", "profile-x")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
            verifyNoInteractions(queryEngine, userInviteService);
        }

        @Test
        @DisplayName("400 when the body contains a field outside the create whitelist")
        void rejectsUnknownField() {
            stubCaller(fullScope());

            assertThatThrownBy(() -> controller.createUser(request,
                    Map.of("email", "new@t.io", "profileId", "profile-a", "status", "ACTIVE")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
            verifyNoInteractions(queryEngine, userInviteService);

            assertThatThrownBy(() -> controller.createUser(request,
                    Map.of("email", "new@t.io", "profileId", "profile-a", "mfaEnabled", false)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("creates the user with server-set tenantId and PENDING_ACTIVATION, then invites")
        @SuppressWarnings("unchecked")
        void createsUserAndQueuesInvite() {
            stubCaller(fullScope());
            Map<String, Object> created = userRow("user-9", "profile-a");
            created.put("status", "PENDING_ACTIVATION");
            when(queryEngine.create(eq(usersDef), any())).thenReturn(created);

            // JSON:API-wrapped body exercises the attributes() unwrapping path.
            Map<String, Object> body = Map.of("data", Map.of("attributes", Map.of(
                    "email", "new@t.io", "profileId", "profile-a", "firstName", "New")));
            ResponseEntity<Map<String, Object>> response = controller.createUser(request, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(eq(usersDef), dataCaptor.capture());
            assertThat(dataCaptor.getValue())
                    .containsEntry("email", "new@t.io")
                    .containsEntry("profileId", "profile-a")
                    .containsEntry("firstName", "New")
                    .containsEntry("tenantId", TENANT)
                    .containsEntry("status", "PENDING_ACTIVATION");
            verify(userInviteService).inviteUser(TENANT, "user-9");
        }
    }

    // --------------------------------------------------- PATCH /users/{id}

    @Nested
    @DisplayName("PATCH /users/{id}")
    class UpdateUser {

        @Test
        @DisplayName("404 when the target user does not exist")
        void notFoundWhenTargetMissing() {
            stubCaller(fullScope());
            when(queryEngine.getById(eq(usersDef), eq("user-2"))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.updateUser(request, "user-2",
                    Map.of("firstName", "Renamed")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("404 when the target's current profile is outside the scope")
        void notFoundWhenTargetProfileOutOfScope() {
            stubCaller(fullScope());
            stubTarget("user-2", "profile-x");

            assertThatThrownBy(() -> controller.updateUser(request, "user-2",
                    Map.of("firstName", "Renamed")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("403 when the delegated admin edits themselves")
        void rejectsSelfEdit() {
            stubCaller(fullScope());
            stubTarget(CALLER_ID, "profile-a");

            assertThatThrownBy(() -> controller.updateUser(request, CALLER_ID,
                    Map.of("firstName", "Renamed")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("400 when the body tries to change the email")
        void rejectsEmailChange() {
            stubCaller(fullScope());
            stubTarget("user-2", "profile-a");

            assertThatThrownBy(() -> controller.updateUser(request, "user-2",
                    Map.of("email", "attacker@evil.io")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
            verify(queryEngine, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("403 when changing status without canDeactivateUsers")
        void rejectsStatusChangeWithoutCapability() {
            stubCaller(new EffectiveDelegatedScope(true, true, false, true,
                    Set.of("profile-a")));
            stubTarget("user-2", "profile-a");

            assertThatThrownBy(() -> controller.updateUser(request, "user-2",
                    Map.of("status", "INACTIVE")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
            verify(queryEngine, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("400 when setting a status outside ACTIVE/INACTIVE even with the capability")
        void rejectsLockedStatus() {
            stubCaller(fullScope());
            stubTarget("user-2", "profile-a");

            assertThatThrownBy(() -> controller.updateUser(request, "user-2",
                    Map.of("status", "LOCKED")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
            verify(queryEngine, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("403 when moving the target to a profile outside the scope")
        void rejectsProfileChangeOutOfScope() {
            stubCaller(fullScope());
            stubTarget("user-2", "profile-a");

            assertThatThrownBy(() -> controller.updateUser(request, "user-2",
                    Map.of("profileId", "profile-x")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
            verify(queryEngine, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("updates whitelisted fields and returns 200")
        @SuppressWarnings("unchecked")
        void updatesWhitelistedFields() {
            stubCaller(fullScope());
            stubTarget("user-2", "profile-a");
            Map<String, Object> updated = userRow("user-2", "profile-a");
            updated.put("firstName", "Renamed");
            when(queryEngine.update(eq(usersDef), eq("user-2"), any()))
                    .thenReturn(Optional.of(updated));

            ResponseEntity<Map<String, Object>> response =
                    controller.updateUser(request, "user-2", Map.of("firstName", "Renamed"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).update(eq(usersDef), eq("user-2"), dataCaptor.capture());
            assertThat(dataCaptor.getValue()).containsEntry("firstName", "Renamed");
        }
    }

    // --------------------------------------------- POST /users/{id}/invite

    @Nested
    @DisplayName("POST /users/{id}/invite")
    class Invite {

        @Test
        @DisplayName("404 for a target whose profile is outside the scope")
        void notFoundForOutOfScopeTarget() {
            stubCaller(fullScope());
            stubTarget("user-2", "profile-x");

            assertThatThrownBy(() -> controller.invite(request, "user-2"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
            verifyNoInteractions(userInviteService);
        }

        @Test
        @DisplayName("re-sends the invite for an in-scope target")
        void resendsInvite() {
            stubCaller(fullScope());
            stubTarget("user-2", "profile-a");
            when(userInviteService.inviteUser(TENANT, "user-2")).thenReturn("token-1");

            ResponseEntity<Map<String, String>> response = controller.invite(request, "user-2");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "QUEUED");
            verify(userInviteService).inviteUser(TENANT, "user-2");
        }
    }

    // ------------------------------------- POST /users/{id}/reset-password

    @Nested
    @DisplayName("POST /users/{id}/reset-password")
    class ResetPassword {

        @Test
        @DisplayName("403 when the scope does not allow password resets")
        void rejectsWithoutCanResetPasswords() {
            stubCaller(new EffectiveDelegatedScope(true, true, true, false,
                    Set.of("profile-a")));

            assertThatThrownBy(() -> controller.resetPassword(request, "user-2"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("sets force_change_on_login on the target's credential record")
        void setsForceChangeOnLogin() {
            stubCaller(fullScope());
            stubTarget("user-2", "profile-a");
            when(jdbcTemplate.update(contains("UPDATE user_credential"), any(), eq("user-2")))
                    .thenReturn(1);

            ResponseEntity<Map<String, String>> response =
                    controller.resetPassword(request, "user-2");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("status", "reset_initiated")
                    .containsEntry("userId", "user-2");
            verify(jdbcTemplate).update(contains("force_change_on_login = true"), any(), eq("user-2"));
            verify(authSessions).revokeAfterCommit(TENANT, "user-2");
        }
    }
}
