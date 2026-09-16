package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads and applies a whole dashboard — the {@code dashboards} row plus its {@code
 * dashboard-components}, matched on {@code title} — as one document, addressed by
 * {@code name} rather than id.
 *
 * <p>Building a dashboard through the generic collection routes is one request for the
 * dashboard row plus one request per widget. {@link #applyTree} collapses that into a
 * single idempotent upsert (one HTTP call, N in-process {@link QueryEngine} writes):
 * applying the same body twice reports {@code created=0, updated=0, deleted=0}. The
 * {@code components} array is authoritative — a widget absent from the body is deleted,
 * mirroring {@link PageLayoutTreeService}'s section semantics.
 *
 * <p>Every write goes through {@link QueryEngine}, the same path the admin API and CLI
 * single-resource commands use, so {@code DashboardComponentConfigHook} (widget config
 * validation) and any cache/event hooks fire exactly as they would for a hand-written
 * create/update — no separate dry-run validate call is needed.
 *
 * @since 1.0.0
 */
@Service
public class DashboardTreeService {

    private static final Logger log = LoggerFactory.getLogger(DashboardTreeService.class);

    private static final Set<String> BODY_KEYS = Set.of(
            "name", "description", "accessLevel", "columnCount", "dynamic", "runningUserId", "components");
    private static final Set<String> COMPONENT_KEYS = Set.of(
            "title", "componentType", "columnPosition", "rowPosition", "columnSpan", "rowSpan", "config");
    private static final Set<String> DASHBOARD_SCALARS = Set.of(
            "description", "accessLevel", "columnCount", "dynamic", "runningUserId");

    private static final String SELECT_DASHBOARD_BY_NAME = """
            SELECT id, name, description, access_level, is_dynamic, running_user_id, column_count
            FROM dashboard WHERE name = ?
            """;

    private static final String SELECT_COMPONENTS = """
            SELECT id, title, component_type, column_position, row_position, column_span, row_span, config
            FROM dashboard_component WHERE dashboard_id = ?
            ORDER BY sort_order, id
            """;

    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DashboardTreeService(QueryEngine queryEngine, CollectionRegistry collectionRegistry,
                                JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Counts of what an apply did, across the dashboard row and its components. */
    public record TreeCounts(int created, int updated, int deleted, int unchanged) {}

    /** Result of an apply: the resolved dashboard plus the diff it produced. */
    public record ApplyResult(String dashboardId, String name, TreeCounts counts) {}

    /** One rejected body position: a JSON Pointer into the request body and what is wrong there. */
    public record TreeError(String pointer, String detail) {}

    /** Body rejected before anything was written; carries every position that failed. */
    public static class TreeValidationException extends RuntimeException {
        private final transient List<TreeError> errors;

        public TreeValidationException(List<TreeError> errors) {
            super(errors.isEmpty() ? "Invalid dashboard tree" : errors.get(0).detail());
            this.errors = List.copyOf(errors);
        }

        public List<TreeError> errors() {
            return errors;
        }
    }

    /**
     * Reads the dashboard as a title-addressed tree. The result is exactly the document
     * {@link #applyTree} accepts, so feeding it straight back reports no diff.
     */
    public Map<String, Object> readTree(String name) {
        Map<String, Object> dashboard = loadDashboard(name);
        String dashboardId = asString(dashboard.get("id"));

        List<Map<String, Object>> components = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(SELECT_COMPONENTS, dashboardId)) {
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("title", asString(row.get("title")));
            component.put("componentType", asString(row.get("component_type")));
            component.put("columnPosition", intOr(row.get("column_position"), 1));
            component.put("rowPosition", intOr(row.get("row_position"), 1));
            component.put("columnSpan", intOr(row.get("column_span"), 1));
            component.put("rowSpan", intOr(row.get("row_span"), 1));
            component.put("config", parseJson(row.get("config")));
            components.add(component);
        }

        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("dashboardId", dashboardId);
        tree.put("name", asString(dashboard.get("name")));
        tree.put("description", asString(dashboard.get("description")));
        tree.put("accessLevel", asString(dashboard.get("access_level")));
        tree.put("dynamic", boolOr(dashboard.get("is_dynamic"), false));
        tree.put("runningUserId", asString(dashboard.get("running_user_id")));
        tree.put("columnCount", intOr(dashboard.get("column_count"), 3));
        tree.put("components", components);
        return tree;
    }

    /**
     * Applies the tree to the named dashboard, creating the dashboard row when it does not
     * exist yet.
     */
    @SuppressWarnings("unchecked")
    public ApplyResult applyTree(String name, Map<String, Object> body) {
        List<TreeError> errors = new ArrayList<>();
        Map<String, Object> safeBody = body == null ? Map.of() : body;

        for (String key : safeBody.keySet()) {
            if (!BODY_KEYS.contains(key)) {
                errors.add(new TreeError("/" + key, "Unknown dashboard tree property '" + key + "'"));
            }
        }

        Object componentsRaw = safeBody.get("components");
        if (!(componentsRaw instanceof List<?> componentList)) {
            errors.add(new TreeError("/components", "'components' is required and must be an array"));
            throw new TreeValidationException(errors);
        }

        List<Map<String, Object>> desired = new ArrayList<>();
        Set<String> seenTitles = new LinkedHashSet<>();
        for (int i = 0; i < componentList.size(); i++) {
            String pointer = "/components/" + i;
            if (!(componentList.get(i) instanceof Map<?, ?> raw)) {
                errors.add(new TreeError(pointer, "Component must be an object"));
                continue;
            }
            Map<String, Object> component = (Map<String, Object>) raw;
            for (String key : component.keySet()) {
                if (!COMPONENT_KEYS.contains(key)) {
                    errors.add(new TreeError(pointer + "/" + key, "Unknown component property '" + key + "'"));
                }
            }
            String title = component.get("title") instanceof String s && !s.isBlank() ? s : null;
            if (title == null) {
                errors.add(new TreeError(pointer + "/title", "'title' is required — components are matched on it"));
            } else if (!seenTitles.add(title)) {
                errors.add(new TreeError(pointer + "/title", "Duplicate component title '" + title + "'"));
            }
            if (!(component.get("componentType") instanceof String type) || type.isBlank()) {
                errors.add(new TreeError(pointer + "/componentType", "'componentType' is required"));
            }
            if (!(component.get("columnPosition") instanceof Number)
                    || !(component.get("rowPosition") instanceof Number)) {
                errors.add(new TreeError(pointer, "'columnPosition' and 'rowPosition' are required numbers"));
            }
            desired.add(component);
        }
        if (!errors.isEmpty()) {
            throw new TreeValidationException(errors);
        }

        Map<String, Object> existingDashboard = findDashboard(name);
        Counts counts = new Counts();
        String dashboardId;
        if (existingDashboard == null) {
            dashboardId = createDashboard(name, safeBody);
            counts.created++;
        } else {
            dashboardId = asString(existingDashboard.get("id"));
            applyDashboardScalars(dashboardId, existingDashboard, safeBody, counts);
        }

        applyComponents(dashboardId, desired, existingDashboard != null, counts);
        log.info("Applied dashboard tree (dashboardId={}, created={}, updated={}, deleted={}, unchanged={})",
                dashboardId, counts.created, counts.updated, counts.deleted, counts.unchanged);
        return new ApplyResult(dashboardId, name, counts.toRecord());
    }

    // ------------------------------------------------------------------

    private static final class Counts {
        int created;
        int updated;
        int deleted;
        int unchanged;

        TreeCounts toRecord() {
            return new TreeCounts(created, updated, deleted, unchanged);
        }
    }

    private String createDashboard(String name, Map<String, Object> body) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", name);
        for (String key : DASHBOARD_SCALARS) {
            if (body.containsKey(key)) {
                attributes.put(key, body.get(key));
            }
        }
        return create("dashboards", attributes);
    }

    private void applyDashboardScalars(String dashboardId, Map<String, Object> current,
                                       Map<String, Object> body, Counts counts) {
        Map<String, Object> currentValues = new LinkedHashMap<>();
        currentValues.put("description", asString(current.get("description")));
        currentValues.put("accessLevel", asString(current.get("access_level")));
        currentValues.put("columnCount", intOr(current.get("column_count"), 3));
        currentValues.put("dynamic", boolOr(current.get("is_dynamic"), false));
        currentValues.put("runningUserId", asString(current.get("running_user_id")));

        Map<String, Object> changes = new LinkedHashMap<>();
        for (String key : DASHBOARD_SCALARS) {
            if (body.containsKey(key) && !Objects.equals(currentValues.get(key), body.get(key))) {
                changes.put(key, body.get(key));
            }
        }
        if (changes.isEmpty()) {
            counts.unchanged++;
            return;
        }
        update("dashboards", dashboardId, changes);
        counts.updated++;
    }

    private void applyComponents(String dashboardId, List<Map<String, Object>> desired,
                                 boolean dashboardPreexisted, Counts counts) {
        // A brand-new dashboard trivially has zero components — skip the read.
        List<Map<String, Object>> existing = dashboardPreexisted
                ? new ArrayList<>(jdbcTemplate.queryForList(SELECT_COMPONENTS, dashboardId))
                : new ArrayList<>();

        int sortOrder = 0;
        for (Map<String, Object> component : desired) {
            String title = component.get("title").toString();
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("componentType", component.get("componentType").toString());
            attributes.put("title", title);
            attributes.put("columnPosition", ((Number) component.get("columnPosition")).intValue());
            attributes.put("rowPosition", ((Number) component.get("rowPosition")).intValue());
            attributes.put("columnSpan", component.get("columnSpan") instanceof Number s ? s.intValue() : 1);
            attributes.put("rowSpan", component.get("rowSpan") instanceof Number s ? s.intValue() : 1);
            attributes.put("config", component.get("config") instanceof Map<?, ?> c ? c : Map.of());
            attributes.put("sortOrder", sortOrder++);

            Map<String, Object> match = existing.stream()
                    .filter(row -> title.equals(asString(row.get("title"))))
                    .findFirst().orElse(null);
            if (match == null) {
                attributes.put("dashboardId", dashboardId);
                create("dashboard-components", attributes);
                counts.created++;
                continue;
            }
            existing.remove(match);

            Map<String, Object> current = new LinkedHashMap<>();
            current.put("componentType", asString(match.get("component_type")));
            current.put("title", asString(match.get("title")));
            current.put("columnPosition", intOr(match.get("column_position"), 1));
            current.put("rowPosition", intOr(match.get("row_position"), 1));
            current.put("columnSpan", intOr(match.get("column_span"), 1));
            current.put("rowSpan", intOr(match.get("row_span"), 1));
            current.put("config", parseJson(match.get("config")));
            applyDiff("dashboard-components", asString(match.get("id")), current, attributes, counts);
        }

        for (Map<String, Object> orphan : existing) {
            delete("dashboard-components", asString(orphan.get("id")));
            counts.deleted++;
        }
    }

    /** Writes only the attributes that actually differ; an all-equal row counts as unchanged. */
    private void applyDiff(String collectionName, String id, Map<String, Object> current,
                           Map<String, Object> desired, Counts counts) {
        Map<String, Object> changes = new LinkedHashMap<>();
        desired.forEach((key, value) -> {
            if ("sortOrder".equals(key)) return; // ordinal, not part of the identity/diff surface
            if (!Objects.equals(current.get(key), value)) {
                changes.put(key, value);
            }
        });
        if (changes.isEmpty()) {
            counts.unchanged++;
            return;
        }
        update(collectionName, id, changes);
        counts.updated++;
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

    private Map<String, Object> loadDashboard(String name) {
        Map<String, Object> dashboard = findDashboard(name);
        if (dashboard == null) {
            throw new IllegalArgumentException("Dashboard '" + name + "' not found");
        }
        return dashboard;
    }

    private Map<String, Object> findDashboard(String name) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_DASHBOARD_BY_NAME, name);
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

    /** JSONB columns arrive as {@code PGobject} (or a raw string); parse them back to structures. */
    private Object parseJson(Object value) {
        if (value == null) {
            return null;
        }
        String candidate = null;
        if ("org.postgresql.util.PGobject".equals(value.getClass().getName())) {
            candidate = value.toString();
        } else if (value instanceof String s) {
            String trimmed = s.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                candidate = trimmed;
            }
        }
        if (candidate == null) {
            return value;
        }
        try {
            return objectMapper.readValue(candidate, Object.class);
        } catch (Exception e) {
            log.warn("Could not parse JSON column value, passing through: {}", e.getMessage());
            return value;
        }
    }
}
