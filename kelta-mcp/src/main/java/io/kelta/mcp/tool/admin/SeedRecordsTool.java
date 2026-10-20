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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk-creates seed records in the endpoint's tenant from plain attribute
 * objects, the shape a template's {@code seeds/<collection>.json} holds.
 *
 * <p>A reference field may carry the parent's natural key instead of its id:
 * {@code references: {"account": "accounts.name"}} resolves each record's
 * {@code account} value to the id of the {@code accounts} record whose
 * {@code name} equals it. Every key is resolved before anything is written, so
 * an unknown key fails the call with no record created.
 *
 * <p>Records go to {@code POST /api/operations} (JSON:API Atomic Operations) in
 * batches of {@value #BATCH_SIZE}, the worker's default
 * {@code kelta.api.max-batch-operations}. Each batch is all-or-nothing; a
 * failure in a later batch reports how many records the earlier batches created.
 */
@Component
public class SeedRecordsTool implements AdminTool {

    static final int BATCH_SIZE = 100;
    static final int MAX_RECORDS = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewayHttpClient gateway;
    private final AdminLookups lookups;

    public SeedRecordsTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
        this.lookups = new AdminLookups(gateway);
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> recordItem = new LinkedHashMap<>();
        recordItem.put("type", "object");
        recordItem.put("description", "Attribute object of one record, keyed by field name.");
        recordItem.put("additionalProperties", true);

        Map<String, Object> recordsArray = new LinkedHashMap<>();
        recordsArray.put("type", "array");
        recordsArray.put("description", "Records to create, in order (1-" + MAX_RECORDS + ").");
        recordsArray.put("items", recordItem);
        recordsArray.put("minItems", 1);
        recordsArray.put("maxItems", MAX_RECORDS);

        Map<String, Object> references = new LinkedHashMap<>();
        references.put("type", "object");
        references.put("description",
                "Optional natural-key resolution for reference fields: {\"<field>\": \"<parentCollection>.<keyField>\"}. "
                + "Each record's <field> value is replaced by the id of the parent record whose <keyField> equals it. "
                + "Seed parents before children.");
        references.put("additionalProperties", Map.of("type", "string"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("collection", Schemas.string("Collection name to create the records in."));
        properties.put("records", recordsArray);
        properties.put("references", references);

        Tool tool = Tool.builder()
                .name("seed_records")
                .title("Seed Records")
                .description("Bulk-create records in this tenant from plain attribute objects (e.g. a template's "
                        + "seed file), resolving reference fields from a parent's natural key. Example: "
                        + "{\"collection\":\"contacts\",\"records\":[{\"email\":\"a@example.com\","
                        + "\"account\":\"Northwind\"}],\"references\":{\"account\":\"accounts.name\"}}. "
                        + "Wraps POST /api/operations in batches of " + BATCH_SIZE + " (each atomic). "
                        + "Not idempotent: a second call creates the records again, or fails on a unique field.")
                .inputSchema(Schemas.object(properties, List.of("collection", "records")))
                .annotations(ToolHints.write(false, false))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    return seed(args == null ? Map.of() : args);
                })
                .build();
    }

    CallToolResult seed(Map<String, Object> args) {
        if (!(args.get("collection") instanceof String collection) || collection.isBlank()) {
            return error("Argument \"collection\" is required.");
        }
        if (!(args.get("records") instanceof List<?> records) || records.isEmpty()) {
            return error("Argument \"records\" must be a non-empty array.");
        }
        if (records.size() > MAX_RECORDS) {
            return error("At most " + MAX_RECORDS + " records per call; got " + records.size() + ".");
        }
        for (int i = 0; i < records.size(); i++) {
            if (!(records.get(i) instanceof Map<?, ?>)) {
                return error("records[" + i + "] must be an object.");
            }
        }
        Map<String, String[]> refs = new LinkedHashMap<>();
        if (args.get("references") instanceof Map<?, ?> refMap) {
            for (Map.Entry<?, ?> entry : refMap.entrySet()) {
                String[] parts = entry.getValue() == null ? new String[0] : entry.getValue().toString().split("\\.", 2);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    return error("references." + entry.getKey()
                            + " must be \"<parentCollection>.<keyField>\", got \"" + entry.getValue() + "\".");
                }
                refs.put(entry.getKey().toString(), parts);
            }
        }

        try {
            List<Map<String, Object>> resolved = new ArrayList<>(records.size());
            Map<String, String> idCache = new HashMap<>();
            for (int i = 0; i < records.size(); i++) {
                Map<String, Object> attrs = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) records.get(i)).entrySet()) {
                    attrs.put(entry.getKey().toString(), entry.getValue());
                }
                for (Map.Entry<String, String[]> ref : refs.entrySet()) {
                    Object key = attrs.get(ref.getKey());
                    if (key == null) continue;
                    String parent = ref.getValue()[0];
                    String keyField = ref.getValue()[1];
                    String cacheKey = parent + "\u0000" + keyField + "\u0000" + key;
                    String id = idCache.computeIfAbsent(cacheKey,
                            k -> lookups.idByNaturalKey(parent, Map.of(keyField, key)));
                    if (id == null) {
                        return error("records[" + i + "]." + ref.getKey() + ": no " + parent + " record with "
                                + keyField + " \"" + key + "\". Nothing was created.");
                    }
                    attrs.put(ref.getKey(), id);
                }
                resolved.add(attrs);
            }

            List<String> ids = new ArrayList<>(resolved.size());
            for (int start = 0; start < resolved.size(); start += BATCH_SIZE) {
                List<Map<String, Object>> ops = new ArrayList<>();
                for (Map<String, Object> attrs : resolved.subList(start, Math.min(start + BATCH_SIZE, resolved.size()))) {
                    ops.add(Map.of("op", "add", "data", Map.of("type", collection, "attributes", attrs)));
                }
                GatewayHttpClient.Response response = gateway.post("/api/operations", Map.of("atomic:operations", ops));
                if (!response.isSuccess()) {
                    return start == 0 ? McpErrorMapper.toResult(response) : partialFailure(response, ids, resolved.size());
                }
                ids.addAll(createdIds(response.body()));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("collection", collection);
            body.put("created", ids.size());
            body.put("ids", ids);
            return CallToolResult.builder()
                    .content(List.of(new TextContent(MAPPER.writeValueAsString(body))))
                    .build();
        } catch (AdminLookups.GatewayFailure e) {
            return McpErrorMapper.toResult(e.response);
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }
    }

    private static List<String> createdIds(String body) {
        List<String> ids = new ArrayList<>();
        if (body == null || body.isBlank()) return ids;
        for (JsonNode result : MAPPER.readTree(body).path("atomic:results")) {
            JsonNode id = result.path("data").path("id");
            if (!id.isMissingNode() && !id.isNull()) ids.add(id.asString());
        }
        return ids;
    }

    /** A later batch failed: the earlier batches are committed, so say how many records exist. */
    private static CallToolResult partialFailure(GatewayHttpClient.Response response, List<String> ids, int total) {
        CallToolResult mapped = McpErrorMapper.toResult(response);
        String detail = mapped.content().get(0) instanceof TextContent text ? text.text() : "";
        Map<String, Object> structured = new LinkedHashMap<>();
        if (mapped.structuredContent() instanceof Map<?, ?> m) {
            m.forEach((k, v) -> structured.put(k.toString(), v));
        }
        structured.put("created", ids.size());
        structured.put("ids", ids);
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent("Created " + ids.size() + " of " + total
                        + " records before a batch failed (earlier batches are committed): " + detail)))
                .structuredContent(structured)
                .build();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
