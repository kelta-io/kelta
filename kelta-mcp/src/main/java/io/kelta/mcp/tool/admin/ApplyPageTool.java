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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Idempotent create-or-update for a {@code ui-pages} row, keyed on {@code path} (falling back to
 * {@code slug} when the path itself doesn't match an existing row — e.g. the page's route is
 * being renamed but its identity, the slug, stays the same).
 *
 * <p>{@code config} is free JSON checked only by {@code UiPageConfigValidator} (KLT-217) — an
 * unknown widget type, an out-of-range data source limit, an unsupported filter operator, and so
 * on. Rather than re-deriving that grammar client-side, this tool dry-runs the whole desired
 * {@code config} through the worker's own {@code POST /api/ui-pages/validate}
 * <strong>before creating or updating the page row at all</strong> — an invalid config fails the
 * whole call with a structured error (JSON Pointer from the validate response) and nothing is
 * written, not even a brand-new page.
 *
 * <p>{@code isHomePage} is not a top-level argument — it round-trips inside {@code config}
 * (see {@code kelta://docs/ui-pages}) exactly as the worker stores it.
 */
@Component
public class ApplyPageTool implements AdminTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MINIMAL_EXAMPLE_CONFIG =
            "{\"schemaVersion\":2,\"dataSources\":[{\"name\":\"accounts\",\"collection\":"
            + "\"accounts\",\"mode\":\"list\",\"limit\":50}],\"components\":[{\"id\":\"h1\","
            + "\"type\":\"heading\",\"props\":{\"text\":\"Accounts\",\"level\":\"h2\"}},"
            + "{\"id\":\"r1\",\"type\":\"repeater\",\"props\":{\"source\":{\"$bind\":"
            + "\"data.accounts\"}},\"children\":[{\"id\":\"t1\",\"type\":\"text\",\"props\":"
            + "{\"content\":\"{{ item.name }}\"}}]}]}";

    private final GatewayHttpClient gateway;
    private final AdminLookups lookups;

    public ApplyPageTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
        this.lookups = new AdminLookups(gateway);
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Schemas.string("Human-readable page name."));
        properties.put("path", Schemas.string(
                "Route the page is served at, e.g. \"/accounts\". The natural key this call "
                        + "upserts by (falls back to slug when path itself doesn't match an "
                        + "existing row)."));
        properties.put("slug", Schemas.string(
                "URL-safe identifier. Auto-derived from name/path when omitted on create; also "
                        + "used as a fallback match key if path changed since the last apply."));
        properties.put("title", Schemas.string("Browser and header title."));
        properties.put("config", Schemas.freeObject(
                "JSON page definition: components, dataSources, variables, access, isHomePage. "
                        + "Checked by POST /api/ui-pages/validate before anything is written — see "
                        + "kelta://docs/ui-pages. Example (heading + repeater over a data source): "
                        + MINIMAL_EXAMPLE_CONFIG));
        properties.put("published", Schemas.bool("Whether the page is live for end users.", false));
        properties.put("active", Schemas.bool("Whether the record is active.", true));

        Tool tool = Tool.builder()
                .name("apply_page")
                .title("Apply Page")
                .description(
                        "Create-or-update a custom UI page (screen builder), keyed on path (falling "
                        + "back to slug). Idempotent: applying the identical body twice reports "
                        + "{\"action\":\"unchanged\",...} on the second call; changing config (or "
                        + "another attribute) reports {\"action\":\"updated\",\"changed\":[...]} naming "
                        + "only the keys that differ. config is dry-run through POST "
                        + "/api/ui-pages/validate before anything is written — an unknown widget type "
                        + "or other invalid config fails the whole call with a structured error "
                        + "(JSON Pointer from the validate response) and writes nothing. Minimal "
                        + "example — a heading plus a repeater over a data source: {\"name\":"
                        + "\"Accounts\",\"path\":\"/accounts\",\"config\":" + MINIMAL_EXAMPLE_CONFIG
                        + "}. Wraps GET+POST/PATCH /api/ui-pages plus a dry-run POST "
                        + "/api/ui-pages/validate.")
                .inputSchema(Schemas.object(properties, List.of("name", "path")))
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

    @SuppressWarnings("unchecked")
    CallToolResult apply(Map<String, Object> args) {
        Object n = args.get("name");
        if (n == null || n.toString().isBlank()) {
            return error("Argument \"name\" is required.");
        }
        Object p = args.get("path");
        if (p == null || p.toString().isBlank()) {
            return error("Argument \"path\" is required.");
        }
        String name = n.toString();
        String path = p.toString();
        String slug = args.get("slug") instanceof String s && !s.isBlank() ? s : null;
        String title = args.get("title") instanceof String t && !t.isBlank() ? t : null;
        Map<String, Object> config = args.get("config") instanceof Map<?, ?> c
                ? (Map<String, Object>) c : Map.of();

        try {
            CallToolResult validationError = validateConfig(config);
            if (validationError != null) {
                return validationError;
            }

            Map<String, Object> desired = new LinkedHashMap<>();
            desired.put("name", name);
            desired.put("path", path);
            if (slug != null) desired.put("slug", slug);
            if (title != null) desired.put("title", title);
            desired.put("config", config);
            if (args.get("published") instanceof Boolean pub) desired.put("published", pub);
            if (args.get("active") instanceof Boolean act) desired.put("active", act);

            String existingId = lookups.idByNaturalKey("ui-pages", Map.of("path", path));
            if (existingId == null && slug != null) {
                existingId = lookups.idByNaturalKey("ui-pages", Map.of("slug", slug));
            }

            String id;
            String action;
            List<String> changed = List.of();
            if (existingId == null) {
                id = createPage(desired);
                action = "created";
            } else {
                Map<String, Object> current = lookups.readAttributes("ui-pages", existingId);
                changed = AdminLookups.diff(current, desired);
                if (changed.isEmpty()) {
                    id = existingId;
                    action = "unchanged";
                } else {
                    Map<String, Object> patchAttrs = new LinkedHashMap<>();
                    for (String key : changed) patchAttrs.put(key, desired.get(key));
                    patchPage(existingId, patchAttrs);
                    id = existingId;
                    action = "updated";
                }
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", action);
            body.put("id", id);
            if (!changed.isEmpty()) body.put("changed", changed);

            return CallToolResult.builder()
                    .content(List.of(new TextContent(MAPPER.writeValueAsString(body))))
                    .build();
        } catch (AdminLookups.GatewayFailure e) {
            return McpErrorMapper.toResult(e.response);
        } catch (RuntimeException e) {
            return McpErrorMapper.fromException(e);
        }
    }

    /**
     * Dry-runs {@code config} through the worker's {@code POST /api/ui-pages/validate} — the
     * same {@code UiPageConfigValidator} the write-path before-save hook runs — before this tool
     * issues a create or update. Returns a structured error (pointer = the validate response's
     * JSON Pointer {@code path}) when the config has any {@code error}-severity problem, or
     * {@code null} when it would save.
     */
    private CallToolResult validateConfig(Map<String, Object> config) {
        GatewayHttpClient.Response response = gateway.post("/api/ui-pages/validate", Map.of("config", config));
        if (!response.isSuccess()) {
            throw new AdminLookups.GatewayFailure(response);
        }
        JsonNode root = MAPPER.readTree(response.body());
        if (root.path("valid").asBoolean(true)) {
            return null;
        }

        List<Object> jsonApiErrors = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        for (JsonNode err : root.path("errors")) {
            String pointer = err.path("path").asString("");
            String message = err.path("message").asString("Invalid config");
            String severity = err.path("severity").asString("error");

            Map<String, Object> jsonApiError = new LinkedHashMap<>();
            jsonApiError.put("status", "400");
            jsonApiError.put("code", "VALIDATION_FAILED");
            jsonApiError.put("title", "Validation Error");
            jsonApiError.put("detail", message);
            jsonApiError.put("source", Map.of("pointer", pointer));
            jsonApiError.put("meta", Map.of("severity", severity));
            jsonApiErrors.add(jsonApiError);
            messages.add((pointer.isBlank() ? "" : pointer + ": ") + message);
        }

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("status", 400);
        structured.put("errors", jsonApiErrors);

        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(String.join("; ", messages))))
                .structuredContent(structured)
                .build();
    }

    private String createPage(Map<String, Object> attributes) {
        Map<String, Object> body = Map.of("data", Map.of("type", "ui-pages", "attributes", attributes));
        GatewayHttpClient.Response response = gateway.post("/api/ui-pages", body);
        if (!response.isSuccess()) {
            throw new AdminLookups.GatewayFailure(response);
        }
        return AdminLookups.firstResourceId(response.body());
    }

    private void patchPage(String id, Map<String, Object> attributes) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "ui-pages", "id", id, "attributes", attributes));
        String path = "/api/ui-pages/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        GatewayHttpClient.Response response = gateway.patch(path, body);
        if (!response.isSuccess()) {
            throw new AdminLookups.GatewayFailure(response);
        }
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(message)))
                .build();
    }
}
