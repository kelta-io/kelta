package io.kelta.worker.controller;

import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.storage.UniqueConstraintViolationException;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.controller.WatchController.CreateWatchRequest;
import io.kelta.worker.controller.WatchController.UpdateWatchRequest;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.Watch;
import io.kelta.worker.repository.WatchRepository;
import io.kelta.worker.repository.WatchTarget;
import io.kelta.worker.repository.WatchTargetRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.billing.EntitlementService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers re-pointing a watch at another target, and support staff acting on a
 * member's watch.
 *
 * <p>Both were previously impossible: {@code targetId} was absent from the update
 * contract, and {@code update}/{@code delete} ignored the support permission that
 * {@code list} and {@code create} already honoured — so correcting a member's
 * watch meant a direct database write, skipping every guard asserted here.
 */
@DisplayName("WatchController — retarget and support writes")
class WatchControllerTest {

    private static final String TENANT = "t1";
    private static final String OWNER = "member-1";
    private static final String OTHER = "member-2";
    private static final String STAFF = "staff-1";
    private static final String WATCH_ID = "watch-1";

    private WatchRepository watchRepository;
    private WatchTargetRepository targetRepository;
    private QueryEngine queryEngine;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private WatchController controller;
    private CollectionRegistry registry;
    private EntitlementService entitlements;
    private UserIdResolver userIdResolver;
    private DynamicCollectionRouter dynamicCollectionRouter;

    /** A controller whose promotable-source allowlist is the given comma list. */
    private WatchController controllerWithPromotable(String sources) {
        return new WatchController(watchRepository, targetRepository, queryEngine,
                registry, entitlements, userIdResolver, permissionResolver,
                bootstrapRepository, new ObjectMapper(), dynamicCollectionRouter, sources);
    }

    @BeforeEach
    void setUp() {
        watchRepository = mock(WatchRepository.class);
        targetRepository = mock(WatchTargetRepository.class);
        queryEngine = mock(QueryEngine.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);

        CollectionRegistry registry = mock(CollectionRegistry.class);
        when(registry.get(WatchController.COLLECTION)).thenReturn(mock(CollectionDefinition.class));
        when(registry.get(WatchController.TARGET_COLLECTION))
                .thenReturn(mock(CollectionDefinition.class));

        UserIdResolver userIdResolver = mock(UserIdResolver.class);
        when(userIdResolver.resolve(anyString(), any())).thenAnswer(i -> i.getArgument(0));

        EntitlementService entitlements = mock(EntitlementService.class);
        when(entitlements.intLimit(anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(i -> (int) i.getArgument(3));

        this.registry = registry;
        this.entitlements = entitlements;
        this.userIdResolver = userIdResolver;
        this.dynamicCollectionRouter = mock(DynamicCollectionRouter.class);
        controller = controllerWithPromotable("");
        TenantContext.set(TENANT);

        when(queryEngine.update(any(), anyString(), any()))
                .thenAnswer(i -> Optional.of(Map.of("id", i.getArgument(1))));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private HttpServletRequest requestFrom(String actor, boolean staff) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-User-Id")).thenReturn(actor);
        when(request.getHeader("X-User-Type")).thenReturn(staff ? "INTERNAL" : "PORTAL");
        when(permissionResolver.getProfileId(request)).thenReturn(staff ? "profile-staff" : "profile-member");
        when(bootstrapRepository.findProfileSystemPermissions(anyString())).thenReturn(
                staff
                        ? List.of(Map.of("permission_name", WatchController.SUPPORT_PERMISSION,
                                         "granted", Boolean.TRUE))
                        : List.of());
        return request;
    }

    private void watchOwnedBy(String member) {
        Watch watch = new Watch(WATCH_ID, TENANT, member, "target-old",
                null, null, Watch.STATUS_ACTIVE, null);
        when(watchRepository.findByMember(TENANT, member)).thenReturn(List.of(watch));
    }

    private void targetExists(String id, boolean active) {
        when(targetRepository.findById(TENANT, id))
                .thenReturn(Optional.of(
                        new WatchTarget(id, TENANT, "src", "ext", "Name", "cat", null, active)));
    }

    @Test
    @DisplayName("re-points a watch at another active target")
    void retargets() {
        watchOwnedBy(OWNER);
        targetExists("target-new", true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);

        controller.update(WATCH_ID,
                new UpdateWatchRequest("target-new", null, null, null, null),
                requestFrom(OWNER, false));

        verify(queryEngine).update(any(), eq(WATCH_ID), data.capture());
        assertThat(data.getValue()).containsEntry("targetId", "target-new");
    }

    @Test
    @DisplayName("rejects an unknown target rather than storing a dangling id")
    void unknownTarget() {
        watchOwnedBy(OWNER);
        when(targetRepository.findById(TENANT, "nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.update(WATCH_ID,
                new UpdateWatchRequest("nope", null, null, null, null),
                requestFrom(OWNER, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown target");
        verify(queryEngine, never()).update(any(), anyString(), any());
    }

    @Test
    @DisplayName("rejects a retired target — the same guard create applies")
    void retiredTarget() {
        watchOwnedBy(OWNER);
        targetExists("target-retired", false);

        assertThatThrownBy(() -> controller.update(WATCH_ID,
                new UpdateWatchRequest("target-retired", null, null, null, null),
                requestFrom(OWNER, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not currently watchable");
        verify(queryEngine, never()).update(any(), anyString(), any());
    }

    @Test
    @DisplayName("a member naming someone else is refused")
    void memberCannotNameAnother() {
        assertThatThrownBy(() -> controller.update(WATCH_ID,
                new UpdateWatchRequest(null, null, null, "PAUSED", OTHER),
                requestFrom(OWNER, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Not permitted");
    }

    @Test
    @DisplayName("support staff may update a named member's watch")
    void supportCanUpdate() {
        watchOwnedBy(OTHER);
        targetExists("target-new", true);

        controller.update(WATCH_ID,
                new UpdateWatchRequest("target-new", null, null, null, OTHER),
                requestFrom(STAFF, true));

        verify(queryEngine).update(any(), eq(WATCH_ID), any());
    }

    @Test
    @DisplayName("support staff may delete a named member's watch")
    void supportCanDelete() {
        watchOwnedBy(OTHER);

        controller.delete(WATCH_ID, OTHER, requestFrom(STAFF, true));

        verify(queryEngine).delete(any(), eq(WATCH_ID));
    }

    @Test
    @DisplayName("a member naming someone else on delete is refused")
    void memberCannotDeleteAnothers() {
        assertThatThrownBy(() -> controller.delete(WATCH_ID, OTHER, requestFrom(OWNER, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Not permitted");
        verify(queryEngine, never()).delete(any(), anyString());
    }

    @Test
    @DisplayName("an omitted targetId leaves the target alone")
    void omittedTargetIdIsNotAChange() {
        watchOwnedBy(OWNER);

        controller.update(WATCH_ID,
                new UpdateWatchRequest(null, null, null, "PAUSED", null),
                requestFrom(OWNER, false));

        verify(targetRepository, never()).findById(anyString(), anyString());
    }

    // ---- promotion: watching something by (source, externalId) ----

    private CreateWatchRequest promoteRequest(String source, String externalId, String name) {
        return new CreateWatchRequest(null, null, null, null, null, source, externalId, name, null);
    }

    @Test
    @DisplayName("resolves an existing target by source and external id without creating one")
    void resolvesExistingBySourceAndExternalId() {
        WatchController promoting = controllerWithPromotable("recreation.gov");
        when(targetRepository.findBySourceAndExternalId(TENANT, "recreation.gov", "232462"))
                .thenReturn(Optional.of(new WatchTarget("target-1", TENANT, "recreation.gov",
                        "232462", "Glacier Basin", "campsites", null, true)));
        when(queryEngine.create(any(), any())).thenAnswer(i -> Map.of("id", "watch-new"));

        promoting.create(promoteRequest("recreation.gov", "232462", "Glacier Basin"),
                requestFrom(OWNER, false));

        // One create — the watch. Nothing was promoted, because it already existed.
        verify(queryEngine, times(1)).create(any(), any());
    }

    @Test
    @DisplayName("creates the target when the source is promotable and it is missing")
    void promotesAMissingTarget() {
        WatchController promoting = controllerWithPromotable("recreation.gov");
        when(targetRepository.findBySourceAndExternalId(TENANT, "recreation.gov", "232462"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new WatchTarget("target-new", TENANT, "recreation.gov",
                        "232462", "Glacier Basin", "campsites", null, true)));
        when(queryEngine.create(any(), any())).thenAnswer(i -> Map.of("id", "x"));

        promoting.create(promoteRequest("recreation.gov", "232462", "Glacier Basin"),
                requestFrom(OWNER, false));

        // Two creates: the target, then the watch.
        verify(queryEngine, times(2)).create(any(), any());
    }

    @Test
    @DisplayName("refuses to promote a source the tenant has not allowed")
    void refusesUnlistedSource() {
        // Default allowlist is empty, so promotion is off. 404 rather than 403: from the
        // caller's side the target does not exist, and whether this deployment permits
        // promotion is not their business.
        when(targetRepository.findBySourceAndExternalId(TENANT, "evil.example", "1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.create(
                promoteRequest("evil.example", "1", "Anything"), requestFrom(OWNER, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown target");
        verify(queryEngine, never()).create(any(), any());
    }

    @Test
    @DisplayName("a concurrent promotion is reused, not failed")
    void concurrentPromotionReusesTheWinner() {
        // The unique index on (tenant_id, source, external_id) is the concurrency control:
        // two members watching the same facility both reach promote(), one wins, the loser
        // re-reads. Checking first and then creating would leave that race open.
        WatchController promoting = controllerWithPromotable("recreation.gov");
        when(targetRepository.findBySourceAndExternalId(TENANT, "recreation.gov", "232462"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new WatchTarget("target-winner", TENANT, "recreation.gov",
                        "232462", "Glacier Basin", "campsites", null, true)));
        when(queryEngine.create(any(), any()))
                .thenThrow(new UniqueConstraintViolationException(
                        "watch-targets", "externalId", "232462"))
                .thenAnswer(i -> Map.of("id", "watch-new"));

        promoting.create(promoteRequest("recreation.gov", "232462", "Glacier Basin"),
                requestFrom(OWNER, false));
        // Reached the watch create despite the target insert losing the race.
        verify(queryEngine, times(2)).create(any(), any());
    }

    @Test
    @DisplayName("promotion needs a name — the row is what a member will see")
    void promotionRequiresAName() {
        WatchController promoting = controllerWithPromotable("recreation.gov");
        when(targetRepository.findBySourceAndExternalId(TENANT, "recreation.gov", "232462"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> promoting.create(
                promoteRequest("recreation.gov", "232462", "  "), requestFrom(OWNER, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    @DisplayName("neither targetId nor source/externalId is a 400, not an NPE")
    void requiresSomeIdentifier() {
        assertThatThrownBy(() -> controller.create(
                new CreateWatchRequest(null, null, null, null, null, null, null, null, null),
                requestFrom(OWNER, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("targetId, or source and externalId, is required");
    }

    @Test
    @DisplayName("a member at their plan limit does not leave a promoted target behind")
    void quotaRejectionPromotesNothing() {
        // Found in production: the 422 fired AFTER the target was created, so retrying at
        // the limit minted unlimited watch_target rows — each one something the poller can
        // be asked to poll — while never creating a watch.
        WatchController promoting = controllerWithPromotable("recreation.gov");
        when(entitlements.intLimit(anyString(), anyString(),
                eq(WatchController.ENTITLEMENT_MAX_WATCHES), anyInt())).thenReturn(1);
        when(watchRepository.countByMemberAndStatus(TENANT, OWNER, Watch.STATUS_ACTIVE))
                .thenReturn(1);
        when(targetRepository.findBySourceAndExternalId(TENANT, "recreation.gov", "274314"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> promoting.create(
                promoteRequest("recreation.gov", "274314", "Silver Valley"),
                requestFrom(OWNER, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("plan limit");

        verify(queryEngine, never()).create(any(), any());
    }
}
