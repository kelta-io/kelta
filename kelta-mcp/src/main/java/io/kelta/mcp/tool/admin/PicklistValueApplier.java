package io.kelta.mcp.tool.admin;

import io.kelta.mcp.client.GatewayHttpClient;
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
 * Idempotent apply of a picklist's values, shared by {@link ApplyPicklistTool}
 * ({@code GLOBAL} source: a global picklist's id) and {@link ApplyFieldPicklistTool}
 * ({@code FIELD} source: the picklist field's own id). Each value is upserted on
 * {@code (picklistSourceType, picklistSourceId, value)}; {@code sortOrder} is the
 * value's 0-based position in the list.
 */
final class PicklistValueApplier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewayHttpClient gateway;
    private final AdminLookups lookups;

    PicklistValueApplier(GatewayHttpClient gateway, AdminLookups lookups) {
        this.gateway = gateway;
        this.lookups = lookups;
    }

    /** A user-readable error when {@code values} is not a non-empty list of {value,label} objects, else null. */
    static String validate(Object values) {
        if (!(values instanceof List<?> list) || list.isEmpty()) {
            return "Argument \"values\" must be a non-empty array.";
        }
        for (Object v : list) {
            if (!(v instanceof Map<?, ?> vMap)
                    || !(vMap.get("value") instanceof String vs) || vs.isBlank()
                    || !(vMap.get("label") instanceof String ls) || ls.isBlank()) {
                return "Each entry in \"values\" needs a non-blank \"value\" and \"label\".";
            }
        }
        return null;
    }

    /**
     * Upserts every value and, with {@code prune}, deactivates the source's other active
     * values. Returns {@code {values:[{value,action,id,changed?}], pruned?:[...]}}.
     * Gateway failures surface as {@link AdminLookups.GatewayFailure}.
     */
    Map<String, Object> apply(String sourceType, String sourceId, List<?> values, boolean prune) {
        List<Object> valueResults = new ArrayList<>();
        Set<String> desiredValues = new LinkedHashSet<>();
        for (int i = 0; i < values.size(); i++) {
            Map<?, ?> vMap = (Map<?, ?>) values.get(i);
            String value = vMap.get("value").toString();
            desiredValues.add(value);

            Map<String, Object> naturalKey = new LinkedHashMap<>();
            naturalKey.put("picklistSourceType", sourceType);
            naturalKey.put("picklistSourceId", sourceId);
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("values", valueResults);
        List<String> pruned = prune ? pruneAbsentValues(sourceType, sourceId, desiredValues) : List.of();
        if (!pruned.isEmpty()) out.put("pruned", pruned);
        return out;
    }

    /**
     * Deactivates every active value of the source whose {@code value} is absent from
     * {@code desiredValues}, and returns their {@code value}s.
     */
    private List<String> pruneAbsentValues(String sourceType, String sourceId, Set<String> desiredValues) {
        String path = "/api/picklist-values?filter[picklistSourceType][eq]=" + sourceType
                + "&filter[picklistSourceId][eq]=" + URLEncoder.encode(sourceId, StandardCharsets.UTF_8)
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
}
