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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for the layout related list {@code watches.memberId} under a
 * {@code users} record layout. {@code useRelatedRecords} (the admin UI's related-list data
 * hook) fetches {@code GET /api/{collectionName}?filter[{foreignKeyField}][eq]={parentId}} — for
 * this related list that is exactly {@code GET /api/watches?filter[memberId][eq]={userId}}, the
 * same {@code /api/watches} path {@link WatchController} now support-branches on. An admin
 * (INTERNAL, holding {@code MANAGE_DATA}) viewing another user's record must still see that
 * user's watches: since they pass no plain {@code memberId} param, the request falls into the
 * new full-tenant delegate to {@link DynamicCollectionRouter}, which must honour the
 * {@code filter[memberId][eq]} the widget sends.
 */
@DisplayName("Layout related list — watches.memberId under a users layout")
class WatchRelatedListMvcTest {

    private static final String TENANT = "t1";
    private static final String STAFF = "staff-1";
    private static final String USER_ID = "user-1";

    @Test
    @DisplayName("GET /api/watches?filter[memberId][eq]={userId} renders that user's watches for an admin")
    void relatedListRendersWatchesForAUser() throws Exception {
        WatchRepository watchRepository = mock(WatchRepository.class);
        WatchTargetRepository targetRepository = mock(WatchTargetRepository.class);
        QueryEngine queryEngine = mock(QueryEngine.class);
        CerbosPermissionResolver permissionResolver = mock(CerbosPermissionResolver.class);
        BootstrapRepository bootstrapRepository = mock(BootstrapRepository.class);
        UserIdResolver userIdResolver = mock(UserIdResolver.class);
        when(userIdResolver.resolve(anyString(), any())).thenAnswer(i -> i.getArgument(0));
        EntitlementService entitlements = mock(EntitlementService.class);

        CollectionDefinition watches = SystemCollectionDefinitions.watches();
        CollectionRegistry registry = mock(CollectionRegistry.class);
        when(registry.get(WatchController.COLLECTION)).thenReturn(watches);
        when(registry.get(WatchController.TARGET_COLLECTION)).thenReturn(mock(CollectionDefinition.class));

        when(permissionResolver.getProfileId(any())).thenReturn("profile-staff");
        when(bootstrapRepository.findProfileSystemPermissions("profile-staff")).thenReturn(
                List.of(Map.of("permission_name", WatchController.SUPPORT_PERMISSION, "granted", Boolean.TRUE)));

        QueryResult result = QueryResult.of(
                List.of(Map.of("id", "w1", "memberId", USER_ID, "status", "ACTIVE")),
                1, Pagination.defaults());
        when(queryEngine.executeQuery(any(), any())).thenReturn(result);

        DynamicCollectionRouter dynamicCollectionRouter = new DynamicCollectionRouter(registry, queryEngine);
        WatchController controller = new WatchController(watchRepository, targetRepository, queryEngine,
                registry, entitlements, userIdResolver, permissionResolver, bootstrapRepository,
                new ObjectMapper(), dynamicCollectionRouter, "");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        TenantContext.set(TENANT);
        try {
            mvc.perform(get("/api/watches")
                            .header("X-Tenant-ID", TENANT)
                            .header("X-User-Id", STAFF)
                            .header("X-User-Type", "INTERNAL")
                            .param("filter[memberId][eq]", USER_ID)
                            .param("page[size]", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value("w1"));
        } finally {
            TenantContext.clear();
        }

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryEngine).executeQuery(any(), captor.capture());
        assertThat(captor.getValue().filters()).anySatisfy(f -> {
            assertThat(f.fieldName()).isEqualTo("memberId");
            assertThat(f.value()).isEqualTo(USER_ID);
        });
        verify(watchRepository, never()).findByMember(anyString(), anyString());
    }
}
