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
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class UpdateCollectionTool implements AdminTool {

    private final GatewayHttpClient gateway;
    private final AdminLookups lookups;

    public UpdateCollectionTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
        this.lookups = new AdminLookups(gateway);
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", Schemas.string("Collection id (UUID) or name."));
        properties.put("displayName", Schemas.string(
                "New human-readable label for the collection itself (e.g. \"Projects\"). Distinct from displayFieldName."));
        properties.put("displayFieldName", Schemas.string(
                "New name of an existing field on this collection used as the per-record display label. Distinct from displayName."));
        properties.put("description", Schemas.string("New description."));
        properties.put("tenantScoped", Schemas.bool("New tenant-scoped flag.", true));

        Tool tool = Tool.builder()
                .name("update_collection")
                .title("Update Collection")
                .description("Update a collection's metadata (display name, display field, description, tenant-scoping). PATCHes /api/collections/{id} — only supplied fields change.")
                .inputSchema(Schemas.object(properties, List.of("id")))
                .annotations(ToolHints.write(true, true))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();
                    if (!(args.get("id") instanceof String idArg) || idArg.isBlank()) {
                        return error("Argument \"id\" is required and must be a string.");
                    }
                    String id = idArg;
                    if (!FieldBodyBuilder.UUID_PATTERN.matcher(id).matches()) {
                        String resolved = lookups.collectionIdByName(id);
                        if (resolved == null) {
                            return error("Collection \"" + id + "\" not found.");
                        }
                        id = resolved;
                    }

                    Map<String, Object> attrs = new LinkedHashMap<>();
                    if (args.get("displayName") instanceof String s && !s.isBlank()) attrs.put("displayName", s);
                    if (args.get("displayFieldName") instanceof String s && !s.isBlank()) {
                        // The worker stores displayFieldId (a field UUID) — a
                        // displayFieldName attribute would be silently ignored.
                        String fieldId = lookups.fieldIdsByName(id).get(s);
                        if (fieldId == null) {
                            return error("Field \"" + s + "\" not found on the collection.");
                        }
                        attrs.put("displayFieldId", fieldId);
                    }
                    if (args.get("description") instanceof String s) attrs.put("description", s);
                    if (args.get("tenantScoped") instanceof Boolean b) attrs.put("tenantScoped", b);
                    if (attrs.isEmpty()) {
                        return error("Provide at least one of displayName, displayFieldName, description, tenantScoped.");
                    }

                    Map<String, Object> body = Map.of("data", Map.of(
                            "type", "collections",
                            "id", id,
                            "attributes", attrs));
                    String path = "/api/collections/" + UriUtils.encodePathSegment(id, StandardCharsets.UTF_8);
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
