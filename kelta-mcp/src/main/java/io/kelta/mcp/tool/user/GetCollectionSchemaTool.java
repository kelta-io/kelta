package io.kelta.mcp.tool.user;

import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.error.McpErrorMapper;
import io.kelta.mcp.tool.AdminTool;
import io.kelta.mcp.tool.Schemas;
import io.kelta.mcp.tool.ToolHints;
import io.kelta.mcp.tool.UserTool;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GetCollectionSchemaTool implements UserTool, AdminTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GatewayHttpClient gateway;

    public GetCollectionSchemaTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("collection", Schemas.string(
                "Collection name or ID (e.g. \"accounts\" or a UUID). Looked up via /api/collections."));
        properties.put("includeRules", Schemas.bool(
                "Also fetch and embed the collection's validation rules (default true).", true));

        Tool tool = Tool.builder()
                .name("get_collection_schema")
                .title("Get Collection Schema")
                .description("Return the full schema for a collection: metadata + all field definitions "
                        + "(type, required, enum values, references) plus its validation rules by default. "
                        + "Wraps GET /api/collections/{name}/schema, and — when includeRules is true — "
                        + "GET /api/validation-rules for that collection. Use this before constructing a "
                        + "query or create_record call to know what attributes are available. "
                        + "Example: {\"collection\": \"accounts\"} returns accounts' fields with types/enums "
                        + "and its active validation rules.")
                .inputSchema(Schemas.object(properties, List.of("collection")))
                .annotations(ToolHints.read())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    Object cv = args == null ? null : args.get("collection");
                    if (cv == null || cv.toString().isBlank()) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Argument \"collection\" is required.")))
                                .build();
                    }
                    String collection = cv.toString();
                    boolean includeRules = readBool(args, "includeRules", true);
                    String encoded = URLEncoder.encode(collection, StandardCharsets.UTF_8);

                    try {
                        GatewayHttpClient.Response schemaRes = gateway.get(
                                "/api/collections/" + encoded + "/schema");
                        if (!schemaRes.isSuccess()) {
                            return McpErrorMapper.toResult(schemaRes);
                        }

                        Map<String, Object> schema = new LinkedHashMap<>(schemaRes.jsonAsMap(OBJECT_MAPPER));
                        if (includeRules) {
                            schema.put("validationRules", fetchValidationRules(encoded));
                        }

                        return CallToolResult.builder()
                                .content(List.of(new TextContent(OBJECT_MAPPER.writeValueAsString(schema))))
                                .build();
                    } catch (RuntimeException e) {
                        return McpErrorMapper.fromException(e);
                    }
                })
                .build();
    }

    /**
     * Validation rules are keyed by the collection's UUID, not its name — a
     * separate lookup resolves the id before the rules can be filtered.
     * Returns null (rather than throwing) when either call fails, so a
     * missing/unreadable rule set doesn't blank out the rest of the schema.
     */
    private Object fetchValidationRules(String encodedCollection) {
        GatewayHttpClient.Response collectionRes = gateway.get("/api/collections/" + encodedCollection);
        String collectionId = collectionRes.isSuccess() ? extractCollectionId(collectionRes.body()) : null;
        if (collectionId == null) {
            return null;
        }
        GatewayHttpClient.Response rulesRes = gateway.get(
                "/api/validation-rules?filter[collectionId][EQ]="
                        + URLEncoder.encode(collectionId, StandardCharsets.UTF_8)
                        + "&page[size]=200");
        if (!rulesRes.isSuccess()) {
            return null;
        }
        return rulesRes.jsonAsMap(OBJECT_MAPPER).get("data");
    }

    private static boolean readBool(Map<String, Object> args, String key, boolean defaultValue) {
        if (args == null) return defaultValue;
        Object v = args.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    /**
     * Pull {@code data.id} out of a JSON:API single-resource response body.
     * Returns null on any parse failure or if the id is missing — callers
     * fall back to skipping the fields fetch in that case.
     */
    static String extractCollectionId(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            JsonNode id = root.path("data").path("id");
            return id.isMissingNode() || id.isNull() ? null : id.asString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
