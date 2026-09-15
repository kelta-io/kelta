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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composite admin tool: build a complete page layout (page-layout +
 * layout-sections + per-section layout-fields) in a single Claude tool call.
 *
 * <p>Wraps three system-collection endpoints with the worker's actual
 * attribute shapes (kebab-case routes, {@code collectionId}/{@code layoutId}/
 * {@code sectionId} parents, {@code heading} for the section title, and
 * {@code fieldId} — resolved from each entry's {@code fieldName} — for layout
 * fields):
 * <ol>
 *   <li>POST /api/page-layouts — layout container</li>
 *   <li>POST /api/layout-sections — one per section, child of the layout</li>
 *   <li>POST /api/layout-fields — one per field, child of its section</li>
 * </ol>
 *
 * <p>The layout is created first; if it fails, no children are
 * attempted. If a section fails, its child fields are skipped but
 * other sections still proceed. The result documents which pieces
 * succeeded so a follow-up call can recover.
 */
@Component
public class CreateLayoutTool implements AdminTool {

    private final GatewayHttpClient gateway;
    private final AdminLookups lookups;

    public CreateLayoutTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
        this.lookups = new AdminLookups(gateway);
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> sectionFieldItem = new LinkedHashMap<>();
        sectionFieldItem.put("type", "object");
        sectionFieldItem.put("description",
                "{\"fieldName\":\"...\",\"sortOrder\":number,\"columnNumber\":0|1|2 (0-based),"
                + "\"columnSpan\":number,\"readOnly\":bool,\"required\":bool,\"labelOverride\":\"...\"}");
        sectionFieldItem.put("additionalProperties", true);

        Map<String, Object> sectionFieldsArr = new LinkedHashMap<>();
        sectionFieldsArr.put("type", "array");
        sectionFieldsArr.put("description", "Fields in display order within this section.");
        sectionFieldsArr.put("items", sectionFieldItem);

        Map<String, Object> sectionItem = new LinkedHashMap<>();
        sectionItem.put("type", "object");
        sectionItem.put("description",
                "{\"sectionName\":\"...\",\"columns\":1|2|3,\"sortOrder\":number,"
                + "\"fields\":[{...}]}");
        Map<String, Object> sectionProps = new LinkedHashMap<>();
        sectionProps.put("sectionName", Schemas.string("Section heading."));
        sectionProps.put("columns", Schemas.integer("Column count (1-3, default 2).", 1, 3));
        sectionProps.put("sortOrder", Schemas.integer("Display order (auto-assigned if omitted).", 0, null));
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
                .description("Build a complete page layout in one call: layout + sections + fields per section. Field entries reference fields by fieldName (resolved to ids). The layout is created first; sections and their fields follow. Returns a summary of what was created and any per-piece failures.")
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

                    String collectionId = lookups.collectionIdByName(cn.toString());
                    if (collectionId == null) {
                        return error("Collection \"" + cn + "\" not found.");
                    }
                    Map<String, String> fieldIds = lookups.fieldIdsByName(collectionId);

                    Map<String, Object> layoutAttrs = new LinkedHashMap<>();
                    layoutAttrs.put("collectionId", collectionId);
                    layoutAttrs.put("name", n.toString());
                    layoutAttrs.put("layoutType", "DETAIL");
                    if (args.get("isDefault") instanceof Boolean b) layoutAttrs.put("isDefault", b);

                    Map<String, Object> layoutBody = Map.of("data", Map.of(
                            "type", "page-layouts",
                            "attributes", layoutAttrs));

                    GatewayHttpClient.Response layoutRes;
                    try {
                        layoutRes = gateway.post("/api/page-layouts", layoutBody);
                    } catch (RuntimeException e) {
                        return McpErrorMapper.fromException(e);
                    }
                    if (!layoutRes.isSuccess()) {
                        return McpErrorMapper.toResult(layoutRes);
                    }
                    String layoutId = AdminLookups.firstResourceId(layoutRes.body());

                    List<Map<String, Object>> sectionResults = new ArrayList<>();
                    for (int i = 0; i < sections.size(); i++) {
                        sectionResults.add(createSection(layoutId, i, sections.get(i), fieldIds));
                    }

                    String summary = "{\n  \"layout\": " + layoutRes.body()
                            + ",\n  \"sections\": " + sectionResults
                            + "\n}";
                    return CallToolResult.builder()
                            .content(List.of(new TextContent(summary)))
                            .build();
                })
                .build();
    }

    private Map<String, Object> createSection(String layoutId, int index, Object sectionRaw,
                                              Map<String, String> fieldIds) {
        if (!(sectionRaw instanceof Map<?, ?> sectionMap)) {
            return Map.of("index", index, "error", "section entry must be an object");
        }
        Object heading = sectionMap.get("sectionName");
        Map<String, Object> sectionAttrs = new LinkedHashMap<>();
        if (layoutId != null) sectionAttrs.put("layoutId", layoutId);
        sectionAttrs.put("heading", heading != null ? heading.toString() : "Section " + (index + 1));
        sectionAttrs.put("columns", sectionMap.get("columns") instanceof Number c ? c.intValue() : 2);
        sectionAttrs.put("sortOrder", sectionMap.get("sortOrder") instanceof Number so ? so.intValue() : index);
        sectionAttrs.put("sectionType", "STANDARD");

        Map<String, Object> sectionBody = Map.of("data", Map.of(
                "type", "layout-sections",
                "attributes", sectionAttrs));
        GatewayHttpClient.Response sectionRes;
        try {
            sectionRes = gateway.post("/api/layout-sections", sectionBody);
        } catch (RuntimeException e) {
            return Map.of("index", index, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (!sectionRes.isSuccess()) {
            return Map.of(
                    "index", index,
                    "sectionName", sectionAttrs.get("heading"),
                    "status", sectionRes.status() == null ? 0 : sectionRes.status().value(),
                    "body", sectionRes.body());
        }

        String sectionId = AdminLookups.firstResourceId(sectionRes.body());
        Object fieldsRaw = sectionMap.get("fields");
        List<Map<String, Object>> fieldResults = new ArrayList<>();
        if (fieldsRaw instanceof List<?> fields) {
            int columns = (int) sectionAttrs.get("columns");
            for (int j = 0; j < fields.size(); j++) {
                fieldResults.add(createLayoutField(sectionId, j, columns, fields.get(j), fieldIds));
            }
        }
        return Map.of(
                "index", index,
                "sectionName", sectionAttrs.get("heading"),
                "status", sectionRes.status() == null ? 0 : sectionRes.status().value(),
                "fields", fieldResults);
    }

    private Map<String, Object> createLayoutField(String sectionId, int index, int columns,
                                                  Object fieldRaw, Map<String, String> fieldIds) {
        if (!(fieldRaw instanceof Map<?, ?> fieldMap)) {
            return Map.of("index", index, "error", "field entry must be an object");
        }
        Object fieldName = fieldMap.get("fieldName");
        String fieldId = fieldName != null ? fieldIds.get(fieldName.toString()) : null;
        if (fieldId == null) {
            return Map.of("index", index,
                    "fieldName", fieldName == null ? "?" : fieldName.toString(),
                    "error", "field not found on collection");
        }

        Map<String, Object> fieldAttrs = new LinkedHashMap<>();
        if (sectionId != null) fieldAttrs.put("sectionId", sectionId);
        fieldAttrs.put("fieldId", fieldId);
        fieldAttrs.put("sortOrder", fieldMap.get("sortOrder") instanceof Number so ? so.intValue() : index);
        fieldAttrs.put("columnNumber", fieldMap.get("columnNumber") instanceof Number c
                ? c.intValue() : index % Math.max(columns, 1));
        if (fieldMap.get("columnSpan") instanceof Number cs) fieldAttrs.put("columnSpan", cs.intValue());
        if (fieldMap.get("required") instanceof Boolean b) fieldAttrs.put("isRequiredOnLayout", b);
        if (fieldMap.get("readOnly") instanceof Boolean b) fieldAttrs.put("isReadOnlyOnLayout", b);
        if (fieldMap.get("labelOverride") instanceof String lo && !lo.isBlank()) fieldAttrs.put("labelOverride", lo);

        Map<String, Object> body = Map.of("data", Map.of(
                "type", "layout-fields",
                "attributes", fieldAttrs));
        GatewayHttpClient.Response fr;
        try {
            fr = gateway.post("/api/layout-fields", body);
        } catch (RuntimeException e) {
            return Map.of("index", index, "error", e.getClass().getSimpleName());
        }
        return Map.of(
                "index", index,
                "fieldName", fieldName.toString(),
                "status", fr.status() == null ? 0 : fr.status().value(),
                "body", fr.isSuccess() ? "ok" : fr.body());
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
