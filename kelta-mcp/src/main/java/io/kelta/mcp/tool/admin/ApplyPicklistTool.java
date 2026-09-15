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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final GatewayHttpClient gateway;
    private final AdminLookups lookups;

    public ApplyPicklistTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
        this.lookups = new AdminLookups(gateway);
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
        if (!(args.get("values") instanceof List<?> values) || values.isEmpty()) {
            return error("Argument \"values\" must be a non-empty array.");
        }
        for (Object v : values) {
            if (!(v instanceof Map<?, ?> vMap)
                    || !(vMap.get("value") instanceof String vs) || vs.isBlank()
                    || !(vMap.get("label") instanceof String ls) || ls.isBlank()) {
                return error("Each entry in \"values\" needs a non-blank \"value\" and \"label\".");
            }
        }
        boolean prune = Boolean.TRUE.equals(args.get("prune"));

        try {
            Map<String, Object> picklistAttrs = new LinkedHashMap<>();
            if (args.get("description") instanceof String d && !d.isBlank()) picklistAttrs.put("description", d);
            AdminLookups.UpsertResult picklist = lookups.upsert(
                    "global-picklists", Map.of("name", n.toString()), picklistAttrs);
            String picklistId = picklist.id();

            List<Object> valueResults = new ArrayList<>();
            Set<String> desiredValues = new LinkedHashSet<>();
            for (int i = 0; i < values.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> vMap = (Map<String, Object>) values.get(i);
                String value = vMap.get("value").toString();
                desiredValues.add(value);

                Map<String, Object> naturalKey = new LinkedHashMap<>();
                naturalKey.put("picklistSourceType", "GLOBAL");
                naturalKey.put("picklistSourceId", picklistId);
                naturalKey.put("value", value);

                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("label", vMap.get("label").toString());
                attrs.put("sortOrder", i);
                if (vMap.get("color") instanceof String c && !c.isBlank()) attrs.put("color", c);
                if (vMap.get("description") instanceof String d && !d.isBlank()) attrs.put("description", d);

                AdminLookups.UpsertResult valueResult = lookups.upsert("picklist-values", naturalKey, attrs);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("value", value);
                entry.put("action", valueResult.action());
                entry.put("id", valueResult.id());
                if (!valueResult.changed().isEmpty()) entry.put("changed", valueResult.changed());
                valueResults.add(entry);
            }

            List<String> pruned = prune ? pruneAbsentValues(picklistId, desiredValues) : List.of();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", picklist.action());
            body.put("id", picklist.id());
            if (!picklist.changed().isEmpty()) body.put("changed", picklist.changed());
            body.put("values", valueResults);
            if (!pruned.isEmpty()) body.put("pruned", pruned);

            return CallToolResult.builder()
                    .content(List.of(new TextContent(MAPPER.writeValueAsString(body))))
                    .build();
        } catch (AdminLookups.GatewayFailure e) {
            return McpErrorMapper.toResult(e.response);
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }
    }

    /**
     * Deactivates every active value on the picklist whose {@code value} is absent from
     * {@code desiredValues}, and returns their {@code value}s.
     */
    private List<String> pruneAbsentValues(String picklistId, Set<String> desiredValues) {
        String path = "/api/picklist-values?filter[picklistSourceType][eq]=GLOBAL"
                + "&filter[picklistSourceId][eq]=" + URLEncoder.encode(picklistId, StandardCharsets.UTF_8)
                + "&filter[isActive][eq]=true&page[size]=200";
        GatewayHttpClient.Response response = gateway.get(path);
        if (!response.isSuccess()) {
            throw new AdminLookups.GatewayFailure(response);
        }
        List<String> pruned = new ArrayList<>();
        JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
        for (JsonNode item : root.path("data")) {
            String value = item.path("attributes").path("value").asString();
            String id = item.path("id").asString();
            if (!value.isBlank() && !desiredValues.contains(value) && !id.isBlank()) {
                deactivate(id);
                pruned.add(value);
            }
        }
        return pruned;
    }

    private void deactivate(String id) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "picklist-values", "id", id, "attributes", Map.of("isActive", false)));
        String path = "/api/picklist-values/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        GatewayHttpClient.Response response = gateway.patch(path, body);
        if (!response.isSuccess()) {
            throw new AdminLookups.GatewayFailure(response);
        }
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
