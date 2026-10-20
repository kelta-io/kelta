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
import java.util.Map;

/**
 * Idempotent create-or-update for a global picklist and its values in one call,
 * keyed on {@code name} for the picklist and {@code (picklistSourceId, value)}
 * for each entry via {@link AdminLookups#upsert}.
 *
 * <p>{@code sortOrder} is never taken from the input — it is always derived
 * from each value's 0-based position in the {@code values} array, so
 * reordering the array is how a caller reorders the picklist. {@code prune:
 * true} deactivates ({@code isActive=false}) any existing active value whose
 * {@code value} isn't present in this call's list; omitted or {@code false}
 * leaves those extras active (additive apply).
 */
@Component
public class ApplyPicklistTool implements AdminTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AdminLookups lookups;
    private final PicklistValueApplier valueApplier;

    public ApplyPicklistTool(GatewayHttpClient gateway) {
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
                "Picklist values in display order — sortOrder is derived from each value's "
                        + "position in this list, not taken from input.");
        valuesArr.put("items", valueItem);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Schemas.string("Picklist name (unique within tenant)."));
        properties.put("description", Schemas.string("Optional picklist description."));
        properties.put("values", valuesArr);
        properties.put("prune", Schemas.bool(
                "Deactivate (isActive=false) any existing active value whose \"value\" is absent from "
                        + "this call's values list. Omitted or false leaves them active.", false));

        Tool tool = Tool.builder()
                .name("apply_picklist")
                .title("Apply Picklist")
                .description(
                        "Create-or-update a global picklist and its values in one call, keyed on name "
                        + "(picklist) and (picklist, value) for each entry. Idempotent: applying the "
                        + "identical body twice reports {\"action\":\"unchanged\",...} for both the "
                        + "picklist and every value on the second call. Example: {\"name\":\"Stage\","
                        + "\"values\":[{\"value\":\"OPEN\",\"label\":\"Open\",\"color\":\"#22C55E\"},"
                        + "{\"value\":\"CLOSED\",\"label\":\"Closed\",\"color\":\"#EF4444\"}],"
                        + "\"prune\":true} writes both values with sortOrder 0 and 1 and deactivates any "
                        + "other active value already on the picklist. Wraps GET+POST/PATCH "
                        + "/api/global-picklists and /api/picklist-values. Deprecates create_picklist for "
                        + "setup scripts that re-run.")
                .inputSchema(Schemas.object(properties, List.of("name", "values")))
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
        Object n = args.get("name");
        if (n == null || n.toString().isBlank()) {
            return error("Argument \"name\" is required.");
        }
        String invalid = PicklistValueApplier.validate(args.get("values"));
        if (invalid != null) {
            return error(invalid);
        }
        List<?> values = (List<?>) args.get("values");
        boolean prune = Boolean.TRUE.equals(args.get("prune"));

        try {
            Map<String, Object> picklistAttrs = new LinkedHashMap<>();
            if (args.get("description") instanceof String d && !d.isBlank()) picklistAttrs.put("description", d);
            AdminLookups.UpsertResult picklist = lookups.upsert(
                    "global-picklists", Map.of("name", n.toString()), picklistAttrs);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", picklist.action());
            body.put("id", picklist.id());
            if (!picklist.changed().isEmpty()) body.put("changed", picklist.changed());
            body.putAll(valueApplier.apply("GLOBAL", picklist.id(), values, prune));

            return CallToolResult.builder()
                    .content(List.of(new TextContent(MAPPER.writeValueAsString(body))))
                    .build();
        } catch (AdminLookups.GatewayFailure e) {
            return McpErrorMapper.toResult(e.response);
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
