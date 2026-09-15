package io.kelta.mcp.tool.admin;

import io.kelta.mcp.client.GatewayHttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gateway-backed name/id resolution shared by the admin tools.
 *
 * <p>All parsing is done with Jackson on the JSON:API envelope. The old
 * substring scan ("first {@code "id"} after {@code "data"}") returned the
 * {@code relationships.createdBy.data.id} — the acting <em>user's</em> UUID —
 * because the worker serializes {@code relationships} before the record's own
 * {@code id}. Every helper here reads {@code data[0].id} / {@code data.id}
 * explicitly so response key order can never matter again.
 */
final class AdminLookups {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewayHttpClient gateway;

    AdminLookups(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    /**
     * The id of the first resource in a JSON:API body: {@code data.id} for a
     * single-resource document, {@code data[0].id} for a list document.
     * Returns null when the body has no resource.
     */
    static String firstResourceId(String json) {
        JsonNode data = dataNode(json);
        if (data == null) return null;
        JsonNode id = data.path("id");
        return id.isMissingNode() || id.isNull() || id.asString().isBlank() ? null : id.asString();
    }

    /** A string attribute of the first resource in a JSON:API body, or null. */
    static String firstResourceAttribute(String json, String attribute) {
        JsonNode data = dataNode(json);
        if (data == null) return null;
        JsonNode value = data.path("attributes").path(attribute);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static JsonNode dataNode(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode data = root.path("data");
            if (data.isArray()) {
                return data.isEmpty() ? null : data.get(0);
            }
            return data.isObject() ? data : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Resolves a collection name to its UUID, or null when not found. */
    String collectionIdByName(String collectionName) {
        String path = "/api/collections?filter[name][eq]="
                + URLEncoder.encode(collectionName, StandardCharsets.UTF_8);
        GatewayHttpClient.Response res = gateway.get(path);
        if (!res.isSuccess() || res.body() == null) {
            return null;
        }
        return firstResourceId(res.body());
    }

    /** Resolves a collection UUID to its name, or null when not found. */
    String collectionNameById(String collectionId) {
        String path = "/api/collections/" + URLEncoder.encode(collectionId, StandardCharsets.UTF_8);
        GatewayHttpClient.Response res = gateway.get(path);
        if (!res.isSuccess() || res.body() == null) {
            return null;
        }
        return firstResourceAttribute(res.body(), "name");
    }

    /**
     * All fields of a collection as {@code fieldName -> fieldId}. Used to
     * resolve layout field references and {@code displayFieldName}.
     */
    Map<String, String> fieldIdsByName(String collectionId) {
        String path = "/api/fields?filter[collectionId][EQ]="
                + URLEncoder.encode(collectionId, StandardCharsets.UTF_8)
                + "&page[size]=200";
        GatewayHttpClient.Response res = gateway.get(path);
        Map<String, String> out = new LinkedHashMap<>();
        if (!res.isSuccess() || res.body() == null) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(res.body());
            for (JsonNode item : root.path("data")) {
                String name = item.path("attributes").path("name").asString();
                String id = item.path("id").asString();
                if (!name.isBlank() && !id.isBlank()) {
                    out.put(name, id);
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }

    /** Outcome of {@link #upsert}: what happened and, for an update, which keys changed. */
    record UpsertResult(String action, String id, List<String> changed) {
        static UpsertResult created(String id) {
            return new UpsertResult("created", id, List.of());
        }

        static UpsertResult unchanged(String id) {
            return new UpsertResult("unchanged", id, List.of());
        }

        static UpsertResult updated(String id, List<String> changed) {
            return new UpsertResult("updated", id, changed);
        }
    }

    /**
     * Thrown when a gateway call made while resolving an {@link #upsert} returns a
     * non-2xx response — the caller catches this and maps {@link #response} via
     * {@code McpErrorMapper.toResult(...)}, the same as any other tool-level gateway
     * failure.
     */
    static final class GatewayFailure extends RuntimeException {
        final GatewayHttpClient.Response response;

        GatewayFailure(GatewayHttpClient.Response response) {
            super("Gateway call failed with status "
                    + (response.status() == null ? "?" : response.status().value()));
            this.response = response;
        }
    }

    /**
     * Create-or-update by natural key: looks up an existing record filtered on
     * {@code naturalKey} (each entry ANDed as {@code filter[key][eq]=value}), then:
     * <ul>
     *   <li>no match — POSTs {@code attributes} (merged with {@code naturalKey}) and
     *       returns {@code created}.</li>
     *   <li>a match — re-reads that record by id (a fresh single-resource GET, not the
     *       filtered list result) and diffs {@code attributes} against it, folding
     *       relationship ids into the comparison so a lookup/master-detail field (only
     *       present in {@code relationships.<field>.data.id} in principle) compares the
     *       same as a plain attribute. No differing key — {@code unchanged}. Otherwise
     *       PATCHes only the differing keys and returns {@code updated} with the list of
     *       changed keys.</li>
     * </ul>
     *
     * <p>Only keys present in {@code attributes} are ever compared or written — a key
     * the caller didn't supply is left at whatever the record already has (or the
     * collection's own default, on create).
     */
    UpsertResult upsert(String collection, Map<String, Object> naturalKey, Map<String, Object> attributes) {
        String existingId = findExisting(collection, naturalKey);
        if (existingId == null) {
            Map<String, Object> createAttrs = new LinkedHashMap<>(naturalKey);
            createAttrs.putAll(attributes);
            return UpsertResult.created(create(collection, createAttrs));
        }

        Map<String, Object> current = readAttributes(collection, existingId);
        List<String> changed = diff(current, attributes);
        if (changed.isEmpty()) {
            return UpsertResult.unchanged(existingId);
        }
        Map<String, Object> patchAttrs = new LinkedHashMap<>();
        for (String key : changed) {
            patchAttrs.put(key, attributes.get(key));
        }
        patch(collection, existingId, patchAttrs);
        return UpsertResult.updated(existingId, changed);
    }

    private String findExisting(String collection, Map<String, Object> naturalKey) {
        StringBuilder path = new StringBuilder("/api/").append(collection).append('?');
        for (Map.Entry<String, Object> entry : naturalKey.entrySet()) {
            path.append("filter[").append(entry.getKey()).append("][eq]=")
                    .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8))
                    .append('&');
        }
        path.append("page[size]=1");
        GatewayHttpClient.Response response = gateway.get(path.toString());
        if (!response.isSuccess()) {
            throw new GatewayFailure(response);
        }
        return firstResourceId(response.body());
    }

    private Map<String, Object> readAttributes(String collection, String id) {
        String path = "/api/" + collection + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        GatewayHttpClient.Response response = gateway.get(path);
        if (!response.isSuccess()) {
            throw new GatewayFailure(response);
        }
        return foldedAttributes(response.body());
    }

    /** {@code attributes}, overlaid with each relationship's {@code data.id} keyed by field name. */
    private static Map<String, Object> foldedAttributes(String json) {
        JsonNode data = dataNode(json);
        return data == null ? new LinkedHashMap<>() : foldedAttributesOf(data);
    }

    /** Same fold as {@link #foldedAttributes(String)}, applied to an already-resolved resource node, plus its {@code id}. */
    private static Map<String, Object> foldedAttributesOf(JsonNode data) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode id = data.path("id");
        if (!id.isMissingNode() && !id.isNull()) {
            out.put("id", id.asString());
        }
        for (Map.Entry<String, JsonNode> entry : data.path("attributes").properties()) {
            out.put(entry.getKey(), MAPPER.convertValue(entry.getValue(), Object.class));
        }
        for (Map.Entry<String, JsonNode> entry : data.path("relationships").properties()) {
            JsonNode relId = entry.getValue().path("data").path("id");
            if (!relId.isMissingNode() && !relId.isNull()) {
                out.put(entry.getKey(), relId.asString());
            }
        }
        return out;
    }

    /**
     * All resources matching {@code filter} (each entry ANDed as {@code filter[key][eq]=value}),
     * each as a folded attribute map ({@code id} + attributes + relationship ids, same shape as
     * {@link #upsert}'s internal diff comparison) — up to {@code pageSize} rows. Used by tools
     * that need the whole current set to resolve a tree (e.g. menu items keyed by parent) rather
     * than a single natural-key lookup.
     */
    List<Map<String, Object>> list(String collection, Map<String, Object> filter, int pageSize) {
        StringBuilder path = new StringBuilder("/api/").append(collection).append('?');
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            path.append("filter[").append(entry.getKey()).append("][eq]=")
                    .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8))
                    .append('&');
        }
        path.append("page[size]=").append(pageSize);
        GatewayHttpClient.Response response = gateway.get(path.toString());
        if (!response.isSuccess()) {
            throw new GatewayFailure(response);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (response.body() == null || response.body().isBlank()) {
            return out;
        }
        JsonNode root = MAPPER.readTree(response.body());
        for (JsonNode item : root.path("data")) {
            out.add(foldedAttributesOf(item));
        }
        return out;
    }

    /** Keys of {@code desired} whose value differs from {@code current}, in {@code desired}'s order. */
    static List<String> diff(Map<String, Object> current, Map<String, Object> desired) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object> entry : desired.entrySet()) {
            JsonNode currentNode = MAPPER.valueToTree(current.get(entry.getKey()));
            JsonNode desiredNode = MAPPER.valueToTree(entry.getValue());
            if (!Objects.equals(currentNode, desiredNode)) {
                changed.add(entry.getKey());
            }
        }
        return changed;
    }

    private String create(String collection, Map<String, Object> attributes) {
        Map<String, Object> body = Map.of("data", Map.of("type", collection, "attributes", attributes));
        GatewayHttpClient.Response response = gateway.post("/api/" + collection, body);
        if (!response.isSuccess()) {
            throw new GatewayFailure(response);
        }
        return firstResourceId(response.body());
    }

    private void patch(String collection, String id, Map<String, Object> attributes) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", collection, "id", id, "attributes", attributes));
        String path = "/api/" + collection + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        GatewayHttpClient.Response response = gateway.patch(path, body);
        if (!response.isSuccess()) {
            throw new GatewayFailure(response);
        }
    }
}
