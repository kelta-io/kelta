package io.kelta.worker.controller;

import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.Watch;
import io.kelta.worker.repository.WatchRepository;
import io.kelta.worker.repository.WatchTargetRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.billing.EntitlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the two response modes {@code GET /api/watches} and {@code GET /api/watches/{id}} now
 * offer: a portal member's own rows (today's shape, no {@code meta}), and support staff's
 * full-tenant JSON:API view (delegated to the real {@link DynamicCollectionRouter}, not
 * reimplemented). Uses a real {@code watches} {@link CollectionDefinition} so the delegated
 * response shape — filters, paging, {@code meta.totalCount} — is genuine, not asserted against a
 * stub.
 */
@DisplayName("WatchController — support-mode list/get")
class WatchControllerSupportModeMvcTest {

    private static final String TENANT = "t1";
    private static final String OWNER = "member-1";
    private static final String STAFF = "staff-1";
    private static final String WATCH_ID = "watch-1";

    private WatchRepository watchRepository;
    private QueryEngine queryEngine;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        watchRepository = mock(WatchRepository.class);
        WatchTargetRepository targetRepository = mock(WatchTargetRepository.class);
        queryEngine = mock(QueryEngine.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        UserIdResolver userIdResolver = mock(UserIdResolver.class);
        when(userIdResolver.resolve(anyString(), any())).thenAnswer(i -> i.getArgument(0));
        EntitlementService entitlements = mock(EntitlementService.class);

        CollectionDefinition watchesDefinition = SystemCollectionDefinitions.watches();
        CollectionRegistry registry = mock(CollectionRegistry.class);
        when(registry.get(WatchController.COLLECTION)).thenReturn(watchesDefinition);
        when(registry.get(WatchController.TARGET_COLLECTION)).thenReturn(mock(CollectionDefinition.class));

        DynamicCollectionRouter dynamicCollectionRouter = new DynamicCollectionRouter(registry, queryEngine);

        WatchController controller = new WatchController(watchRepository, targetRepository, queryEngine,
                registry, entitlements, userIdResolver, permissionResolver, bootstrapRepository,
                new ObjectMapper(), dynamicCollectionRouter, "");

        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void grantSupport(boolean granted) {
        when(permissionResolver.getProfileId(any())).thenReturn("profile-staff");
        when(bootstrapRepository.findProfileSystemPermissions("profile-staff")).thenReturn(
                granted
                        ? List.of(Map.of("permission_name", WatchController.SUPPORT_PERMISSION,
                                         "granted", Boolean.TRUE))
                        : List.of());
    }

    @Test
    @DisplayName("support staff with no memberId gets the tenant's full JSON:API view, honouring filter + page[size]")
    void supportListsFullTenant() throws Exception {
        grantSupport(true);
        Watch w1 = new Watch("w1", TENANT, "member-a", "target-1", null, null, Watch.STATUS_ACTIVE, null);
        Watch w2 = new Watch("w2", TENANT, "member-b", "target-2", null, null, Watch.STATUS_ACTIVE, null);
        QueryResult result = QueryResult.of(
                List.of(recordOf(w1), recordOf(w2)), 7, new Pagination(1, 2));
        when(queryEngine.executeQuery(any(), any())).thenReturn(result);

        mvc.perform(get("/api/watches")
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Id", STAFF)
                        .header("X-User-Type", "INTERNAL")
                        .param("filter[status][eq]", "ACTIVE")
                        .param("page[size]", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.totalCount").value(7))
                .andExpect(jsonPath("$.meta.pageSize").value(2));

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryEngine).executeQuery(any(), captor.capture());
        QueryRequest sent = captor.getValue();
        assertThat(sent.pagination().pageSize()).isEqualTo(2);
        assertThat(sent.filters()).anySatisfy(f -> {
            assertThat(f.fieldName()).isEqualTo("status");
            assertThat(f.value()).isEqualTo("ACTIVE");
        });
        // Never went through the owner-scoped repository path.
        verify(watchRepository, never()).findByMember(anyString(), anyString());
    }

    @Test
    @DisplayName("a portal member still sees only their own rows in exactly today's shape")
    void portalMemberOwnRowsOnly() throws Exception {
        Watch mine = new Watch(WATCH_ID, TENANT, OWNER, "target-1", null, null, Watch.STATUS_ACTIVE, null);
        when(watchRepository.findByMember(TENANT, OWNER)).thenReturn(List.of(mine));

        mvc.perform(get("/api/watches")
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Id", OWNER)
                        .header("X-User-Type", "PORTAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(WATCH_ID))
                .andExpect(jsonPath("$.meta").doesNotExist());

        verify(queryEngine, never()).executeQuery(any(), any());
    }

    @Test
    @DisplayName("a non-support INTERNAL caller is refused, not silently scoped to an empty self")
    void nonSupportInternalIsForbidden() throws Exception {
        grantSupport(false);

        mvc.perform(get("/api/watches")
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Id", STAFF)
                        .header("X-User-Type", "INTERNAL"))
                .andExpect(status().isForbidden());

        verify(queryEngine, never()).executeQuery(any(), any());
        verify(watchRepository, never()).findByMember(anyString(), anyString());
    }

    @Test
    @DisplayName("a PORTAL actor whose profile grants MANAGE_DATA is still refused support-mode access")
    void portalWithManageDataStillOwnerScoped() throws Exception {
        // hasSupportPermission short-circuits PORTAL to false before any profile lookup, so
        // even a profile that (mis)grants MANAGE_DATA never reaches the full-tenant branch.
        grantSupport(true);
        Watch mine = new Watch(WATCH_ID, TENANT, OWNER, "target-1", null, null, Watch.STATUS_ACTIVE, null);
        when(watchRepository.findByMember(TENANT, OWNER)).thenReturn(List.of(mine));

        mvc.perform(get("/api/watches")
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Id", OWNER)
                        .header("X-User-Type", "PORTAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta").doesNotExist());

        verify(queryEngine, never()).executeQuery(any(), any());
    }

    @Test
    @DisplayName("GET /api/watches/{id} resolves any row in the tenant for support staff")
    void supportGetsAnyRowById() throws Exception {
        grantSupport(true);
        Watch foreign = new Watch(WATCH_ID, TENANT, "someone-else", "target-1", null, null,
                Watch.STATUS_ACTIVE, null);
        when(queryEngine.getById(any(), eq(WATCH_ID))).thenReturn(Optional.of(recordOf(foreign)));

        mvc.perform(get("/api/watches/{id}", WATCH_ID)
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Id", STAFF)
                        .header("X-User-Type", "INTERNAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(WATCH_ID));

        verify(watchRepository, never()).findByMember(anyString(), anyString());
    }

    @Test
    @DisplayName("GET /api/watches/{id} for a foreign row is 404, not 403, for a plain member")
    void memberCannotFetchForeignRowById() throws Exception {
        when(watchRepository.findByMember(TENANT, OWNER)).thenReturn(List.of());

        mvc.perform(get("/api/watches/{id}", WATCH_ID)
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Id", OWNER)
                        .header("X-User-Type", "PORTAL"))
                .andExpect(status().isNotFound());

        verify(queryEngine, never()).getById(any(), anyString());
    }

    private static Map<String, Object> recordOf(Watch watch) {
        return Map.of(
                "id", watch.id(),
                "memberId", watch.memberId(),
                "targetId", watch.targetId(),
                "status", watch.status());
    }
}
