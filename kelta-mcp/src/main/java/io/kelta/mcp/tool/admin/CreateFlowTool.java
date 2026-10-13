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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CreateFlowTool implements AdminTool {

    private final GatewayHttpClient gateway;

    public CreateFlowTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Schemas.string("Flow name (unique within tenant)."));
        properties.put("description", Schemas.string("Optional description."));
        properties.put("flowType", Schemas.string(
                "Flow type: RECORD_TRIGGERED, SCHEDULED, AUTOLAUNCHED, or SCREEN. "
                        + "Legacy alias \"triggerType\" is also accepted."));
        properties.put("triggerConfig", Schemas.freeObject(
                "Trigger-specific config (e.g. {\"collectionName\":\"orders\"} for RECORD_TRIGGERED, "
                        + "{\"cron\":\"0 * * * *\"} for SCHEDULED)."));
        properties.put("definition", Schemas.freeObject(
                "Flow JSON definition: nodes, edges, conditions, actions. Shape is flow-engine specific."));
        properties.put("active", Schemas.bool("Whether the flow is active on creation (default false).", false));
        properties.put("runAsUserId", Schemas.string(
                "User UUID stamped as createdBy/updatedBy on records this flow writes when no user "
                + "started the run (cron/NATS/webhook). Defaults to the flow owner."));
        properties.put("stepLogging", Schemas.string(
                "Step-log level: FULL (default; every step with input/output snapshots), ERRORS_ONLY "
                + "(every step, snapshots only for a failed step — recommended for high-frequency or "
                + "high-volume flows) or NONE (only a failed step's row)."));
        properties.put("retentionDays", Schemas.integer(
                "Days a finished run and its step logs are kept before the retention sweep deletes "
                + "them (1-3650). Omit to use the platform-wide window (60 days by default).", 1, 3650));

        Tool tool = Tool.builder()
                .name("create_flow")
                .title("Create Flow")
                .description("Create a new automation flow. Wraps POST /api/flows. The definition payload is opaque to this tool — pass the flow-engine JSON directly.")
                .inputSchema(Schemas.object(properties, List.of("name", "flowType", "definition")))
                .annotations(ToolHints.write(false, false))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();
                    Object name = args.get("name");
                    Object flowType = args.get("flowType");
                    if (flowType == null || flowType.toString().isBlank()) {
                        flowType = args.get("triggerType");
                    }
                    Object def = args.get("definition");
                    if (name == null || name.toString().isBlank()
                            || flowType == null || flowType.toString().isBlank()
                            || !(def instanceof Map<?, ?> definition) || definition.isEmpty()) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent(
                                        "Arguments \"name\", \"flowType\", and a non-empty \"definition\" are required.")))
                                .build();
                    }

                    Map<String, Object> attrs = new LinkedHashMap<>();
                    attrs.put("name", name.toString());
                    attrs.put("flowType", flowType.toString());
                    attrs.put("definition", definition);
                    if (args.get("description") instanceof String s && !s.isBlank()) attrs.put("description", s);
                    if (args.get("triggerConfig") instanceof Map<?, ?> tc) attrs.put("triggerConfig", tc);
                    if (args.get("active") instanceof Boolean b) attrs.put("active", b);
                    else attrs.put("active", false);
                    if (args.get("runAsUserId") instanceof String r && !r.isBlank()) attrs.put("runAsUserId", r);
                    if (args.get("stepLogging") instanceof String l && !l.isBlank()) attrs.put("stepLogging", l);
                    if (args.get("retentionDays") instanceof Number days) attrs.put("retentionDays", days);

                    Map<String, Object> body = Map.of("data", Map.of(
                            "type", "flows",
                            "attributes", attrs));
                    try {
                        return McpErrorMapper.toResult(gateway.post("/api/flows", body));
                    } catch (RuntimeException e) {
                        return McpErrorMapper.fromException(e);
                    }
                })
                .build();
    }
}
