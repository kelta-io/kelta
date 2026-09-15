package io.kelta.mcp.tool.user;

import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.error.McpErrorMapper;
import io.kelta.mcp.tool.Schemas;
import io.kelta.mcp.tool.ToolHints;
import io.kelta.mcp.tool.UserTool;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class QueryCollectionTool implements UserTool {

    /**
     * Operators the worker actually honors — mirrors
     * {@code io.kelta.runtime.query.FilterOperator}, whose {@code parse}
     * rejects (rather than silently drops) any other token. We mirror the
     * accepted set here so the MCP boundary rejects unsupported operators
     * with a clear message instead of forwarding them to the gateway, which
     * would 400 with less actionable context for an LLM consumer.
     *
     * <p>Notable absences vs. common JSON:API conventions:
     * <ul>
     *   <li>{@code NIN} — no negated-IN on the worker; combine with a
     *       separate query and diff client-side.</li>
     *   <li>{@code BETWEEN} — combine {@code GTE} + {@code LTE} on the same
     *       field.</li>
     *   <li>{@code STARTSWITH} / {@code ENDSWITH} / {@code IS_NULL} — wrong
     *       spellings; the enum names are {@code STARTS} / {@code ENDS} /
     *       {@code ISNULL}.</li>
     * </ul>
     */
    private static final List<String> ALLOWED_OPS = List.of(
            "EQ", "NEQ", "GT", "GTE", "LT", "LTE",
            "CONTAINS", "STARTS", "ENDS",
            "ICONTAINS", "ISTARTS", "IENDS", "IEQ",
            "ISNULL", "IN");

    private final GatewayHttpClient gateway;

    public QueryCollectionTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> filter = Schemas.freeObject("""
                { field: { OP: value } }. Ops on one field AND together (ranges); fields AND across; NO OR.
                Supported: EQ NEQ GT GTE LT LTE CONTAINS STARTS ENDS ICONTAINS ISTARTS IENDS IEQ ISNULL IN.
                IN value is a CSV string or array, e.g. "a,b" or ["a","b"].
                Workarounds: BETWEEN→GTE+LTE; STARTSWITH/ENDSWITH→STARTS/ENDS; IS_NULL→ISNULL; OR→multiple queries.
                Ex: { "status": { "EQ": "ACTIVE" }, "createdAt": { "GTE": "2026-01-01T00:00:00Z" } }""");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("collection", Schemas.string(
                "Collection name from `list_collections` (e.g. \"customers\")."));
        properties.put("filter", filter);
        properties.put("sort", Schemas.string(
                "CSV sort keys; '-' prefix = desc. Ex: \"-createdAt,lastName\"."));
        properties.put("pageSize", Schemas.integer(
                "Records per page (default 20, max 200). Use 1 to fetch only meta.totalCount.", 1, 200));
        properties.put("pageNumber", Schemas.integer(
                "1-based page index (default 1).", 1, null));
        properties.put("fields", Schemas.string(
                "Sparse fieldset — CSV attribute names. Default: all. Ex: \"firstName,lastName,email\"."));
        properties.put("include", Schemas.string(
                "CSV relationship names to eager-load (returned in top-level included[]). Ex: \"account,owner\"."));

        Tool tool = Tool.builder()
                .name("query_collection")
                .title("Query Collection")
                .description("""
                        List records from a Kelta collection (GET /api/{collection}). For one \
                        record by id use `get_record`; for full-text use `search`; for field \
                        names use `get_collection_schema`.

                        Filter ops: EQ NEQ GT GTE LT LTE CONTAINS STARTS ENDS ICONTAINS ISTARTS \
                        IENDS IEQ ISNULL IN. Multiple ops on one field AND together (ranges); \
                        fields AND across; NO OR. Rejected names map to workarounds — full list \
                        in the `filter` arg.

                        Returns JSON:API: data[], optional included[], meta.totalCount, \
                        links.next/prev.

                        Examples:
                          // count only — read meta.totalCount
                          { "collection": "customers", "pageSize": 1 }

                          // BETWEEN-style range on one field
                          { "collection": "orders",
                            "filter": { "total": { "GTE": 100, "LTE": 1000 } } }

                          // active customers, newest first, with related account
                          { "collection": "customers",
                            "filter":  { "status": { "EQ": "ACTIVE" } },
                            "sort":    "-createdAt",
                            "include": "account",
                            "pageSize": 25 }
                        """)
                .inputSchema(Schemas.object(properties, List.of("collection")))
                .annotations(ToolHints.read())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) args = Map.of();
                    Object cv = args.get("collection");
                    if (cv == null || cv.toString().isBlank()) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent("Argument \"collection\" is required.")))
                                .build();
                    }
                    String opError = validateFilterOperators(args.get("filter"));
                    if (opError != null) {
                        return CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new TextContent(opError)))
                                .build();
                    }
                    String collection = cv.toString();
                    String query = buildQueryString(args);
                    String path = "/api/" + URLEncoder.encode(collection, StandardCharsets.UTF_8)
                            + (query.isEmpty() ? "" : "?" + query);
                    try {
                        return McpErrorMapper.toResult(gateway.get(path));
                    } catch (RuntimeException e) {
                        return McpErrorMapper.fromException(e);
                    }
                })
                .build();
    }

    /**
     * Returns a user-facing error message if {@code filter} contains an
     * operator outside {@link #ALLOWED_OPS}, otherwise null. Validating at
     * the MCP boundary turns a silent worker drop (which produces wrong
     * results) into an actionable error the caller can correct.
     */
    static String validateFilterOperators(Object filter) {
        if (!(filter instanceof Map<?, ?> filterMap)) return null;
        for (Map.Entry<?, ?> e : filterMap.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> ops)) continue;
            for (Object opKey : ops.keySet()) {
                String op = String.valueOf(opKey).toUpperCase(Locale.ROOT);
                if (!ALLOWED_OPS.contains(op)) {
                    return "Unsupported filter operator \"" + opKey + "\" on field \""
                            + e.getKey() + "\". Supported: " + ALLOWED_OPS
                            + ". Workarounds — ranges: GTE+LTE on one field; "
                            + "case-insensitive: ICONTAINS / ISTARTS / IENDS / IEQ; "
                            + "null check: ISNULL with value \"true\"/\"false\"; "
                            + "multi-value match: IN with a CSV or array value; "
                            + "OR is not supported in a single query — "
                            + "run multiple calls and union client-side.";
                }
            }
        }
        return null;
    }

    static String buildQueryString(Map<String, Object> args) {
        List<String> parts = new ArrayList<>();
        Object filter = args.get("filter");
        if (filter instanceof Map<?, ?> filterMap) {
            for (Map.Entry<?, ?> e : filterMap.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> ops)) continue;
                String fieldName = String.valueOf(e.getKey());
                for (Map.Entry<?, ?> op : ops.entrySet()) {
                    String opName = String.valueOf(op.getKey()).toUpperCase(Locale.ROOT);
                    if (!ALLOWED_OPS.contains(opName)) continue;
                    if ("IN".equals(opName)) {
                        String csv = inValues(op.getValue()).stream()
                                .map(QueryCollectionTool::enc)
                                .collect(Collectors.joining(","));
                        if (!csv.isEmpty()) {
                            parts.add("filter[" + enc(fieldName) + "][in]=" + csv);
                        }
                    } else {
                        parts.add("filter[" + enc(fieldName) + "][" + opName + "]=" + enc(String.valueOf(op.getValue())));
                    }
                }
            }
        }
        Object sort = args.get("sort");
        if (sort != null && !sort.toString().isBlank()) {
            parts.add("sort=" + enc(sort.toString()));
        }
        Object pageSize = args.get("pageSize");
        if (pageSize != null) parts.add("page[size]=" + enc(pageSize.toString()));
        Object pageNumber = args.get("pageNumber");
        if (pageNumber != null) parts.add("page[number]=" + enc(pageNumber.toString()));
        Object fields = args.get("fields");
        if (fields != null && !fields.toString().isBlank()) parts.add("fields=" + enc(fields.toString()));
        Object include = args.get("include");
        if (include != null && !include.toString().isBlank()) parts.add("include=" + enc(include.toString()));
        return String.join("&", parts);
    }

    /**
     * Normalizes an {@code IN} operator value into individual string values.
     * Accepts either a CSV string ({@code "a,b"}) or an array
     * ({@code ["a", "b"]}); blanks are trimmed and dropped.
     */
    private static List<String> inValues(Object value) {
        if (value == null) return List.of();
        List<Object> raw = value instanceof Collection<?> coll
                ? new ArrayList<>(coll)
                : List.of(value);
        List<String> values = new ArrayList<>();
        for (Object item : raw) {
            if (item == null) continue;
            for (String part : String.valueOf(item).split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) values.add(trimmed);
            }
        }
        return values;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
