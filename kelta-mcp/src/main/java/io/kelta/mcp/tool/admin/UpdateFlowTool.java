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
public class UpdateFlowTool implements AdminTool {

    private final GatewayHttpClient gateway;

    public UpdateFlowTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", Schemas.string("Flow id (UUID)."));
        properties.put("name", Schemas.string("New flow name."));
        properties.put("description", Schemas.string("New description."));
        properties.put("triggerConfig", Schemas.freeObject("New trigger config."));
        properties.put("definition", Schemas.freeObject("New flow definition."));
        properties.put("active", Schemas.bool("Active flag — toggles enable/disable.", false));
        properties.put("runAsUserId", Schemas.string(
                "User UUID stamped as createdBy/updatedBy on records this flow writes when no user "
                + "started the run (cron/NATS/webhook). Empty string clears it (falls back to owner)."));
        properties.put("stepLogging", Schemas.string(
                "Step-log level: FULL (default; every step with input/output snapshots), ERRORS_ONLY "
                + "(every step, snapshots only for a failed step — recommended for high-frequency or "
                + "high-volume flows) or NONE (only a failed step's row)."));
        properties.put("retentionDays", Schemas.integer(
                "Days a finished run and its step logs are kept before the retention sweep deletes "
                + "them (1-3650). 0 clears the override (the platform-wide window applies again).", 0, 3650));

        Tool tool = Tool.builder()
                .name("update_flow")
                .title("Update Flow")
                .description("Update an existing flow's metadata, definition, or active state. Wraps PATCH /api/flows/{id}. Toggling \"active\" enables or disables the trigger without redeploying the definition.")
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
                    if (args.get("description") instanceof String s) attrs.put("description", s);
                    if (args.get("triggerConfig") instanceof Map<?, ?> tc) attrs.put("triggerConfig", tc);
                    if (args.get("definition") instanceof Map<?, ?> d) attrs.put("definition", d);
                    if (args.get("active") instanceof Boolean b) attrs.put("active", b);
                    if (args.get("runAsUserId") instanceof String r) attrs.put("runAsUserId", r.isBlank() ? null : r);
                    if (args.get("stepLogging") instanceof String l && !l.isBlank()) attrs.put("stepLogging", l);
                    if (args.get("retentionDays") instanceof Number days) {
                        attrs.put("retentionDays", days.doubleValue() == 0 ? null : days);
                    }
                    if (attrs.isEmpty()) {
                        return error("Provide at least one of name, description, triggerConfig, definition, active, runAsUserId, stepLogging, retentionDays.");
                    }

                    Map<String, Object> body = Map.of("data", Map.of(
                            "type", "flows",
                            "id", id.toString(),
                            "attributes", attrs));
                    String path = "/api/flows/" + URLEncoder.encode(id.toString(), StandardCharsets.UTF_8);
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
