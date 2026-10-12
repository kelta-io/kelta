package io.kelta.worker.controller;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.PermissionSystemCollections;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.util.WithTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DelegatedAdminScopeController Tests")
@WithTenant("tenant-1")
class DelegatedAdminScopeControllerTest {

    private static final String COLLECTION = "delegated-admin-scopes";

    @Mock private QueryEngine queryEngine;
    @Mock private CollectionRegistry collectionRegistry;
    @Mock private CerbosPermissionResolver permissionResolver;
    @Mock private BootstrapRepository bootstrapRepository;
    @Mock private HttpServletRequest request;

    @Captor private ArgumentCaptor<Map<String, Object>> dataCaptor;

    /**
     * Single shared instance — {@code CollectionDefinitionBuilder.build()} stamps
     * {@code Instant.now()}, so two factory calls are not value-equal.
     */
    private final CollectionDefinition definition = PermissionSystemCollections.delegatedAdminScopes();

    private DelegatedAdminScopeController controller;

    @BeforeEach
    void setUp() {
        controller = new DelegatedAdminScopeController(
                queryEngine, collectionRegistry, permissionResolver, bootstrapRepository);
    }

    private void grantManageDelegatedAdmins() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_DELEGATED_ADMINS", "granted", true)));
    }

    private void stubDefinition() {
        when(collectionRegistry.get(COLLECTION)).thenReturn(definition);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @Nested
    @DisplayName("Authorization")
    class Authorization {

        @Test
        @DisplayName("403 when the caller's profile lacks MANAGE_DELEGATED_ADMINS")
        void shouldRejectWithoutManageDelegatedAdmins() {
            when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
            when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                    Map.of("permission_name", "MANAGE_DELEGATED_ADMINS", "granted", false)));

            assertThatThrownBy(() -> controller.list(request, 50, 1))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
            verifyNoInteractions(queryEngine);
        }

        @Test
        @DisplayName("403 when no gateway identity headers are present")
        void shouldRejectWithoutIdentity() {
            when(permissionResolver.getProfileId(request)).thenReturn(null);

            assertThatThrownBy(() -> controller.list(request, 50, 1))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
            verifyNoInteractions(queryEngine);
        }
    }

    @Nested
    @DisplayName("POST /api/admin/delegated-admin-scopes")
    class Create {

        @Test
        @DisplayName("400 when name is missing")
        void shouldRejectCreateWithoutName() {
            grantManageDelegatedAdmins();

            assertThatThrownBy(() -> controller.create(request,
                    new LinkedHashMap<>(Map.of("description", "no name here"))))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
            verifyNoInteractions(queryEngine);
        }

        @Test
        @DisplayName("injects tenantId, returns 201 with the created id")
        void shouldCreateScope() {
            grantManageDelegatedAdmins();
            stubDefinition();
            when(queryEngine.create(any(), any())).thenReturn(new LinkedHashMap<>(Map.of(
                    "id", "scope-9", "name", "Support Admins", "tenantId", "tenant-1")));

            Map<String, Object> body = Map.of("data", Map.of(
                    "type", COLLECTION,
                    "attributes", Map.of("name", "Support Admins")));
            ResponseEntity<Map<String, Object>> response = controller.create(request, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(data(response)).containsEntry("id", "scope-9");
            verify(queryEngine).create(same(definition), dataCaptor.capture());
            assertThat(dataCaptor.getValue())
                    .containsEntry("tenantId", "tenant-1")
                    .containsEntry("name", "Support Admins");
        }
    }

    @Nested
    @DisplayName("PATCH /api/admin/delegated-admin-scopes/{id}")
    class Update {

        @Test
        @DisplayName("strips tenantId from the incoming attributes")
        void shouldStripTenantIdOnUpdate() {
            grantManageDelegatedAdmins();
            stubDefinition();
            when(queryEngine.getById(any(), eq("scope-1")))
                    .thenReturn(Optional.of(Map.of("id", "scope-1", "name", "Old")));
            when(queryEngine.update(any(), eq("scope-1"), any()))
                    .thenReturn(Optional.of(new LinkedHashMap<>(Map.of(
                            "id", "scope-1", "name", "Renamed", "tenantId", "tenant-1"))));

            Map<String, Object> body = new LinkedHashMap<>(Map.of(
                    "name", "Renamed", "tenantId", "evil-tenant"));
            ResponseEntity<Map<String, Object>> response = controller.update(request, "scope-1", body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(queryEngine).update(same(definition), eq("scope-1"), dataCaptor.capture());
            assertThat(dataCaptor.getValue())
                    .doesNotContainKey("tenantId")
                    .containsEntry("name", "Renamed");
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/delegated-admin-scopes/{id}")
    class Delete {

        @Test
        @DisplayName("204 and queryEngine.delete invoked")
        void shouldDeleteScope() {
            grantManageDelegatedAdmins();
            stubDefinition();
            when(queryEngine.getById(any(), eq("scope-1")))
                    .thenReturn(Optional.of(Map.of("id", "scope-1", "name", "Support Admins")));
            when(queryEngine.delete(any(), eq("scope-1"))).thenReturn(true);

            ResponseEntity<Void> response = controller.delete(request, "scope-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(queryEngine).delete(same(definition), eq("scope-1"));
        }
    }

    @Nested
    @DisplayName("GET /api/admin/delegated-admin-scopes/{id}")
    class Get {

        @Test
        @DisplayName("404 when the scope does not exist")
        void shouldReturn404ForUnknownScope() {
            grantManageDelegatedAdmins();
            stubDefinition();
            when(queryEngine.getById(any(), eq("missing"))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.get(request, "missing"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
        }
    }
}
