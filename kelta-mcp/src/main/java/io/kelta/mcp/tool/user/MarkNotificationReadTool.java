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
public class MarkNotificationReadTool implements UserTool {

    private final GatewayHttpClient gateway;

    public MarkNotificationReadTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", Schemas.string("Id of one of your notifications (from list_notifications)."));

        Tool tool = Tool.builder()
                .name("mark_notification_read")
                .title("Mark Notification Read")
                .description("Mark one of your in-app notifications read. Wraps POST /api/me/notifications/{id}/read; returns the notification and meta.unreadCount. Repeating the call keeps the first read time.")
                .inputSchema(Schemas.object(properties, List.of("id")))
                .annotations(ToolHints.write(false, true))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();
                    Object id = args.get("id");
                    if (id == null || id.toString().isBlank()) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Argument \"id\" is required.")))
                                .build();
                    }
                    String path = "/api/me/notifications/"
                            + URLEncoder.encode(id.toString(), StandardCharsets.UTF_8) + "/read";
                    try {
                        return McpErrorMapper.toResult(gateway.post(path, Map.of()));
                    } catch (RuntimeException e) {
                        return McpErrorMapper.fromException(e);
                    }
                })
                .build();
    }
}
