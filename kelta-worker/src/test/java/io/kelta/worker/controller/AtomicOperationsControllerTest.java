package io.kelta.worker.controller;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.DefaultQueryEngine;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.worker.interceptor.FieldWriteGuard;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.CollectionLifecycleManager;
import io.kelta.worker.util.WithTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the atomic-operations endpoint's error envelope AND per-operation
 * object-level authorization (the gateway skips its per-collection Cerbos check
 * on this static route, so the controller enforces it).
 */
@DisplayName("AtomicOperationsController")
@WithTenant("tenant-1")
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
                authzService, permissionResolver, new FieldWriteGuard(authzService), 100);

        // Default: an authenticated caller with identity headers present.
        when(permissionResolver.getEmail(request)).thenReturn("u@x.io");
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(permissionResolver.getTenantId(request)).thenReturn("tenant-1");
        // Default: every submitted field is writable.
        when(authzService.batchCheckFieldAccess(anyString(), anyString(), anyString(), anyString(), anyList(),
                eq("write"))).thenAnswer(inv -> inv.getArgument(4));
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

    // ---- Field-level write rules ---------------------------------------------------

    @Test
    @DisplayName("an add drops the attributes the caller may not write, as a single create does")
    @SuppressWarnings("unchecked")
    void addAppliesFieldWriteRules() {
        CollectionDefinition contacts = mock(CollectionDefinition.class);
        when(registry.get("contacts")).thenReturn(contacts);
        when(lifecycleManager.getCollectionIdByName("contacts")).thenReturn("uuid-c");
        when(authzService.checkCollectionAccess(anyString(), anyString(), anyString(), eq("uuid-c"), eq("create")))
                .thenReturn(true);
        when(authzService.batchCheckFieldAccess(eq("u@x.io"), eq("profile-1"), eq("tenant-1"), eq("contacts"),
                anyList(), eq("write"))).thenReturn(List.of("name"));
        when(queryEngine.create(eq(contacts), anyMap())).thenReturn(Map.of("id", "rec-1", "name", "x"));

        Map<String, Object> op = Map.of("op", "add", "data", Map.of(
                "type", "contacts", "attributes", Map.of("name", "x", "internalScore", 95)));
        ResponseEntity<?> response = controller.executeOperations(Map.of("atomic:operations", List.of(op)), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(queryEngine).create(eq(contacts), captor.capture());
        assertThat(captor.getValue()).containsEntry("name", "x").doesNotContainKey("internalScore");
    }

    @Test
    @DisplayName("an update drops the attributes the caller may not write")
    @SuppressWarnings("unchecked")
    void updateAppliesFieldWriteRules() {
        CollectionDefinition contacts = mock(CollectionDefinition.class);
        when(registry.get("contacts")).thenReturn(contacts);
        when(lifecycleManager.getCollectionIdByName("contacts")).thenReturn("uuid-c");
        when(authzService.checkCollectionAccess(anyString(), anyString(), anyString(), eq("uuid-c"), eq("edit")))
                .thenReturn(true);
        when(authzService.batchCheckFieldAccess(anyString(), anyString(), anyString(), eq("contacts"),
                anyList(), eq("write"))).thenReturn(List.of("name"));
        when(queryEngine.update(eq(contacts), eq("rec-1"), anyMap()))
                .thenReturn(java.util.Optional.of(Map.of("id", "rec-1", "name", "y")));

        Map<String, Object> op = Map.of("op", "update",
                "ref", Map.of("type", "contacts", "id", "rec-1"),
                "data", Map.of("type", "contacts", "id", "rec-1",
                        "attributes", Map.of("name", "y", "internalScore", 1)));
        ResponseEntity<?> response = controller.executeOperations(Map.of("atomic:operations", List.of(op)), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(queryEngine).update(eq(contacts), eq("rec-1"), captor.capture());
        assertThat(captor.getValue()).containsEntry("name", "y").doesNotContainKey("internalScore");
    }

    // ---- System-maintained fields (KLT-609) -------------------------------------------

    /** Records whether the batch transaction committed or rolled back. */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        boolean committed;
        boolean rolledBack;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { committed = true; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
    }

    /**
     * The controller behind its {@code @Transactional} proxy, over a real query engine, so the
     * system-maintained check and the batch's commit/rollback are the production ones.
     */
    private AtomicOperationsController transactionalController(StorageAdapter storage,
                                                               RecordingTransactionManager txManager) {
        CollectionDefinition inspections = new CollectionDefinitionBuilder()
                .name("inspections").displayName("Inspections")
                .addField(FieldDefinition.string("name"))
                .addField(FieldDefinition.doubleField("count").withSystemMaintained(true))
                .build();
        when(registry.get("inspections")).thenReturn(inspections);
        when(lifecycleManager.getCollectionIdByName("inspections")).thenReturn("uuid-i");
        when(authzService.checkCollectionAccess(anyString(), anyString(), anyString(), eq("uuid-i"), anyString()))
                .thenReturn(true);
        when(storage.create(eq(inspections), anyMap()))
                .thenAnswer(inv -> new java.util.HashMap<>(inv.<Map<String, Object>>getArgument(1)));

        AtomicOperationsController target = new AtomicOperationsController(new DefaultQueryEngine(storage),
                registry, lifecycleManager, authzService, permissionResolver, new FieldWriteGuard(authzService), 100);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return (AtomicOperationsController) proxy.getProxy();
    }

    private static Map<String, Object> addInspection(Map<String, Object> attributes) {
        return Map.of("op", "add", "data", Map.of("type", "inspections", "attributes", attributes));
    }

    @Test
    @DisplayName("an add setting a system-maintained field is rejected with 400 and the whole batch rolls back")
    @SuppressWarnings("unchecked")
    void systemMaintainedWriteRejectsAndRollsBackBatch() {
        StorageAdapter storage = mock(StorageAdapter.class);
        RecordingTransactionManager txManager = new RecordingTransactionManager();
        AtomicOperationsController tx = transactionalController(storage, txManager);
        CallerContext caller = new CallerContext(UUID.randomUUID().toString(),
                CallerContext.UserType.INTERNAL, true, true);

        ResponseEntity<?> response = CallerContext.callAs(caller, () -> tx.executeOperations(
                Map.of("atomic:operations", List.of(
                        addInspection(Map.of("name", "first")),
                        addInspection(Map.of("name", "second", "count", 99)))),
                request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> err = ((List<Map<String, Object>>) ((Map<String, Object>) response.getBody())
                .get("errors")).get(0);
        assertThat(err.get("code")).isEqualTo("systemMaintained");
        assertThat((String) err.get("detail")).contains("'count'").contains("'inspections'");
        assertThat(err.get("source")).isEqualTo(Map.of("pointer", "/atomic:operations/1/data/attributes/count"));
        // The first add reached storage inside the batch transaction, which then rolled back.
        verify(storage).create(any(), anyMap());
        assertThat(txManager.rolledBack).isTrue();
        assertThat(txManager.committed).isFalse();
    }

    @Test
    @DisplayName("a batch that leaves system-maintained fields alone commits")
    void batchWithoutSystemMaintainedWritesCommits() {
        StorageAdapter storage = mock(StorageAdapter.class);
        RecordingTransactionManager txManager = new RecordingTransactionManager();
        AtomicOperationsController tx = transactionalController(storage, txManager);
        CallerContext caller = new CallerContext(UUID.randomUUID().toString(),
                CallerContext.UserType.INTERNAL, true, true);

        ResponseEntity<?> response = CallerContext.callAs(caller, () -> tx.executeOperations(
                Map.of("atomic:operations", List.of(addInspection(Map.of("name", "first")))), request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(txManager.committed).isTrue();
        assertThat(txManager.rolledBack).isFalse();
    }

    // ---- Unsupported system collections (PLT-660) --------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fields", "picklist-values"})
    @DisplayName("an operation on a system collection with its own controller is rejected with 400 before anything runs")
    @SuppressWarnings("unchecked")
    void rejectsUnsupportedSystemCollection(String systemCollection) {
        CollectionDefinition contacts = mock(CollectionDefinition.class);
        when(registry.get("contacts")).thenReturn(contacts);
        when(registry.get(systemCollection)).thenReturn(SystemCollectionDefinitions.byName().get(systemCollection));
        when(lifecycleManager.getCollectionIdByName(anyString())).thenReturn("uuid-x");
        when(authzService.checkCollectionAccess(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "rec-1", "name", "x"));

        ResponseEntity<?> response = controller.executeOperations(
                Map.of("atomic:operations", List.of(addOp("contacts"), addOp(systemCollection))), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> errors = (List<Map<String, Object>>) ((Map<String, Object>) response.getBody())
                .get("errors");
        assertThat(errors).hasSize(1);
        Map<String, Object> err = errors.get(0);
        assertThat(err.get("status")).isEqualTo("400");
        assertThat(err.get("code")).isEqualTo("UNSUPPORTED_COLLECTION");
        assertThat(err.get("detail")).isEqualTo("Atomic operations do not support the system collection '"
                + systemCollection + "'; use POST /api/" + systemCollection + " instead");
        assertThat(err.get("source")).isEqualTo(Map.of("pointer", "/atomic:operations/1"));
        verifyNoInteractions(queryEngine);
        verifyNoInteractions(authzService);
    }

    @Test
    @DisplayName("an unsupported system collection on an update or remove is rejected too")
    @SuppressWarnings("unchecked")
    void rejectsUnsupportedSystemCollectionOnRemove() {
        when(registry.get("fields")).thenReturn(SystemCollectionDefinitions.byName().get("fields"));

        ResponseEntity<?> response = controller.executeOperations(Map.of("atomic:operations", List.of(
                Map.of("op", "remove", "ref", Map.of("type", "fields", "id", "f-1")))), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> err = ((List<Map<String, Object>>) ((Map<String, Object>) response.getBody())
                .get("errors")).get(0);
        assertThat(err.get("code")).isEqualTo("UNSUPPORTED_COLLECTION");
        assertThat(err.get("source")).isEqualTo(Map.of("pointer", "/atomic:operations/0"));
        verifyNoInteractions(queryEngine);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"users", "user-permission-sets", "group-memberships", "delegated-admin-scopes"})
    @DisplayName("identity system collections guarded by IdentityCollectionGuardHook stay writable")
    void identityCollectionsStayAccepted(String identityCollection) {
        // `user-permission-sets` has no platform definition today; the guard hook still names it.
        CollectionDefinition definition = SystemCollectionDefinitions.byName().getOrDefault(identityCollection,
                new CollectionDefinitionBuilder().name(identityCollection).displayName(identityCollection)
                        .addField(FieldDefinition.string("name")).systemCollection(true).build());
        assertThat(definition.systemCollection()).isTrue();
        when(registry.get(identityCollection)).thenReturn(definition);
        when(lifecycleManager.getCollectionIdByName(identityCollection)).thenReturn("uuid-id");
        when(authzService.checkCollectionAccess(anyString(), anyString(), anyString(), eq("uuid-id"), eq("create")))
                .thenReturn(true);
        when(queryEngine.create(eq(definition), anyMap())).thenReturn(Map.of("id", "rec-1"));

        ResponseEntity<?> response = controller.executeOperations(
                Map.of("atomic:operations", List.of(addOp(identityCollection))), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(queryEngine).create(eq(definition), anyMap());
    }

    // ---- Tenant stamping (PLT-710) ---------------------------------------------------

    @SuppressWarnings("unchecked")
    private AtomicOperationsController membershipController(Map<String, Map<String, Object>> rows) {
        CollectionDefinition memberships = SystemCollectionDefinitions.byName().get("group-memberships");
        assertThat(memberships.tenantScoped()).isTrue();
        when(registry.get("group-memberships")).thenReturn(memberships);
        when(lifecycleManager.getCollectionIdByName("group-memberships")).thenReturn("uuid-gm");
        when(authzService.checkCollectionAccess(anyString(), anyString(), anyString(), eq("uuid-gm"), eq("create")))
                .thenReturn(true);
        StorageAdapter storage = mock(StorageAdapter.class);
        when(storage.create(eq(memberships), anyMap())).thenAnswer(inv -> {
            Map<String, Object> row = new java.util.HashMap<>(inv.<Map<String, Object>>getArgument(1));
            rows.put((String) row.get("id"), row);
            return row;
        });
        when(storage.getById(eq(memberships), anyString()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(rows.get(inv.<String>getArgument(1))));
        queryEngine = new DefaultQueryEngine(storage);
        return new AtomicOperationsController(queryEngine, registry, lifecycleManager, authzService,
                permissionResolver, new FieldWriteGuard(authzService), 100);
    }

    private static Map<String, Object> addMembership(Map<String, Object> attributes) {
        return Map.of("op", "add", "data", Map.of("type", "group-memberships", "attributes", attributes));
    }

    @Test
    @DisplayName("an add on a tenant-scoped system collection is stamped with the request's tenant and reads back with it")
    @SuppressWarnings("unchecked")
    void addOnTenantScopedSystemCollectionStampsTenant() {
        Map<String, Map<String, Object>> rows = new java.util.HashMap<>();
        AtomicOperationsController tenantController = membershipController(rows);

        ResponseEntity<?> response = tenantController.executeOperations(Map.of("atomic:operations", List.of(
                addMembership(Map.of("groupId", UUID.randomUUID().toString(), "memberType", "USER",
                        "memberId", UUID.randomUUID().toString())))), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<io.kelta.jsonapi.AtomicResult> results = (List<io.kelta.jsonapi.AtomicResult>)
                ((Map<String, Object>) response.getBody()).get("atomic:results");
        assertThat(results.get(0).data().attributes()).containsEntry("tenantId", "tenant-1");
        String id = results.get(0).data().id();
        Map<String, Object> readBack = queryEngine.getById(registry.get("group-memberships"), id).orElseThrow();
        assertThat(readBack).containsEntry("tenantId", "tenant-1");
    }

    @Test
    @DisplayName("an add naming another tenant's tenantId is rejected with 422 at its index and writes nothing")
    @SuppressWarnings("unchecked")
    void addWithForeignTenantIdIsRejected() {
        Map<String, Map<String, Object>> rows = new java.util.HashMap<>();
        AtomicOperationsController tenantController = membershipController(rows);

        ResponseEntity<?> response = tenantController.executeOperations(Map.of("atomic:operations", List.of(
                addMembership(Map.of("groupId", UUID.randomUUID().toString(), "memberType", "USER",
                        "memberId", UUID.randomUUID().toString(), "tenantId", "tenant-2")))), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        Map<String, Object> err = ((List<Map<String, Object>>) ((Map<String, Object>) response.getBody())
                .get("errors")).get(0);
        assertThat(err.get("code")).isEqualTo("OPERATION_FAILED");
        assertThat((String) err.get("detail")).contains("tenant-2");
        assertThat(err.get("source")).isEqualTo(Map.of("pointer", "/atomic:operations/0"));
        assertThat(rows).isEmpty();
    }
}
