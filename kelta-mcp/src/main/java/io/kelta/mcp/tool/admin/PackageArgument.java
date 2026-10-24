package io.kelta.mcp.tool.admin;

import io.kelta.mcp.tool.Schemas;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * The {@code package} argument shared by {@link DiffMetadataTool} and {@link ApplyMetadataTool}:
 * the package document {@code export_metadata} returns, given either as a JSON object or as the
 * JSON text itself. The worker's {@code /api/packages/import[/preview]} accepts the package as a
 * raw {@code application/json} body as well as the CLI's multipart {@code file} part, so the
 * tools send the parsed object as the request body.
 */
final class PackageArgument {

    static final String NAME = "package";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PackageArgument() {}

    static Map<String, Object> schema() {
        Map<String, Object> schema = Schemas.freeObject(
                "The metadata package document, as returned by export_metadata (a JSON object, or "
                        + "the same JSON as a string).");
        schema.put("type", java.util.List.of("object", "string"));
        return schema;
    }

    /** The package as a map, or null when the argument is missing or is not a JSON object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                Object parsed = MAPPER.readValue(text, Object.class);
                return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
