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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Idempotent create-or-update of a collection together with its inline fields, their
 * field-scoped picklist values and the collection's display field — what otherwise takes
 * create_collection + add_field per field + apply_field_picklist per picklist +
 * update_collection.
 *
 * <p>The collection is upserted on {@code name} and each field on {@code (collectionId, name)}
 * via {@link AdminLookups#upsert}; field bodies go through {@link FieldBodyBuilder} (the same
 * friendly shape as create_collection) and inline picklist values through
 * {@link PicklistValueApplier}. {@code displayFieldName} is resolved to a field UUID after the
 * fields exist and written as {@code displayFieldId} in a second collection upsert — the worker
 * ignores a {@code displayFieldName} attribute.
 *
 * <p>Fields not named in the call are left alone. A field that fails is reported with its mapped
 * gateway error alongside the collection and the fields that succeeded, rather than aborting the
 * whole call — there is no transaction across system collections.
 */
@Component
public class ApplyCollectionTool implements AdminTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> PICKLIST_TYPES = Set.of("PICKLIST", "MULTI_PICKLIST");

    private final AdminLookups lookups;
    private final FieldBodyBuilder bodyBuilder;
    private final PicklistValueApplier valueApplier;

    public ApplyCollectionTool(GatewayHttpClient gateway) {
        this.lookups = new AdminLookups(gateway);
        this.bodyBuilder = new FieldBodyBuilder(gateway);
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
                "picklist/multiPicklist only: the field's own values (picklistSourceType FIELD), in display "
                        + "order — sortOrder is each value's position.");
        valuesArr.put("items", valueItem);

        Map<String, Object> fieldProps = new LinkedHashMap<>();
        fieldProps.put("fieldName", Schemas.string("Field name — identifies the field within the collection."));
        fieldProps.put("type", Schemas.string(
                "text|number|decimal|boolean|date|datetime|picklist|multiPicklist|reference|masterDetail|json|..."
                        + " (same aliases as add_field)."));
        fieldProps.put("required", Schemas.bool("Whether a value is required.", false));
        fieldProps.put("unique", Schemas.bool("Whether values must be unique.", false));
        fieldProps.put("description", Schemas.string("Optional description."));
        fieldProps.put("values", valuesArr);
        fieldProps.put("prune", Schemas.bool(
                "With values: deactivate the field's other active values absent from the list.", false));

        Map<String, Object> fieldItem = new LinkedHashMap<>();
        fieldItem.put("type", "object");
        fieldItem.put("properties", fieldProps);
        fieldItem.put("required", List.of("fieldName", "type"));
        fieldItem.put("additionalProperties", true);

        Map<String, Object> fieldsArr = new LinkedHashMap<>();
        fieldsArr.put("type", "array");
        fieldsArr.put("description",
                "Fields to create or update, in order. Accepts every add_field argument (displayName, "
                        + "indexed, defaultValue, referenceCollection, ...). Fields not listed are left alone.");
        fieldsArr.put("items", fieldItem);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Schemas.string("Collection name — identifies the collection (e.g. \"projects\")."));
        properties.put("displayName", Schemas.string(
                "Human-readable label for the collection (e.g. \"Projects\"). Omit to leave it alone."));
        properties.put("description", Schemas.string("Description shown in the admin UI. Omit to leave it alone."));
        properties.put("tenantScoped", Schemas.bool(
                "Whether records are tenant-scoped. Omit to leave it alone (default true on create).", true));
        properties.put("fields", fieldsArr);
        properties.put("displayFieldName", Schemas.string(
                "Name of the field used as each record's display label (one of fields, or an existing field)."));

        Tool tool = Tool.builder()
                .name("apply_collection")
                .title("Apply Collection")
                .description(
                        "Create-or-update a collection with its fields, field-scoped picklist values and display "
                        + "field in one call, keyed on name (fields on fieldName). Idempotent: a second identical "
                        + "call reports {\"action\":\"unchanged\"} for the collection and every field; a changed "
                        + "attribute reports \"updated\" with the changed keys and PATCHes only that record. "
                        + "Fields not named in the call are left alone — nothing is deleted. Example: "
                        + "{\"name\":\"deals\",\"displayName\":\"Deals\",\"fields\":[{\"fieldName\":\"title\","
                        + "\"type\":\"text\",\"required\":true},{\"fieldName\":\"stage\",\"type\":\"picklist\","
                        + "\"values\":[{\"value\":\"OPEN\",\"label\":\"Open\"}]}],\"displayFieldName\":\"title\"}. "
                        + "A field that fails is reported with its error next to the ones that succeeded. "
                        + "Replaces create_collection + add_field + apply_field_picklist + update_collection.")
                .inputSchema(Schemas.object(properties, List.of("name")))
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
        if (!(args.get("name") instanceof String name) || name.isBlank()) {
            return error("Argument \"name\" is required.");
        }
        List<Map<String, Object>> fieldArgs = new ArrayList<>();
        if (args.get("fields") != null) {
            if (!(args.get("fields") instanceof List<?> list)) {
                return error("Argument \"fields\" must be an array.");
            }
            for (int i = 0; i < list.size(); i++) {
                String invalid = validateField(i, list.get(i));
                if (invalid != null) {
                    return error(invalid);
                }
                Map<String, Object> f = new LinkedHashMap<>();
                ((Map<?, ?>) list.get(i)).forEach((k, v) -> f.put(String.valueOf(k), v));
                fieldArgs.add(f);
            }
        }

        AdminLookups.UpsertResult collection;
        try {
            Map<String, Object> attrs = new LinkedHashMap<>();
            if (args.get("displayName") instanceof String s && !s.isBlank()) attrs.put("displayName", s);
            if (args.get("description") instanceof String s) attrs.put("description", s);
            if (args.get("tenantScoped") instanceof Boolean b) attrs.put("tenantScoped", b);
            collection = lookups.upsert("collections", Map.of("name", name), attrs);
        } catch (AdminLookups.GatewayFailure e) {
            return McpErrorMapper.toResult(e.response);
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }

        boolean failed = false;
        boolean changed = !"unchanged".equals(collection.action());
        Map<String, String> fieldIds = new LinkedHashMap<>();
        List<Map<String, Object>> fieldResults = new ArrayList<>();
        for (Map<String, Object> f : fieldArgs) {
            Map<String, Object> entry = applyField(collection.id(), f, fieldIds);
            fieldResults.add(entry);
            failed |= entry.containsKey("error");
            changed |= !"unchanged".equals(entry.get("action")) || valuesChanged(entry.get("values"));
        }

        List<String> collectionChanged = new ArrayList<>(collection.changed());
        Map<String, Object> displayField = null;
        if (args.get("displayFieldName") instanceof String dfn && !dfn.isBlank()) {
            displayField = applyDisplayField(name, collection.id(), dfn, fieldIds);
            failed |= displayField.containsKey("error");
            if ("updated".equals(displayField.get("action"))) {
                changed = true;
                collectionChanged.add("displayFieldId");
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "created".equals(collection.action()) ? "created" : changed ? "updated" : "unchanged");
        body.put("id", collection.id());
        if (!collectionChanged.isEmpty()) body.put("changed", collectionChanged);
        if (!fieldResults.isEmpty()) body.put("fields", fieldResults);
        if (displayField != null) body.put("displayField", displayField);
        return CallToolResult.builder()
                .isError(failed)
                .content(List.of(new TextContent(MAPPER.writeValueAsString(body))))
                .build();
    }

    private static String validateField(int index, Object item) {
        if (!(item instanceof Map<?, ?> f)) {
            return "fields[" + index + "] must be an object.";
        }
        if (!(f.get("fieldName") instanceof String fn) || fn.isBlank()) {
            return "fields[" + index + "] needs a non-blank \"fieldName\".";
        }
        if (!(f.get("type") instanceof String t) || FieldBodyBuilder.resolveNativeType(t) == null) {
            return "Field \"" + fn + "\" needs a known \"type\" (see add_field for accepted aliases).";
        }
        if (f.get("values") == null) {
            return null;
        }
        if (!PICKLIST_TYPES.contains(FieldBodyBuilder.resolveNativeType(t))) {
            return "Field \"" + fn + "\": \"values\" is only allowed on picklist and multiPicklist fields.";
        }
        if (f.get("picklistSourceType") instanceof String st && !"FIELD".equalsIgnoreCase(st)) {
            return "Field \"" + fn + "\": inline \"values\" are field-scoped — omit picklistSourceType or set it to FIELD.";
        }
        String invalid = PicklistValueApplier.validate(f.get("values"));
        return invalid == null ? null : "Field \"" + fn + "\": " + invalid;
    }

    /** Upserts one field (and its inline values); failures land in the entry's {@code error}. */
    private Map<String, Object> applyField(String collectionId, Map<String, Object> f, Map<String, String> fieldIds) {
        String fieldName = f.get("fieldName").toString();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("fieldName", fieldName);

        Map<String, Object> builderArgs = new LinkedHashMap<>(f);
        builderArgs.remove("collectionName");
        builderArgs.put("collectionId", collectionId);
        if (f.get("values") != null) {
            builderArgs.put("picklistSourceType", "FIELD");
        }
        try {
            FieldBodyBuilder.Result built = bodyBuilder.build(builderArgs);
            if (built.isError()) {
                entry.put("action", "failed");
                entry.put("error", built.errorMessage());
                return entry;
            }
            AdminLookups.UpsertResult result = lookups.upsert("fields",
                    naturalKey(collectionId, fieldName), fieldAttributes(built.body()));
            fieldIds.put(fieldName, result.id());
            entry.put("action", result.action());
            entry.put("id", result.id());
            if (!result.changed().isEmpty()) entry.put("changed", result.changed());

            if (f.get("values") instanceof List<?> values) {
                entry.put("values", valueApplier.apply("FIELD", result.id(), values,
                        Boolean.TRUE.equals(f.get("prune"))));
            }
        } catch (AdminLookups.GatewayFailure e) {
            entry.putIfAbsent("action", "failed");
            entry.put("error", errorText(McpErrorMapper.toResult(e.response)));
        } catch (RuntimeException e) {
            entry.putIfAbsent("action", "failed");
            entry.put("error", errorText(McpErrorMapper.fromException(e)));
        }
        return entry;
    }

    /**
     * The built field body's attributes minus the natural key, with each relationship (e.g.
     * {@code referenceCollectionId}) folded in as its id — the worker reads a relationship as
     * the same field value, and {@link AdminLookups#upsert} diffs the folded shape.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldAttributes(Map<String, Object> body) {
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        Map<String, Object> attrs = new LinkedHashMap<>((Map<String, Object>) data.get("attributes"));
        attrs.remove("collectionId");
        attrs.remove("name");
        if (data.get("relationships") instanceof Map<?, ?> relationships) {
            relationships.forEach((key, rel) -> {
                if (rel instanceof Map<?, ?> r && r.get("data") instanceof Map<?, ?> d) {
                    attrs.put(String.valueOf(key), d.get("id"));
                }
            });
        }
        return attrs;
    }

    private Map<String, Object> applyDisplayField(String name, String collectionId, String displayFieldName,
                                                  Map<String, String> fieldIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fieldName", displayFieldName);
        try {
            String fieldId = fieldIds.containsKey(displayFieldName)
                    ? fieldIds.get(displayFieldName)
                    : lookups.fieldIdsByName(collectionId).get(displayFieldName);
            if (fieldId == null) {
                out.put("action", "failed");
                out.put("error", "Field \"" + displayFieldName + "\" not found on the collection.");
                return out;
            }
            out.put("id", fieldId);
            AdminLookups.UpsertResult result =
                    lookups.upsert("collections", Map.of("name", name), Map.of("displayFieldId", fieldId));
            out.put("action", result.action());
        } catch (AdminLookups.GatewayFailure e) {
            out.put("action", "failed");
            out.put("error", errorText(McpErrorMapper.toResult(e.response)));
        } catch (RuntimeException e) {
            out.put("action", "failed");
            out.put("error", errorText(McpErrorMapper.fromException(e)));
        }
        return out;
    }

    private static Map<String, Object> naturalKey(String collectionId, String fieldName) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("collectionId", collectionId);
        key.put("name", fieldName);
        return key;
    }

    private static boolean valuesChanged(Object summary) {
        if (!(summary instanceof Map<?, ?> s)) return false;
        if (s.get("pruned") != null) return true;
        if (s.get("values") instanceof List<?> values) {
            for (Object v : values) {
                if (v instanceof Map<?, ?> m && !"unchanged".equals(m.get("action"))) return true;
            }
        }
        return false;
    }

    private static String errorText(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
