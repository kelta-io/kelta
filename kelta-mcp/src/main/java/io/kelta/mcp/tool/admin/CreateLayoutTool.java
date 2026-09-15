package io.kelta.mcp.tool.admin;

import io.kelta.mcp.tool.AdminTool;
import io.kelta.mcp.tool.Schemas;
import io.kelta.mcp.tool.ToolHints;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composite admin tool: build a complete page layout (page-layout + sections + fields)
 * in a single Claude tool call.
 *
 * @deprecated translates its legacy field-name-keyed argument shape into {@link ApplyLayoutTool}'s
 * tree body and delegates there — kept only so existing callers don't break. New callers
 * should use {@code apply_layout} directly, which also supports related lists, a layout
 * header, and idempotent restructuring of an existing layout.
 */
@Component
@Deprecated
public class CreateLayoutTool implements AdminTool {

    private final ApplyLayoutTool applyLayoutTool;

    public CreateLayoutTool(ApplyLayoutTool applyLayoutTool) {
        this.applyLayoutTool = applyLayoutTool;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> sectionFieldItem = new LinkedHashMap<>();
        sectionFieldItem.put("type", "object");
        sectionFieldItem.put("description",
                "{\"fieldName\":\"...\",\"columnNumber\":0|1|2 (0-based),"
                + "\"readOnly\":bool,\"required\":bool,\"labelOverride\":\"...\"}");
        sectionFieldItem.put("additionalProperties", true);

        Map<String, Object> sectionFieldsArr = new LinkedHashMap<>();
        sectionFieldsArr.put("type", "array");
        sectionFieldsArr.put("description", "Fields in display order within this section.");
        sectionFieldsArr.put("items", sectionFieldItem);

        Map<String, Object> sectionItem = new LinkedHashMap<>();
        sectionItem.put("type", "object");
        sectionItem.put("description",
                "{\"sectionName\":\"...\",\"columns\":1-4,\"fields\":[{...}]}");
        Map<String, Object> sectionProps = new LinkedHashMap<>();
        sectionProps.put("sectionName", Schemas.string("Section heading."));
        sectionProps.put("columns", Schemas.integer("Column count (1-4, default 2).", 1, 4));
        sectionProps.put("fields", sectionFieldsArr);
        sectionItem.put("properties", sectionProps);
        sectionItem.put("additionalProperties", true);

        Map<String, Object> sectionsArr = new LinkedHashMap<>();
        sectionsArr.put("type", "array");
        sectionsArr.put("description", "Sections in display order. Each section contains its own field list.");
        sectionsArr.put("items", sectionItem);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Schemas.string("Layout name (unique within collection)."));
        properties.put("collectionName", Schemas.string("Collection this layout displays."));
        properties.put("isDefault", Schemas.bool("Whether this is the default layout for the collection.", false));
        properties.put("sections", sectionsArr);

        Tool tool = Tool.builder()
                .name("create_layout")
                .title("Create Layout")
                .description("[DEPRECATED — use apply_layout instead] Build a complete page layout "
                        + "in one call: layout + sections + fields per section. Field entries "
                        + "reference fields by fieldName. Delegates to apply_layout under the hood; "
                        + "apply_layout also supports related lists, a layout header, and idempotent "
                        + "restructuring of an existing layout by id.")
                .inputSchema(Schemas.object(properties, List.of("name", "collectionName", "sections")))
                .annotations(ToolHints.write(false, false))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();
                    Object n = args.get("name");
                    Object cn = args.get("collectionName");
                    Object s = args.get("sections");
                    if (n == null || n.toString().isBlank()
                            || cn == null || cn.toString().isBlank()) {
                        return error("Arguments \"name\" and \"collectionName\" are required.");
                    }
                    if (!(s instanceof List<?> sections)) {
                        return error("Argument \"sections\" must be an array.");
                    }
                    return applyLayoutTool.apply(translate(n.toString(), cn.toString(), args, sections));
                })
                .build();
    }

    /** Translates the legacy fieldName/sectionName/columnNumber shape into apply_layout's tree body. */
    private static Map<String, Object> translate(String name, String collectionName,
                                                  Map<String, Object> args, List<?> sections) {
        Map<String, Object> translated = new LinkedHashMap<>();
        translated.put("name", name);
        translated.put("collectionName", collectionName);
        if (args.get("isDefault") instanceof Boolean b) translated.put("isDefault", b);

        List<Object> translatedSections = new ArrayList<>();
        for (Object sectionObj : sections) {
            if (!(sectionObj instanceof Map<?, ?> sectionMap)) continue;
            Map<String, Object> section = new LinkedHashMap<>();
            Object heading = sectionMap.get("sectionName");
            section.put("heading", heading != null ? heading.toString() : "Section");
            if (sectionMap.get("columns") instanceof Number c) section.put("columns", c.intValue());

            List<Object> translatedFields = new ArrayList<>();
            if (sectionMap.get("fields") instanceof List<?> fields) {
                for (Object fieldObj : fields) {
                    if (!(fieldObj instanceof Map<?, ?> fieldMap)) continue;
                    Map<String, Object> field = new LinkedHashMap<>();
                    Object fieldName = fieldMap.get("fieldName");
                    if (fieldName == null) continue;
                    field.put("name", fieldName.toString());
                    if (fieldMap.get("columnNumber") instanceof Number c) field.put("column", c.intValue());
                    if (fieldMap.get("readOnly") instanceof Boolean b) field.put("readOnly", b);
                    if (fieldMap.get("required") instanceof Boolean b) field.put("required", b);
                    if (fieldMap.get("labelOverride") instanceof String lo && !lo.isBlank()) {
                        field.put("label", lo);
                    }
                    translatedFields.add(field);
                }
            }
            section.put("fields", translatedFields);
            translatedSections.add(section);
        }
        translated.put("sections", translatedSections);
        return translated;
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
