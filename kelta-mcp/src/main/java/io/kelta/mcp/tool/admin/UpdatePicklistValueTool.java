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
public class UpdatePicklistValueTool implements AdminTool {

    private final GatewayHttpClient gateway;

    public UpdatePicklistValueTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", Schemas.string("Picklist value id (UUID)."));
        properties.put("value", Schemas.string("New stored value."));
        properties.put("label", Schemas.string("New display label."));
        properties.put("color", Schemas.string("New hex color used when the value is rendered as a badge, e.g. \"#22C55E\"."));
        properties.put("sortOrder", Schemas.integer("New display order.", null, null));
        properties.put("isActive", Schemas.bool("New active flag.", true));
        properties.put("isDefault", Schemas.bool("New default flag.", false));

        Tool tool = Tool.builder()
                .name("update_picklist_value")
                .title("Update Picklist Value")
                .description("Update a picklist value's mutable attributes. Wraps PATCH /api/picklist-values/{id}. Only the supplied fields are sent — to deactivate a value, prefer deactivate_picklist_value.")
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
                    if (args.get("value") instanceof String s && !s.isBlank()) attrs.put("value", s);
                    if (args.get("label") instanceof String s && !s.isBlank()) attrs.put("label", s);
                    if (args.get("color") instanceof String s && !s.isBlank()) attrs.put("color", s);
                    if (args.get("sortOrder") instanceof Number n) attrs.put("sortOrder", n.intValue());
                    if (args.get("isActive") instanceof Boolean b) attrs.put("isActive", b);
                    if (args.get("isDefault") instanceof Boolean b) attrs.put("isDefault", b);
                    if (attrs.isEmpty()) {
                        return error("Provide at least one of value, label, color, sortOrder, isActive, isDefault.");
                    }

                    Map<String, Object> body = Map.of("data", Map.of(
                            "type", "picklist-values",
                            "id", id.toString(),
                            "attributes", attrs));
                    String path = "/api/picklist-values/"
                            + URLEncoder.encode(id.toString(), StandardCharsets.UTF_8);
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
