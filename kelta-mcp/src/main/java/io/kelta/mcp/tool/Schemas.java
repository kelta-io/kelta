package io.kelta.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny helpers for building tool {@code inputSchema} descriptors. The MCP SDK
 * takes the schema as a raw JSON Schema {@code Map}; these helpers keep tool
 * authors away from hand-built maps and give consistent shapes.
 */
public final class Schemas {

    private Schemas() {}

    /** Empty no-args schema. */
    public static Map<String, Object> empty() {
        return object(Map.of(), List.of());
    }

    /** Object schema with the given properties and required field names. */
    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", required);
        m.put("additionalProperties", false);
        return m;
    }

    /** Convenience for a string property descriptor. */
    public static Map<String, Object> string(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", description);
        return m;
    }

    /** Convenience for an integer property descriptor with optional bounds. */
    public static Map<String, Object> integer(String description, Integer min, Integer max) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "integer");
        m.put("description", description);
        if (min != null) m.put("minimum", min);
        if (max != null) m.put("maximum", max);
        return m;
    }

    /** Convenience for a boolean property descriptor with default. */
    public static Map<String, Object> bool(String description, boolean defaultValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "boolean");
        m.put("description", description);
        m.put("default", defaultValue);
        return m;
    }

    /** Convenience for a free-form object property (e.g. filter / params). */
    public static Map<String, Object> freeObject(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("description", description);
        m.put("additionalProperties", true);
        return m;
    }

    /**
     * Convenience for a property that accepts any JSON scalar (string, number,
     * boolean, or null). Used for parameters like {@code defaultValue} where
     * the type depends on the field's declared type rather than a fixed schema.
     */
    public static Map<String, Object> anyScalar(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", List.of("string", "number", "boolean", "null"));
        m.put("description", description);
        return m;
    }
}
