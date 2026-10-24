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
public class ListNotificationsTool implements UserTool {

    private final GatewayHttpClient gateway;

    public ListNotificationsTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("unreadOnly", Schemas.bool("Return only unread notifications.", false));
        properties.put("pageSize", Schemas.integer("Records per page (default 20).", 1, 200));
        properties.put("pageNumber", Schemas.integer("Page number, 1-based (default 1).", 1, null));

        Tool tool = Tool.builder()
                .name("list_notifications")
                .title("List Notifications")
                .description("List your own in-app notifications, newest first. Wraps GET /api/me/notifications; meta.unreadCount carries your unread total. Use mark_notification_read to mark one read.")
                .inputSchema(Schemas.object(properties, List.of()))
                .annotations(ToolHints.read())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();

                    List<String> parts = new ArrayList<>();
                    Object unreadOnly = args.get("unreadOnly");
                    if (Boolean.TRUE.equals(unreadOnly) || "true".equalsIgnoreCase(String.valueOf(unreadOnly))) {
                        parts.add("unread=true");
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

                    String path = "/api/me/notifications"
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
