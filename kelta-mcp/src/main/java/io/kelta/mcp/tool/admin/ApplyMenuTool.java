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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Idempotent create-or-update for a UI menu ("app") and its items/groups in one call, keyed on
 * {@code name} for the menu (via {@link AdminLookups#upsert}) and {@code (menu, parent, label)}
 * for each item — resolved locally against one {@link AdminLookups#list} of the menu's existing
 * items rather than a remote lookup per item, since a top-level item's natural parent is
 * {@code null} and {@code AdminLookups.upsert}'s remote {@code filter[parentId][eq]=...} has no
 * way to ask for "is null".
 *
 * <p>An item with a non-empty {@code children} array is a group header (its own {@code path}, if
 * any, is written but ignored by the nav renderer — group headers organize, they don't
 * navigate); nesting is one level deep, matching {@code ui-menus.md}. Each child's {@code
 * parentId} is resolved to the group's id (existing or freshly created) before the child is
 * applied. {@code displayOrder} is always derived from position, never taken from input.
 *
 * <p>{@code path} is validated against the end-user shell's nav grammar
 * ({@code navTabs.ts}) client-side, before any gateway call — an invalid path fails the whole
 * apply with a structured error and writes nothing, rather than silently storing an item that
 * would never appear in the rendered nav.
 *
 * <p>{@code prune:true} hard-deletes any existing item absent from this call's tree (unlike
 * {@code apply_picklist}, which only deactivates); omitted or {@code false} reports those items
 * as {@code stale} instead of touching them.
 */
@Component
public class ApplyMenuTool implements AdminTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** `/resources/<collection>`, optionally followed by a `?query` — see navTabs.ts parseResourcePath. */
    private static final Pattern RESOURCE_PATH = Pattern.compile("^/resources/[^/?]+");
    /** `/p/<slug>` or `/app/p/<slug>`. */
    private static final Pattern PAGE_PATH = Pattern.compile("^/(?:app/)?p/[^/]+");
    /** `/dashboards/<id>` or `/app/dashboards/<id>`. */
    private static final Pattern DASHBOARD_PATH = Pattern.compile("^/(?:app/)?dashboards/[^/]+");
    /** `/reports/<id>` or `/app/reports/<id>`. */
    private static final Pattern REPORT_PATH = Pattern.compile("^/(?:app/)?reports/[^/]+");
    /** `/chat` or `/app/chat`. */
    private static final Pattern CHAT_PATH = Pattern.compile("^/(?:app/)?chat/?$");

    private static final String PATH_GRAMMAR = "/resources/<collection>[?query], /p/<slug> or "
            + "/app/p/<slug>, /dashboards/<id> or /app/dashboards/<id>, /reports/<id> or "
            + "/app/reports/<id>, /chat or /app/chat (see navTabs.ts / kelta://docs/ui-menus)";

    private final GatewayHttpClient gateway;
    private final AdminLookups lookups;

    public ApplyMenuTool(GatewayHttpClient gateway) {
        this.gateway = gateway;
        this.lookups = new AdminLookups(gateway);
    }

    @Override
    public SyncToolSpecification toSpecification() {
        Map<String, Object> childItem = new LinkedHashMap<>();
        childItem.put("type", "object");
        Map<String, Object> childProps = new LinkedHashMap<>();
        childProps.put("label", Schemas.string("Display label."));
        childProps.put("path", Schemas.string(
                "Route this item navigates to. Omit for a group header. Must match the nav "
                        + "grammar: " + PATH_GRAMMAR + "."));
        childProps.put("icon", Schemas.string("Icon name rendered beside the label."));
        childItem.put("properties", childProps);
        childItem.put("additionalProperties", false);

        Map<String, Object> childrenArr = new LinkedHashMap<>();
        childrenArr.put("type", "array");
        childrenArr.put("description",
                "Child items rendered as a dropdown under this group header. Nesting is one "
                        + "level deep — a child cannot itself have children.");
        childrenArr.put("items", childItem);

        Map<String, Object> itemItem = new LinkedHashMap<>();
        itemItem.put("type", "object");
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("label", Schemas.string("Display label."));
        itemProps.put("path", Schemas.string(
                "Route this item navigates to. Omit for a group header (an item with children "
                        + "and no path). Must match the nav grammar: " + PATH_GRAMMAR + "."));
        itemProps.put("icon", Schemas.string("Icon name rendered beside the label."));
        itemProps.put("children", childrenArr);
        itemItem.put("properties", itemProps);
        itemItem.put("additionalProperties", false);

        Map<String, Object> itemsArr = new LinkedHashMap<>();
        itemsArr.put("type", "array");
        itemsArr.put("description",
                "Menu items/groups in display order — displayOrder is derived from position, "
                        + "not taken from input. Any existing item not listed here (matched on "
                        + "label within its parent) is reported as \"stale\", or deleted when "
                        + "prune:true.");
        itemsArr.put("items", itemItem);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Schemas.string("Menu name — the app's identity (unique within tenant)."));
        properties.put("description", Schemas.string("Optional menu description."));
        properties.put("icon", Schemas.string("Switcher icon for this app."));
        properties.put("isDefault", Schemas.bool("Whether this is the default app.", false));
        properties.put("active", Schemas.bool("Whether the app is shown in the end-user shell.", true));
        properties.put("items", itemsArr);
        properties.put("prune", Schemas.bool(
                "Hard-delete any existing item absent from this call's tree. Omitted or false "
                        + "reports those items as \"stale\" and leaves them in place.", false));

        Tool tool = Tool.builder()
                .name("apply_menu")
                .title("Apply Menu")
                .description(
                        "Create-or-update a UI menu (\"app\") and its items/groups in one call, keyed "
                        + "on name (menu) and (menu, parent, label) for each item. Idempotent: applying "
                        + "the identical body twice reports {\"action\":\"unchanged\",...} for the menu "
                        + "and every item on the second call. An item with a non-empty \"children\" array "
                        + "is a group header (dropdown); each child's parentId resolves to the group's id. "
                        + "Example: {\"name\":\"Main\",\"items\":[{\"label\":\"Reports\",\"children\":"
                        + "[{\"label\":\"Sales\",\"path\":\"/reports/sales-id\"}]},{\"label\":\"Tasks\","
                        + "\"path\":\"/resources/tasks\"}]} creates a \"Reports\" group whose child "
                        + "\"Sales\" gets parentId set to the group's id, plus a top-level \"Tasks\" item. "
                        + "path is validated against the nav grammar (" + PATH_GRAMMAR + ") before "
                        + "anything is written — an invalid path fails the whole call with a structured "
                        + "error. Wraps GET+POST/PATCH(/DELETE) /api/ui-menus and /api/ui-menu-items.")
                .inputSchema(Schemas.object(properties, List.of("name", "items")))
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
        Object n = args.get("name");
        if (n == null || n.toString().isBlank()) {
            return error("Argument \"name\" is required.");
        }
        if (!(args.get("items") instanceof List<?> items)) {
            return error("Argument \"items\" must be an array.");
        }
        CallToolResult validationError = validateItems(items, "/items", false);
        if (validationError != null) {
            return validationError;
        }
        String name = n.toString();
        boolean prune = Boolean.TRUE.equals(args.get("prune"));

        try {
            Map<String, Object> menuAttrs = new LinkedHashMap<>();
            if (args.get("description") instanceof String d && !d.isBlank()) menuAttrs.put("description", d);
            if (args.get("icon") instanceof String ic && !ic.isBlank()) menuAttrs.put("icon", ic);
            if (args.get("isDefault") instanceof Boolean b) menuAttrs.put("isDefault", b);
            if (args.get("active") instanceof Boolean b) menuAttrs.put("active", b);

            AdminLookups.UpsertResult menuResult = lookups.upsert("ui-menus", Map.of("name", name), menuAttrs);
            String menuId = menuResult.id();

            List<Map<String, Object>> existing = lookups.list("ui-menu-items", Map.of("menuId", menuId), 200);
            Map<String, Map<String, Object>> existingIndex = new LinkedHashMap<>();
            for (Map<String, Object> row : existing) {
                existingIndex.put(itemKey(asString(row.get("parentId")), asString(row.get("label"))), row);
            }

            Set<String> seenKeys = new LinkedHashSet<>();
            List<Object> itemResults = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> itemMap = (Map<String, Object>) items.get(i);
                itemResults.add(applyItem(itemMap, menuId, null, i, existingIndex, seenKeys));
            }

            List<String> pruned = new ArrayList<>();
            List<String> stale = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> entry : existingIndex.entrySet()) {
                if (seenKeys.contains(entry.getKey())) continue;
                Map<String, Object> stray = entry.getValue();
                String label = asString(stray.get("label"));
                String id = asString(stray.get("id"));
                if (prune) {
                    deleteMenuItem(id);
                    pruned.add(label);
                } else {
                    stale.add(label);
                }
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", menuResult.action());
            body.put("id", menuId);
            if (!menuResult.changed().isEmpty()) body.put("changed", menuResult.changed());
            body.put("items", itemResults);
            if (!pruned.isEmpty()) body.put("pruned", pruned);
            if (!stale.isEmpty()) body.put("stale", stale);

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
     * Create-or-update one item against the pre-fetched {@code existingIndex}, then recurse into
     * {@code children} (with this item's resolved id as their {@code parentId}). Adds this item's
     * key to {@code seenKeys} so the caller can tell which existing rows were not revisited.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> applyItem(Map<String, Object> itemMap, String menuId, String parentId, int index,
            Map<String, Map<String, Object>> existingIndex, Set<String> seenKeys) {
        String label = itemMap.get("label").toString();
        String path = itemMap.get("path") instanceof String p && !p.isBlank() ? p : null;
        String icon = itemMap.get("icon") instanceof String ic && !ic.isBlank() ? ic : null;
        List<Object> children = itemMap.get("children") instanceof List<?> c
                ? (List<Object>) c : List.of();

        String key = itemKey(parentId, label);
        seenKeys.add(key);
        Map<String, Object> existing = existingIndex.get(key);

        Map<String, Object> desired = new LinkedHashMap<>();
        desired.put("path", path);
        desired.put("icon", icon);
        desired.put("displayOrder", index);

        String id;
        String action;
        List<String> changed = List.of();
        if (existing == null) {
            Map<String, Object> createAttrs = new LinkedHashMap<>();
            createAttrs.put("menuId", menuId);
            if (parentId != null) createAttrs.put("parentId", parentId);
            createAttrs.put("label", label);
            createAttrs.put("displayOrder", index);
            if (path != null) createAttrs.put("path", path);
            if (icon != null) createAttrs.put("icon", icon);
            id = createMenuItem(createAttrs);
            action = "created";
        } else {
            id = asString(existing.get("id"));
            changed = AdminLookups.diff(existing, desired);
            if (changed.isEmpty()) {
                action = "unchanged";
            } else {
                Map<String, Object> patchAttrs = new LinkedHashMap<>();
                for (String k : changed) patchAttrs.put(k, desired.get(k));
                patchMenuItem(id, patchAttrs);
                action = "updated";
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("action", action);
        result.put("id", id);
        if (!changed.isEmpty()) result.put("changed", changed);

        if (!children.isEmpty()) {
            List<Object> childResults = new ArrayList<>();
            for (int i = 0; i < children.size(); i++) {
                childResults.add(applyItem((Map<String, Object>) children.get(i), menuId, id, i,
                        existingIndex, seenKeys));
            }
            result.put("children", childResults);
        }
        return result;
    }

    /**
     * Recursively validates the whole items tree — required non-blank {@code label}, {@code path}
     * (if present) against the nav grammar, and at most one level of {@code children} nesting —
     * before any gateway call is made, so an invalid body writes nothing. Returns {@code null}
     * when everything is valid.
     */
    private CallToolResult validateItems(List<?> items, String basePointer, boolean nested) {
        for (int i = 0; i < items.size(); i++) {
            String pointer = basePointer + "/" + i;
            Object raw = items.get(i);
            if (!(raw instanceof Map<?, ?> itemMap)) {
                return error("Item at " + pointer + " must be an object.");
            }
            if (!(itemMap.get("label") instanceof String label) || label.isBlank()) {
                return error("Item at " + pointer + " needs a non-blank \"label\".");
            }
            if (itemMap.get("path") instanceof String path && !path.isBlank() && !isValidPath(path)) {
                return invalidPathError(pointer + "/path", path);
            }
            if (itemMap.get("children") instanceof List<?> children && !children.isEmpty()) {
                if (nested) {
                    return error("Item at " + pointer
                            + " cannot have \"children\" — nesting is one level deep.");
                }
                CallToolResult childError = validateItems(children, pointer + "/children", true);
                if (childError != null) return childError;
            }
        }
        return null;
    }

    private static boolean isValidPath(String path) {
        return RESOURCE_PATH.matcher(path).find()
                || PAGE_PATH.matcher(path).find()
                || DASHBOARD_PATH.matcher(path).find()
                || REPORT_PATH.matcher(path).find()
                || CHAT_PATH.matcher(path).find();
    }

    private static String itemKey(String parentId, String label) {
        return (parentId == null ? "" : parentId) + " " + label;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private String createMenuItem(Map<String, Object> attributes) {
        Map<String, Object> body = Map.of("data", Map.of("type", "ui-menu-items", "attributes", attributes));
        GatewayHttpClient.Response response = gateway.post("/api/ui-menu-items", body);
        if (!response.isSuccess()) {
            throw new AdminLookups.GatewayFailure(response);
        }
        return AdminLookups.firstResourceId(response.body());
    }

    private void patchMenuItem(String id, Map<String, Object> attributes) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "ui-menu-items", "id", id, "attributes", attributes));
        String path = "/api/ui-menu-items/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        GatewayHttpClient.Response response = gateway.patch(path, body);
        if (!response.isSuccess()) {
            throw new AdminLookups.GatewayFailure(response);
        }
    }

    private void deleteMenuItem(String id) {
        String path = "/api/ui-menu-items/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        GatewayHttpClient.Response response = gateway.delete(path);
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

    /**
     * Structured 400 for a path that doesn't match the nav grammar — shaped like the gateway's
     * own JSON:API {@code errors[]} envelope (see {@code conventions.md}) even though this
     * rejection never reaches the gateway, so callers can branch on {@code errors[0].code}
     * exactly as they would for a server-side validation failure.
     */
    private static CallToolResult invalidPathError(String pointer, String path) {
        String detail = "path \"" + path + "\" does not match the menu nav grammar: " + PATH_GRAMMAR + ".";
        Map<String, Object> jsonApiError = new LinkedHashMap<>();
        jsonApiError.put("status", "400");
        jsonApiError.put("code", "INVALID_PATH");
        jsonApiError.put("title", "Invalid Path");
        jsonApiError.put("detail", detail);
        jsonApiError.put("source", Map.of("pointer", pointer));

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("status", 400);
        structured.put("errors", List.of(jsonApiError));

        return CallToolResult.builder()
                .isError(true)
                .content(List.of(new TextContent(detail)))
                .structuredContent(structured)
                .build();
    }
}
