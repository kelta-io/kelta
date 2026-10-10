package io.kelta.worker.controller;

import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.CallerContext.UserType;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.DefaultQueryEngine;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.worker.filter.CallerContextFilter;
import io.kelta.worker.listener.OwnerScopeGuardHook;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.Watch;
import io.kelta.worker.repository.WatchRepository;
import io.kelta.worker.repository.WatchTarget;
import io.kelta.worker.repository.WatchTargetRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.billing.EntitlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code WatchController}'s support mode against the real write path: a real
 * {@link DefaultQueryEngine} with the real {@link OwnerScopeGuardHook} registered, the real
 * {@link CallerContextFilter} binding the caller from the gateway headers, and the real
 * {@code watches} definition (ownerField=memberId, ownerScope=ALL). Only storage is an in-memory
 * stand-in, so what the guard admits or rejects is genuine.
 *
 * <p>Also covers the two read modes {@code GET /api/watches} and {@code GET /api/watches/{id}}
 * offer: a portal member's own rows (no {@code meta}), and support staff's full-tenant JSON:API
 * view delegated to the real {@link DynamicCollectionRouter}.
 */
@DisplayName("WatchController — support mode with the real owner guard")
class WatchControllerSupportModeMvcTest {

    private static final String TENANT = "t1";
    private static final String OWNER = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_MEMBER = "22222222-2222-2222-2222-222222222222";
    private static final String STAFF = "33333333-3333-3333-3333-333333333333";
    private static final String WATCH_ID = "watch-1";
    private static final String TARGET_ID = "target-1";
    private static final String STAFF_PROFILE = "profile-staff";

    private WatchRepository watchRepository;
    private WatchTargetRepository targetRepository;
    private StorageAdapter storage;
    private QueryEngine queryEngine;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private CollectionDefinition watches;
    private final Map<String, Map<String, Object>> rows = new ConcurrentHashMap<>();
    private final AtomicReference<CallerContext> callerAtQuery = new AtomicReference<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        watchRepository = mock(WatchRepository.class);
        targetRepository = mock(WatchTargetRepository.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        UserIdResolver userIdResolver = mock(UserIdResolver.class);
        when(userIdResolver.resolve(anyString(), any())).thenAnswer(i -> i.getArgument(0));
        EntitlementService entitlements = mock(EntitlementService.class);
        when(entitlements.intLimit(anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(i -> i.getArgument(3));
        when(permissionResolver.getProfileId(any())).thenAnswer(i ->
                ((jakarta.servlet.http.HttpServletRequest) i.getArgument(0)).getHeader("X-User-Profile-Id"));

        watches = SystemCollectionDefinitions.watches();
        CollectionRegistry registry = mock(CollectionRegistry.class);
        when(registry.get(WatchController.COLLECTION)).thenReturn(watches);

        storage = inMemoryStorage();
        BeforeSaveHookRegistry hooks = new BeforeSaveHookRegistry();
        queryEngine = new DefaultQueryEngine(storage, null, null, null, null, null, null, null, hooks);
        hooks.register(new OwnerScopeGuardHook(registry, queryEngine));

        DynamicCollectionRouter dynamicCollectionRouter = new DynamicCollectionRouter(registry, queryEngine);
        WatchController controller = new WatchController(watchRepository, targetRepository, queryEngine,
                registry, entitlements, userIdResolver, permissionResolver, bootstrapRepository,
                new ObjectMapper(), dynamicCollectionRouter, "");

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CallerContextFilter(userIdResolver, bootstrapRepository))
                .build();
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Rows keyed by id; {@code query} reports the bound caller so read bypasses are visible. */
    private StorageAdapter inMemoryStorage() {
        StorageAdapter adapter = mock(StorageAdapter.class);
        when(adapter.getById(any(), anyString())).thenAnswer(i ->
                Optional.ofNullable(rows.get((String) i.getArgument(1))).map(HashMap::new));
        when(adapter.create(any(), any())).thenAnswer(i -> {
            Map<String, Object> data = new HashMap<>(i.getArgument(1));
            rows.put((String) data.get("id"), data);
            return new HashMap<>(data);
        });
        when(adapter.update(any(), anyString(), any())).thenAnswer(i -> {
            Map<String, Object> row = rows.get((String) i.getArgument(1));
            if (row == null) {
                return Optional.empty();
            }
            row.putAll(i.getArgument(2));
            return Optional.of(new HashMap<>(row));
        });
        when(adapter.delete(any(), anyString())).thenAnswer(i -> rows.remove((String) i.getArgument(1)) != null);
        return adapter;
    }

    private void seedWatch(String id, String memberId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("tenantId", TENANT);
        row.put("memberId", memberId);
        row.put("targetId", TARGET_ID);
        row.put("status", Watch.STATUS_ACTIVE);
        rows.put(id, row);
        when(watchRepository.findByMember(TENANT, memberId)).thenReturn(List.of(
                new Watch(id, TENANT, memberId, TARGET_ID, null, null, Watch.STATUS_ACTIVE, null)));
    }

    private void grant(String... permissions) {
        when(bootstrapRepository.findProfileSystemPermissions(STAFF_PROFILE)).thenReturn(
                java.util.Arrays.stream(permissions)
                        .map(p -> Map.<String, Object>of("permission_name", p, "granted", Boolean.TRUE))
                        .toList());
    }

    private static MockHttpServletRequestBuilder asStaff(MockHttpServletRequestBuilder request) {
        return request.header("X-Tenant-ID", TENANT)
                .header("X-User-Id", STAFF)
                .header("X-User-Type", "INTERNAL")
                .header("X-User-Profile-Id", STAFF_PROFILE);
    }

    private static MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request, String member) {
        return request.header("X-Tenant-ID", TENANT)
                .header("X-User-Id", member)
                .header("X-User-Type", "PORTAL");
    }

    private void stubQuery(QueryResult result) {
        when(storage.query(any(), any())).thenAnswer(i -> {
            callerAtQuery.set(CallerContext.current().orElse(null));
            return result;
        });
    }

    // ------------------------------------------------------------------ support writes

    @Test
    @DisplayName("MANAGE_DATA support PATCH of a member's watch succeeds through the real guard")
    void supportPatchOfMemberWatchSucceeds() throws Exception {
        grant(SupportPermissions.MANAGE_DATA);
        seedWatch(WATCH_ID, OWNER);

        mvc.perform(asStaff(patch("/api/watches/{id}", WATCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"memberId\":\"" + OWNER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(Watch.STATUS_PAUSED));

        assertThat(rows.get(WATCH_ID)).containsEntry("status", Watch.STATUS_PAUSED)
                .containsEntry("memberId", OWNER);
    }

    @Test
    @DisplayName("MANAGE_DATA support DELETE of a member's watch succeeds through the real guard")
    void supportDeleteOfMemberWatchSucceeds() throws Exception {
        grant(SupportPermissions.MANAGE_DATA);
        seedWatch(WATCH_ID, OWNER);

        mvc.perform(asStaff(delete("/api/watches/{id}", WATCH_ID)).param("memberId", OWNER))
                .andExpect(status().isNoContent());

        assertThat(rows).doesNotContainKey(WATCH_ID);
    }

    @Test
    @DisplayName("MANAGE_DATA support POST for a member creates a watch owned by that member")
    void supportCreateForMemberIsOwnedByMember() throws Exception {
        grant(SupportPermissions.MANAGE_DATA);
        when(targetRepository.findById(TENANT, TARGET_ID)).thenReturn(Optional.of(
                new WatchTarget(TARGET_ID, TENANT, "src", "ext-1", "Site 1", "camping", null, true)));

        mvc.perform(asStaff(post("/api/watches"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":\"" + TARGET_ID + "\",\"memberId\":\"" + OWNER + "\","
                                + "\"criteria\":{\"dateStart\":\"2026-11-01\",\"dateEnd\":\"2026-11-03\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.memberId").value(OWNER));

        assertThat(rows.values()).singleElement().satisfies(row -> assertThat(row).containsEntry("memberId", OWNER));
    }

    // ------------------------------------------------------------------ member writes

    @Test
    @DisplayName("a member's PATCH and DELETE of their own watch succeed")
    void memberOwnWritesSucceed() throws Exception {
        seedWatch(WATCH_ID, OWNER);

        mvc.perform(asMember(patch("/api/watches/{id}", WATCH_ID), OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isOk());
        mvc.perform(asMember(delete("/api/watches/{id}", WATCH_ID), OWNER))
                .andExpect(status().isNoContent());

        assertThat(rows).doesNotContainKey(WATCH_ID);
    }

    @Test
    @DisplayName("a plain member's PATCH/DELETE of another member's watch is 404 and leaves it untouched")
    void memberWriteOnForeignWatchIsNotFound() throws Exception {
        seedWatch(WATCH_ID, OWNER);
        when(watchRepository.findByMember(TENANT, OTHER_MEMBER)).thenReturn(List.of());

        mvc.perform(asMember(patch("/api/watches/{id}", WATCH_ID), OTHER_MEMBER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(asMember(delete("/api/watches/{id}", WATCH_ID), OTHER_MEMBER))
                .andExpect(status().isNotFound());

        assertThat(rows.get(WATCH_ID)).containsEntry("status", Watch.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("the generic write path (QueryEngine) refuses another member's watch with 404 too")
    void genericWriteOnForeignWatchIsNotFound() {
        seedWatch(WATCH_ID, OWNER);
        CallerContext otherMember = new CallerContext(OTHER_MEMBER, UserType.PORTAL, false, false);

        assertNotFound(() -> CallerContext.callAs(otherMember,
                () -> queryEngine.update(watches, WATCH_ID, Map.of("status", Watch.STATUS_PAUSED))));
        assertNotFound(() -> CallerContext.callAs(otherMember, () -> queryEngine.delete(watches, WATCH_ID)));

        assertThat(rows.get(WATCH_ID)).containsEntry("status", Watch.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("support staff without MODIFY_ALL_DATA cannot write a member's watch directly — only via support mode")
    void staffDirectWriteWithoutBypassIsNotFound() {
        seedWatch(WATCH_ID, OWNER);
        CallerContext staff = new CallerContext(STAFF, UserType.INTERNAL, false, false);

        assertNotFound(() -> CallerContext.callAs(staff,
                () -> queryEngine.update(watches, WATCH_ID, Map.of("status", Watch.STATUS_PAUSED))));
    }

    @Test
    @DisplayName("VIEW_ALL_DATA never authorizes acting on another member: POST/PATCH/DELETE with memberId are 403")
    void viewAllDataCannotActForAnotherMember() throws Exception {
        grant(SupportPermissions.VIEW_ALL_DATA);
        seedWatch(WATCH_ID, OWNER);

        mvc.perform(asStaff(post("/api/watches"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":\"" + TARGET_ID + "\",\"memberId\":\"" + OWNER + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(asStaff(patch("/api/watches/{id}", WATCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"memberId\":\"" + OWNER + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(asStaff(delete("/api/watches/{id}", WATCH_ID)).param("memberId", OWNER))
                .andExpect(status().isForbidden());

        assertThat(rows.get(WATCH_ID)).containsEntry("status", Watch.STATUS_ACTIVE);
    }

    // ------------------------------------------------------------------ reads

    @Test
    @DisplayName("MANAGE_DATA support with no memberId gets the full JSON:API view, read with the viewAll bypass")
    void supportListsFullTenant() throws Exception {
        grant(SupportPermissions.MANAGE_DATA);
        stubQuery(QueryResult.of(List.of(
                Map.of("id", "w1", "memberId", OWNER, "status", Watch.STATUS_ACTIVE),
                Map.of("id", "w2", "memberId", OTHER_MEMBER, "status", Watch.STATUS_ACTIVE)),
                7, new Pagination(1, 2)));

        mvc.perform(asStaff(get("/api/watches"))
                        .param("filter[status][eq]", "ACTIVE")
                        .param("page[size]", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.totalCount").value(7))
                .andExpect(jsonPath("$.meta.pageSize").value(2));

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(storage).query(any(), captor.capture());
        assertThat(captor.getValue().pagination().pageSize()).isEqualTo(2);
        assertThat(captor.getValue().filters()).anySatisfy(f -> {
            assertThat(f.fieldName()).isEqualTo("status");
            assertThat(f.value()).isEqualTo("ACTIVE");
        });
        // MANAGE_DATA alone does not lift the owner-scoped read predicate; support mode must.
        assertThat(callerAtQuery.get()).isNotNull();
        assertThat(callerAtQuery.get().userId()).isEqualTo(STAFF);
        assertThat(callerAtQuery.get().viewAll()).isTrue();
        verify(watchRepository, never()).findByMember(anyString(), anyString());
    }

    @Test
    @DisplayName("an INTERNAL caller with VIEW_ALL_DATA gets the tenant's full view")
    void viewAllDataListsFullTenant() throws Exception {
        grant(SupportPermissions.VIEW_ALL_DATA);
        stubQuery(QueryResult.of(List.of(Map.of("id", "w1", "memberId", OWNER)), 1, new Pagination(1, 20)));

        mvc.perform(asStaff(get("/api/watches")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.totalCount").value(1));

        assertThat(callerAtQuery.get().viewAll()).isTrue();
        verify(watchRepository, never()).findByMember(anyString(), anyString());
    }

    @Test
    @DisplayName("GET /api/watches/{id} resolves any row in the tenant for support staff")
    void supportGetsAnyRowById() throws Exception {
        grant(SupportPermissions.MANAGE_DATA);
        seedWatch(WATCH_ID, OWNER);

        mvc.perform(asStaff(get("/api/watches/{id}", WATCH_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(WATCH_ID));

        verify(watchRepository, never()).findByMember(anyString(), anyString());
    }

    @Test
    @DisplayName("a non-support INTERNAL caller is refused, not silently scoped to an empty self")
    void nonSupportInternalIsForbidden() throws Exception {
        grant();

        mvc.perform(asStaff(get("/api/watches")))
                .andExpect(status().isForbidden());

        verify(storage, never()).query(any(), any());
        verify(watchRepository, never()).findByMember(anyString(), anyString());
    }

    @Test
    @DisplayName("a portal member sees only their own rows in the member shape, even if their profile grants MANAGE_DATA")
    void portalMemberOwnRowsOnly() throws Exception {
        grant(SupportPermissions.MANAGE_DATA, SupportPermissions.VIEW_ALL_DATA);
        seedWatch(WATCH_ID, OWNER);

        mvc.perform(asMember(get("/api/watches"), OWNER).header("X-User-Profile-Id", STAFF_PROFILE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(WATCH_ID))
                .andExpect(jsonPath("$.meta").doesNotExist());

        verify(storage, never()).query(any(), any());
    }

    @Test
    @DisplayName("GET /api/watches/{id} for a foreign row is 404, not 403, for a plain member")
    void memberCannotFetchForeignRowById() throws Exception {
        seedWatch(WATCH_ID, OWNER);
        when(watchRepository.findByMember(TENANT, OTHER_MEMBER)).thenReturn(List.of());

        mvc.perform(asMember(get("/api/watches/{id}", WATCH_ID), OTHER_MEMBER))
                .andExpect(status().isNotFound());

        verify(storage, never()).getById(any(), eq(WATCH_ID));
    }

    private static void assertNotFound(Runnable write) {
        assertThatThrownBy(write::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
