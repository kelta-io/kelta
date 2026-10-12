package io.kelta.worker.interceptor;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.router.SubResourceAuthorizer.Access;
import io.kelta.runtime.router.SubResourceAuthorizer.Decision;
import io.kelta.runtime.router.SubResourceAuthorizer.Operation;
import io.kelta.runtime.router.SubResourceAuthorizer.Outcome;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.CollectionLifecycleManager;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("CerbosSubResourceAuthorizer")
@WithTenant("tenant-1")
class CerbosSubResourceAuthorizerTest {

    private static final String EMAIL = "user@example.com";
    private static final String PROFILE = "profile-1";
    private static final String TENANT = "tenant-1";

    private CerbosAuthorizationService authz;
    private CollectionLifecycleManager lifecycle;
    private CerbosSubResourceAuthorizer authorizer;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        authz = mock(CerbosAuthorizationService.class);
        lifecycle = mock(CollectionLifecycleManager.class);
        authorizer = new CerbosSubResourceAuthorizer(authz, new CerbosPermissionResolver(), lifecycle, true);
        request = new MockHttpServletRequest();
        request.addHeader("X-User-Email", EMAIL);
        request.addHeader("X-User-Profile-Id", PROFILE);
        request.addHeader("X-Cerbos-Scope", TENANT);
        when(lifecycle.getTenantVisibleCollectionId(anyString(), eq(TENANT)))
                .thenAnswer(inv -> "id-" + inv.getArgument(0));
    }

    private static CollectionDefinition collection(String name, boolean system) {
        return new CollectionDefinitionBuilder()
                .name(name)
                .displayName(name)
                .addField(FieldDefinition.string("name"))
                .systemCollection(system)
                .build();
    }

    private void grant(String collection, String action) {
        when(authz.checkCollectionAccess(EMAIL, PROFILE, TENANT, "id-" + collection, action)).thenReturn(true);
    }

    private void grantSystem(String permission) {
        when(authz.checkSystemPermission(EMAIL, PROFILE, TENANT, permission)).thenReturn(true);
    }

    private Decision authorize(CollectionDefinition parent, CollectionDefinition child, String childId, Operation op) {
        return authorizer.authorize(new Access(parent, "p-1", child, childId, op), request);
    }

    @Nested
    @DisplayName("under a static parent route")
    class StaticParent {

        private final CollectionDefinition scripts = collection("scripts", true);
        private final CollectionDefinition logs = collection("script-execution-logs", true);

        @Test
        @DisplayName("a child list needs read on the child collection")
        void listDeniedWithoutChildRead() {
            Decision decision = authorize(scripts, logs, null, Operation.READ);

            assertThat(decision.outcome()).isEqualTo(Outcome.FORBIDDEN);
            assertThat(decision.detail()).isEqualTo("Insufficient permissions for read on script-execution-logs");
            // The static parent's own rule is API_ACCESS only, so no object check on it
            verify(authz, never()).checkCollectionAccess(any(), any(), any(), eq("id-scripts"), any());
        }

        @Test
        @DisplayName("a child list is allowed with read on the child collection")
        void listAllowedWithChildRead() {
            grant("script-execution-logs", "read");

            assertThat(authorize(scripts, logs, null, Operation.READ).allowed()).isTrue();
        }

        @Test
        @DisplayName("each write needs the method's action on the child collection")
        void writesMapToActions() {
            grant("script-execution-logs", "read");

            assertThat(authorize(scripts, logs, null, Operation.CREATE).detail())
                    .isEqualTo("Insufficient permissions for create on script-execution-logs");
            assertThat(authorize(scripts, logs, "l-1", Operation.EDIT).detail())
                    .isEqualTo("Insufficient permissions for edit on script-execution-logs");
            assertThat(authorize(scripts, logs, "l-1", Operation.DELETE).detail())
                    .isEqualTo("Insufficient permissions for delete on script-execution-logs");

            grant("script-execution-logs", "delete");
            assertThat(authorize(scripts, logs, "l-1", Operation.DELETE).allowed()).isTrue();
        }

        @Test
        @DisplayName("a parent whose static route checks reads needs read on it")
        void usersParentNeedsRead() {
            CollectionDefinition users = collection("users", true);
            CollectionDefinition orders = collection("orders", false);
            grant("orders", "read");

            Decision denied = authorize(users, orders, null, Operation.READ);
            assertThat(denied.outcome()).isEqualTo(Outcome.FORBIDDEN);
            assertThat(denied.detail()).isEqualTo("Insufficient permissions for read on users");

            grant("users", "read");
            assertThat(authorize(users, orders, null, Operation.READ).allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("under a collection parent route")
    class CollectionParent {

        private final CollectionDefinition accounts = collection("accounts", false);
        private final CollectionDefinition contacts = collection("contacts", false);

        @Test
        @DisplayName("needs read on the parent")
        void parentRead() {
            grant("contacts", "create");

            Decision decision = authorize(accounts, contacts, null, Operation.CREATE);

            assertThat(decision.detail()).isEqualTo("Insufficient permissions for read on accounts");
            verify(authz, never()).checkCollectionAccess(any(), any(), any(), eq("id-contacts"), any());
        }

        @Test
        @DisplayName("and the child's own action")
        void childAction() {
            grant("accounts", "read");
            assertThat(authorize(accounts, contacts, null, Operation.CREATE).outcome()).isEqualTo(Outcome.FORBIDDEN);

            grant("contacts", "create");
            assertThat(authorize(accounts, contacts, null, Operation.CREATE).allowed()).isTrue();
        }

        @Test
        @DisplayName("a profile Cerbos allows everywhere (VIEW_ALL_DATA / MODIFY_ALL_DATA) is allowed")
        void allDataProfile() {
            when(authz.checkCollectionAccess(eq(EMAIL), eq(PROFILE), eq(TENANT), anyString(), anyString()))
                    .thenReturn(true);

            assertThat(authorize(accounts, contacts, "c-1", Operation.EDIT).allowed()).isTrue();
            assertThat(authorize(accounts, contacts, "c-1", Operation.DELETE).allowed()).isTrue();
        }

        @Test
        @DisplayName("fails closed when the child's collection id cannot be resolved")
        void unresolvedIdDenies() {
            grant("accounts", "read");
            when(lifecycle.getTenantVisibleCollectionId("contacts", TENANT)).thenReturn(null);

            assertThat(authorize(accounts, contacts, null, Operation.READ).outcome()).isEqualTo(Outcome.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("a child that is itself a static route")
    class StaticChild {

        private final CollectionDefinition collections = collection("collections", true);

        @Test
        @DisplayName("an API_ACCESS-only static child needs no object permission")
        void apiAccessOnlyChild() {
            CollectionDefinition layouts = collection("page-layouts", true);

            assertThat(authorize(collections, layouts, null, Operation.READ).allowed()).isTrue();
            assertThat(authorize(collections, layouts, null, Operation.CREATE).allowed()).isTrue();
            verifyNoInteractions(authz);
        }

        @Test
        @DisplayName("a users child write is admitted by the object grant or MANAGE_USERS")
        void usersChildWrite() {
            CollectionDefinition profiles = collection("profiles", true);
            CollectionDefinition users = collection("users", true);

            assertThat(authorize(profiles, users, "u-1", Operation.EDIT).outcome()).isEqualTo(Outcome.FORBIDDEN);

            grantSystem("MANAGE_USERS");
            assertThat(authorize(profiles, users, "u-1", Operation.EDIT).allowed()).isTrue();
        }

        @Test
        @DisplayName("a users child read needs read on users")
        void usersChildRead() {
            CollectionDefinition profiles = collection("profiles", true);
            CollectionDefinition users = collection("users", true);
            grantSystem("MANAGE_USERS"); // a write override, not a read grant

            assertThat(authorize(profiles, users, null, Operation.READ).outcome()).isEqualTo(Outcome.FORBIDDEN);

            grant("users", "read");
            assertThat(authorize(profiles, users, null, Operation.READ).allowed()).isTrue();
        }

        @Test
        @DisplayName("a tenants child: MANAGE_TENANTS, else only the caller's own tenant record")
        void tenantsChild() {
            CollectionDefinition tenants = collection("tenants", true);

            assertThat(authorize(collections, tenants, null, Operation.READ).outcome()).isEqualTo(Outcome.FORBIDDEN);
            assertThat(authorize(collections, tenants, TENANT, Operation.READ).allowed()).isTrue();
            assertThat(authorize(collections, tenants, "other-tenant", Operation.READ).outcome())
                    .isEqualTo(Outcome.NOT_FOUND);
            assertThat(authorize(collections, tenants, TENANT, Operation.EDIT).detail())
                    .isEqualTo("Insufficient permissions: MANAGE_TENANTS required");

            grantSystem("MANAGE_TENANTS");
            assertThat(authorize(collections, tenants, null, Operation.READ).allowed()).isTrue();
            assertThat(authorize(collections, tenants, "other-tenant", Operation.EDIT).allowed()).isTrue();
        }

        @Test
        @DisplayName("a tenant collection that shares a static route's name is object-checked")
        void tenantCollectionNamedLikeStaticRoute() {
            CollectionDefinition accounts = collection("accounts", false);
            CollectionDefinition reports = collection("reports", false);
            grant("accounts", "read");

            assertThat(authorize(accounts, reports, null, Operation.READ).outcome()).isEqualTo(Outcome.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("requests without the gateway's identity headers are not checked")
    void noIdentity() {
        MockHttpServletRequest internal = new MockHttpServletRequest();
        internal.addHeader("X-Tenant-ID", TENANT);

        Decision decision = authorizer.authorize(new Access(collection("collections", true), "c-1",
                collection("fields", true), null, Operation.READ), internal);

        assertThat(decision.allowed()).isTrue();
        verifyNoInteractions(authz);
    }

    @Test
    @DisplayName("nothing is checked when permissions are disabled")
    void permissionsDisabled() {
        CerbosSubResourceAuthorizer disabled =
                new CerbosSubResourceAuthorizer(authz, new CerbosPermissionResolver(), lifecycle, false);

        assertThat(disabled.authorize(new Access(collection("accounts", false), "a-1",
                collection("contacts", false), null, Operation.CREATE), request).allowed()).isTrue();
        verifyNoInteractions(authz);
    }

    @Nested
    @DisplayName("authorizeCollection (a direct request to the collection)")
    class DirectCollection {

        @Test
        @DisplayName("a create needs create on the collection")
        void createNeedsCreate() {
            Decision denied = authorizer.authorizeCollection(collection("contacts", false), Operation.CREATE, request);
            assertThat(denied.outcome()).isEqualTo(Outcome.FORBIDDEN);
            assertThat(denied.detail()).isEqualTo("Insufficient permissions for create on contacts");

            grant("contacts", "create");
            assertThat(authorizer.authorizeCollection(collection("contacts", false), Operation.CREATE, request)
                    .allowed()).isTrue();
        }

        @Test
        @DisplayName("a system collection behind a static route gets that route's rule")
        void staticRouteRule() {
            Decision denied = authorizer.authorizeCollection(collection("users", true), Operation.CREATE, request);
            assertThat(denied.allowed()).isFalse();

            grantSystem("MANAGE_USERS");
            assertThat(authorizer.authorizeCollection(collection("users", true), Operation.CREATE, request)
                    .allowed()).isTrue();
        }

        @Test
        @DisplayName("requests without the gateway's identity headers are not checked")
        void noIdentity() {
            MockHttpServletRequest internal = new MockHttpServletRequest();
            internal.addHeader("X-Tenant-ID", TENANT);

            assertThat(authorizer.authorizeCollection(collection("contacts", false), Operation.CREATE, internal)
                    .allowed()).isTrue();
            verifyNoInteractions(authz);
        }
    }
}
