package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads and applies a whole UI menu ("app") — the {@code ui-menus} row plus its {@code
 * ui-menu-items} tree — as one document, addressed by {@code name} rather than id.
 *
 * <p>Building a menu through the generic collection routes is one request for the menu row
 * plus one request per item. {@link #applyTree} collapses that into a single idempotent
 * upsert (one HTTP call, N in-process {@link QueryEngine} writes): applying the same body
 * twice reports {@code created=0, updated=0, deleted=0}. The {@code items} array is
 * authoritative — an item absent from the body is deleted, mirroring {@code
 * PageLayoutTreeService}'s section semantics.
 *
 * <p>An item with a non-empty {@code children} array is a group header; nesting is one
 * level deep, matching {@code ApplyMenuTool} (the kelta-mcp admin tool this endpoint lets
 * the CLI reach without kelta-mcp in the loop). Items are matched on {@code (parentId,
 * label)} — a top-level item's natural parent is {@code null}.
 *
 * @since 1.0.0
 */
@Service
public class MenuTreeService {

    private static final Logger log = LoggerFactory.getLogger(MenuTreeService.class);

    private static final Set<String> BODY_KEYS = Set.of(
            "name", "description", "icon", "isDefault", "active", "displayOrder", "items");
    private static final Set<String> ITEM_KEYS = Set.of("label", "path", "icon", "children");
    private static final Set<String> MENU_SCALARS = Set.of(
            "description", "icon", "isDefault", "active", "displayOrder");

    private static final String SELECT_MENU_BY_NAME = """
            SELECT id, name, description, icon, is_default, active, display_order
            FROM ui_menu WHERE name = ?
            """;

    private static final String SELECT_ITEMS = """
            SELECT id, parent_id, label, path, icon, display_order
            FROM ui_menu_item WHERE menu_id = ?
            ORDER BY parent_id NULLS FIRST, display_order, id
            """;

    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final JdbcTemplate jdbcTemplate;

    public MenuTreeService(QueryEngine queryEngine, CollectionRegistry collectionRegistry,
                           JdbcTemplate jdbcTemplate) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Counts of what an apply did, across the menu row and its items. */
    public record TreeCounts(int created, int updated, int deleted, int unchanged) {}

    /** Result of an apply: the resolved menu plus the diff it produced. */
    public record ApplyResult(String menuId, String name, TreeCounts counts) {}

    /** One rejected body position: a JSON Pointer into the request body and what is wrong there. */
    public record TreeError(String pointer, String detail) {}

    /** Body rejected before anything was written; carries every position that failed. */
    public static class TreeValidationException extends RuntimeException {
        private final transient List<TreeError> errors;

        public TreeValidationException(List<TreeError> errors) {
            super(errors.isEmpty() ? "Invalid menu tree" : errors.get(0).detail());
            this.errors = List.copyOf(errors);
        }

        public List<TreeError> errors() {
            return errors;
        }
    }

    /**
     * Reads the menu as a label-addressed tree. The result is exactly the document
     * {@link #applyTree} accepts, so feeding it straight back reports no diff.
     */
    public Map<String, Object> readTree(String name) {
        Map<String, Object> menu = loadMenu(name);
        String menuId = asString(menu.get("id"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_ITEMS, menuId);
        Map<String, List<Map<String, Object>>> childrenByParent = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            childrenByParent.computeIfAbsent(asString(row.get("parent_id")), k -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : childrenByParent.getOrDefault(null, List.of())) {
            items.add(itemToTree(row, childrenByParent));
        }

        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("menuId", menuId);
        tree.put("name", asString(menu.get("name")));
        tree.put("description", asString(menu.get("description")));
        tree.put("icon", asString(menu.get("icon")));
        tree.put("isDefault", boolOr(menu.get("is_default"), false));
        tree.put("active", boolOr(menu.get("active"), true));
        tree.put("items", items);
        return tree;
    }

    private Map<String, Object> itemToTree(Map<String, Object> row,
                                           Map<String, List<Map<String, Object>>> childrenByParent) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", asString(row.get("label")));
        item.put("path", asString(row.get("path")));
        item.put("icon", asString(row.get("icon")));
        List<Map<String, Object>> children = childrenByParent.get(asString(row.get("id")));
        if (children != null && !children.isEmpty()) {
            List<Map<String, Object>> childTrees = new ArrayList<>();
            for (Map<String, Object> child : children) {
                childTrees.add(itemToTree(child, childrenByParent));
            }
            item.put("children", childTrees);
        }
        return item;
    }

    /** Applies the tree to the named menu, creating the menu row when it does not exist yet. */
    @SuppressWarnings("unchecked")
    public ApplyResult applyTree(String name, Map<String, Object> body) {
        List<TreeError> errors = new ArrayList<>();
        Map<String, Object> safeBody = body == null ? Map.of() : body;

        for (String key : safeBody.keySet()) {
            if (!BODY_KEYS.contains(key)) {
                errors.add(new TreeError("/" + key, "Unknown menu tree property '" + key + "'"));
            }
        }

        Object itemsRaw = safeBody.get("items");
        if (!(itemsRaw instanceof List<?> itemList)) {
            errors.add(new TreeError("/items", "'items' is required and must be an array"));
            throw new TreeValidationException(errors);
        }
        validateItems(itemList, "/items", false, errors);
        if (!errors.isEmpty()) {
            throw new TreeValidationException(errors);
        }

        Map<String, Object> existingMenu = findMenu(name);
        Counts counts = new Counts();
        String menuId;
        if (existingMenu == null) {
            menuId = createMenu(name, safeBody);
            counts.created++;
        } else {
            menuId = asString(existingMenu.get("id"));
            applyMenuScalars(menuId, existingMenu, safeBody, counts);
        }

        List<Map<String, Object>> existingItems = existingMenu != null
                ? new ArrayList<>(jdbcTemplate.queryForList(SELECT_ITEMS, menuId))
                : new ArrayList<>();
        Map<String, Map<String, Object>> existingIndex = new LinkedHashMap<>();
        for (Map<String, Object> row : existingItems) {
            existingIndex.put(itemKey(asString(row.get("parent_id")), asString(row.get("label"))), row);
        }

        Set<String> seenKeys = new LinkedHashSet<>();
        for (int i = 0; i < itemList.size(); i++) {
            applyItem((Map<String, Object>) itemList.get(i), menuId, null, i, existingIndex, seenKeys, counts);
        }
        for (Map.Entry<String, Map<String, Object>> entry : existingIndex.entrySet()) {
            if (seenKeys.contains(entry.getKey())) continue;
            delete("ui-menu-items", asString(entry.getValue().get("id")));
            counts.deleted++;
        }

        log.info("Applied menu tree (menuId={}, created={}, updated={}, deleted={}, unchanged={})",
                menuId, counts.created, counts.updated, counts.deleted, counts.unchanged);
        return new ApplyResult(menuId, name, counts.toRecord());
    }

    // ------------------------------------------------------------------

    private void validateItems(List<?> items, String basePointer, boolean nested, List<TreeError> errors) {
        for (int i = 0; i < items.size(); i++) {
            String pointer = basePointer + "/" + i;
            if (!(items.get(i) instanceof Map<?, ?> raw)) {
                errors.add(new TreeError(pointer, "Item must be an object"));
                continue;
            }
            for (Object key : raw.keySet()) {
                if (!ITEM_KEYS.contains(key)) {
                    errors.add(new TreeError(pointer + "/" + key, "Unknown item property '" + key + "'"));
                }
            }
            if (!(raw.get("label") instanceof String label) || label.isBlank()) {
                errors.add(new TreeError(pointer + "/label", "'label' is required"));
            }
            if (raw.get("children") instanceof List<?> children && !children.isEmpty()) {
                if (nested) {
                    errors.add(new TreeError(pointer + "/children",
                            "Nesting is one level deep — a child cannot itself have children"));
                } else {
                    validateItems(children, pointer + "/children", true, errors);
                }
            }
        }
    }

    private static final class Counts {
        int created;
        int updated;
        int deleted;
        int unchanged;

        TreeCounts toRecord() {
            return new TreeCounts(created, updated, deleted, unchanged);
        }
    }

    private String createMenu(String name, Map<String, Object> body) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", name);
        for (String key : MENU_SCALARS) {
            if (body.containsKey(key)) {
                attributes.put(key, body.get(key));
            }
        }
        return create("ui-menus", attributes);
    }

    private void applyMenuScalars(String menuId, Map<String, Object> current, Map<String, Object> body,
                                  Counts counts) {
        Map<String, Object> currentValues = new LinkedHashMap<>();
        currentValues.put("description", asString(current.get("description")));
        currentValues.put("icon", asString(current.get("icon")));
        currentValues.put("isDefault", boolOr(current.get("is_default"), false));
        currentValues.put("active", boolOr(current.get("active"), true));
        currentValues.put("displayOrder", intOr(current.get("display_order"), 0));

        Map<String, Object> changes = new LinkedHashMap<>();
        for (String key : MENU_SCALARS) {
            if (body.containsKey(key) && !Objects.equals(currentValues.get(key), body.get(key))) {
                changes.put(key, body.get(key));
            }
        }
        if (changes.isEmpty()) {
            counts.unchanged++;
            return;
        }
        update("ui-menus", menuId, changes);
        counts.updated++;
    }

    /**
     * Create-or-update one item against the pre-fetched {@code existingIndex}, then recurse
     * into {@code children} with this item's resolved id as their {@code parentId}.
     */
    @SuppressWarnings("unchecked")
    private void applyItem(Map<String, Object> itemMap, String menuId, String parentId, int index,
                           Map<String, Map<String, Object>> existingIndex, Set<String> seenKeys, Counts counts) {
        String label = itemMap.get("label").toString();
        String path = itemMap.get("path") instanceof String p && !p.isBlank() ? p : null;
        String icon = itemMap.get("icon") instanceof String ic && !ic.isBlank() ? ic : null;
        List<Object> children = itemMap.get("children") instanceof List<?> c ? (List<Object>) c : List.of();

        String key = itemKey(parentId, label);
        seenKeys.add(key);
        Map<String, Object> existing = existingIndex.get(key);

        String id;
        if (existing == null) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("menuId", menuId);
            if (parentId != null) attributes.put("parentId", parentId);
            attributes.put("label", label);
            attributes.put("displayOrder", index);
            if (path != null) attributes.put("path", path);
            if (icon != null) attributes.put("icon", icon);
            id = create("ui-menu-items", attributes);
            counts.created++;
        } else {
            id = asString(existing.get("id"));
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("path", asString(existing.get("path")));
            current.put("icon", asString(existing.get("icon")));
            current.put("displayOrder", intOr(existing.get("display_order"), 0));

            Map<String, Object> desired = new LinkedHashMap<>();
            desired.put("path", path);
            desired.put("icon", icon);
            desired.put("displayOrder", index);

            Map<String, Object> changes = new LinkedHashMap<>();
            desired.forEach((k, v) -> {
                if (!Objects.equals(current.get(k), v)) changes.put(k, v);
            });
            if (changes.isEmpty()) {
                counts.unchanged++;
            } else {
                update("ui-menu-items", id, changes);
                counts.updated++;
            }
        }

        for (int i = 0; i < children.size(); i++) {
            applyItem((Map<String, Object>) children.get(i), menuId, id, i, existingIndex, seenKeys, counts);
        }
    }

    private static String itemKey(String parentId, String label) {
        return (parentId == null ? "" : parentId) + " " + label;
    }

    // ------------------------------------------------------------------
    // QueryEngine + JDBC plumbing
    // ------------------------------------------------------------------

    private CollectionDefinition definition(String collectionName) {
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null) {
            throw new IllegalStateException("System collection not initialized: " + collectionName);
        }
        return definition;
    }

    private String create(String collectionName, Map<String, Object> attributes) {
        CollectionDefinition definition = definition(collectionName);
        Map<String, Object> data = new LinkedHashMap<>(attributes);
        String tenantId = TenantContext.get();
        if (definition.tenantScoped() && tenantId != null) {
            data.putIfAbsent("tenantId", tenantId);
        }
        return asString(queryEngine.create(definition, data).get("id"));
    }

    private void update(String collectionName, String id, Map<String, Object> attributes) {
        queryEngine.update(definition(collectionName), id, attributes);
    }

    private void delete(String collectionName, String id) {
        queryEngine.delete(definition(collectionName), id);
    }

    private Map<String, Object> loadMenu(String name) {
        Map<String, Object> menu = findMenu(name);
        if (menu == null) {
            throw new IllegalArgumentException("Menu '" + name + "' not found");
        }
        return menu;
    }

    private Map<String, Object> findMenu(String name) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_MENU_BY_NAME, name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------
    // Value helpers
    // ------------------------------------------------------------------

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isEmpty() ? null : s;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        return null;
    }

    private static int intOr(Object value, int fallback) {
        Integer parsed = asInteger(value);
        return parsed == null ? fallback : parsed;
    }

    private static boolean boolOr(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }
}
