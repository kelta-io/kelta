package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.SelfProfileWriteContext;
import io.kelta.worker.service.delegated.DelegatedWriteContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdentityCollectionGuardHook Tests")
class IdentityCollectionGuardHookTest {

    private static final String PROFILE_HEADER = "X-User-Profile-Id";

    @Mock private BootstrapRepository bootstrapRepository;

    private IdentityCollectionGuardHook hook;

    @BeforeEach
    void setUp() {
        hook = new IdentityCollectionGuardHook(bootstrapRepository);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequestWithProfile(String profileId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (profileId != null) {
            request.addHeader(PROFILE_HEADER, profileId);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @SafeVarargs
    private final void stubPermissions(Map<String, Object>... permissions) {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1"))
                .thenReturn(List.of(permissions));
    }

    @Nested
    @DisplayName("Admitted paths")
    class AdmittedPaths {

        @Test
        @DisplayName("non-guarded collection passes even with an unprivileged identity")
        void nonGuardedCollectionIsAdmitted() {
            bindRequestWithProfile("profile-1");

            BeforeSaveResult result = hook.beforeCreate("accounts", Map.of(), "tenant-1");

            assertThat(result.isSuccess()).isTrue();
            verifyNoInteractions(bootstrapRepository);
        }

        @Test
        @DisplayName("no request context bound (flows, schedulers, NATS) passes on users")
        void noRequestContextIsAdmitted() {
            BeforeSaveResult result = hook.beforeCreate("users", Map.of(), "tenant-1");

            assertThat(result.isSuccess()).isTrue();
            verifyNoInteractions(bootstrapRepository);
        }

        @Test
        @DisplayName("request context without X-User-Profile-Id (SCIM/internal) passes")
        void requestWithoutIdentityHeaderIsAdmitted() {
            bindRequestWithProfile(null);

            BeforeSaveResult result = hook.beforeCreate("users", Map.of(), "tenant-1");

            assertThat(result.isSuccess()).isTrue();
            verifyNoInteractions(bootstrapRepository);
        }

        @Test
        @DisplayName("MANAGE_USERS admits writes to users")
        void manageUsersIsAdmitted() {
            bindRequestWithProfile("profile-1");
            stubPermissions(Map.of("permission_name", "MANAGE_USERS", "granted", true));

            BeforeSaveResult result = hook.beforeCreate("users", Map.of(), "tenant-1");

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("MODIFY_ALL_DATA admits writes to group-memberships")
        void modifyAllDataIsAdmitted() {
            bindRequestWithProfile("profile-1");
            stubPermissions(Map.of("permission_name", "MODIFY_ALL_DATA", "granted", true));

            BeforeSaveResult result = hook.beforeUpdate(
                    "group-memberships", "gm-1", Map.of(), Map.of(), "tenant-1");

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("DelegatedWriteContext.runAuthorized admits an otherwise-blocked write")
        void delegatedWriteContextIsAdmitted() {
            bindRequestWithProfile("profile-1");
            BeforeSaveResult[] result = new BeforeSaveResult[1];

            DelegatedWriteContext.runAuthorized(
                    () -> result[0] = hook.beforeCreate("users", Map.of(), "tenant-1"));

            assertThat(result[0].isSuccess()).isTrue();
            verifyNoInteractions(bootstrapRepository);
        }
    }

    @Nested
    @DisplayName("Blocked paths")
    class BlockedPaths {

        @Test
        @DisplayName("identity without MANAGE_USERS or MODIFY_ALL_DATA is blocked on all "
                + "identity collections for create, update, and delete")
        void unprivilegedIdentityIsBlocked() {
            bindRequestWithProfile("profile-1");
            stubPermissions(
                    Map.of("permission_name", "API_ACCESS", "granted", true),
                    Map.of("permission_name", "MANAGE_USERS", "granted", false));

            for (String collection : List.of("users", "user-permission-sets", "group-memberships")) {
                assertBlocked(hook.beforeCreate(collection, Map.of(), "tenant-1"), collection);
                assertBlocked(hook.beforeUpdate(collection, "rec-1", Map.of(), Map.of(), "tenant-1"),
                        collection);
                assertBlocked(hook.beforeDelete(collection, "rec-1", "tenant-1"), collection);
            }
        }

        @Test
        @DisplayName("delegated-admin-scopes requires MANAGE_DELEGATED_ADMINS — MANAGE_USERS "
                + "alone is blocked")
        void manageUsersAloneIsBlockedOnDelegatedAdminScopes() {
            bindRequestWithProfile("profile-1");
            stubPermissions(Map.of("permission_name", "MANAGE_USERS", "granted", true));

            assertBlocked(hook.beforeCreate("delegated-admin-scopes", Map.of(), "tenant-1"),
                    "delegated-admin-scopes");
        }

        @Test
        @DisplayName("MANAGE_DELEGATED_ADMINS admits writes to delegated-admin-scopes")
        void manageDelegatedAdminsIsAdmittedOnDelegatedAdminScopes() {
            bindRequestWithProfile("profile-1");
            stubPermissions(Map.of("permission_name", "MANAGE_DELEGATED_ADMINS", "granted", true));

            BeforeSaveResult result =
                    hook.beforeCreate("delegated-admin-scopes", Map.of(), "tenant-1");

            assertThat(result.isSuccess()).isTrue();
        }

        private void assertBlocked(BeforeSaveResult result, String collection) {
            assertThat(result.isSuccess()).as("write to %s should be blocked", collection).isFalse();
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).message())
                    .contains("Insufficient permissions to modify " + collection);
        }
    }

    @Nested
    @DisplayName("Self-profile writes (SelfProfileWriteContext)")
    class SelfProfileWrites {

        private static final String SELF = "11111111-1111-1111-1111-111111111111";
        private static final String OTHER = "22222222-2222-2222-2222-222222222222";
        private static final Set<String> ALLOWED =
                Set.of("firstName", "lastName", "locale", "timezone");

        private BeforeSaveResult underGrant(Supplier<BeforeSaveResult> write) {
            return SelfProfileWriteContext.callAuthorized(SELF, ALLOWED, write::get);
        }

        private void stubUnprivileged() {
            stubPermissions(Map.of("permission_name", "API_ACCESS", "granted", true));
        }

        @Test
        @DisplayName("a users write without the context is still blocked")
        void withoutContextIsBlocked() {
            bindRequestWithProfile("profile-1");
            stubUnprivileged();

            BeforeSaveResult result = hook.beforeUpdate(
                    "users", SELF, Map.of("timezone", "UTC"), Map.of(), "tenant-1");

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("a context-bound own write of allowed fields is admitted")
        void ownAllowedWriteIsAdmitted() {
            bindRequestWithProfile("profile-1");

            BeforeSaveResult result = underGrant(() -> hook.beforeUpdate("users", SELF,
                    Map.of("firstName", "Sam", "timezone", "UTC", "updatedAt", Instant.now()),
                    Map.of(), "tenant-1"));

            assertThat(result.isSuccess()).isTrue();
            verifyNoInteractions(bootstrapRepository);
        }

        @Test
        @DisplayName("a context-bound write to another id is blocked")
        void otherIdIsBlocked() {
            bindRequestWithProfile("profile-1");
            stubUnprivileged();

            BeforeSaveResult result = underGrant(() -> hook.beforeUpdate(
                    "users", OTHER, Map.of("timezone", "UTC"), Map.of(), "tenant-1"));

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("a context-bound write to a non-allow-listed field is blocked")
        void nonAllowListedFieldIsBlocked() {
            bindRequestWithProfile("profile-1");
            stubUnprivileged();

            BeforeSaveResult result = underGrant(() -> hook.beforeUpdate("users", SELF,
                    Map.of("timezone", "UTC", "profileId", "admin-profile"), Map.of(), "tenant-1"));

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("the context never admits a create or delete on users")
        void createAndDeleteAreBlocked() {
            bindRequestWithProfile("profile-1");
            stubUnprivileged();

            assertThat(underGrant(() -> hook.beforeCreate(
                    "users", Map.of("firstName", "Sam"), "tenant-1")).isSuccess()).isFalse();
            assertThat(underGrant(() -> hook.beforeDelete("users", SELF, "tenant-1")).isSuccess())
                    .isFalse();
        }

        @Test
        @DisplayName("the context never admits writes to other identity collections")
        void otherIdentityCollectionsAreBlocked() {
            bindRequestWithProfile("profile-1");
            stubUnprivileged();

            for (String collection : List.of("group-memberships", "user-permission-sets")) {
                assertThat(underGrant(() -> hook.beforeUpdate(
                        collection, SELF, Map.of("timezone", "UTC"), Map.of(), "tenant-1")).isSuccess())
                        .as(collection).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("Registration contract")
    class RegistrationContract {

        @Test
        @DisplayName("guards every identity collection, including user-permission-sets")
        void guardedCollections() {
            assertThat(IdentityCollectionGuardHook.GUARDED).containsExactlyInAnyOrder(
                    "users", "user-permission-sets", "group-memberships", "delegated-admin-scopes");
        }

        @Test
        @DisplayName("runs before all other hooks (negative order)")
        void runsBeforeOtherHooks() {
            assertThat(hook.getOrder()).isLessThan(0);
        }

        @Test
        @DisplayName("registers as a wildcard hook")
        void registersAsWildcard() {
            assertThat(hook.getCollectionName()).isEqualTo(BeforeSaveHookRegistry.WILDCARD);
        }
    }
}
