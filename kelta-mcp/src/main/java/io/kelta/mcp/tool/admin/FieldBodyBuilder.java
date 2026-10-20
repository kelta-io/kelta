package io.kelta.mcp.tool.admin;

import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.runtime.model.FieldType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Translates MCP-friendly field-creation arguments into the JSON:API body the
 * worker's {@code POST /api/fields} endpoint expects. Shared by {@link AddFieldTool}
 * and {@link CreateCollectionTool} so both go through the same alias mapping
 * and per-type payload assembly.
 *
 * <p>Field-type config is written in the shape the admin UI reads
 * ({@code fieldTypeConfig.globalPicklistId} — see kelta-ui's
 * {@code usePicklistOptions.resolvePicklistSource}), and lookup fields carry a
 * {@code referenceTarget} attribute (target collection <em>name</em>) alongside
 * the {@code referenceCollectionId} relationship, because the UI displays the
 * name, not the relationship.
 */
final class FieldBodyBuilder {

    static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final AdminLookups lookups;

    FieldBodyBuilder(GatewayHttpClient gateway) {
        this.lookups = new AdminLookups(gateway);
    }

    /** Builds the body, or returns a {@link Result} carrying a user-readable error. */
    Result build(Map<String, Object> args) {
        Object fn = args.get("fieldName");
        Object t = args.get("type");
        if (fn == null || fn.toString().isBlank()
                || t == null || t.toString().isBlank()) {
            return Result.error("Arguments \"fieldName\" and \"type\" are required.");
        }

        String collectionId = resolveCollectionId(args);
        if (collectionId == null) {
            return Result.error("Provide either \"collectionId\" or \"collectionName\".");
        }

        String nativeType = resolveNativeType(t.toString());
        if (nativeType == null) {
            return Result.error("Unknown field type \"" + t + "\". See the tool description for accepted aliases.");
        }

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("collectionId", collectionId);
        attrs.put("name", fn.toString());
        attrs.put("type", nativeType);
        if (args.get("displayName") instanceof String s && !s.isBlank()) attrs.put("displayName", s);
        if (args.get("required") instanceof Boolean b) attrs.put("required", b);
        if (args.get("unique") instanceof Boolean b) attrs.put("uniqueConstraint", b);
        if (args.get("indexed") instanceof Boolean b) attrs.put("indexed", b);
        if (args.get("searchable") instanceof Boolean b) attrs.put("searchable", b);
        if (args.get("description") instanceof String s && !s.isBlank()) attrs.put("description", s);
        Object dv = args.get("defaultValue");
        if (dv instanceof String || dv instanceof Boolean || dv instanceof Number) {
            attrs.put("defaultValue", dv);
        }
        if (args.get("validation") instanceof Map<?, ?> v) attrs.put("constraints", v);

        Map<String, Object> relationships = new LinkedHashMap<>();

        if ("PICKLIST".equals(nativeType) || "MULTI_PICKLIST".equals(nativeType)) {
            String err = applyPicklistConfig(args, attrs);
            if (err != null) return Result.error(err);
        } else if (isLookupType(nativeType)) {
            String err = applyReferenceRelationship(args, attrs, relationships);
            if (err != null) return Result.error(err);
        } else if ("VECTOR".equals(nativeType)) {
            String err = applyVectorConfig(args, attrs);
            if (err != null) return Result.error(err);
        }

        // Data masking — additive, string-typed fields only. Merges into any
        // fieldTypeConfig already assembled above (maskable types don't overlap
        // picklist/vector, so in practice this is the only writer).
        String maskErr = applyMaskingConfig(args, nativeType, attrs);
        if (maskErr != null) return Result.error(maskErr);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "fields");
        data.put("attributes", attrs);
        if (!relationships.isEmpty()) {
            data.put("relationships", relationships);
        }
        return Result.ok(Map.of("data", data));
    }

    String resolveCollectionId(Map<String, Object> args) {
        if (args.get("collectionId") instanceof String cid && !cid.isBlank()) {
            return cid;
        }
        if (args.get("collectionName") instanceof String cn && !cn.isBlank()) {
            if (UUID_PATTERN.matcher(cn).matches()) {
                return cn;
            }
            return lookups.collectionIdByName(cn);
        }
        return null;
    }

    /**
     * The id of the first resource in a JSON:API body. Kept as the shared
     * entry point for tools that parse create/list responses.
     */
    static String extractFirstId(String json) {
        return AdminLookups.firstResourceId(json);
    }

    private String applyPicklistConfig(Map<String, Object> args, Map<String, Object> attrs) {
        String sourceType = "GLOBAL";
        if (args.get("picklistSourceType") instanceof String st && !st.isBlank()) {
            sourceType = st.toUpperCase(Locale.ROOT);
        }
        Object pid = args.get("picklistSourceId");
        if (!(pid instanceof String picklistSourceId) || picklistSourceId.isBlank()) {
            if ("FIELD".equals(sourceType)) {
                // Field-scoped: the values are owned by this field (picklistSourceId = the
                // field's own id, assigned on create), so there is nothing to bind yet —
                // apply_field_picklist adds the values afterwards.
                return null;
            }
            return "picklist fields require \"picklistSourceId\" (UUID of the global picklist), "
                    + "or picklistSourceType \"FIELD\" for values owned by the field (then apply_field_picklist).";
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("picklistSourceType", sourceType);
        if ("GLOBAL".equals(sourceType)) {
            // Canonical key the admin UI resolves (usePicklistOptions): a field
            // written without it renders with NO picklist binding in the UI.
            config.put("globalPicklistId", picklistSourceId);
        } else {
            config.put("picklistSourceId", picklistSourceId);
        }
        attrs.put("fieldTypeConfig", config);
        return null;
    }

    private String applyVectorConfig(Map<String, Object> args, Map<String, Object> attrs) {
        Object raw = args.get("dimension");
        if (raw == null) {
            return "Vector fields require a \"dimension\" argument.";
        }
        int dim;
        if (raw instanceof Number n) {
            dim = n.intValue();
        } else {
            try {
                dim = Integer.parseInt(raw.toString());
            } catch (NumberFormatException e) {
                return "\"dimension\" must be an integer.";
            }
        }
        if (dim < 1 || dim > 16384) {
            return "\"dimension\" must be between 1 and 16384.";
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dimension", dim);
        attrs.put("fieldTypeConfig", config);
        return null;
    }

    /** Native field types that accept a data-masking config — mirrors the worker's {@code FieldMaskingService.MASKABLE_TYPES}. */
    private static final java.util.Set<String> MASKABLE_TYPES = java.util.Set.of(
            "STRING", "TEXT", "RICH_TEXT", "EMAIL", "PHONE", "URL", "ENCRYPTED", "EXTERNAL_ID");

    private static final java.util.Set<String> MASK_STRATEGIES =
            java.util.Set.of("FULL", "LAST4", "EMAIL", "CUSTOM");

    /**
     * Applies an optional {@code masking} config from the {@code maskingType}
     * (+ optional {@code maskingChar} / {@code maskingCustomPattern}) args onto
     * {@code fieldTypeConfig.masking}. No-op when {@code maskingType} is absent
     * or {@code NONE}; errors on a non-maskable type or unknown strategy.
     */
    @SuppressWarnings("unchecked")
    private String applyMaskingConfig(Map<String, Object> args, String nativeType, Map<String, Object> attrs) {
        Object raw = args.get("maskingType");
        if (!(raw instanceof String s) || s.isBlank() || "NONE".equalsIgnoreCase(s)) {
            return null;
        }
        String strategy = s.trim().toUpperCase(Locale.ROOT);
        if (!MASK_STRATEGIES.contains(strategy)) {
            return "\"maskingType\" must be one of FULL, LAST4, EMAIL, CUSTOM (or NONE).";
        }
        if (!MASKABLE_TYPES.contains(nativeType)) {
            return "Data masking is only supported on string-typed fields; \"" + nativeType + "\" is not maskable.";
        }
        Map<String, Object> masking = new LinkedHashMap<>();
        masking.put("type", strategy);
        if (args.get("maskingChar") instanceof String mc && !mc.isBlank()) {
            masking.put("maskChar", mc.substring(0, 1));
        }
        if ("CUSTOM".equals(strategy) && args.get("maskingCustomPattern") instanceof String cp && !cp.isBlank()) {
            masking.put("customPattern", cp);
        }
        Object existing = attrs.get("fieldTypeConfig");
        Map<String, Object> config = existing instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : new LinkedHashMap<>();
        config.put("masking", masking);
        attrs.put("fieldTypeConfig", config);
        return null;
    }

    private String applyReferenceRelationship(Map<String, Object> args,
                                              Map<String, Object> attrs,
                                              Map<String, Object> relationships) {
        String targetId = null;
        String targetName = null;
        if (args.get("referenceCollectionId") instanceof String s && !s.isBlank()) {
            targetId = s;
        } else if (args.get("referenceCollection") instanceof String s && !s.isBlank()) {
            if (UUID_PATTERN.matcher(s).matches()) {
                targetId = s;
            } else {
                targetName = s;
                targetId = lookups.collectionIdByName(s);
            }
        }
        if (targetId == null) {
            return "reference/lookup fields require \"referenceCollectionId\" (UUID of the target collection) or a resolvable \"referenceCollection\" name.";
        }
        if (targetName == null) {
            targetName = lookups.collectionNameById(targetId);
        }
        if (targetName != null && !targetName.isBlank()) {
            // The admin UI displays referenceTarget (the collection NAME) on
            // relationship fields; without it the field shows no target.
            attrs.put("referenceTarget", targetName);
        }

        if (args.get("relationshipName") instanceof String rn && !rn.isBlank()) {
            attrs.put("relationshipName", rn);
        }
        if (args.get("relationshipType") instanceof String rt && !rt.isBlank()) {
            attrs.put("relationshipType", rt.toUpperCase(Locale.ROOT));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "collections");
        data.put("id", targetId);
        relationships.put("referenceCollectionId", Map.of("data", data));
        return null;
    }

    private static boolean isLookupType(String nativeType) {
        return "LOOKUP".equals(nativeType)
                || "REFERENCE".equals(nativeType)
                || "MASTER_DETAIL".equals(nativeType);
    }

    /**
     * Maps an MCP-friendly type alias to the native uppercase {@code FieldType}
     * enum value the worker accepts. Any exact enum name (e.g. {@code CURRENCY},
     * {@code TEXT}, {@code GEOLOCATION}) passes through verbatim; friendly
     * camelCase/lowercase aliases cover the common types. Returns null for
     * unknown inputs.
     *
     * <p>Note: {@code text}/{@code longText} intentionally map to {@code STRING}
     * — the admin UI has no renderer for the {@code TEXT} type, and both map to
     * the same Postgres column type. Pass {@code TEXT} explicitly if you really
     * want the native type.
     */
    static String resolveNativeType(String alias) {
        if (alias == null) return null;
        String trimmed = alias.trim();
        if (trimmed.isEmpty()) return null;
        String mapped = switch (trimmed) {
            case "text", "string", "String", "STRING" -> "STRING";
            case "longText", "longtext", "long_text", "textarea" -> "STRING";
            case "number", "integer", "Integer", "INTEGER" -> "INTEGER";
            case "decimal", "double", "Double", "DOUBLE" -> "DOUBLE";
            case "long", "Long", "LONG" -> "LONG";
            case "boolean", "bool", "Boolean", "BOOLEAN" -> "BOOLEAN";
            case "date", "Date", "DATE" -> "DATE";
            case "datetime", "dateTime", "DateTime", "DATETIME" -> "DATETIME";
            case "picklist", "Picklist", "PICKLIST" -> "PICKLIST";
            case "multiPicklist", "multipicklist", "MULTI_PICKLIST" -> "MULTI_PICKLIST";
            case "reference", "Reference", "REFERENCE",
                 "lookup", "Lookup", "LOOKUP" -> "LOOKUP";
            case "masterDetail", "master_detail", "MASTER_DETAIL" -> "MASTER_DETAIL";
            case "json", "Json", "JSON" -> "JSON";
            case "richText", "rich_text", "RichText", "RICH_TEXT" -> "RICH_TEXT";
            case "vector", "Vector", "VECTOR" -> "VECTOR";
            case "currency", "Currency" -> "CURRENCY";
            case "percent", "Percent" -> "PERCENT";
            case "url", "Url" -> "URL";
            case "email", "Email" -> "EMAIL";
            case "phone", "Phone" -> "PHONE";
            case "autoNumber", "auto_number", "autonumber" -> "AUTO_NUMBER";
            case "externalId", "external_id" -> "EXTERNAL_ID";
            case "encrypted", "Encrypted" -> "ENCRYPTED";
            case "geolocation", "Geolocation" -> "GEOLOCATION";
            case "array", "Array" -> "ARRAY";
            default -> null;
        };
        if (mapped != null) {
            return mapped;
        }
        // Any exact FieldType enum name passes through (CURRENCY, TEXT, PERCENT, ...).
        try {
            return FieldType.valueOf(trimmed).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record Result(Map<String, Object> body, String errorMessage) {
        static Result ok(Map<String, Object> body) {
            return new Result(body, null);
        }

        static Result error(String message) {
            return new Result(null, message);
        }

        boolean isError() {
            return errorMessage != null;
        }
    }
}
