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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creates a record in the {@code list-views} system collection.
 *
 * <p>Maps the friendly args onto the worker's actual shape: {@code columns}
 * is a JSON array (from the {@code displayedFields} CSV), the filter object
 * becomes the {@code filters} array, and {@code filters} is ALWAYS sent —
 * the system collection's declared default is unusable (string {@code "[]"}
 * on a JSON field), so omitting it fails validation. (This tool previously
 * posted to a non-existent {@code /api/listViews} path with pass-through
 * attribute names the worker never understood.)
 */
@Component
public class CreateListViewTool implements AdminTool {

    private final GatewayHttpClient gateway;
    private final AdminLookups lookups;

    public CreateListViewTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
        this.lookups = new AdminLookups(gateway);
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("collectionName", Schemas.string("Collection the list view applies to."));
        properties.put("name", Schemas.string("List view name (unique within collection)."));
        properties.put("displayedFields", Schemas.string(
                "Comma-separated field names to show as columns, in display order."));
        properties.put("filter", Schemas.freeObject(
                "Optional saved-filter expression in JSON:API filter shape, e.g. {\"status\":{\"EQ\":\"OPEN\"}}."));
        properties.put("sort", Schemas.string("Default sort field, '-' prefix for descending, e.g. \"-createdAt\"."));
        properties.put("isDefault", Schemas.bool("Whether this is the default list view.", false));
        properties.put("visibility", Schemas.string("PRIVATE (default) or PUBLIC."));
        properties.put("viewType", Schemas.string(
                "Renderer everyone opening this view gets: TABLE (default), KANBAN, CALENDAR or GALLERY."));
        properties.put("typeConfig", Schemas.freeObject(
                "Settings for the chosen renderer, e.g. "
                        + "{\"kanban\":{\"laneField\":\"status\",\"cardFields\":[\"title\"]}}."));

        Tool tool = Tool.builder()
                .name("create_listview")
                .title("Create List View")
                .description("Create a saved list view (column set + filter + sort) for a collection. Wraps POST /api/list-views.")
                .inputSchema(Schemas.object(properties, List.of("collectionName", "name", "displayedFields")))
                .annotations(ToolHints.write(false, false))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();
                    Object cn = args.get("collectionName");
                    Object name = args.get("name");
                    Object df = args.get("displayedFields");
                    if (cn == null || cn.toString().isBlank()
                            || name == null || name.toString().isBlank()
                            || df == null || df.toString().isBlank()) {
                        return error("Arguments \"collectionName\", \"name\", and \"displayedFields\" are required.");
                    }

                    String collectionId = lookups.collectionIdByName(cn.toString());
                    if (collectionId == null) {
                        return error("Collection \"" + cn + "\" not found.");
                    }

                    List<String> columns = Arrays.stream(df.toString().split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();

                    Map<String, Object> attrs = new LinkedHashMap<>();
                    attrs.put("collectionId", collectionId);
                    attrs.put("name", name.toString());
                    attrs.put("columns", columns);
                    attrs.put("isDefault", args.get("isDefault") instanceof Boolean b ? b : Boolean.FALSE);
                    // Always sent: the declared default ("[]" as a STRING on a JSON
                    // field) fails the worker's own type validation when omitted.
                    attrs.put("filters", toFilters(args.get("filter")));
                    if (args.get("visibility") instanceof String v && !v.isBlank()) {
                        attrs.put("visibility", v);
                    }
                    // Renderer + its config (V196) — what makes a view publishable as a
                    // board rather than a column set every user re-configures.
                    if (args.get("viewType") instanceof String vt && !vt.isBlank()) {
                        attrs.put("viewType", vt.trim().toUpperCase(Locale.ROOT));
                    }
                    if (args.get("typeConfig") instanceof Map<?, ?> tc && !tc.isEmpty()) {
                        attrs.put("typeConfig", tc);
                    }
                    if (args.get("sort") instanceof String s && !s.isBlank()) {
                        String first = s.split(",")[0].trim();
                        boolean desc = first.startsWith("-");
                        attrs.put("sortField", desc ? first.substring(1) : first);
                        attrs.put("sortDirection", desc ? "DESC" : "ASC");
                    }

                    Map<String, Object> body = Map.of("data", Map.of(
                            "type", "list-views",
                            "attributes", attrs));
                    try {
                        return McpErrorMapper.toResult(gateway.post("/api/list-views", body));
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
