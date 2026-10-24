package io.kelta.mcp.tool.admin;

import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.error.McpErrorMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Shared call path of {@link ValidateDashboardTool} and {@link DashboardDataTool}, mirroring
 * {@code kelta dashboards validate|data}: resolve the dashboard name (or pass an id through),
 * then {@code POST /api/dashboards/{id}/<action>} with the caller's body ({@code {}} when none)
 * and return the worker's response as-is.
 */
final class DashboardEndpoint {

    private DashboardEndpoint() {}

    @SuppressWarnings("unchecked")
    static CallToolResult call(GatewayHttpClient gateway, Map<String, Object> args, String action) {
        if (!(args.get("dashboard") instanceof String dashboard) || dashboard.isBlank()) {
            return McpErrorMapper.badRequest("Argument \"dashboard\" (name or id) is required.", List.of());
        }
        Object rawBody = args.get("body");
        if (rawBody != null && !(rawBody instanceof Map<?, ?>)) {
            return McpErrorMapper.badRequest("Argument \"body\" must be a JSON object.", List.of());
        }
        Map<String, Object> body = rawBody == null ? Map.of() : (Map<String, Object>) rawBody;
        try {
            String id = new AdminLookups(gateway).dashboardIdByNameOrId(dashboard);
            if (id == null) {
                return CallToolResult.builder()
                        .isError(true)
                        .content(List.of(new TextContent("Dashboard \"" + dashboard + "\" not found.")))
                        .build();
            }
            String path = "/api/dashboards/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/" + action;
            return McpErrorMapper.toResult(gateway.post(path, body));
        } catch (AdminLookups.GatewayFailure e) {
            return McpErrorMapper.toResult(e.response);
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }
    }
}
