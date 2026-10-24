package io.kelta.mcp.tool.admin;

import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.error.McpErrorMapper;
import io.kelta.mcp.tool.AdminTool;
import io.kelta.mcp.tool.Schemas;
import io.kelta.mcp.tool.ToolHints;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hosted counterpart of {@code kelta metadata export}: {@code POST /api/packages/export} with the
 * same body the CLI builds ({@code name}, {@code version}, {@code collectionNames}, {@code
 * excludeTypes} — each only when given). The worker answers with the package as an {@code
 * application/json} attachment; where the CLI writes it to a file, this tool returns the JSON
 * text itself, ready to pass to {@code diff_metadata} / {@code apply_metadata}.
 */
@Component
public class ExportMetadataTool implements AdminTool {

    private final GatewayHttpClient gateway;

    public ExportMetadataTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Schemas.string("Package name (default: the tenant slug)."));
        properties.put("version", Schemas.string("Package version (default: 1.0.0)."));
        properties.put("collections", stringArray(
                "Collection names: export only these, with their validation rules, page layouts, list "
                        + "views, record-triggered flows, global picklists and the dashboards with a widget "
                        + "on them. Omit for the whole tenant."));
        properties.put("exclude", stringArray(
                "Item types to leave out: list-view, dashboard, flow, page-layout, validation-rule, "
                        + "ui-page, ui-menu (or the package type names LIST_VIEW, DASHBOARD, ...)."));

        Tool tool = Tool.builder()
                .name("export_metadata")
                .title("Export Metadata")
                .description(
                        "Export this tenant's metadata as a package and return the package JSON as text "
                        + "(the same document `kelta metadata export` writes to a file). Seed records are "
                        + "never exported. Pass the result to diff_metadata to preview it against a tenant "
                        + "or apply_metadata to install it. Example: {\"collections\":[\"deals\",\"accounts\"],"
                        + "\"exclude\":[\"dashboard\"]}. Wraps POST /api/packages/export.")
                .inputSchema(Schemas.object(properties, List.of()))
                .annotations(ToolHints.read())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    return export(args == null ? Map.of() : args);
                })
                .build();
    }

    CallToolResult export(Map<String, Object> args) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (args.get("name") instanceof String name && !name.isBlank()) body.put("name", name);
        if (args.get("version") instanceof String version && !version.isBlank()) body.put("version", version);

        List<String> collections = stringList(args.get("collections"));
        if (collections == null) {
            return McpErrorMapper.badRequest("Argument \"collections\" must be an array of strings.", List.of());
        }
        if (!collections.isEmpty()) body.put("collectionNames", collections);

        List<String> exclude = stringList(args.get("exclude"));
        if (exclude == null) {
            return McpErrorMapper.badRequest("Argument \"exclude\" must be an array of strings.", List.of());
        }
        if (!exclude.isEmpty()) body.put("excludeTypes", exclude.stream().map(ExportMetadataTool::toPackageType).toList());

        try {
            return McpErrorMapper.toResult(gateway.post("/api/packages/export", body));
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }
    }

    /** {@code list-views} / {@code dashboard} / {@code page_layout} → LIST_VIEW, DASHBOARD, PAGE_LAYOUT (as the CLI maps them). */
    static String toPackageType(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replaceAll("S$", "");
    }

    /** Non-blank trimmed entries; empty when absent, null when the value is not a string array. */
    private static List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> items)) return null;
        List<String> out = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String s)) return null;
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private static Map<String, Object> stringArray(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", Map.of("type", "string"));
        return schema;
    }
}
