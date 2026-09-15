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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class UpdateListViewTool implements AdminTool {

    private final GatewayHttpClient gateway;

    public UpdateListViewTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", Schemas.string("List view id (UUID)."));
        properties.put("name", Schemas.string("New list view name."));
        properties.put("displayedFields", Schemas.string(
                "New comma-separated field names to show as columns, in display order."));
        properties.put("filter", Schemas.freeObject(
                "New saved-filter expression in JSON:API filter shape, e.g. {\"status\":{\"EQ\":\"OPEN\"}}. "
                        + "Pass an empty object {} to clear all filters."));
        properties.put("sort", Schemas.string(
                "New default sort field, '-' prefix for descending, e.g. \"-createdAt\"."));
        properties.put("isDefault", Schemas.bool("New default-for-collection flag.", false));
        properties.put("visibility", Schemas.string("PRIVATE or PUBLIC."));
        properties.put("rowLimit", Schemas.integer(
                "New maximum rows rendered: one of 10, 25, 50 or 100.", 10, 100));
        properties.put("viewType", Schemas.string(
                "Renderer everyone opening this view gets: TABLE, KANBAN, CALENDAR or GALLERY."));
        properties.put("typeConfig", Schemas.freeObject(
                "Settings for the chosen renderer, e.g. "
                        + "{\"kanban\":{\"laneField\":\"status\",\"cardFields\":[\"title\"]}}. "
                        + "Pass an empty object {} to clear them."));

        Tool tool = Tool.builder()
                .name("update_listview")
                .title("Update List View")
                .description("Update a saved list view's name, columns, filter, or sort. Wraps PATCH /api/list-views/{id}.")
                .inputSchema(Schemas.object(properties, List.of("id")))
                .annotations(ToolHints.write(true, true))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();
                    Object id = args.get("id");
                    if (id == null || id.toString().isBlank()) {
                        return error("Argument \"id\" is required.");
                    }
                    Map<String, Object> attrs = new LinkedHashMap<>();
                    if (args.get("name") instanceof String s && !s.isBlank()) attrs.put("name", s);
                    if (args.get("displayedFields") instanceof String df && !df.isBlank()) {
                        attrs.put("columns", Arrays.stream(df.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList());
                    }
                    if (args.containsKey("filter")) {
                        attrs.put("filters", toFilters(args.get("filter")));
                    }
                    if (args.get("sort") instanceof String s && !s.isBlank()) {
                        String first = s.split(",")[0].trim();
                        boolean desc = first.startsWith("-");
                        attrs.put("sortField", desc ? first.substring(1) : first);
                        attrs.put("sortDirection", desc ? "DESC" : "ASC");
                    }
                    if (args.get("isDefault") instanceof Boolean b) attrs.put("isDefault", b);
                    if (args.get("visibility") instanceof String v && !v.isBlank()) attrs.put("visibility", v);
                    if (args.get("rowLimit") instanceof Number rl) attrs.put("rowLimit", rl.intValue());
                    // Renderer + its config (V196).
                    if (args.get("viewType") instanceof String vt && !vt.isBlank()) {
                        attrs.put("viewType", vt.trim().toUpperCase(Locale.ROOT));
                    }
                    if (args.containsKey("typeConfig")) {
                        Object tc = args.get("typeConfig");
                        attrs.put("typeConfig",
                                tc instanceof Map<?, ?> m && !m.isEmpty() ? m : null);
                    }
                    if (attrs.isEmpty()) {
                        return error("Provide at least one of name, displayedFields, filter, sort, "
                                + "isDefault, visibility, rowLimit, viewType, typeConfig.");
                    }
                    Map<String, Object> body = Map.of("data", Map.of(
                            "type", "list-views",
                            "id", id.toString(),
                            "attributes", attrs));
                    String path = "/api/list-views/" + URLEncoder.encode(id.toString(), StandardCharsets.UTF_8);
                    try {
                        return McpErrorMapper.toResult(gateway.patch(path, body));
                    } catch (RuntimeException e) {
                        return McpErrorMapper.fromException(e);
                    }
                })
                .build();
    }

    /** {"status":{"EQ":"OPEN"}} → [{"field":"status","operator":"EQ","value":"OPEN"}]. */
    private static List<Map<String, Object>> toFilters(Object filterArg) {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (filterArg instanceof Map<?, ?> filterMap) {
            for (Map.Entry<?, ?> fieldEntry : filterMap.entrySet()) {
                if (fieldEntry.getValue() instanceof Map<?, ?> ops) {
                    for (Map.Entry<?, ?> op : ops.entrySet()) {
                        Map<String, Object> filter = new LinkedHashMap<>();
                        filter.put("field", String.valueOf(fieldEntry.getKey()));
                        filter.put("operator", String.valueOf(op.getKey()));
                        filter.put("value", op.getValue());
                        filters.add(filter);
                    }
                }
            }
        }
        return filters;
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
