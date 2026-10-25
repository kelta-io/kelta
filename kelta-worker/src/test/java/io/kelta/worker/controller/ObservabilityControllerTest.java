package io.kelta.worker.controller;

import io.kelta.worker.service.AuditQueryService;
import io.kelta.worker.service.ObservabilityQueryService;
import io.kelta.worker.service.ObservabilityQueryService.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ObservabilityController Tests")
class ObservabilityControllerTest {

    private final MockHttpServletRequest adminRequest = AdminPermissionGuardFixture.request();

    private ObservabilityQueryService queryService;
    private AuditQueryService auditQueryService;
    private ObservabilityController controller;

    @BeforeEach
    void setUp() {
        queryService = mock(ObservabilityQueryService.class);
        auditQueryService = mock(AuditQueryService.class);
        controller = new ObservabilityController(queryService, auditQueryService,
                AdminPermissionGuardFixture.granting("VIEW_SETUP"));
    }

    @Nested
    @DisplayName("searchRequestLogs")
    class SearchRequestLogs {

        @Test
        void shouldReturnResultsWithDefaults() {
            when(queryService.searchTraceSpans(any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(new SearchResult(List.of(), 0));

            var response = controller.searchRequestLogs(adminRequest, "slug", null, null, null, null, null, null, null, 0, 50);
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        void shouldReturn500OnException() {
            when(queryService.searchTraceSpans(any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenThrow(new RuntimeException("Tempo error"));

            var response = controller.searchRequestLogs(adminRequest, "slug", null, null, null, null, null, null, null, 0, 50);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("getRequestLog")
    class GetRequestLog {

        @Test
        void shouldReturnTraceDetail() {
            when(queryService.getTraceDetail("trace-123")).thenReturn(List.of(Map.of("span", "data")));

            var response = controller.getRequestLog(adminRequest, "trace-123");
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        void shouldReturn500OnException() {
            when(queryService.getTraceDetail(any())).thenThrow(new RuntimeException("error"));

            var response = controller.getRequestLog(adminRequest, "trace-123");
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("searchLogs")
    class SearchLogs {

        @Test
        void shouldReturnLogResults() {
            when(queryService.searchLogs(any(), anyInt(), anyInt()))
                    .thenReturn(new SearchResult(List.of(), 0));

            var response = controller.searchLogs(adminRequest, null, null, null, null, null, null, 0, 50);
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("searchAudit")
    class SearchAudit {

        @Test
        void shouldReturnAuditResults() {
            when(auditQueryService.searchAudit(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(new SearchResult(List.of(), 0));

            var response = controller.searchAudit(adminRequest, "tenant-1", null, null, null, null, null, 0, 50);
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        void shouldReturn500OnException() {
            when(auditQueryService.searchAudit(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenThrow(new RuntimeException("DB error"));

            var response = controller.searchAudit(adminRequest, "tenant-1", null, null, null, null, null, 0, 50);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        }
    }

    @Test
    @DisplayName("A profile without VIEW_SETUP (API_ACCESS only, like Portal User) gets 403 on every handler")
    void deniedWithoutViewSetup() {
        controller = new ObservabilityController(queryService, auditQueryService,
                AdminPermissionGuardFixture.apiAccessOnly());

        AdminPermissionGuardFixture.assertForbidden(() -> controller.searchRequestLogs(adminRequest, "slug",
                null, null, null, null, null, null, null, 0, 50), "VIEW_SETUP");
        AdminPermissionGuardFixture.assertForbidden(() -> controller.getRequestLog(adminRequest, "trace-1"), "VIEW_SETUP");
        AdminPermissionGuardFixture.assertForbidden(() -> controller.searchLogs(adminRequest,
                null, null, null, null, null, null, 0, 50), "VIEW_SETUP");
        AdminPermissionGuardFixture.assertForbidden(() -> controller.searchAudit(adminRequest, "tenant-1",
                null, null, null, null, null, 0, 50), "VIEW_SETUP");
        verifyNoInteractions(queryService, auditQueryService);
    }

    @Test
    @DisplayName("A profile holding VIEW_SETUP can search request logs")
    void allowedWithViewSetup() {
        when(queryService.searchTraceSpans(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new SearchResult(List.of(), 0));

        var response = controller.searchRequestLogs(adminRequest, "slug", null, null, null, null, null, null, null, 0, 50);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
