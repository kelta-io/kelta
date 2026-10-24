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

/** Hosted counterpart of {@code kelta dashboards validate} — {@code POST /api/dashboards/{id}/validate}. */
@Component
public class ValidateDashboardTool implements AdminTool {

    private final GatewayHttpClient gateway;

    public ValidateDashboardTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("dashboard", Schemas.string("Dashboard name or id."));
        properties.put("body", Schemas.freeObject(
                "Optional request body, e.g. {\"components\":[...]} to validate unsaved widgets. "
                        + "Omit to validate the dashboard's saved components."));

        Tool tool = Tool.builder()
                .name("validate_dashboard")
                .title("Validate Dashboard")
                .description(
                        "Check a dashboard's widget configs (unknown collection/field/operator, "
                        + "non-aggregatable field, grid position outside columnCount) without writing "
                        + "anything; returns {\"valid\":...,\"components\":[{\"errors\":[...]}]} as the "
                        + "worker reports it. Example: {\"dashboard\":\"Sales Overview\"}. Wraps POST "
                        + "/api/dashboards/{id}/validate (the name is resolved to the id first).")
                .inputSchema(Schemas.object(properties, List.of("dashboard")))
                .annotations(ToolHints.read())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    return DashboardEndpoint.call(gateway, args == null ? Map.of() : args, "validate");
                })
                .build();
    }
}
