package io.kelta.mcp.tool.user;

import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.error.McpErrorMapper;
import io.kelta.mcp.tool.Schemas;
import io.kelta.mcp.tool.ToolHints;
import io.kelta.mcp.tool.UserTool;
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
public class GetFlowRunTool implements UserTool {

    private final GatewayHttpClient gateway;

    public GetFlowRunTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("executionId", Schemas.string("Flow execution id returned by execute_flow."));
        properties.put("includeSteps", Schemas.bool(
                "If true, also fetch step-level execution logs and merge them into the response.", false));

        Tool tool = Tool.builder()
                .name("get_flow_run")
                .title("Get Flow Run")
                .description("Fetch the status of a flow execution. Wraps GET /api/flows/executions/{executionId}, with optional step-by-step logs from /steps merged in. Use to poll a long-running flow. Without MANAGE_WORKFLOWS or VIEW_SETUP you can read only a run you started, as status fields only, and steps come back null.")
                .inputSchema(Schemas.object(properties, List.of("executionId")))
                .annotations(ToolHints.read())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();
                    Object id = args.get("executionId");
                    if (id == null || id.toString().isBlank()) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Argument \"executionId\" is required.")))
                                .build();
                    }
                    boolean includeSteps = readBool(args, "includeSteps", false);
                    String encoded = URLEncoder.encode(id.toString(), StandardCharsets.UTF_8);
                    try {
                        GatewayHttpClient.Response runRes = gateway.get(
                                "/api/flows/executions/" + encoded);
                        if (!runRes.isSuccess()) {
                            return McpErrorMapper.toResult(runRes);
                        }
                        if (!includeSteps) {
                            return McpErrorMapper.toResult(runRes);
                        }
                        GatewayHttpClient.Response stepsRes = gateway.get(
                                "/api/flows/executions/" + encoded + "/steps");
                        String body = "{\n  \"execution\": " + runRes.body()
                                + ",\n  \"steps\": "
                                + (stepsRes.isSuccess() ? stepsRes.body() : "null")
                                + "\n}";
                        return CallToolResult.builder()
                                .content(List.of(new TextContent(body)))
                                .build();
                    } catch (RuntimeException e) {
                        return McpErrorMapper.fromException(e);
                    }
                })
                .build();
    }

    private static boolean readBool(Map<String, Object> args, String key, boolean defaultValue) {
        Object v = args.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }
}
