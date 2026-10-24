package io.kelta.mcp.tool.admin;

import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.tool.AdminTool;
import io.kelta.mcp.tool.Schemas;
import io.kelta.mcp.tool.ToolHints;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hosted counterpart of {@code kelta dashboards data} — {@code POST /api/dashboards/{id}/data}. */
@Component
public class DashboardDataTool implements AdminTool {

    private final GatewayHttpClient gateway;

    public DashboardDataTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("dashboard", Schemas.string("Dashboard name or id."));
        properties.put("body", Schemas.freeObject(
                "Optional runtime parameters, e.g. {\"timeRange\":\"LAST_30_DAYS\"}."));

        Tool tool = Tool.builder()
                .name("dashboard_data")
                .title("Dashboard Data")
                .description(
                        "Execute a dashboard's widgets and return their data as the worker computes it "
                        + "(read-only). Example: {\"dashboard\":\"Sales Overview\",\"body\":{\"timeRange\":"
                        + "\"LAST_30_DAYS\"}}. Wraps POST /api/dashboards/{id}/data (the name is resolved "
                        + "to the id first).")
                .inputSchema(Schemas.object(properties, List.of("dashboard")))
                .annotations(ToolHints.read())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    return DashboardEndpoint.call(gateway, args == null ? Map.of() : args, "data");
                })
                .build();
    }
}
