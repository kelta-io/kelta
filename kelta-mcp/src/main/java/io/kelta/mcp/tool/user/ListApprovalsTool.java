package io.kelta.mcp.tool.user;

import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.error.McpErrorMapper;
import io.kelta.mcp.tool.Schemas;
import io.kelta.mcp.tool.ToolHints;
import io.kelta.mcp.tool.UserTool;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ListApprovalsTool implements UserTool {

    private final GatewayHttpClient gateway;

    public ListApprovalsTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", Schemas.string(
                "Filter by status: \"pending\", \"approved\", \"rejected\", or \"all\" (default: pending)."));
        properties.put("pageSize", Schemas.integer("Records per page (default 20).", 1, 200));
        properties.put("pageNumber", Schemas.integer("Page number, 1-based (default 1).", 1, null));

        Tool tool = Tool.builder()
                .name("list_approvals")
                .title("List Approvals")
                .description("List approval instances, optionally filtered by status. Wraps the approvalInstances system collection. Useful to find what's waiting on you.")
                .inputSchema(Schemas.object(properties, List.of()))
                .annotations(ToolHints.read())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();
                    String status = args.get("status") instanceof String s ? s : "pending";

                    List<String> parts = new ArrayList<>();
                    if (!"all".equalsIgnoreCase(status) && !status.isBlank()) {
                        parts.add("filter[status][EQ]=" + URLEncoder.encode(
                                status.toUpperCase(java.util.Locale.ROOT), StandardCharsets.UTF_8));
                    }
                    Object pageSize = args.get("pageSize");
                    if (pageSize != null) {
                        parts.add("page[size]=" + URLEncoder.encode(
                                pageSize.toString(), StandardCharsets.UTF_8));
                    }
                    Object pageNumber = args.get("pageNumber");
                    if (pageNumber != null) {
                        parts.add("page[number]=" + URLEncoder.encode(
                                pageNumber.toString(), StandardCharsets.UTF_8));
                    }

                    String path = "/api/approval-instances"
                            + (parts.isEmpty() ? "" : "?" + String.join("&", parts));
                    try {
                        return McpErrorMapper.toResult(gateway.get(path));
                    } catch (RuntimeException e) {
                        return McpErrorMapper.fromException(e);
                    }
                })
                .build();
    }
}
