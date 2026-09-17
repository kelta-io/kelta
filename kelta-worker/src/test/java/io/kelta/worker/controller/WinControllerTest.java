package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.controller.WinController.CreateWinRequest;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.WinRepository;
import io.kelta.worker.repository.WinRepository.TargetStats;
import io.kelta.worker.repository.WinRepository.Win;
import io.kelta.worker.service.CerbosPermissionResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("WinController")
class WinControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String CALLER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private WinRepository winRepository;
    private QueryEngine queryEngine;
    private CollectionRegistry collectionRegistry;
    private UserIdResolver userIdResolver;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private WinController controller;

    @BeforeEach
    void setUp() {
        winRepository = mock(WinRepository.class);
        queryEngine = mock(QueryEngine.class);
        collectionRegistry = mock(CollectionRegistry.class);
        userIdResolver = mock(UserIdResolver.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        controller = new WinController(winRepository, queryEngine, collectionRegistry, userIdResolver,
                permissionResolver, bootstrapRepository, mock(DynamicCollectionRouter.class));

        when(collectionRegistry.get("wins")).thenReturn(mock(CollectionDefinition.class));
        when(userIdResolver.resolve(any(), eq(TENANT))).thenReturn(CALLER);
        when(queryEngine.create(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletRequest requestFrom(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId);
        request.addHeader("X-User-Type", "PORTAL");
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedCreateData() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(queryEngine).create(any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("create stamps the caller as owner and sets a first-name claimant label")
    void createStampsOwner() {
        when(winRepository.findMemberDisplayFirstName(TENANT, CALLER)).thenReturn(Optional.of("Marcus"));

        ResponseEntity<Map<String, Object>> response = controller.create(
                new CreateWinRequest("target-9", null, null, "campsites",
                        "2 nights at Glacier", 2, true, null),
                requestFrom("marcus@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = capturedCreateData();
        assertThat(data).containsEntry("tenantId", TENANT);
        assertThat(data).containsEntry("memberId", CALLER);
        assertThat(data).containsEntry("summary", "2 nights at Glacier");
        assertThat(data).containsEntry("isPublic", true);
        assertThat(data).containsEntry("claimantName", "Marcus");
        assertThat(data).containsEntry("targetId", "target-9");
    }

    @Test
    @DisplayName("create rejects a blank summary")
    void createRejectsBlankSummary() {
        assertThatThrownBy(() -> controller.create(
                new CreateWinRequest(null, null, null, null, "  ", null, null, null),
                requestFrom("marcus@example.com")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(queryEngine);
    }

    @Test
    @DisplayName("create clamps an over-length summary to the column width")
    void createClampsSummary() {
        String huge = "x".repeat(500);
        controller.create(new CreateWinRequest(null, null, null, null, huge, null, false, null),
                requestFrom("marcus@example.com"));

        assertThat((String) capturedCreateData().get("summary")).hasSize(280);
    }

    @Test
    @DisplayName("create defaults isPublic to false when omitted")
    void createDefaultsPrivate() {
        controller.create(new CreateWinRequest(null, null, null, null, "quiet win", null, null, null),
                requestFrom("marcus@example.com"));
        assertThat(capturedCreateData()).containsEntry("isPublic", false);
    }

    @Test
    @DisplayName("recent returns redacted ticker items with NO member identity")
    void recentIsRedacted() {
        Win win = new Win("w1", "secret-member-uuid", "target-9", null, null,
                "campsites", "2 nights at Glacier", 2, true, "Marcus", Instant.now());
        when(winRepository.findRecentPublic(eq(TENANT), anyInt())).thenReturn(List.of(win));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) controller.recent(20).get("data");

        assertThat(data).hasSize(1);
        Map<String, Object> item = data.get(0);
        assertThat(item).containsEntry("claimantName", "Marcus");
        assertThat(item).containsEntry("summary", "2 nights at Glacier");
        assertThat(item).doesNotContainKey("memberId");
        assertThat(item).doesNotContainKey("id");
    }

    @Test
    @DisplayName("list returns only the caller's own wins")
    void listOwnerScoped() {
        Win mine = new Win("w1", CALLER, null, null, null, null, "my win", null, false, null, Instant.now());
        when(winRepository.findByMember(TENANT, CALLER)).thenReturn(List.of(mine));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) controller
                .list(null, null, requestFrom("marcus@example.com")).getBody().get("data");

        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsEntry("summary", "my win");
        verify(winRepository).findByMember(TENANT, CALLER);
    }

    @Test
    @DisplayName("stats returns the target win count")
    void statsReturnsCount() {
        Instant last = Instant.now();
        when(winRepository.statsForTarget(TENANT, "target-9"))
                .thenReturn(new TargetStats("target-9", 7L, last));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) controller.stats("target-9").get("data");

        assertThat(data).containsEntry("targetId", "target-9");
        assertThat(data).containsEntry("winCount", 7L);
        assertThat(data).containsEntry("lastWinAt", last);
    }
}
