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
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Idempotent create-or-update of a <em>field-scoped</em> picklist: the values
 * owned by one picklist field ({@code picklistSourceType=FIELD},
 * {@code picklistSourceId}=the field's id) rather than a global picklist.
 *
 * <p>Field-scoped values travel with their field through metadata export and
 * apply (import remaps them by collection and field name), which global
 * picklist bindings do not. Same value semantics as {@link ApplyPicklistTool}
 * via {@link PicklistValueApplier}.
 */
@Component
public class ApplyFieldPicklistTool implements AdminTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> PICKLIST_TYPES = Set.of("PICKLIST", "MULTI_PICKLIST");

    private final AdminLookups lookups;
    private final PicklistValueApplier valueApplier;

    public ApplyFieldPicklistTool(GatewayHttpClient gateway) {
        this.lookups = new AdminLookups(gateway);
        this.valueApplier = new PicklistValueApplier(gateway, lookups);
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> valueItem = new LinkedHashMap<>();
        valueItem.put("type", "object");
        Map<String, Object> valueProps = new LinkedHashMap<>();
        valueProps.put("value", Schemas.string("Stored value (e.g. \"OPEN\")."));
        valueProps.put("label", Schemas.string("Display label (e.g. \"Open\")."));
        valueProps.put("color", Schemas.string("Hex color used when rendered as a badge, e.g. \"#22C55E\"."));
        valueProps.put("description", Schemas.string("Optional description."));
        valueItem.put("properties", valueProps);
        valueItem.put("additionalProperties", false);

        Map<String, Object> valuesArr = new LinkedHashMap<>();
        valuesArr.put("type", "array");
        valuesArr.put("description",
                "Values in display order — sortOrder is derived from each value's position in this list.");
        valuesArr.put("items", valueItem);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("collectionName", Schemas.string("Collection that owns the picklist field."));
        properties.put("collectionId", Schemas.string("Optional UUID of the collection — bypasses the name lookup."));
        properties.put("fieldName", Schemas.string("Name of an existing picklist or multiPicklist field."));
        properties.put("values", valuesArr);
        properties.put("prune", Schemas.bool(
                "Deactivate (isActive=false) any existing active value of the field whose \"value\" is "
                        + "absent from this call's values list. Omitted or false leaves them active.", false));

        Tool tool = Tool.builder()
                .name("apply_field_picklist")
                .title("Apply Field Picklist")
                .description(
                        "Create-or-update the field-scoped values of a picklist field (values owned by the "
                        + "field, picklistSourceType=FIELD, instead of a global picklist). Create the field "
                        + "first with add_field {\"type\":\"picklist\",\"picklistSourceType\":\"FIELD\"} (no "
                        + "picklistSourceId), then call this with {\"collectionName\":\"deals\","
                        + "\"fieldName\":\"stage\",\"values\":[{\"value\":\"OPEN\",\"label\":\"Open\"}]}. "
                        + "Idempotent: a second identical call reports every value \"unchanged\". Wraps "
                        + "GET /api/fields and GET+POST/PATCH /api/picklist-values.")
                .inputSchema(Schemas.object(properties, List.of("fieldName", "values")))
                .annotations(ToolHints.write(true, true))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    return apply(args == null ? Map.of() : args);
                })
                .build();
    }

    CallToolResult apply(Map<String, Object> args) {
        if (!(args.get("fieldName") instanceof String fieldName) || fieldName.isBlank()) {
            return error("Argument \"fieldName\" is required.");
        }
        String invalid = PicklistValueApplier.validate(args.get("values"));
        if (invalid != null) {
            return error(invalid);
        }
        List<?> values = (List<?>) args.get("values");
        boolean prune = Boolean.TRUE.equals(args.get("prune"));

        try {
            String collectionId = resolveCollectionId(args);
            if (collectionId == null) {
                return error("Provide \"collectionName\" (an existing collection) or \"collectionId\".");
            }
            Map<String, Object> fieldKey = new LinkedHashMap<>();
            fieldKey.put("collectionId", collectionId);
            fieldKey.put("name", fieldName);
            List<Map<String, Object>> fields = lookups.list("fields", fieldKey, 1);
            if (fields.isEmpty()) {
                return error("No field \"" + fieldName + "\" on that collection. Create it with add_field "
                        + "{\"type\":\"picklist\",\"picklistSourceType\":\"FIELD\"} first.");
            }
            Map<String, Object> field = fields.get(0);
            Object type = field.get("type");
            if (type == null || !PICKLIST_TYPES.contains(type.toString().toUpperCase(Locale.ROOT))) {
                return error("Field \"" + fieldName + "\" is " + type + ", not a picklist field.");
            }
            String fieldId = field.get("id").toString();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fieldId", fieldId);
            body.putAll(valueApplier.apply("FIELD", fieldId, values, prune));
            return CallToolResult.builder()
                    .content(List.of(new TextContent(MAPPER.writeValueAsString(body))))
                    .build();
        } catch (AdminLookups.GatewayFailure e) {
            return McpErrorMapper.toResult(e.response);
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }
    }

    private String resolveCollectionId(Map<String, Object> args) {
        if (args.get("collectionId") instanceof String cid && !cid.isBlank()) {
            return cid;
        }
        if (args.get("collectionName") instanceof String cn && !cn.isBlank()) {
            return FieldBodyBuilder.UUID_PATTERN.matcher(cn).matches() ? cn : lookups.collectionIdByName(cn);
        }
        return null;
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
