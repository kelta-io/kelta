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
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Idempotent create-or-update for a saved list view, keyed on
 * {@code (collectionId, name)} via {@link AdminLookups#upsert}: the first call
 * creates the row, a repeat call with the identical body reports {@code
 * unchanged}, and a call that only changes e.g. {@code rowLimit} reports
 * {@code updated} with {@code changed:["rowLimit"]} — no separate
 * list-then-decide round trip, and no create/update races on the caller side.
 *
 * <p>{@code columns} and {@code filters} are the view's core shape and are
 * always written (an omitted {@code filters} means "no filters", matching the
 * worker's own required-field default — see {@link CreateListViewTool}).
 * {@code sort}, {@code rowLimit}, {@code visibility} and {@code isDefault} are
 * only compared/written when supplied, leaving them at their current value
 * (or the collection's default, on create) otherwise.
 *
 * <p>{@code rowLimit} and each filter's {@code operator} are NOT validated
 * client-side — {@code ListViewConfigHook} (KLT-212) already validates rowLimit
 * against the allowed set and canonicalizes the operator via {@code
 * FilterOperator.parse}, so re-deriving that grammar here would just drift.
 */
@Component
public class ApplyListViewTool implements AdminTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewayHttpClient gateway;
    private final AdminLookups lookups;

    public ApplyListViewTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
        this.lookups = new AdminLookups(gateway);
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> columnsArr = new LinkedHashMap<>();
        columnsArr.put("type", "array");
        columnsArr.put("description", "Field names shown as columns, in display order.");
        columnsArr.put("items", Map.of("type", "string"));

        Map<String, Object> filterItem = new LinkedHashMap<>();
        filterItem.put("type", "object");
        Map<String, Object> filterProps = new LinkedHashMap<>();
        filterProps.put("field", Schemas.string("Field name to filter on."));
        filterProps.put("operator", Schemas.string(
                "Filter operator (eq, neq, gt, lt, gte, lte, in, contains, isnull, ...). "
                        + "Validated and canonicalized server-side."));
        filterProps.put("value", Map.of("description",
                "Filter value. For \"in\", pass an array (or a comma-separated string)."));
        filterItem.put("properties", filterProps);
        filterItem.put("additionalProperties", false);

        Map<String, Object> filtersArr = new LinkedHashMap<>();
        filtersArr.put("type", "array");
        filtersArr.put("description",
                "Filter conditions ANDed together. Always written as given — omit entirely for "
                        + "\"no filters\" (this tool applies the view's whole shape, it does not patch "
                        + "individual filters in).");
        filtersArr.put("items", filterItem);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("collectionName", Schemas.string("Collection the list view applies to."));
        properties.put("name", Schemas.string("List view name — identifies the view together with collectionName."));
        properties.put("columns", columnsArr);
        properties.put("filters", filtersArr);
        properties.put("sort", Schemas.string(
                "Sort field, '-' prefix for descending, e.g. \"-createdAt\". Omit to leave the current sort alone."));
        properties.put("rowLimit", Schemas.integer(
                "Maximum rows rendered. Omit to leave the current value alone.", null, null));
        properties.put("visibility", Schemas.string("PRIVATE, PUBLIC or GROUP. Omit to leave the current value alone."));
        properties.put("isDefault", Schemas.bool(
                "Whether this is the default view for the collection+visibility. Omit to leave the current value alone.", false));

        Tool tool = Tool.builder()
                .name("apply_listview")
                .title("Apply List View")
                .description(
                        "Create-or-update a saved list view, keyed on (collectionName, name). Idempotent: "
                        + "applying the identical body twice reports {\"action\":\"unchanged\",\"id\":...} on "
                        + "the second call; changing one field reports {\"action\":\"updated\",\"changed\":"
                        + "[...]}. Example: {\"collectionName\":\"projects\",\"name\":\"Open by owner\","
                        + "\"columns\":[\"name\",\"owner\",\"status\"],\"filters\":[{\"field\":\"status\","
                        + "\"operator\":\"in\",\"value\":[\"OPEN\",\"PENDING\"]}],\"sort\":\"-createdAt\","
                        + "\"rowLimit\":25}. Wraps GET+POST/PATCH /api/list-views. Deprecates create_listview "
                        + "for setup scripts that re-run.")
                .inputSchema(Schemas.object(properties, List.of("collectionName", "name", "columns")))
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

    CallToolResult apply(Map<String, Object> args) {
        Object cn = args.get("collectionName");
        Object nameArg = args.get("name");
        if (cn == null || cn.toString().isBlank() || nameArg == null || nameArg.toString().isBlank()) {
            return error("Arguments \"collectionName\" and \"name\" are required.");
        }
        if (!(args.get("columns") instanceof List<?> columns) || columns.isEmpty()) {
            return error("Argument \"columns\" must be a non-empty array.");
        }
        String name = nameArg.toString();

        try {
            String collectionId = lookups.collectionIdByName(cn.toString());
            if (collectionId == null) {
                return error("Collection \"" + cn + "\" not found.");
            }

            Map<String, Object> naturalKey = new LinkedHashMap<>();
            naturalKey.put("collectionId", collectionId);
            naturalKey.put("name", name);

            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("columns", columns);
            attrs.put("filters", args.get("filters") instanceof List<?> filters ? filters : List.of());
            if (args.get("sort") instanceof String s && !s.isBlank()) {
                String first = s.split(",")[0].trim();
                boolean desc = first.startsWith("-");
                attrs.put("sortField", desc ? first.substring(1) : first);
                attrs.put("sortDirection", desc ? "DESC" : "ASC");
            }
            if (args.get("rowLimit") instanceof Number rl) attrs.put("rowLimit", rl.intValue());
            if (args.get("visibility") instanceof String v && !v.isBlank()) attrs.put("visibility", v);
            if (args.get("isDefault") instanceof Boolean b) attrs.put("isDefault", b);

            AdminLookups.UpsertResult result = lookups.upsert("list-views", naturalKey, attrs);
            return CallToolResult.builder()
                    .content(List.of(new TextContent(toJson(result))))
                    .build();
        } catch (AdminLookups.GatewayFailure e) {
            return McpErrorMapper.toResult(e.response);
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }
    }

    private static String toJson(AdminLookups.UpsertResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", result.action());
        body.put("id", result.id());
        if (!result.changed().isEmpty()) {
            body.put("changed", result.changed());
        }
        return MAPPER.writeValueAsString(body);
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
