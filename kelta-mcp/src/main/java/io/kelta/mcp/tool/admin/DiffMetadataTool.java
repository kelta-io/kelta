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
 * Hosted counterpart of {@code kelta metadata diff}: previews what a package would change through
 * {@code POST /api/packages/import/preview}, which writes nothing.
 */
@Component
public class DiffMetadataTool implements AdminTool {

    private final GatewayHttpClient gateway;

    public DiffMetadataTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PackageArgument.NAME, PackageArgument.schema());

        Tool tool = Tool.builder()
                .name("diff_metadata")
                .title("Diff Metadata Package")
                .description(
                        "Preview the changes a metadata package would make to this tenant, without writing "
                        + "anything (the same preview `kelta metadata diff` prints). Pass the package JSON "
                        + "export_metadata returned. Wraps POST /api/packages/import/preview.")
                .inputSchema(Schemas.object(properties, List.of(PackageArgument.NAME)))
                .annotations(ToolHints.read())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    return diff(args == null ? Map.of() : args);
                })
                .build();
    }

    CallToolResult diff(Map<String, Object> args) {
        Map<String, Object> pkg = PackageArgument.parse(args.get(PackageArgument.NAME));
        if (pkg == null) {
            return McpErrorMapper.badRequest(
                    "Argument \"package\" is required and must be a package JSON object.", List.of());
        }
        try {
            return McpErrorMapper.toResult(gateway.post("/api/packages/import/preview", pkg));
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }
    }
}
