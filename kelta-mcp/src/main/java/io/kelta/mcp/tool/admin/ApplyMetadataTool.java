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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hosted counterpart of {@code kelta metadata apply}: {@code POST /api/packages/import} with the
 * CLI's query options — {@code dryRun=true} only when asked, {@code conflictMode} always
 * ({@code skip} by default, as the CLI's {@code --conflict} defaults).
 */
@Component
public class ApplyMetadataTool implements AdminTool {

    private final GatewayHttpClient gateway;

    public ApplyMetadataTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> conflictMode = Schemas.string(
                "On an item that already exists: skip (default) or overwrite. Seed records are always skipped.");
        conflictMode.put("enum", List.of("skip", "overwrite"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PackageArgument.NAME, PackageArgument.schema());
        properties.put("dryRun", Schemas.bool("Validate the package without writing anything.", false));
        properties.put("conflictMode", conflictMode);

        Tool tool = Tool.builder()
                .name("apply_metadata")
                .title("Apply Metadata Package")
                .description(
                        "Install a metadata package into this tenant: its metadata items, then its seed "
                        + "records section (the same import `kelta metadata apply` runs). Pass the package "
                        + "JSON export_metadata returned; preview it first with diff_metadata. Returns "
                        + "created/updated/skipped/failed counts and per-item results. Example: "
                        + "{\"package\":{...},\"conflictMode\":\"overwrite\"}. Wraps POST /api/packages/import.")
                .inputSchema(Schemas.object(properties, List.of(PackageArgument.NAME)))
                .annotations(ToolHints.write(true, false))
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
        Map<String, Object> pkg = PackageArgument.parse(args.get(PackageArgument.NAME));
        if (pkg == null) {
            return McpErrorMapper.badRequest(
                    "Argument \"package\" is required and must be a package JSON object.", List.of());
        }
        String conflictMode = "skip";
        if (args.get("conflictMode") instanceof String mode && !mode.isBlank()) {
            if (!mode.equals("skip") && !mode.equals("overwrite")) {
                return McpErrorMapper.badRequest(
                        "Argument \"conflictMode\" must be skip or overwrite.", List.of());
            }
            conflictMode = mode;
        }
        StringBuilder path = new StringBuilder("/api/packages/import?");
        if (Boolean.TRUE.equals(args.get("dryRun"))) path.append("dryRun=true&");
        path.append("conflictMode=").append(conflictMode);
        try {
            return McpErrorMapper.toResult(gateway.post(path.toString(), pkg));
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }
    }
}
