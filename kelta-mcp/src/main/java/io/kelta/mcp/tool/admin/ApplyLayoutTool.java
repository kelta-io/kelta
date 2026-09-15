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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a whole page layout — sections, field placements and related lists — in one
 * gateway call, wrapping the worker's {@code PUT .../tree} endpoint (KLT-214):
 * <ul>
 *   <li>{@code PUT /api/page-layouts/{layoutId}/tree} when {@code layoutId} is given.</li>
 *   <li>{@code PUT /api/collections/{collectionName}/layouts/{name}/tree} otherwise —
 *       creates the layout row if it does not exist yet, updates it if it does.</li>
 * </ul>
 *
 * <p>The tree endpoint is a full diff-and-apply: reapplying the exact same body is a
 * no-op ({@code created=0, updated=0, deleted=0}), and the worker does all the
 * name→id resolution and validation server-side, so this tool makes exactly one
 * gateway call per apply — no client-side lookups.
 *
 * <p>One client-side transform: a field placement without an explicit {@code column}
 * is assigned {@code index % columns} (0-based) within its section, so "just list the
 * fields" produces a sensible left-to-right, top-to-bottom layout instead of every
 * field landing in column 0 (the tree endpoint's own default for an omitted column).
 */
@Component
public class ApplyLayoutTool implements AdminTool {

    private final GatewayHttpClient gateway;

    public ApplyLayoutTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> fieldItem = new LinkedHashMap<>();
        fieldItem.put("type", "object");
        Map<String, Object> fieldProps = new LinkedHashMap<>();
        fieldProps.put("name", Schemas.string("Field name on the collection."));
        fieldProps.put("column", Schemas.integer(
                "0-based column within the section. Omit to auto-assign left-to-right "
                        + "(index % columns).", 0, null));
        fieldProps.put("label", Schemas.string("Label override for this placement."));
        fieldProps.put("helpText", Schemas.string("Help text override for this placement."));
        fieldProps.put("readOnly", Schemas.bool("Read-only on this layout.", false));
        fieldProps.put("required", Schemas.bool("Required on this layout.", false));
        fieldItem.put("properties", fieldProps);
        fieldItem.put("additionalProperties", false);

        Map<String, Object> fieldsArr = new LinkedHashMap<>();
        fieldsArr.put("type", "array");
        fieldsArr.put("description", "Field placements in display order.");
        fieldsArr.put("items", fieldItem);

        Map<String, Object> sectionItem = new LinkedHashMap<>();
        sectionItem.put("type", "object");
        Map<String, Object> sectionProps = new LinkedHashMap<>();
        sectionProps.put("heading", Schemas.string(
                "Section heading — sections are matched on this across repeated applies."));
        sectionProps.put("columns", Schemas.integer("Column count (1-4, default 2).", 1, 4));
        sectionProps.put("collapsed", Schemas.bool("Collapsed by default.", false));
        sectionProps.put("fields", fieldsArr);
        sectionItem.put("properties", sectionProps);
        sectionItem.put("additionalProperties", false);

        Map<String, Object> sectionsArr = new LinkedHashMap<>();
        sectionsArr.put("type", "array");
        sectionsArr.put("description",
                "Sections in display order. Any existing section/field/related-list not listed "
                        + "here is deleted — the body is the whole desired layout, not a patch.");
        sectionsArr.put("items", sectionItem);

        Map<String, Object> relatedItem = new LinkedHashMap<>();
        relatedItem.put("type", "object");
        Map<String, Object> relatedProps = new LinkedHashMap<>();
        relatedProps.put("collection", Schemas.string("Related collection name."));
        relatedProps.put("relationshipField", Schemas.string(
                "Lookup field on the related collection that points back at this layout's collection."));
        relatedProps.put("displayColumns", Schemas.freeObject(
                "Array of field names on the related collection shown as columns."));
        relatedProps.put("sortField", Schemas.string("Field name to sort the related list by."));
        relatedProps.put("sortDirection", Schemas.string("ASC or DESC."));
        relatedProps.put("rowLimit", Schemas.integer("Max rows shown (default 10).", 1, null));
        relatedItem.put("properties", relatedProps);
        relatedItem.put("additionalProperties", false);

        Map<String, Object> relatedArr = new LinkedHashMap<>();
        relatedArr.put("type", "array");
        relatedArr.put("description",
                "Related lists shown on this layout. Omit the key entirely to leave existing "
                        + "related lists untouched; an empty array removes them all.");
        relatedArr.put("items", relatedItem);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("layoutId", Schemas.string(
                "Existing layout id (UUID). Omit to address the layout by collectionName + name "
                        + "instead — that path creates the layout if it doesn't exist yet."));
        properties.put("collectionName", Schemas.string(
                "Collection this layout displays. Required when layoutId is omitted."));
        properties.put("name", Schemas.string("Layout name."));
        properties.put("layoutType", Schemas.string("Layout type (default DETAIL, only used on create)."));
        properties.put("isDefault", Schemas.bool("Whether this is the default layout for the collection.", false));
        properties.put("description", Schemas.string("Layout description."));
        properties.put("headerConfig", Schemas.freeObject("Header block configuration."));
        properties.put("sections", sectionsArr);
        properties.put("relatedLists", relatedArr);

        Tool tool = Tool.builder()
                .name("apply_layout")
                .title("Apply Layout")
                .description(
                        "Apply a page layout's full structure (sections, field placements, related "
                        + "lists) in one call. Idempotent: applying the identical body twice reports "
                        + "created=0, updated=0, deleted=0 on the second call. Anything not listed "
                        + "in sections/relatedLists is removed — this replaces the layout's structure, "
                        + "it does not patch it. Example: {\"collectionName\":\"projects\",\"name\":"
                        + "\"Main\",\"sections\":[{\"heading\":\"Overview\",\"columns\":2,\"fields\":"
                        + "[{\"name\":\"name\"},{\"name\":\"owner\"},{\"name\":\"status\"}]}]} places "
                        + "name/owner/status at columns 0,1,0. To restructure an existing layout, pass "
                        + "its layoutId instead of collectionName. Deprecates create_layout.")
                .inputSchema(Schemas.object(properties, List.of("sections")))
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

    /**
     * Builds and sends the tree PUT for the given friendly args — shared with
     * {@link CreateLayoutTool}, which translates its legacy argument shape into this
     * one and delegates here instead of issuing its own gateway calls.
     */
    CallToolResult apply(Map<String, Object> args) {
        Object layoutIdArg = args.get("layoutId");
        String layoutId = layoutIdArg instanceof String s && !s.isBlank() ? s : null;
        Object collectionNameArg = args.get("collectionName");
        String collectionName = collectionNameArg instanceof String s && !s.isBlank() ? s : null;
        Object nameArg = args.get("name");
        String name = nameArg instanceof String s && !s.isBlank() ? s : null;

        if (!(args.get("sections") instanceof List<?>)) {
            return error("Argument \"sections\" must be an array.");
        }
        if (layoutId == null && (collectionName == null || name == null)) {
            return error("Provide \"layoutId\", or both \"collectionName\" and \"name\".");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (name != null) body.put("name", name);
        if (args.get("layoutType") instanceof String s && !s.isBlank()) body.put("layoutType", s);
        if (args.get("isDefault") instanceof Boolean b) body.put("isDefault", b);
        if (args.get("description") instanceof String s) body.put("description", s);
        if (args.get("headerConfig") != null) body.put("headerConfig", args.get("headerConfig"));
        body.put("sections", withDefaultColumns(args.get("sections")));
        if (args.get("relatedLists") instanceof List<?> relatedLists) body.put("relatedLists", relatedLists);

        String path = layoutId != null
                ? "/api/page-layouts/" + URLEncoder.encode(layoutId, StandardCharsets.UTF_8) + "/tree"
                : "/api/collections/" + URLEncoder.encode(collectionName, StandardCharsets.UTF_8)
                        + "/layouts/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/tree";

        GatewayHttpClient.Response response;
        try {
            response = gateway.put(path, body);
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }
        return McpErrorMapper.toResult(response);
    }

    /**
     * Fills in a 0-based {@code column} (index % columns) for every field placement
     * that doesn't already specify one — the tree endpoint itself defaults an omitted
     * column to a flat 0, which would stack every field in the first column.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> withDefaultColumns(Object sectionsRaw) {
        List<Object> result = new ArrayList<>();
        if (!(sectionsRaw instanceof List<?> sections)) {
            return result;
        }
        for (Object sectionObj : sections) {
            if (!(sectionObj instanceof Map<?, ?> sectionMap)) {
                result.add(sectionObj);
                continue;
            }
            Map<String, Object> section = new LinkedHashMap<>((Map<String, Object>) sectionMap);
            int columns = section.get("columns") instanceof Number n ? Math.max(n.intValue(), 1) : 2;
            if (section.get("fields") instanceof List<?> fields) {
                List<Object> newFields = new ArrayList<>();
                for (int i = 0; i < fields.size(); i++) {
                    Object fieldObj = fields.get(i);
                    if (fieldObj instanceof Map<?, ?> fieldMap) {
                        Map<String, Object> field = new LinkedHashMap<>((Map<String, Object>) fieldMap);
                        if (!(field.get("column") instanceof Number)) {
                            field.put("column", i % columns);
                        }
                        newFields.add(field);
                    } else {
                        newFields.add(fieldObj);
                    }
                }
                section.put("fields", newFields);
            }
            result.add(section);
        }
        return result;
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
