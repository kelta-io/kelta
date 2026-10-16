package io.kelta.worker.controller;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.ReportSystemCollections;
import io.kelta.runtime.query.*;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.interceptor.CerbosRecordAuthorizationAdvice;
import io.kelta.worker.interceptor.DerivedDataController;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.ReportExecutionService;
import io.kelta.worker.service.ReportExecutionService.ColumnConfig;
import io.kelta.worker.service.ReportExecutionService.ReportResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportExecutionControllerTest {

    private ReportExecutionService reportExecutionService;
    private QueryEngine queryEngine;
    private CollectionRegistry collectionRegistry;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private CerbosRecordAuthorizationAdvice recordAuthorization;
    private ReportExecutionController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        reportExecutionService = mock(ReportExecutionService.class);
        queryEngine = mock(QueryEngine.class);
        collectionRegistry = mock(CollectionRegistry.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        recordAuthorization = mock(CerbosRecordAuthorizationAdvice.class);
        request = mock(HttpServletRequest.class);
        controller = new ReportExecutionController(reportExecutionService, queryEngine,
                collectionRegistry, permissionResolver, bootstrapRepository, recordAuthorization);
        grantPermission("VIEW_ANALYTICS");
        when(recordAuthorization.isRecordReadable(any(), anyString(), any())).thenReturn(true);
    }

    private void grantPermission(String permissionName) {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", permissionName, "granted", true)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getAttributes(Map<String, Object> body) {
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return (Map<String, Object>) data.get("attributes");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMeta(Map<String, Object> body) {
        return (Map<String, Object>) body.get("meta");
    }

    @Test
    void shouldExecuteReportSuccessfully() {
        // Set up reports collection in registry
        CollectionDefinition reportsDef = ReportSystemCollections.reports();
        when(collectionRegistry.get("reports")).thenReturn(reportsDef);

        // Set up report config lookup
        Map<String, Object> reportConfig = new HashMap<>();
        reportConfig.put("id", "report-1");
        reportConfig.put("name", "Test Report");
        reportConfig.put("reportType", "TABULAR");
        reportConfig.put("primaryCollectionId", "col-1");
        reportConfig.put("columns", "[{\"fieldName\":\"name\",\"label\":\"Name\",\"type\":\"string\"}]");
        when(queryEngine.getById(eq(reportsDef), eq("report-1")))
            .thenReturn(Optional.of(reportConfig));

        // Set up execution result
        List<ColumnConfig> columns = List.of(new ColumnConfig("name", "Name", "string"));
        List<Map<String, Object>> data = List.of(Map.of("name", "Acme"));
        PaginationMetadata meta = new PaginationMetadata(1, 1, 200, 1);
        ReportResult result = new ReportResult(data, meta, columns, null, null);
        when(reportExecutionService.execute(eq(reportConfig), eq(1), eq(200), any()))
            .thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.executeReport("report-1", 1, 200, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> attrs = getAttributes(response.getBody());
        assertEquals("report-1", attrs.get("reportId"));
        assertEquals("Test Report", attrs.get("reportName"));
        assertEquals("TABULAR", attrs.get("reportType"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) attrs.get("records");
        assertEquals(1, records.size());

        Map<String, Object> responseMeta = getMeta(response.getBody());
        assertEquals(1L, responseMeta.get("totalCount"));
    }

    @Test
    void shouldReturn404WhenReportNotFound() {
        CollectionDefinition reportsDef = ReportSystemCollections.reports();
        when(collectionRegistry.get("reports")).thenReturn(reportsDef);
        when(queryEngine.getById(eq(reportsDef), eq("nonexistent")))
            .thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.executeReport("nonexistent", 1, 200, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturn400WhenExecutionFails() {
        CollectionDefinition reportsDef = ReportSystemCollections.reports();
        when(collectionRegistry.get("reports")).thenReturn(reportsDef);

        Map<String, Object> reportConfig = new HashMap<>();
        reportConfig.put("id", "report-bad");
        reportConfig.put("name", "Bad Report");
        reportConfig.put("primaryCollectionId", "bad-col");
        reportConfig.put("columns", "[]");
        when(queryEngine.getById(eq(reportsDef), eq("report-bad")))
            .thenReturn(Optional.of(reportConfig));

        when(reportExecutionService.execute(any(), anyInt(), anyInt(), any()))
            .thenThrow(new ReportExecutionService.ReportExecutionException("Collection not found"));

        ResponseEntity<Map<String, Object>> response = controller.executeReport("report-bad", 1, 200, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void shouldReturn404WhenReportsCollectionNotInRegistry() {
        when(collectionRegistry.get("reports")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.executeReport("report-1", 1, 200, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturnGroupedDataWhenGroupByIsSet() {
        CollectionDefinition reportsDef = ReportSystemCollections.reports();
        when(collectionRegistry.get("reports")).thenReturn(reportsDef);

        Map<String, Object> reportConfig = new HashMap<>();
        reportConfig.put("id", "report-grp");
        reportConfig.put("name", "Grouped Report");
        reportConfig.put("reportType", "SUMMARY");
        reportConfig.put("primaryCollectionId", "col-1");
        reportConfig.put("columns", "[{\"fieldName\":\"status\",\"label\":\"Status\",\"type\":\"string\"}]");
        reportConfig.put("groupBy", "status");
        when(queryEngine.getById(eq(reportsDef), eq("report-grp")))
            .thenReturn(Optional.of(reportConfig));

        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        groups.put("open", List.of(Map.of("status", "open")));
        groups.put("closed", List.of(Map.of("status", "closed")));

        List<ColumnConfig> columns = List.of(new ColumnConfig("status", "Status", "string"));
        PaginationMetadata meta = new PaginationMetadata(2, 1, 200, 1);
        ReportResult result = new ReportResult(
            List.of(Map.of("status", "open"), Map.of("status", "closed")),
            meta, columns, groups, Map.of());
        when(reportExecutionService.execute(eq(reportConfig), eq(1), eq(200), any()))
            .thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.executeReport("report-grp", 1, 200, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> attrs = getAttributes(response.getBody());
        assertNotNull(attrs.get("groups"));
        assertNotNull(attrs.get("groupAggregations"));
    }

    @Test
    void executeRejectsCallerWithoutIdentity() {
        when(permissionResolver.getProfileId(request)).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.executeReport("report-1", 1, 200, request));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(reportExecutionService);
    }

    @Test
    void executeRejectsProfileWithoutAnalyticsPermission() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "API_ACCESS", "granted", true),
                Map.of("permission_name", "VIEW_ANALYTICS", "granted", false)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.executeReport("report-1", 1, 200, request));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(reportExecutionService);
    }

    @Test
    void executeAcceptsManageReportsAsFallback() {
        grantPermission("MANAGE_REPORTS");
        CollectionDefinition reportsDef = ReportSystemCollections.reports();
        when(collectionRegistry.get("reports")).thenReturn(reportsDef);
        when(queryEngine.getById(eq(reportsDef), eq("report-1"))).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.executeReport("report-1", 1, 200, request);

        // Gate passed — the 404 proves the request reached the report lookup
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void exportRejectsProfileWithoutAnalyticsPermission() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "API_ACCESS", "granted", true)));
        HttpServletResponse response = mock(HttpServletResponse.class);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.exportReport("report-1", "csv", request, response));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(reportExecutionService);
    }

    @Test
    void isExemptFromTheRecordAdviceAsADerivedDataController() {
        assertTrue(controller instanceof DerivedDataController);
    }

    @Test
    void executeReturns404WhenReportIsNotReadable() {
        CollectionDefinition reportsDef = ReportSystemCollections.reports();
        when(collectionRegistry.get("reports")).thenReturn(reportsDef);
        Map<String, Object> reportConfig = Map.of("id", "report-1", "name", "Hidden");
        when(queryEngine.getById(eq(reportsDef), eq("report-1"))).thenReturn(Optional.of(reportConfig));
        when(recordAuthorization.isRecordReadable(request, "reports", reportConfig)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.executeReport("report-1", 1, 200, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verifyNoInteractions(reportExecutionService);
    }

    @Test
    void exportReturns404WhenReportIsNotReadable() {
        CollectionDefinition reportsDef = ReportSystemCollections.reports();
        when(collectionRegistry.get("reports")).thenReturn(reportsDef);
        Map<String, Object> reportConfig = Map.of("id", "report-1", "name", "Hidden");
        when(queryEngine.getById(eq(reportsDef), eq("report-1"))).thenReturn(Optional.of(reportConfig));
        when(recordAuthorization.isRecordReadable(request, "reports", reportConfig)).thenReturn(false);
        HttpServletResponse response = mock(HttpServletResponse.class);

        controller.exportReport("report-1", "csv", request, response);

        verify(response).setStatus(404);
        verifyNoInteractions(reportExecutionService);
    }
}
