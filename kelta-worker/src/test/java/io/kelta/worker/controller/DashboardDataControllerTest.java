package io.kelta.worker.controller;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.*;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.DashboardComponentValidator;
import io.kelta.worker.service.DashboardDataService;
import io.kelta.worker.service.DashboardDataService.WidgetExecutionException;
import io.kelta.worker.service.DashboardDataService.WidgetResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardDataControllerTest {

    private DashboardDataService dashboardDataService;
    private DashboardComponentValidator dashboardComponentValidator;
    private QueryEngine queryEngine;
    private CollectionRegistry collectionRegistry;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private HttpServletRequest request;
    private DashboardDataController controller;

    @BeforeEach
    void setUp() {
        dashboardDataService = mock(DashboardDataService.class);
        dashboardComponentValidator = mock(DashboardComponentValidator.class);
        queryEngine = mock(QueryEngine.class);
        collectionRegistry = mock(CollectionRegistry.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        request = mock(HttpServletRequest.class);
        controller = new DashboardDataController(
            dashboardDataService, dashboardComponentValidator, queryEngine, collectionRegistry,
            permissionResolver, bootstrapRepository);
        grantPermission("VIEW_ANALYTICS");
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

    // =========================================================================
    // Dashboard data endpoint tests
    // =========================================================================

    @Test
    void shouldExecuteDashboardWithMultipleWidgets() {
        setupDashboardRegistry();

        // Dashboard record
        Map<String, Object> dashboard = Map.of("id", "dash-1", "name", "Sales Dashboard");
        when(queryEngine.getById(any(), eq("dash-1")))
            .thenReturn(Optional.of(dashboard));

        // Components
        List<Map<String, Object>> components = List.of(
            Map.of("id", "comp-1", "componentType", "metric", "dashboardId", "dash-1",
                   "sortOrder", 1, "config", Map.of()),
            Map.of("id", "comp-2", "componentType", "chart", "dashboardId", "dash-1",
                   "sortOrder", 2, "config", Map.of()));
        QueryResult componentsResult = QueryResult.of(components, 2L, new Pagination(1, 100));

        // First getById for dashboard, then executeQuery for components
        CollectionDefinition componentsDef = SystemCollectionDefinitions.dashboardComponents();
        when(collectionRegistry.get("dashboard-components")).thenReturn(componentsDef);
        when(queryEngine.executeQuery(eq(componentsDef), any())).thenReturn(componentsResult);

        // Widget execution results
        Map<String, WidgetResult> widgetResults = new LinkedHashMap<>();
        widgetResults.put("comp-1", new WidgetResult("metric",
            Map.of("value", 42L), null, null));
        widgetResults.put("comp-2", new WidgetResult("chart",
            Map.of("series", List.of()), null, null));
        when(dashboardDataService.executeDashboard(eq("dash-1"), eq(components), any(), any()))
            .thenReturn(widgetResults);

        ResponseEntity<Map<String, Object>> response = controller.executeDashboard(
            "dash-1", Map.of(), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> attrs = getAttributes(response.getBody());
        assertEquals("dash-1", attrs.get("dashboardId"));
        assertEquals("Sales Dashboard", attrs.get("dashboardName"));
        assertNotNull(attrs.get("widgets"));

        Map<String, Object> meta = getMeta(response.getBody());
        assertEquals(2, meta.get("widgetCount"));
        assertEquals(0L, meta.get("errorCount"));
    }

    @Test
    void shouldReturn404WhenDashboardNotFound() {
        setupDashboardRegistry();
        when(queryEngine.getById(any(), eq("nonexistent")))
            .thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.executeDashboard(
            "nonexistent", null, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturn404WhenDashboardsCollectionMissing() {
        when(collectionRegistry.get("dashboards")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.executeDashboard(
            "dash-1", null, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturnEmptyWidgetsWhenNoComponents() {
        setupDashboardRegistry();

        Map<String, Object> dashboard = Map.of("id", "dash-2", "name", "Empty Dashboard");
        when(queryEngine.getById(any(), eq("dash-2")))
            .thenReturn(Optional.of(dashboard));

        CollectionDefinition componentsDef = SystemCollectionDefinitions.dashboardComponents();
        when(collectionRegistry.get("dashboard-components")).thenReturn(componentsDef);
        when(queryEngine.executeQuery(eq(componentsDef), any()))
            .thenReturn(QueryResult.empty(new Pagination(1, 100)));

        ResponseEntity<Map<String, Object>> response = controller.executeDashboard(
            "dash-2", null, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> attrs = getAttributes(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> widgets = (Map<String, Object>) attrs.get("widgets");
        assertTrue(widgets.isEmpty());
    }

    @Test
    void shouldReportWidgetErrorsInResponse() {
        setupDashboardRegistry();

        Map<String, Object> dashboard = Map.of("id", "dash-3", "name", "Error Dashboard");
        when(queryEngine.getById(any(), eq("dash-3")))
            .thenReturn(Optional.of(dashboard));

        List<Map<String, Object>> components = List.of(
            Map.of("id", "comp-err", "componentType", "metric", "dashboardId", "dash-3",
                   "sortOrder", 1, "config", Map.of()));
        CollectionDefinition componentsDef = SystemCollectionDefinitions.dashboardComponents();
        when(collectionRegistry.get("dashboard-components")).thenReturn(componentsDef);
        when(queryEngine.executeQuery(eq(componentsDef), any()))
            .thenReturn(QueryResult.of(components, 1L, new Pagination(1, 100)));

        Map<String, WidgetResult> widgetResults = Map.of(
            "comp-err", WidgetResult.error("Collection not found"));
        when(dashboardDataService.executeDashboard(eq("dash-3"), eq(components), any(), any()))
            .thenReturn(widgetResults);

        ResponseEntity<Map<String, Object>> response = controller.executeDashboard(
            "dash-3", Map.of(), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> meta = getMeta(response.getBody());
        assertEquals(1L, meta.get("errorCount"));
    }

    // =========================================================================
    // Single component endpoint tests
    // =========================================================================

    @Test
    void shouldExecuteSingleComponent() {
        CollectionDefinition componentsDef = SystemCollectionDefinitions.dashboardComponents();
        when(collectionRegistry.get("dashboard-components")).thenReturn(componentsDef);

        Map<String, Object> component = new HashMap<>();
        component.put("id", "comp-single");
        component.put("componentType", "metric");
        component.put("dashboardId", "dash-1");
        component.put("config", Map.of("collectionName", "accounts", "aggregateFunction", "COUNT"));
        when(queryEngine.getById(eq(componentsDef), eq("comp-single")))
            .thenReturn(Optional.of(component));

        WidgetResult widgetResult = new WidgetResult("metric",
            Map.of("value", 99L), null, null);
        when(dashboardDataService.executeWidget(eq(component), any(), any()))
            .thenReturn(widgetResult);

        ResponseEntity<Map<String, Object>> response = controller.executeComponent(
            "dash-1", "comp-single", Map.of(), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> attrs = getAttributes(response.getBody());
        assertEquals("comp-single", attrs.get("componentId"));
        assertEquals("dash-1", attrs.get("dashboardId"));
        assertEquals("metric", attrs.get("type"));
    }

    @Test
    void shouldReturn404WhenComponentNotFound() {
        CollectionDefinition componentsDef = SystemCollectionDefinitions.dashboardComponents();
        when(collectionRegistry.get("dashboard-components")).thenReturn(componentsDef);
        when(queryEngine.getById(eq(componentsDef), eq("nonexistent")))
            .thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.executeComponent(
            "dash-1", "nonexistent", null, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturn404WhenComponentBelongsToDifferentDashboard() {
        CollectionDefinition componentsDef = SystemCollectionDefinitions.dashboardComponents();
        when(collectionRegistry.get("dashboard-components")).thenReturn(componentsDef);

        Map<String, Object> component = new HashMap<>();
        component.put("id", "comp-other");
        component.put("dashboardId", "dash-other");
        when(queryEngine.getById(eq(componentsDef), eq("comp-other")))
            .thenReturn(Optional.of(component));

        ResponseEntity<Map<String, Object>> response = controller.executeComponent(
            "dash-1", "comp-other", null, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturn400WhenWidgetExecutionFails() {
        CollectionDefinition componentsDef = SystemCollectionDefinitions.dashboardComponents();
        when(collectionRegistry.get("dashboard-components")).thenReturn(componentsDef);

        Map<String, Object> component = new HashMap<>();
        component.put("id", "comp-bad");
        component.put("dashboardId", "dash-1");
        component.put("componentType", "metric");
        when(queryEngine.getById(eq(componentsDef), eq("comp-bad")))
            .thenReturn(Optional.of(component));

        when(dashboardDataService.executeWidget(eq(component), any(), any()))
            .thenThrow(new WidgetExecutionException("Invalid config"));

        ResponseEntity<Map<String, Object>> response = controller.executeComponent(
            "dash-1", "comp-bad", Map.of(), request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void shouldReturn404WhenComponentsCollectionMissing() {
        when(collectionRegistry.get("dashboard-components")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.executeComponent(
            "dash-1", "comp-1", null, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // =========================================================================
    // Validate endpoint tests
    // =========================================================================

    @Test
    void validateReportsValidWhenNoErrors() {
        CollectionDefinition componentsDef = SystemCollectionDefinitions.dashboardComponents();
        when(collectionRegistry.get("dashboard-components")).thenReturn(componentsDef);

        Map<String, Object> component = new HashMap<>();
        component.put("id", "comp-ok");
        component.put("componentType", "metric");
        component.put("config", Map.of("collectionName", "accounts"));
        when(queryEngine.executeQuery(eq(componentsDef), any()))
            .thenReturn(QueryResult.of(List.of(component), 1L, new Pagination(1, 100)));

        when(dashboardComponentValidator.validate(any())).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.validateDashboard(
            "dash-1", null, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(true, body.get("valid"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components = (List<Map<String, Object>>) body.get("components");
        assertEquals(1, components.size());
        assertEquals("comp-ok", components.get(0).get("id"));
        assertTrue(((List<?>) components.get(0).get("errors")).isEmpty());
    }

    @Test
    void validateReportsErrorsAndMarksInvalidWithoutWrites() {
        Map<String, Object> candidate = new HashMap<>();
        candidate.put("id", "comp-bad");
        candidate.put("componentType", "chart");
        candidate.put("config", Map.of("collectionName", "nope"));

        when(dashboardComponentValidator.validate(any())).thenReturn(
            List.of(new BeforeSaveResult.ValidationError("config/collectionName", "Unknown collection 'nope'")));

        ResponseEntity<Map<String, Object>> response = controller.validateDashboard(
            "dash-1", Map.of("components", List.of(candidate)), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(false, body.get("valid"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components = (List<Map<String, Object>>) body.get("components");
        assertEquals(1, components.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) components.get(0).get("errors");
        assertEquals(1, errors.size());
        assertEquals("config/collectionName", errors.get(0).get("field"));

        // Dry run — nothing is persisted through the query engine or dashboard service.
        verifyNoInteractions(dashboardDataService);
        verify(queryEngine, never()).create(any(), any());
    }

    @Test
    void validateRejectsCallerWithoutAnalyticsPermission() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "API_ACCESS", "granted", true)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.validateDashboard("dash-1", null, request));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(dashboardComponentValidator);
    }

    // =========================================================================
    // Authorization gate tests
    // =========================================================================

    @Test
    void dashboardRejectsCallerWithoutIdentity() {
        when(permissionResolver.getProfileId(request)).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.executeDashboard("dash-1", null, request));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(dashboardDataService);
    }

    @Test
    void dashboardRejectsProfileWithoutAnalyticsPermission() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "API_ACCESS", "granted", true),
                Map.of("permission_name", "VIEW_ANALYTICS", "granted", false)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.executeDashboard("dash-1", null, request));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(dashboardDataService);
    }

    @Test
    void componentRejectsProfileWithoutAnalyticsPermission() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "API_ACCESS", "granted", true)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.executeComponent("dash-1", "comp-1", null, request));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(dashboardDataService);
    }

    @Test
    void dashboardAcceptsManageReportsAsFallback() {
        grantPermission("MANAGE_REPORTS");
        setupDashboardRegistry();
        when(queryEngine.getById(any(), eq("dash-1"))).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.executeDashboard("dash-1", null, request);

        // Gate passed — the 404 proves the request reached the dashboard lookup
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setupDashboardRegistry() {
        CollectionDefinition dashboardsDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("dashboards")).thenReturn(dashboardsDef);
    }
}
