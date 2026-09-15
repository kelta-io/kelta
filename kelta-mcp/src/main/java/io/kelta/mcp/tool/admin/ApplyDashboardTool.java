package io.kelta.mcp.tool.admin;

import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.error.McpErrorMapper;
import io.kelta.mcp.tool.AdminTool;
import io.kelta.mcp.tool.Schemas;
import io.kelta.mcp.tool.ToolHints;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Idempotent create-or-update for a {@code dashboards} row and its {@code
 * dashboard-components} by title, in one call — keyed on {@code name} for the dashboard
 * (via {@link AdminLookups#upsert}) and {@code title} for each component, resolved locally
 * against one {@link AdminLookups#list} of the dashboard's existing components (the same
 * shape {@link ApplyMenuTool} uses for menu items).
 *
 * <p>{@code config} is free JSON checked only by {@code DashboardComponentValidator} (KLT-213)
 * — a wrong {@code collectionName}/field/operator or a grid position outside the dashboard's
 * {@code columnCount}. Rather than re-deriving that grammar client-side (which would just
 * drift, as {@link ApplyListViewTool}'s class doc notes for {@code rowLimit}/operators), this
 * tool dry-runs the whole desired component set through the worker's own {@code POST
 * /api/dashboards/{id}/validate} <strong>before creating or updating the dashboard row
 * itself</strong>, and before creating, updating or deleting a single component — an invalid
 * component fails the whole call with a structured error (pointer {@code
 * /components/<index>/<field>}) and nothing is written at all, not even a brand-new
 * dashboard.
 *
 * <p>{@code sortOrder} is always derived from array position, matching {@code apply_menu}'s
 * {@code displayOrder} and {@code apply_picklist}'s {@code sortOrder}. A component's optional
 * {@code report} is a report <em>name</em>, resolved to {@code reportId} via {@link
 * AdminLookups#idByNaturalKey} — omit it entirely for a component whose {@code config} names a
 * target collection directly (`dashboard_component.report_id` is a nullable lookup, not a
 * master-detail). {@code prune:true} hard-deletes an existing component absent from the
 * desired set; omitted or {@code false} reports it as {@code stale} instead, mirroring {@code
 * apply_menu}.
 */
@Component
public class ApplyDashboardTool implements AdminTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Path placeholder for {@code POST /api/dashboards/{id}/validate} when the named dashboard
     * doesn't exist yet — validation must run <em>before</em> the dashboard row is created, so
     * there is no real id to dry-run against. A well-formed but unassigned id makes the worker's
     * {@code resolveColumnCount} lookup miss cleanly (columnCount-overflow checking is simply
     * skipped, since there is no persisted columnCount yet to check against) rather than fail on
     * a malformed id; the columnPosition &lt; 1 check this tool relies on is independent of that
     * lookup and still runs.
     */
    static final String UNSAVED_DASHBOARD_ID = "00000000-0000-0000-0000-000000000000";

    private final GatewayHttpClient gateway;
    private final AdminLookups lookups;

    public ApplyDashboardTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
        this.lookups = new AdminLookups(gateway);
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> componentItem = new LinkedHashMap<>();
        componentItem.put("type", "object");
        Map<String, Object> componentProps = new LinkedHashMap<>();
        componentProps.put("title", Schemas.string(
                "Widget title — components are matched on this within the dashboard across repeated applies."));
        componentProps.put("componentType", Schemas.string(
                "Widget renderer: metric, chart, table or recent."));
        componentProps.put("columnPosition", Schemas.integer("1-based grid column the widget starts at.", 1, null));
        componentProps.put("rowPosition", Schemas.integer("1-based grid row the widget starts at.", 1, null));
        componentProps.put("columnSpan", Schemas.integer("How many grid columns the widget spans (default 1).", 1, null));
        componentProps.put("rowSpan", Schemas.integer("How many grid rows the widget spans (default 1).", 1, null));
        componentProps.put("config", Schemas.freeObject(
                "Widget settings: target collection, aggregate, filters, chart style. Checked by "
                        + "DashboardComponentValidator before anything is written — see kelta://docs/dashboards. "
                        + "Ids inside config.filters are the caller's to resolve; there is no token syntax."));
        componentProps.put("report", Schemas.string(
                "Name of an existing saved report backing this widget. Omit when config names a "
                        + "collection directly — reportId is optional."));
        componentItem.put("properties", componentProps);
        componentItem.put("additionalProperties", false);

        Map<String, Object> componentsArr = new LinkedHashMap<>();
        componentsArr.put("type", "array");
        componentsArr.put("description",
                "Widgets in this dashboard, matched by title. sortOrder is derived from array "
                        + "position, not taken from input. Any existing component not listed here is "
                        + "reported as \"stale\", or deleted when prune:true.");
        componentsArr.put("items", componentItem);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Schemas.string("Dashboard name — unique within tenant."));
        properties.put("description", Schemas.string("Optional dashboard description."));
        properties.put("accessLevel", Schemas.string("Who may open the dashboard: PRIVATE, PUBLIC or HIDDEN."));
        properties.put("columnCount", Schemas.integer("Width of the dashboard grid in columns.", 1, null));
        properties.put("components", componentsArr);
        properties.put("prune", Schemas.bool(
                "Hard-delete any existing component absent from this call's components array. "
                        + "Omitted or false reports those components as \"stale\" and leaves them in place.", false));

        Tool tool = Tool.builder()
                .name("apply_dashboard")
                .title("Apply Dashboard")
                .description(
                        "Create-or-update a dashboard and its components in one call, keyed on name "
                        + "(dashboard) and title (each component). Idempotent: applying the identical body "
                        + "twice reports {\"action\":\"unchanged\",...} for the dashboard and every "
                        + "component on the second call. Every component is validated (unknown collection/"
                        + "field/operator, non-aggregatable rollup/formula field, or a grid position outside "
                        + "columnCount) before anything is written — an invalid component fails the whole "
                        + "call with a structured error pointing at /components/<index>/<field> and writes "
                        + "nothing. Example: {\"name\":\"Sales Overview\",\"columnCount\":3,\"components\":"
                        + "[{\"title\":\"Total Deals\",\"componentType\":\"metric\",\"columnPosition\":1,"
                        + "\"rowPosition\":1,\"config\":{\"collectionName\":\"deals\",\"aggregateField\":"
                        + "\"amount\",\"aggregateType\":\"SUM\"}},{\"title\":\"Deals by Stage\","
                        + "\"componentType\":\"chart\",\"columnPosition\":2,\"rowPosition\":1,\"columnSpan\":2,"
                        + "\"config\":{\"collectionName\":\"deals\",\"groupByField\":\"stage\",\"chartType\":"
                        + "\"bar\"}},{\"title\":\"Recent Deals\",\"componentType\":\"table\",\"columnPosition\":1,"
                        + "\"rowPosition\":2,\"columnSpan\":3,\"config\":{\"collectionName\":\"deals\",\"fields\":"
                        + "[\"name\",\"amount\",\"stage\"]}}]} builds a metric, a chart and a table widget. "
                        + "Wraps GET+POST/PATCH(/DELETE) /api/dashboards and /api/dashboard-components plus a "
                        + "dry-run POST /api/dashboards/{id}/validate.")
                .inputSchema(Schemas.object(properties, List.of("name", "components")))
                .annotations(ToolHints.write(true, true))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    return apply(args == null ? Map.of() : args);
                })
                .build();
    }

    @SuppressWarnings("unchecked")
    CallToolResult apply(Map<String, Object> args) {
        Object n = args.get("name");
        if (n == null || n.toString().isBlank()) {
            return error("Argument \"name\" is required.");
        }
        if (!(args.get("components") instanceof List<?> componentsArg)) {
            return error("Argument \"components\" must be an array.");
        }
        String name = n.toString();
        boolean prune = Boolean.TRUE.equals(args.get("prune"));

        List<Map<String, Object>> components = new ArrayList<>();
        for (int i = 0; i < componentsArg.size(); i++) {
            if (!(componentsArg.get(i) instanceof Map<?, ?> raw)) {
                return error("components/" + i + " must be an object.");
            }
            Map<String, Object> component = (Map<String, Object>) raw;
            if (!(component.get("title") instanceof String title) || title.isBlank()) {
                return error("components/" + i + " needs a non-blank \"title\".");
            }
            if (!(component.get("componentType") instanceof String type) || type.isBlank()) {
                return error("components/" + i + " needs a non-blank \"componentType\".");
            }
            if (!(component.get("columnPosition") instanceof Number) || !(component.get("rowPosition") instanceof Number)) {
                return error("components/" + i + " needs numeric \"columnPosition\" and \"rowPosition\".");
            }
            components.add(component);
        }

        try {
            Map<String, Object> dashboardAttrs = new LinkedHashMap<>();
            if (args.get("description") instanceof String d) dashboardAttrs.put("description", d);
            if (args.get("accessLevel") instanceof String a && !a.isBlank()) dashboardAttrs.put("accessLevel", a);
            if (args.get("columnCount") instanceof Number c) dashboardAttrs.put("columnCount", c.intValue());

            String existingDashboardId = lookups.idByNaturalKey("dashboards", Map.of("name", name));

            List<Map<String, Object>> desired = new ArrayList<>();
            for (int i = 0; i < components.size(); i++) {
                Map<String, Object> component = components.get(i);
                String reportId = null;
                if (component.get("report") instanceof String reportName && !reportName.isBlank()) {
                    reportId = lookups.idByNaturalKey("reports", Map.of("name", reportName));
                    if (reportId == null) {
                        return error("Report \"" + reportName + "\" not found.");
                    }
                }
                desired.add(buildDesiredComponent(component, existingDashboardId, reportId, i));
            }

            CallToolResult validationError = validateComponents(
                    existingDashboardId != null ? existingDashboardId : UNSAVED_DASHBOARD_ID, desired);
            if (validationError != null) {
                return validationError;
            }

            AdminLookups.UpsertResult dashboardResult = lookups.upsert("dashboards", Map.of("name", name), dashboardAttrs);
            String dashboardId = dashboardResult.id();
            if (!dashboardId.equals(existingDashboardId)) {
                for (Map<String, Object> component : desired) {
                    component.put("dashboardId", dashboardId);
                }
            }

            List<Map<String, Object>> existing = lookups.list("dashboard-components", Map.of("dashboardId", dashboardId), 200);
            Map<String, Map<String, Object>> existingIndex = new LinkedHashMap<>();
            for (Map<String, Object> row : existing) {
                existingIndex.put(String.valueOf(row.get("title")), row);
            }

            Set<String> seenTitles = new LinkedHashSet<>();
            List<Object> componentResults = new ArrayList<>();
            for (Map<String, Object> desiredComponent : desired) {
                componentResults.add(applyComponent(desiredComponent, existingIndex, seenTitles));
            }

            List<String> pruned = new ArrayList<>();
            List<String> stale = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> entry : existingIndex.entrySet()) {
                if (seenTitles.contains(entry.getKey())) continue;
                String id = String.valueOf(entry.getValue().get("id"));
                if (prune) {
                    deleteComponent(id);
                    pruned.add(entry.getKey());
                } else {
                    stale.add(entry.getKey());
                }
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", dashboardResult.action());
            body.put("id", dashboardId);
            if (!dashboardResult.changed().isEmpty()) body.put("changed", dashboardResult.changed());
            body.put("components", componentResults);
            if (!pruned.isEmpty()) body.put("pruned", pruned);
            if (!stale.isEmpty()) body.put("stale", stale);

            return CallToolResult.builder()
                    .content(List.of(new TextContent(MAPPER.writeValueAsString(body))))
                    .build();
        } catch (AdminLookups.GatewayFailure e) {
            return McpErrorMapper.toResult(e.response);
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }
    }

    /** Builds the effective attribute map for one desired component — used for validate, diff, create and patch. */
    private static Map<String, Object> buildDesiredComponent(Map<String, Object> component, String dashboardId,
            String reportId, int index) {
        Map<String, Object> desired = new LinkedHashMap<>();
        desired.put("dashboardId", dashboardId);
        desired.put("reportId", reportId);
        desired.put("componentType", component.get("componentType").toString());
        desired.put("title", component.get("title").toString());
        desired.put("columnPosition", ((Number) component.get("columnPosition")).intValue());
        desired.put("rowPosition", ((Number) component.get("rowPosition")).intValue());
        desired.put("columnSpan", component.get("columnSpan") instanceof Number s ? s.intValue() : 1);
        desired.put("rowSpan", component.get("rowSpan") instanceof Number s ? s.intValue() : 1);
        desired.put("config", component.get("config") instanceof Map<?, ?> c ? c : Map.of());
        desired.put("sortOrder", index);
        return desired;
    }

    /**
     * Dry-runs the whole desired component set through the worker's {@code
     * POST /api/dashboards/{id}/validate} — the same {@code DashboardComponentValidator}
     * the write-path {@code BeforeSaveHook} runs — before this tool issues a single create,
     * update or delete. Returns a structured error (pointer {@code /components/<index>/<field>})
     * when any component is invalid, or {@code null} when the whole set is clean.
     */
    private CallToolResult validateComponents(String dashboardId, List<Map<String, Object>> desired) {
        String path = "/api/dashboards/" + URLEncoder.encode(dashboardId, StandardCharsets.UTF_8) + "/validate";
        Map<String, Object> body = Map.of("components", desired);
        GatewayHttpClient.Response response = gateway.post(path, body);
        if (!response.isSuccess()) {
            throw new AdminLookups.GatewayFailure(response);
        }
        JsonNode root = MAPPER.readTree(response.body());
        if (root.path("valid").asBoolean(true)) {
            return null;
        }

        List<Object> jsonApiErrors = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        JsonNode resultsNode = root.path("components");
        for (int i = 0; i < resultsNode.size(); i++) {
            for (JsonNode fieldError : resultsNode.get(i).path("errors")) {
                String field = fieldError.path("field").asString("");
                String message = fieldError.path("message").asString("Invalid value");
                String pointer = "/components/" + i + (field.isBlank() ? "" : "/" + field);

                Map<String, Object> jsonApiError = new LinkedHashMap<>();
                jsonApiError.put("status", "400");
                jsonApiError.put("code", "VALIDATION_FAILED");
                jsonApiError.put("title", "Validation Error");
                jsonApiError.put("detail", message);
                jsonApiError.put("source", Map.of("pointer", pointer));
                jsonApiErrors.add(jsonApiError);
                messages.add(pointer + ": " + message);
            }
        }

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("status", 400);
        structured.put("errors", jsonApiErrors);

        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(String.join("; ", messages))))
                .structuredContent(structured)
                .build();
    }

    /** Create-or-update one component against the pre-fetched {@code existingIndex}, keyed on title. */
    private Map<String, Object> applyComponent(Map<String, Object> desired,
            Map<String, Map<String, Object>> existingIndex, Set<String> seenTitles) {
        String title = String.valueOf(desired.get("title"));
        seenTitles.add(title);
        Map<String, Object> existing = existingIndex.get(title);

        String id;
        String action;
        List<String> changed = List.of();
        if (existing == null) {
            Map<String, Object> createAttrs = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : desired.entrySet()) {
                if (entry.getValue() != null) createAttrs.put(entry.getKey(), entry.getValue());
            }
            id = createComponent(createAttrs);
            action = "created";
        } else {
            id = String.valueOf(existing.get("id"));
            changed = AdminLookups.diff(existing, desired);
            if (changed.isEmpty()) {
                action = "unchanged";
            } else {
                Map<String, Object> patchAttrs = new LinkedHashMap<>();
                for (String key : changed) patchAttrs.put(key, desired.get(key));
                patchComponent(id, patchAttrs);
                action = "updated";
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", title);
        result.put("action", action);
        result.put("id", id);
        if (!changed.isEmpty()) result.put("changed", changed);
        return result;
    }

    private String createComponent(Map<String, Object> attributes) {
        Map<String, Object> body = Map.of("data", Map.of("type", "dashboard-components", "attributes", attributes));
        GatewayHttpClient.Response response = gateway.post("/api/dashboard-components", body);
        if (!response.isSuccess()) {
            throw new AdminLookups.GatewayFailure(response);
        }
        return AdminLookups.firstResourceId(response.body());
    }

    private void patchComponent(String id, Map<String, Object> attributes) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "dashboard-components", "id", id, "attributes", attributes));
        String path = "/api/dashboard-components/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        GatewayHttpClient.Response response = gateway.patch(path, body);
        if (!response.isSuccess()) {
            throw new AdminLookups.GatewayFailure(response);
        }
    }

    private void deleteComponent(String id) {
        String path = "/api/dashboard-components/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        GatewayHttpClient.Response response = gateway.delete(path);
        if (!response.isSuccess()) {
            throw new AdminLookups.GatewayFailure(response);
        }
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
