package io.kelta.worker.controller;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.CollectionLifecycleManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the atomic-operations endpoint's error envelope AND per-operation
 * object-level authorization (the gateway skips its per-collection Cerbos check
 * on this static route, so the controller enforces it).
 */
@DisplayName("AtomicOperationsController")
class AtomicOperationsControllerTest {

    private AtomicOperationsController controller;
    private QueryEngine queryEngine;
    private CollectionRegistry registry;
    private CollectionLifecycleManager lifecycleManager;
    private CerbosAuthorizationService authzService;
    private CerbosPermissionResolver permissionResolver;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        queryEngine = mock(QueryEngine.class);
        registry = mock(CollectionRegistry.class);
        lifecycleManager = mock(CollectionLifecycleManager.class);
        authzService = mock(CerbosAuthorizationService.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        request = mock(HttpServletRequest.class);
        controller = new AtomicOperationsController(queryEngine, registry, lifecycleManager,
                authzService, permissionResolver, 100);
        TenantContext.set("tenant-1");

        // Default: an authenticated caller with identity headers present.
        when(permissionResolver.getEmail(request)).thenReturn("u@x.io");
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(permissionResolver.getTenantId(request)).thenReturn("tenant-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("missing atomic:operations key returns 400 with INVALID_PAYLOAD")
    @SuppressWarnings("unchecked")
    void missingOperationsKey() {
        ResponseEntity<?> response = controller.executeOperations(Map.of(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        Map<String, Object> err = ((List<Map<String, Object>>) body.get("errors")).get(0);
        assertThat(err.get("status")).isEqualTo("400");
        assertThat(err.get("code")).isEqualTo("INVALID_PAYLOAD");
        assertThat(err.get("title")).isEqualTo("Invalid request");
        assertThat(err.get("detail")).isEqualTo("Missing 'atomic:operations' array");
    }

    @Test
    @DisplayName("empty operations array returns 400 with EMPTY_BATCH")
    @SuppressWarnings("unchecked")
    void emptyOperationsArray() {
        ResponseEntity<?> response = controller.executeOperations(
                Map.of("atomic:operations", List.of()), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        Map<String, Object> err = ((List<Map<String, Object>>) body.get("errors")).get(0);
        assertThat(err.get("status")).isEqualTo("400");
        assertThat(err.get("code")).isEqualTo("EMPTY_BATCH");
        assertThat(err.get("detail")).isEqualTo("No operations provided");
    }

    @Test
    @DisplayName("non-array operations value returns 400 with PARSE_ERROR")
    @SuppressWarnings("unchecked")
    void nonArrayOperationsValue() {
        ResponseEntity<?> response = controller.executeOperations(
                Map.of("atomic:operations", "not-an-array"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        Map<String, Object> err = ((List<Map<String, Object>>) body.get("errors")).get(0);
        assertThat(err.get("status")).isEqualTo("400");
        assertThat(err.get("code")).isEqualTo("PARSE_ERROR");
        assertThat(err.get("detail")).asString().isNotEmpty();
    }

    // ---- Per-operation authorization ------------------------------------------------

    private static Map<String, Object> addOp(String collection) {
        return Map.of("op", "add", "data", Map.of(
                "type", collection, "attributes", Map.of("name", "x")));
    }

    @Test
    @DisplayName("returns 403 when the caller has no identity")
    @SuppressWarnings("unchecked")
    void deniesWithoutIdentity() {
        when(permissionResolver.getProfileId(request)).thenReturn(null);

        ResponseEntity<?> response = controller.executeOperations(
                Map.of("atomic:operations", List.of(addOp("contacts"))), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        Map<String, Object> err = ((List<Map<String, Object>>) body.get("errors")).get(0);
        assertThat(err.get("code")).isEqualTo("FORBIDDEN");
        verify(queryEngine, never()).create(any(), any());
    }

    @Test
    @DisplayName("returns 403 when Cerbos denies an operation's collection+action")
    void deniesWhenCerbosDenies() {
        when(lifecycleManager.getCollectionIdByName("contacts")).thenReturn("uuid-c");
        when(authzService.checkCollectionAccess("u@x.io", "profile-1", "tenant-1", "uuid-c", "create"))
                .thenReturn(false);

        ResponseEntity<?> response = controller.executeOperations(
                Map.of("atomic:operations", List.of(addOp("contacts"))), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(queryEngine, never()).create(any(), any());
    }

    @Test
    @DisplayName("authorizes each operation before executing; passes on allow")
    void allowsWhenCerbosAllows() {
        when(lifecycleManager.getCollectionIdByName("contacts")).thenReturn("uuid-c");
        when(authzService.checkCollectionAccess(anyString(), anyString(), anyString(), eq("uuid-c"), eq("create")))
                .thenReturn(true);

        ResponseEntity<?> response = controller.executeOperations(
                Map.of("atomic:operations", List.of(addOp("contacts"))), request);

        // Authorization passed (not 403). Execution itself fails on the mocked
        // registry (unknown collection → 422), which is fine — we only assert authz ran.
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        verify(authzService).checkCollectionAccess("u@x.io", "profile-1", "tenant-1", "uuid-c", "create");
    }

    @Test
    @DisplayName("deduplicates the Cerbos check per (collection, action)")
    void dedupesChecksPerCollectionAction() {
        when(lifecycleManager.getCollectionIdByName("contacts")).thenReturn("uuid-c");
        when(authzService.checkCollectionAccess(anyString(), anyString(), anyString(), eq("uuid-c"), eq("create")))
                .thenReturn(true);

        controller.executeOperations(
                Map.of("atomic:operations", List.of(addOp("contacts"), addOp("contacts"), addOp("contacts"))),
                request);

        verify(authzService, org.mockito.Mockito.times(1))
                .checkCollectionAccess(anyString(), anyString(), anyString(), eq("uuid-c"), eq("create"));
    }

    // ---- Audit stamping ------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeAddAndCaptureCreate() {
        CollectionDefinition contacts = mock(CollectionDefinition.class);
        when(registry.get("contacts")).thenReturn(contacts);
        when(lifecycleManager.getCollectionIdByName("contacts")).thenReturn("uuid-c");
        when(authzService.checkCollectionAccess(anyString(), anyString(), anyString(), eq("uuid-c"), eq("create")))
                .thenReturn(true);
        when(queryEngine.create(eq(contacts), anyMap())).thenReturn(Map.of("id", "rec-1", "name", "x"));

        ResponseEntity<?> response = controller.executeOperations(
                Map.of("atomic:operations", List.of(addOp("contacts"))), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(queryEngine).create(eq(contacts), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("stamps createdBy/updatedBy with the bound caller's platform_user UUID")
    void stampsAuditFieldsFromCallerContext() {
        String userId = UUID.randomUUID().toString();
        CallerContext caller = new CallerContext(userId, CallerContext.UserType.INTERNAL, false, false);

        List<Map<String, Object>> captured = new java.util.ArrayList<>();
        CallerContext.runAs(caller, () -> captured.add(executeAddAndCaptureCreate()));

        assertThat(captured.get(0))
                .containsEntry("createdBy", userId)
                .containsEntry("updatedBy", userId);
    }

    @Test
    @DisplayName("stamps no audit fields when no caller is bound")
    void noAuditFieldsWithoutCallerContext() {
        Map<String, Object> attributes = executeAddAndCaptureCreate();

        assertThat(attributes)
                .doesNotContainKey("createdBy")
                .doesNotContainKey("updatedBy");
    }
}
