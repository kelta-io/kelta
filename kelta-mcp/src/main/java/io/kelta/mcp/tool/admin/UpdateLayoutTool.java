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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class UpdateLayoutTool implements AdminTool {

    private final GatewayHttpClient gateway;

    public UpdateLayoutTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", Schemas.string("Layout id (UUID)."));
        properties.put("name", Schemas.string("New name."));
        properties.put("isDefault", Schemas.bool("New default-for-collection flag.", false));
        properties.put("recordTypeName", Schemas.string("New record type assignment."));

        Tool tool = Tool.builder()
                .name("update_layout")
                .title("Update Layout")
                .description("Update a page layout's metadata (name, default flag, record type). PATCHes /api/page-layouts/{id}. To restructure sections or fields, use delete_layout + create_layout — full section replacement isn't supported in a single tool call.")
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
                    if (args.get("isDefault") instanceof Boolean b) attrs.put("isDefault", b);
                    if (args.get("recordTypeName") instanceof String s) attrs.put("recordTypeName", s);
                    if (attrs.isEmpty()) {
                        return error("Provide at least one of name, isDefault, recordTypeName.");
                    }
                    Map<String, Object> body = Map.of("data", Map.of(
                            "type", "page-layouts",
                            "id", id.toString(),
                            "attributes", attrs));
                    String path = "/api/page-layouts/" + URLEncoder.encode(id.toString(), StandardCharsets.UTF_8);
                    try {
                        return McpErrorMapper.toResult(gateway.patch(path, body));
                    } catch (RuntimeException e) {
                        return McpErrorMapper.fromException(e);
                    }
                })
                .build();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
