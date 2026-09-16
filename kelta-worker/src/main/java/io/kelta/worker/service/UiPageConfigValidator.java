package io.kelta.worker.service;

import io.kelta.runtime.query.Pagination;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a {@code ui-pages.config} document — shared by {@link UiPageConfigHook} (write path)
 * and {@code POST /api/ui-pages/validate} (dry-run path).
 *
 * <p>{@code config} is a free JSON column, so none of this is caught by generic field validation.
 * A widget {@code type} the builder does not know renders as a placeholder, a binding to a data
 * source nobody declared resolves to null, and a data source over the fan-out caps is silently
 * truncated by the client — all of which look to an author like "the page is blank".
 *
 * <p>Problems carry a {@link Severity}:
 * <ul>
 *   <li>{@link Severity#ERROR} — the config is objectively wrong against the platform's own
 *       vocabulary and caps. {@link UiPageConfigHook} rejects the save.</li>
 *   <li>{@link Severity#WARNING} — the config saves, but part of it will not do what it says.
 *       A half-authored page (a binding written before its data source) must stay saveable.</li>
 * </ul>
 *
 * <p><b>Known limitation.</b> The widget vocabulary is the built-in catalogue only. Page component
 * types contributed at runtime by a plugin or a module UI bundle are registered in the browser and
 * have no server-side declaration, so a page using one is rejected as an unknown type. See
 * {@code concerns.md}.
 *
 * @since 1.0.0
 */
@Service
public class UiPageConfigValidator {

    /** Mirrors {@code MAX_PAGE_DATA_SOURCES} in {@code kelta-ui/app/.../model/limits.ts}. */
    public static final int MAX_PAGE_DATA_SOURCES = 12;

    /** Bounds recursion over a hand-authored tree; far deeper than any page the builder produces. */
    static final int MAX_DEPTH = 32;

    private static final Set<String> DATA_SOURCE_MODES = Set.of("list", "single");

    /** `{{ token }}` merge tags in a literal string prop — mirrors `interpolate.ts`. */
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");

    /**
     * A {@code data.<name>} reference inside a binding token, in either path or expr mode. The
     * look-behind keeps it to a leading path segment, so {@code metadata.x} and {@code item.data.x}
     * are not mistaken for a page data source.
     */
    private static final Pattern DATA_REFERENCE =
            Pattern.compile("(?<![\\w.$])data\\.([A-Za-z_$][\\w$]*)");

    public enum Severity { ERROR, WARNING }

    /** One problem, addressed by a JSON Pointer into the config document. */
    public record Problem(String path, String message, Severity severity) {

        public boolean isError() {
            return severity == Severity.ERROR;
        }
    }

    private final PageWidgetCatalog catalog;

    public UiPageConfigValidator(PageWidgetCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Validates a config document. Returns every problem found, deduplicated, in document order —
     * an author fixing a page should see all of them at once rather than one per round trip.
     */
    public List<Problem> validate(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return List.of();
        }
        List<Problem> problems = new ArrayList<>();
        Set<String> declaredSources = validateDataSources(config.get("dataSources"), problems);
        validateComponents(config.get("components"), declaredSources, problems);
        scanDataSourceBindings(config.get("dataSources"), declaredSources, problems);
        return List.copyOf(new LinkedHashSet<>(problems));
    }

    /** True when nothing found would block a save. */
    public boolean isSaveable(List<Problem> problems) {
        return problems.stream().noneMatch(Problem::isError);
    }

    // =========================================================================
    // Data sources
    // =========================================================================

    /** Validates the declared sources and returns their names, so bindings can be checked against them. */
    private Set<String> validateDataSources(Object raw, List<Problem> problems) {
        Set<String> names = new LinkedHashSet<>();
        if (raw == null) {
            return names;
        }
        if (!(raw instanceof List<?> list)) {
            problems.add(error("/dataSources", "dataSources must be an array"));
            return names;
        }
        if (list.size() > MAX_PAGE_DATA_SOURCES) {
            problems.add(error("/dataSources", "A page may declare at most " + MAX_PAGE_DATA_SOURCES
                    + " data sources (MAX_PAGE_DATA_SOURCES); " + list.size() + " declared. "
                    + "Each source fires its own fetch on page load; the client drops the extras."));
        }
        for (int i = 0; i < list.size(); i++) {
            String path = "/dataSources/" + i;
            if (!(list.get(i) instanceof Map<?, ?> raw2)) {
                problems.add(error(path, "A data source must be an object"));
                continue;
            }
            Map<String, Object> source = asMap(raw2);

            String name = stringValue(source.get("name"));
            if (isBlank(name)) {
                problems.add(error(path + "/name", "A data source requires a name; it is what "
                        + "bindings reference as data.<name>"));
            } else if (!names.add(name)) {
                problems.add(error(path + "/name", "Duplicate data source name '" + name + "'"));
            }

            if (isBlank(stringValue(source.get("collection")))) {
                problems.add(error(path + "/collection", "A data source requires a collection"));
            }

            validateLimit(source.get("limit"), path + "/limit", problems);
            validateMode(source.get("mode"), path + "/mode", problems);
            validateFilter(source.get("filter"), path + "/filter", problems);
        }
        return names;
    }

    private void validateLimit(Object raw, String path, List<Problem> problems) {
        if (raw == null) {
            return;
        }
        Integer limit = intValue(raw);
        if (limit == null) {
            problems.add(error(path, "limit must be an integer"));
        } else if (limit < 1) {
            problems.add(error(path, "limit must be at least 1"));
        } else if (limit > Pagination.MAX_HTTP_PAGE_SIZE) {
            problems.add(error(path, "limit " + limit + " exceeds the "
                    + Pagination.MAX_HTTP_PAGE_SIZE + "-row cap on a page data source"));
        }
    }

    private void validateMode(Object raw, String path, List<Problem> problems) {
        String mode = stringValue(raw);
        if (mode != null && !DATA_SOURCE_MODES.contains(mode.toLowerCase(Locale.ROOT))) {
            problems.add(error(path, "Unknown data source mode '" + mode + "'; expected list or single"));
        }
    }

    /**
     * A page data source's {@code filter} is a field-to-value map that the client fetch always
     * compares with {@code EQ} ({@code usePageDataSources.buildListUrl}). A value that is an
     * operator map — {@code {"amount": {"GT": 100}}} — would be stringified into the query as
     * {@code [object Object]}, so it is rejected rather than silently mis-filtered.
     */
    private void validateFilter(Object raw, String path, List<Problem> problems) {
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Map<?, ?> rawFilter)) {
            problems.add(error(path, "filter must be an object mapping field names to values"));
            return;
        }
        for (Map.Entry<String, Object> entry : asMap(rawFilter).entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof Map<?, ?> nested) || isBinding(value)) {
                continue;
            }
            for (String operator : operatorsOf(asMap(nested))) {
                if (!"EQ".equalsIgnoreCase(operator)) {
                    problems.add(error(path + "/" + entry.getKey(),
                            "A page data source filter compares with EQ only; operator '" + operator
                                    + "' on field '" + entry.getKey() + "' is not supported"));
                }
            }
        }
    }

    /** Reads the operator(s) out of a nested filter value, in either the `{OP: v}` or `{operator, value}` shape. */
    private List<String> operatorsOf(Map<String, Object> nested) {
        Object explicit = nested.get("operator");
        if (explicit != null) {
            return List.of(String.valueOf(explicit));
        }
        return List.copyOf(nested.keySet());
    }

    /** A source's `filter` values and `recordId` may themselves be bindings over the page scope. */
    private void scanDataSourceBindings(Object raw, Set<String> sources, List<Problem> problems) {
        if (!(raw instanceof List<?> list)) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> rawSource)) {
                continue;
            }
            Map<String, Object> source = asMap(rawSource);
            String path = "/dataSources/" + i;
            scanBindings(source.get("filter"), path + "/filter", 0, sources, problems);
            scanBindings(source.get("recordId"), path + "/recordId", 0, sources, problems);
        }
    }

    // =========================================================================
    // Component tree
    // =========================================================================

    private void validateComponents(Object raw, Set<String> sources, List<Problem> problems) {
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> list)) {
            problems.add(error("/components", "components must be an array"));
            return;
        }
        walkComponents(list, "/components", 0, sources, problems);
    }

    private void walkComponents(List<?> nodes, String path, int depth, Set<String> sources,
                                List<Problem> problems) {
        if (depth > MAX_DEPTH) {
            problems.add(error(path, "Component tree is nested deeper than " + MAX_DEPTH + " levels"));
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            String nodePath = path + "/" + i;
            if (!(nodes.get(i) instanceof Map<?, ?> rawNode)) {
                problems.add(error(nodePath, "A component must be an object"));
                continue;
            }
            Map<String, Object> node = asMap(rawNode);

            String type = stringValue(node.get("type"));
            if (isBlank(type)) {
                problems.add(error(nodePath + "/type", "A component requires a type"));
            } else if (!catalog.isBuiltinType(type)) {
                problems.add(error(nodePath + "/type", "Unknown widget type '" + type
                        + "'; GET /api/pages/widgets lists the built-in catalogue"));
            }

            scanBindings(node.get("props"), nodePath + "/props", depth, sources, problems);
            scanBindings(node.get("events"), nodePath + "/events", depth, sources, problems);

            Object children = node.get("children");
            if (children == null) {
                continue;
            }
            if (!(children instanceof List<?> childNodes)) {
                problems.add(error(nodePath + "/children", "children must be an array"));
                continue;
            }
            walkComponents(childNodes, nodePath + "/children", depth + 1, sources, problems);
        }
    }

    // =========================================================================
    // Bindings
    // =========================================================================

    /**
     * Walks a prop/event subtree for binding tokens — a {@code $bind} object or a literal string
     * carrying {@code {{ … }}} merge tags — and checks each {@code data.<name>} reference against
     * the declared sources.
     */
    private void scanBindings(Object value, String path, int depth, Set<String> sources,
                              List<Problem> problems) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = asMap(rawMap);
            if (map.get("$bind") instanceof String token) {
                checkDataReferences(token, path, sources, problems);
                return;
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                scanBindings(entry.getValue(), path + "/" + entry.getKey(), depth + 1, sources, problems);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                scanBindings(list.get(i), path + "/" + i, depth + 1, sources, problems);
            }
        } else if (value instanceof String text && text.contains("{{")) {
            Matcher matcher = TEMPLATE_TOKEN.matcher(text);
            while (matcher.find()) {
                checkDataReferences(matcher.group(1), path, sources, problems);
            }
        }
    }

    private void checkDataReferences(String token, String path, Set<String> sources,
                                     List<Problem> problems) {
        Matcher matcher = DATA_REFERENCE.matcher(token);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!sources.contains(name)) {
                problems.add(new Problem(path, "Binding references data source '" + name
                        + "', which is not declared in config.dataSources", Severity.WARNING));
            }
        }
    }

    // =========================================================================
    // Parsing helpers
    // =========================================================================

    private static Problem error(String path, String message) {
        return new Problem(path, message, Severity.ERROR);
    }

    private static boolean isBinding(Object value) {
        return value instanceof Map<?, ?> map && map.get("$bind") instanceof String;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
