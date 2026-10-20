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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AddFieldTool implements AdminTool {

    private final GatewayHttpClient gateway;
    private final FieldBodyBuilder bodyBuilder;

    public AddFieldTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
        this.bodyBuilder = new FieldBodyBuilder(gateway);
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("collectionName", Schemas.string(
                "Collection the field belongs to. Resolved to collectionId via lookup unless collectionId is provided."));
        properties.put("collectionId", Schemas.string(
                "Optional UUID of the collection — bypasses the name lookup."));
        properties.put("fieldName", Schemas.string("Field name (camelCase recommended)."));
        properties.put("displayName", Schemas.string("Optional human-readable label shown in the admin UI."));
        properties.put("type", Schemas.string(
                "Field type alias. Mapped to the native uppercase enum: "
                + "text|string→STRING, longText→STRING, number|integer→INTEGER, decimal|double→DOUBLE, "
                + "long→LONG, boolean→BOOLEAN, date→DATE, datetime→DATETIME, "
                + "picklist→PICKLIST, multiPicklist→MULTI_PICKLIST, "
                + "reference|lookup→LOOKUP, masterDetail→MASTER_DETAIL, json→JSON, "
                + "currency→CURRENCY, percent→PERCENT, url→URL, email→EMAIL, phone→PHONE, "
                + "autoNumber→AUTO_NUMBER, externalId→EXTERNAL_ID, encrypted→ENCRYPTED, "
                + "geolocation→GEOLOCATION, array→ARRAY, richText→RICH_TEXT, vector→VECTOR. "
                + "Every uppercase FieldType enum name is also accepted verbatim."));
        properties.put("required", Schemas.bool("Whether the field is required (default false).", false));
        properties.put("unique", Schemas.bool("Whether values must be unique (default false). Sent as uniqueConstraint.", false));
        properties.put("indexed", Schemas.bool("Whether the column has a database index (default false).", false));
        properties.put("searchable", Schemas.bool("Whether the field is indexed for full-text search (default false).", false));
        properties.put("description", Schemas.string("Optional description shown in the admin UI."));
        properties.put("defaultValue", Schemas.anyScalar(
                "Optional default value. Pass it in the field's native JSON type — e.g. true for booleans, 360 for numbers, \"hello\" for strings. The platform coerces and stores it according to the field type."));
        properties.put("validation", Schemas.freeObject(
                "Optional validation config (regex, min/max, etc.) — shape depends on the field type."));
        properties.put("referenceCollectionId", Schemas.string(
                "UUID of the target collection for reference/lookup fields."));
        properties.put("referenceCollection", Schemas.string(
                "(Legacy) Target collection name for reference/lookup fields. Resolved via name lookup."));
        properties.put("relationshipName", Schemas.string(
                "Human-readable relationship name for reference/lookup fields."));
        properties.put("relationshipType", Schemas.string(
                "Relationship semantics for reference/lookup fields: \"LOOKUP\" (default) or \"MASTER_DETAIL\"."));
        properties.put("picklistSourceId", Schemas.string(
                "UUID of the global picklist for picklist/multiPicklist fields. Omit with picklistSourceType FIELD."));
        properties.put("picklistSourceType", Schemas.string(
                "Source of picklist values: \"GLOBAL\" (default, needs picklistSourceId) or \"FIELD\" "
                + "(values owned by this field — add them afterwards with apply_field_picklist)."));
        properties.put("maskingType", Schemas.string(
                "Optional data masking for string-typed fields: FULL, LAST4, EMAIL, or CUSTOM (NONE clears). "
                + "Users whose profile lacks unmask rights see the value redacted."));
        properties.put("maskingChar", Schemas.string(
                "Optional single character used for masked positions (default •)."));
        properties.put("maskingCustomPattern", Schemas.string(
                "Pattern for maskingType=CUSTOM: '#' reveals a character, any other char is shown literally, right-aligned."));

        Tool tool = Tool.builder()
                .name("add_field")
                .title("Add Field")
                .description("Add a field to an existing collection. Wraps POST /api/fields with a JSON:API body, translating friendly type aliases (text, number, picklist, …) into the native uppercase enum and assembling per-type payload (fieldTypeConfig for picklists, referenceCollectionId relationship + relationshipName for lookups).")
                .inputSchema(Schemas.object(properties, List.of("fieldName", "type")))
                .annotations(ToolHints.write(false, false))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();

                    FieldBodyBuilder.Result result = bodyBuilder.build(args);
                    if (result.isError()) {
                        return error(result.errorMessage());
                    }
                    try {
                        return McpErrorMapper.toResult(gateway.post("/api/fields", result.body()));
                    } catch (RuntimeException e) {
                        return McpErrorMapper.fromException(e);
                    }
                })
                .build();
    }

    /**
     * Rewrites the camelCase {@code richText} alias to canonical {@code rich_text}
     * so the worker's FieldLifecycleHook (which accepts snake_case lowercase or
     * uppercase enum forms) doesn't reject it. Other inputs pass through.
     */
    private static String normalizeType(String raw) {
        return "richText".equals(raw) ? "rich_text" : raw;
    }

    private static Integer readDimension(Object raw) {
        return switch (raw) {
            case Integer i -> i;
            case Number n -> n.intValue();
            case String s when !s.isBlank() -> {
                try {
                    yield Integer.parseInt(s.trim());
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case null, default -> null;
        };
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
