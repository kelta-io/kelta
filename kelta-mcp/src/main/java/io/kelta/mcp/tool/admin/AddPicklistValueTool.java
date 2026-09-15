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
public class AddPicklistValueTool implements AdminTool {

    private final GatewayHttpClient gateway;

    public AddPicklistValueTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("picklist", Schemas.string(
                "Picklist name or id (UUID). Names are resolved via "
                        + "GET /api/global-picklists?filter[name][EQ]=... before the value is created."));
        properties.put("value", Schemas.string("Stored value (e.g. \"OPEN\")."));
        properties.put("label", Schemas.string("Display label (e.g. \"Open\")."));
        properties.put("color", Schemas.string("Hex color used when the value is rendered as a badge, e.g. \"#22C55E\"."));
        properties.put("sortOrder", Schemas.integer("Display order. Defaults to 0 if omitted.", null, null));
        properties.put("isActive", Schemas.bool("Whether the value is active. Defaults to true.", true));
        properties.put("isDefault", Schemas.bool("Whether the value is the picklist default. Defaults to false.", false));

        Tool tool = Tool.builder()
                .name("add_picklist_value")
                .title("Add Picklist Value")
                .description("Append a value to an existing global picklist. Wraps POST /api/picklist-values with picklistSourceType=GLOBAL and picklistSourceId set to the picklist's UUID. Accepts the picklist by name or UUID; names are resolved first.")
                .inputSchema(Schemas.object(properties, List.of("picklist", "value", "label")))
                .annotations(ToolHints.write(false, false))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();
                    Object pv = args.get("picklist");
                    Object v = args.get("value");
                    Object l = args.get("label");
                    if (pv == null || pv.toString().isBlank()) {
                        return error("Argument \"picklist\" is required.");
                    }
                    if (v == null || v.toString().isBlank()) {
                        return error("Argument \"value\" is required.");
                    }
                    if (l == null || l.toString().isBlank()) {
                        return error("Argument \"label\" is required.");
                    }
                    String input = pv.toString();
                    try {
                        String id;
                        if (GetPicklistTool.UUID_PATTERN.matcher(input).matches()) {
                            id = input;
                        } else {
                            GatewayHttpClient.Response lookup = gateway.get(
                                    "/api/global-picklists?filter[name][EQ]="
                                            + URLEncoder.encode(input, StandardCharsets.UTF_8)
                                            + "&page[size]=1");
                            if (!lookup.isSuccess()) {
                                return McpErrorMapper.toResult(lookup);
                            }
                            id = DeleteCollectionTool.extractFirstId(lookup.body());
                            if (id == null) {
                                return error("No picklist found with name \"" + input + "\".");
                            }
                        }

                        Map<String, Object> attrs = new LinkedHashMap<>();
                        attrs.put("value", v.toString());
                        attrs.put("label", l.toString());
                        attrs.put("picklistSourceType", "GLOBAL");
                        attrs.put("picklistSourceId", id);
                        attrs.put("globalPicklistId", id);
                        attrs.put("sortOrder", args.get("sortOrder") instanceof Number n ? n.intValue() : 0);
                        attrs.put("isActive", !(args.get("isActive") instanceof Boolean b) || b);
                        attrs.put("isDefault", args.get("isDefault") instanceof Boolean b && b);
                        if (args.get("color") instanceof String c && !c.isBlank()) attrs.put("color", c);

                        Map<String, Object> body = Map.of("data", Map.of(
                                "type", "picklist-values",
                                "attributes", attrs));
                        return McpErrorMapper.toResult(gateway.post("/api/picklist-values", body));
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
