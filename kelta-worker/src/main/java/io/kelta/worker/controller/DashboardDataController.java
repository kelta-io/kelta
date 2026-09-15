package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.*;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.DashboardComponentValidator;
import io.kelta.worker.service.DashboardDataService;
import io.kelta.worker.service.DashboardDataService.WidgetResult;
import io.kelta.worker.service.ReportExecutionService.MaskingPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * REST controller for dashboard widget data endpoints.
 *
 * <p>Provides endpoints to fetch aggregated data for dashboard widgets
 * (metric cards, bar charts, data tables, and recent records).
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/dashboards")
public class DashboardDataController {

    private static final Logger log = LoggerFactory.getLogger(DashboardDataController.class);
    private static final String VIEW_PERMISSION = "VIEW_ANALYTICS";
    private static final String MANAGE_PERMISSION = "MANAGE_REPORTS";

    private final DashboardDataService dashboardDataService;
    private final DashboardComponentValidator dashboardComponentValidator;
    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public DashboardDataController(DashboardDataService dashboardDataService,
                                   DashboardComponentValidator dashboardComponentValidator,
                                   QueryEngine queryEngine,
                                   CollectionRegistry collectionRegistry,
                                   CerbosPermissionResolver permissionResolver,
                                   BootstrapRepository bootstrapRepository) {
        this.dashboardDataService = dashboardDataService;
        this.dashboardComponentValidator = dashboardComponentValidator;
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    /**
     * Gates dashboard data access on the VIEW_ANALYTICS system permission; MANAGE_REPORTS
     * (the authoring permission) also passes. Fail-closed: no resolvable profile is rejected.
     * Must run before the endpoint's try/catch so the 403 is not converted to a 500.
     */
    private void requireAnalyticsAccess(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> (VIEW_PERMISSION.equals(p.get("permission_name"))
                        || MANAGE_PERMISSION.equals(p.get("permission_name")))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, VIEW_PERMISSION + " permission required");
        }
    }

    /** Builds the data-masking principal for the calling user from the gateway-forwarded identity headers. */
    private MaskingPrincipal principalOf(HttpServletRequest request) {
        return new MaskingPrincipal(
            permissionResolver.getEmail(request),
            permissionResolver.getProfileId(request),
            permissionResolver.getTenantId(request));
    }

    /**
     * Executes all widgets on a dashboard and returns their data.
     *
     * @param dashboardId the dashboard ID
     * @param body        optional runtime parameters (timeRange, startDate, endDate, etc.)
     * @return widget data keyed by component ID
     */
    @PostMapping("/{dashboardId}/data")
    public ResponseEntity<Map<String, Object>> executeDashboard(
            @PathVariable String dashboardId,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {

        requireAnalyticsAccess(request);

        try {
            // Load dashboard record
            Map<String, Object> dashboard = loadDashboard(dashboardId);
            if (dashboard == null) {
                return ResponseEntity.notFound().build();
            }

            // Load all components for this dashboard
            List<Map<String, Object>> components = loadDashboardComponents(dashboardId);
            if (components.isEmpty()) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("dashboardId", dashboardId);
                attributes.put("dashboardName", dashboard.get("name"));
                attributes.put("widgets", Map.of());

                return ResponseEntity.ok(
                    JsonApiResponseBuilder.single("dashboard-data", dashboardId, attributes));
            }

            Map<String, String> runtimeParams = body != null ? body : Map.of();
            Map<String, WidgetResult> results = dashboardDataService.executeDashboard(
                dashboardId, components, runtimeParams, principalOf(request));

            // Build response
            Map<String, Object> widgetData = new LinkedHashMap<>();
            for (Map.Entry<String, WidgetResult> entry : results.entrySet()) {
                WidgetResult wr = entry.getValue();
                Map<String, Object> widget = new LinkedHashMap<>();
                if (wr.error() != null) {
                    widget.put("error", wr.error());
                } else {
                    widget.put("type", wr.type());
                    widget.put("data", wr.data());
                    if (wr.pagination() != null) {
                        widget.put("pagination", wr.pagination());
                    }
                }
                widgetData.put(entry.getKey(), widget);
            }

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("dashboardId", dashboardId);
            attributes.put("dashboardName", dashboard.get("name"));
            attributes.put("widgets", widgetData);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("widgetCount", components.size());
            meta.put("errorCount", results.values().stream()
                .filter(r -> r.error() != null).count());

            return ResponseEntity.ok(
                JsonApiResponseBuilder.single("dashboard-data", dashboardId, attributes, meta));

        } catch (Exception e) {
            log.error("Dashboard data execution error: dashboardId={}", dashboardId, e);
            return ResponseEntity.internalServerError().body(
                JsonApiResponseBuilder.error("500", "Internal Server Error",
                    "Failed to execute dashboard"));
        }
    }

    /**
     * Executes a single dashboard component/widget and returns its data.
     *
     * @param dashboardId the dashboard ID
     * @param componentId the component ID
     * @param body        optional runtime parameters
     * @return widget data for the single component
     */
    @PostMapping("/{dashboardId}/components/{componentId}/data")
    public ResponseEntity<Map<String, Object>> executeComponent(
            @PathVariable String dashboardId,
            @PathVariable String componentId,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {

        requireAnalyticsAccess(request);

        try {
            // Load the specific component
            Map<String, Object> component = loadComponent(componentId, dashboardId);
            if (component == null) {
                return ResponseEntity.notFound().build();
            }

            Map<String, String> runtimeParams = body != null ? body : Map.of();
            WidgetResult result = dashboardDataService.executeWidget(
                component, runtimeParams, principalOf(request));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("componentId", componentId);
            attributes.put("dashboardId", dashboardId);

            if (result.error() != null) {
                attributes.put("error", result.error());
            } else {
                attributes.put("type", result.type());
                attributes.put("data", result.data());
                if (result.pagination() != null) {
                    attributes.put("pagination", result.pagination());
                }
            }

            return ResponseEntity.ok(
                JsonApiResponseBuilder.single("widget-data", componentId, attributes));

        } catch (DashboardDataService.WidgetExecutionException e) {
            log.warn("Widget execution failed: componentId={}, error={}", componentId, e.getMessage());
            return ResponseEntity.badRequest().body(
                JsonApiResponseBuilder.error("400", "Bad Request", e.getMessage()));
        } catch (Exception e) {
            log.error("Widget execution error: componentId={}", componentId, e);
            return ResponseEntity.internalServerError().body(
                JsonApiResponseBuilder.error("500", "Internal Server Error",
                    "Failed to execute widget"));
        }
    }

    /**
     * Validates a dashboard's components without persisting anything, using the same
     * {@link DashboardComponentValidator} the write-path {@code BeforeSaveHook} runs.
     * If the request body carries a {@code components} array, those (candidate,
     * possibly-unsaved) components are validated in place of the dashboard's saved
     * ones — the shape the builder UI uses to check a config before it saves.
     *
     * @param dashboardId the dashboard ID
     * @param body        optional {@code {"components": [...]}} payload of candidate components
     * @return {@code {valid, components: [{id, errors}]}}
     */
    @PostMapping("/{dashboardId}/validate")
    public ResponseEntity<Map<String, Object>> validateDashboard(
            @PathVariable String dashboardId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        requireAnalyticsAccess(request);

        List<Map<String, Object>> components = extractCandidateComponents(body);
        if (components == null) {
            components = loadDashboardComponents(dashboardId);
        }

        boolean valid = true;
        List<Map<String, Object>> componentResults = new ArrayList<>();
        for (Map<String, Object> component : components) {
            Map<String, Object> effective = new LinkedHashMap<>(component);
            effective.putIfAbsent("dashboardId", dashboardId);

            List<BeforeSaveResult.ValidationError> errors = dashboardComponentValidator.validate(effective);
            if (!errors.isEmpty()) {
                valid = false;
            }

            Map<String, Object> componentResult = new LinkedHashMap<>();
            componentResult.put("id", component.get("id"));
            componentResult.put("errors", errors.stream()
                .map(e -> Map.of("field", e.field(), "message", e.message()))
                .toList());
            componentResults.add(componentResult);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", valid);
        result.put("components", componentResults);

        return ResponseEntity.ok(result);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractCandidateComponents(Map<String, Object> body) {
        if (body == null || !(body.get("components") instanceof List<?> list)) {
            return null;
        }
        List<Map<String, Object>> components = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                components.add((Map<String, Object>) m);
            }
        }
        return components;
    }

    // =========================================================================
    // Data loading helpers
    // =========================================================================

    private Map<String, Object> loadDashboard(String dashboardId) {
        CollectionDefinition dashboardsDef = collectionRegistry.get("dashboards");
        if (dashboardsDef == null) {
            log.error("Dashboards system collection not found in registry");
            return null;
        }
        return queryEngine.getById(dashboardsDef, dashboardId).orElse(null);
    }

    private List<Map<String, Object>> loadDashboardComponents(String dashboardId) {
        CollectionDefinition componentsDef = collectionRegistry.get("dashboard-components");
        if (componentsDef == null) {
            log.error("Dashboard-components system collection not found in registry");
            return List.of();
        }

        List<FilterCondition> filters = List.of(
            new FilterCondition("dashboardId", FilterOperator.EQ, dashboardId));
        List<SortField> sorting = List.of(
            new SortField("sortOrder", SortDirection.ASC));

        QueryRequest request = new QueryRequest(
            new Pagination(1, 100), sorting, List.of(), filters);

        QueryResult result = queryEngine.executeQuery(componentsDef, request);
        return result.data();
    }

    private Map<String, Object> loadComponent(String componentId, String dashboardId) {
        CollectionDefinition componentsDef = collectionRegistry.get("dashboard-components");
        if (componentsDef == null) {
            log.error("Dashboard-components system collection not found in registry");
            return null;
        }

        Optional<Map<String, Object>> component = queryEngine.getById(componentsDef, componentId);
        if (component.isEmpty()) return null;

        // Verify the component belongs to the requested dashboard
        String compDashboardId = (String) component.get().get("dashboardId");
        if (!dashboardId.equals(compDashboardId)) {
            log.warn("Component {} does not belong to dashboard {}", componentId, dashboardId);
            return null;
        }

        return component.get();
    }
}
