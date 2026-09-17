package io.kelta.worker.controller;

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
import io.kelta.worker.repository.WinRepository;
import io.kelta.worker.repository.WinRepository.Win;
import io.kelta.worker.service.CerbosPermissionResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
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
 * Covers the two response modes {@code GET /api/wins} and {@code GET /api/wins/{id}} now offer,
 * mirroring {@code WatchControllerSupportModeMvcTest}: a portal member's own rows (today's
 * shape, no {@code meta}), and support staff's full-tenant JSON:API view (delegated to the real
 * {@link DynamicCollectionRouter}, not reimplemented).
 */
@DisplayName("WinController — support-mode list/get")
class WinControllerSupportModeMvcTest {

    private static final String TENANT = "t1";
    private static final String OWNER = "member-1";
    private static final String STAFF = "staff-1";
    private static final String WIN_ID = "win-1";

    private WinRepository winRepository;
    private QueryEngine queryEngine;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        winRepository = mock(WinRepository.class);
        queryEngine = mock(QueryEngine.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        UserIdResolver userIdResolver = mock(UserIdResolver.class);
        when(userIdResolver.resolve(anyString(), any())).thenAnswer(i -> i.getArgument(0));

        CollectionDefinition winsDefinition = SystemCollectionDefinitions.wins();
        CollectionRegistry registry = mock(CollectionRegistry.class);
        when(registry.get(WinController.COLLECTION)).thenReturn(winsDefinition);

        DynamicCollectionRouter dynamicCollectionRouter = new DynamicCollectionRouter(registry, queryEngine);

        WinController controller = new WinController(winRepository, queryEngine, registry, userIdResolver,
                permissionResolver, bootstrapRepository, dynamicCollectionRouter);

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
                        ? List.of(Map.of("permission_name", WinController.SUPPORT_PERMISSION,
                                         "granted", Boolean.TRUE))
                        : List.of());
    }

    @Test
    @DisplayName("support staff with no memberId gets the tenant's full JSON:API view, honouring filter + page[size]")
    void supportListsFullTenant() throws Exception {
        grantSupport(true);
        QueryResult result = QueryResult.of(
                List.of(recordOf("w1", "member-a"), recordOf("w2", "member-b")), 9, new Pagination(1, 2));
        when(queryEngine.executeQuery(any(), any())).thenReturn(result);

        mvc.perform(get("/api/wins")
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Id", STAFF)
                        .header("X-User-Type", "INTERNAL")
                        .param("filter[isPublic][eq]", "true")
                        .param("page[size]", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.totalCount").value(9))
                .andExpect(jsonPath("$.meta.pageSize").value(2));

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryEngine).executeQuery(any(), captor.capture());
        assertThat(captor.getValue().pagination().pageSize()).isEqualTo(2);
        verify(winRepository, never()).findByMember(anyString(), anyString());
    }

    @Test
    @DisplayName("a portal member still sees only their own wins in exactly today's shape")
    void portalMemberOwnRowsOnly() throws Exception {
        Win mine = new Win(WIN_ID, OWNER, "target-1", null, null, null, "my win", null, false, null, Instant.now());
        when(winRepository.findByMember(TENANT, OWNER)).thenReturn(List.of(mine));

        mvc.perform(get("/api/wins")
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Id", OWNER)
                        .header("X-User-Type", "PORTAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(WIN_ID))
                .andExpect(jsonPath("$.meta").doesNotExist());

        verify(queryEngine, never()).executeQuery(any(), any());
    }

    @Test
    @DisplayName("a non-support INTERNAL caller is refused")
    void nonSupportInternalIsForbidden() throws Exception {
        grantSupport(false);

        mvc.perform(get("/api/wins")
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Id", STAFF)
                        .header("X-User-Type", "INTERNAL"))
                .andExpect(status().isForbidden());

        verify(queryEngine, never()).executeQuery(any(), any());
        verify(winRepository, never()).findByMember(anyString(), anyString());
    }

    @Test
    @DisplayName("a PORTAL actor whose profile grants MANAGE_DATA is still refused support-mode access")
    void portalWithManageDataStillOwnerScoped() throws Exception {
        grantSupport(true);
        Win mine = new Win(WIN_ID, OWNER, "target-1", null, null, null, "my win", null, false, null, Instant.now());
        when(winRepository.findByMember(TENANT, OWNER)).thenReturn(List.of(mine));

        mvc.perform(get("/api/wins")
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Id", OWNER)
                        .header("X-User-Type", "PORTAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta").doesNotExist());

        verify(queryEngine, never()).executeQuery(any(), any());
    }

    @Test
    @DisplayName("GET /api/wins/{id} resolves any row in the tenant for support staff")
    void supportGetsAnyRowById() throws Exception {
        grantSupport(true);
        when(queryEngine.getById(any(), eq(WIN_ID))).thenReturn(Optional.of(recordOf(WIN_ID, "someone-else")));

        mvc.perform(get("/api/wins/{id}", WIN_ID)
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Id", STAFF)
                        .header("X-User-Type", "INTERNAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(WIN_ID));

        verify(winRepository, never()).findByMember(anyString(), anyString());
    }

    @Test
    @DisplayName("GET /api/wins/{id} for a foreign row is 404, not 403, for a plain member")
    void memberCannotFetchForeignRowById() throws Exception {
        when(winRepository.findByMember(TENANT, OWNER)).thenReturn(List.of());

        mvc.perform(get("/api/wins/{id}", WIN_ID)
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Id", OWNER)
                        .header("X-User-Type", "PORTAL"))
                .andExpect(status().isNotFound());

        verify(queryEngine, never()).getById(any(), anyString());
    }

    private static Map<String, Object> recordOf(String id, String memberId) {
        return Map.of("id", id, "memberId", memberId, "summary", "a win");
    }
}
