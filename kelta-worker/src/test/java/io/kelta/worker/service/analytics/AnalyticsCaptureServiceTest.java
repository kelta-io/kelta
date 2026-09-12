package io.kelta.worker.service.analytics;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.AnalyticsEventRepository;
import io.kelta.worker.repository.AnalyticsEventRepository.AnalyticsEvent;
import io.kelta.worker.service.analytics.AnalyticsCaptureService.IncomingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("AnalyticsCaptureService")
class AnalyticsCaptureServiceTest {

    private AnalyticsEventRepository repository;
    private io.kelta.runtime.router.UserIdResolver userIdResolver;
    private AnalyticsCaptureService service;

    @BeforeEach
    void setUp() {
        repository = mock(AnalyticsEventRepository.class);
        userIdResolver = mock(io.kelta.runtime.router.UserIdResolver.class);
        // Identity by default: existing tests pass ids and expect them stored as-is.
        org.mockito.Mockito.lenient().when(userIdResolver.resolve(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        service = new AnalyticsCaptureService(repository, new ObjectMapper(), userIdResolver);
        TenantContext.set("tenant-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private List<AnalyticsEvent> captureInserted(String tenantId) {
        ArgumentCaptor<List<AnalyticsEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertAll(eq(tenantId), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("captureSearch writes one SEARCH_QUERY row with the query and zeroResult flag")
    void captureSearchWritesRow() {
        service.captureSearch("maroon bells campsite", true, "target-9", "user-1");

        List<AnalyticsEvent> rows = captureInserted("tenant-1");
        assertThat(rows).hasSize(1);
        AnalyticsEvent e = rows.get(0);
        assertThat(e.eventType()).isEqualTo("SEARCH_QUERY");
        assertThat(e.query()).isEqualTo("maroon bells campsite");
        assertThat(e.zeroResult()).isTrue();
        assertThat(e.matchedTargetId()).isEqualTo("target-9");
        assertThat(e.memberId()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("captureSearch ignores a blank query")
    void captureSearchIgnoresBlank() {
        service.captureSearch("   ", false, null, "user-1");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("clamps an over-length query to the column limit")
    void clampsQuery() {
        String huge = "x".repeat(5000);
        service.captureSearch(huge, false, null, "user-1");

        List<AnalyticsEvent> rows = captureInserted("tenant-1");
        assertThat(rows.get(0).query()).hasSize(AnalyticsCaptureService.MAX_QUERY_LEN);
    }

    @Test
    @DisplayName("clamps a future occurredAt to now")
    void clampsFutureOccurredAt() {
        Instant future = Instant.now().plus(2, ChronoUnit.DAYS);
        IncomingEvent event = new IncomingEvent("PAGE_VIEW", null, null, null,
                "/pricing", null, null, "s1", null, future);

        int accepted = service.capture(List.of(event), "user-1");

        assertThat(accepted).isEqualTo(1);
        List<AnalyticsEvent> rows = captureInserted("tenant-1");
        assertThat(rows.get(0).occurredAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("serializes utm/metadata maps to JSON")
    void serializesJson() {
        IncomingEvent event = new IncomingEvent("ASSISTANT_QUESTION", "how do I get an earlier slot?",
                null, null, null, "https://example.com",
                Map.of("source", "campaign"), "s1", Map.of("k", "v"), null);

        service.capture(List.of(event), "user-1");

        List<AnalyticsEvent> rows = captureInserted("tenant-1");
        assertThat(rows.get(0).utmJson()).contains("\"source\":\"campaign\"");
        assertThat(rows.get(0).metadataJson()).contains("\"k\":\"v\"");
    }

    @Test
    @DisplayName("skips an event with a blank eventType rather than failing the batch")
    void skipsBlankEventType() {
        IncomingEvent bad = new IncomingEvent("  ", "q", null, null, null, null, null, null, null, null);
        IncomingEvent good = new IncomingEvent("PAGE_VIEW", null, null, null, "/x", null, null, null, null, null);

        int accepted = service.capture(List.of(bad, good), "user-1");

        assertThat(accepted).isEqualTo(1);
        List<AnalyticsEvent> rows = captureInserted("tenant-1");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).eventType()).isEqualTo("PAGE_VIEW");
    }

    @Test
    @DisplayName("best-effort: a repository failure is swallowed and returns 0")
    void swallowsRepositoryFailure() {
        doThrow(new RuntimeException("db down"))
                .when(repository).insertAll(any(), any(), anyList());

        int accepted = service.capture(
                List.of(new IncomingEvent("PAGE_VIEW", null, null, null, "/x", null, null, null, null, null)),
                "user-1");

        assertThat(accepted).isZero();
    }

    @Test
    @DisplayName("skips capture entirely when no tenant is bound")
    void skipsWithoutTenant() {
        TenantContext.clear();
        int accepted = service.capture(
                List.of(new IncomingEvent("PAGE_VIEW", null, null, null, "/x", null, null, null, null, null)),
                "user-1");

        assertThat(accepted).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Resolves the X-User-Id email to platform_user.id before storing member_id")
    @SuppressWarnings("unchecked")
    void resolvesEmailToMemberId() {
        // member_id is varchar(36) and means platform_user.id. Every row in production held an
        // email — short enough to fit — and one over 36 characters failed the whole batch.
        org.mockito.Mockito.when(userIdResolver.resolve("a-rather-long.address@example-company.com", "tenant-1"))
                .thenReturn("uuid-9");
        org.mockito.ArgumentCaptor<java.util.List<AnalyticsEventRepository.AnalyticsEvent>> rows =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);

        service.capture(java.util.List.of(new AnalyticsCaptureService.IncomingEvent(
                        "page_view", null, null, null, "/", null, null, null, null, null)),
                "a-rather-long.address@example-company.com");

        org.mockito.Mockito.verify(repository).insertAll(org.mockito.ArgumentMatchers.eq("tenant-1"),
                org.mockito.ArgumentMatchers.eq("uuid-9"), rows.capture());
        assertThat(rows.getValue().getFirst().memberId()).isEqualTo("uuid-9");
    }
}
