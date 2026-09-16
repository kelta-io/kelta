package io.kelta.worker.service;

import io.kelta.worker.service.UiPageConfigValidator.Problem;
import io.kelta.worker.service.UiPageConfigValidator.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write-path rules for a {@code ui-pages.config}. The catalogue is the real generated
 * {@code page-widgets.json} on the classpath, so a widget renamed in kelta-ui without a
 * regeneration surfaces here too.
 */
@DisplayName("ui-pages config validation")
class UiPageConfigValidatorTest {

    private UiPageConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UiPageConfigValidator(new PageWidgetCatalog(new ObjectMapper()));
    }

    private static Map<String, Object> component(String id, String type) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("props", new LinkedHashMap<String, Object>());
        return node;
    }

    private static Map<String, Object> dataSource(String name, String collection) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", name);
        source.put("collection", collection);
        source.put("mode", "list");
        return source;
    }

    private List<Problem> errorsOf(List<Problem> problems) {
        return problems.stream().filter(Problem::isError).toList();
    }

    @Test
    @DisplayName("a page built from catalogue widgets and declared sources is clean")
    void validPageHasNoProblems() {
        Map<String, Object> heading = component("h1", "heading");
        heading.put("props", Map.of("text", "Open tickets: {{data.tickets.length}}"));

        Map<String, Object> config = Map.of(
                "schemaVersion", 2,
                "dataSources", List.of(dataSource("tickets", "tickets")),
                "components", List.of(component("c1", "container"), heading));

        assertThat(validator.validate(config)).isEmpty();
    }

    @Test
    @DisplayName("the kelta-test-harness ui-pages fixtures still save (they run only in CI)")
    void harnessFixturesRemainValid() {
        // PageRenderV2ScenarioTest's config, verbatim. Those Testcontainers scenarios do not run
        // under /verify, so a rule tightened here would only surface as a red Quality Gate.
        Map<String, Object> heading = component("h1", "heading");
        heading.put("props", Map.of("text", "Open tickets"));
        Map<String, Object> table = component("t1", "table");
        table.put("props", Map.of("source", "data.tickets"));

        Map<String, Object> config = Map.of(
                "schemaVersion", 2,
                "layout", Map.of("kind", "grid"),
                "variables", List.of(Map.of("name", "statusFilter", "type", "string", "default", "open")),
                "dataSources", List.of(dataSource("tickets", "tickets")),
                "components", List.of(heading, table));

        assertThat(validator.validate(config)).isEmpty();
        // PageRenderAuthzScenarioTest / UiPageCreateScenarioTest.
        assertThat(validator.validate(Map.of("schemaVersion", 2,
                "access", Map.of("requiredPermission", "VIEW_SETUP"),
                "components", List.of(heading)))).isEmpty();
        assertThat(validator.validate(Map.of(
                "layout", Map.of("type", "sidebar"), "components", List.of()))).isEmpty();
    }

    @Test
    @DisplayName("an unknown widget type is an error at /components/<i>/type")
    void unknownWidgetTypeIsAnError() {
        Map<String, Object> config = Map.of("components", List.of(component("c1", "nope")));

        List<Problem> problems = validator.validate(config);

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.path()).isEqualTo("/components/0/type");
            assertThat(problem.severity()).isEqualTo(Severity.ERROR);
            assertThat(problem.message()).contains("nope").contains("/api/pages/widgets");
        });
        assertThat(validator.isSaveable(problems)).isFalse();
    }

    @Test
    @DisplayName("an unknown type nested in children is addressed by its full pointer")
    void unknownTypeInChildrenIsAddressedByPointer() {
        Map<String, Object> container = component("c1", "container");
        container.put("children", List.of(component("c2", "column"), component("c3", "bogus")));

        List<Problem> problems = validator.validate(Map.of("components", List.of(container)));

        assertThat(errorsOf(problems)).singleElement().satisfies(problem ->
                assertThat(problem.path()).isEqualTo("/components/0/children/1/type"));
    }

    @Test
    @DisplayName("a component without a type is an error")
    void missingTypeIsAnError() {
        List<Problem> problems = validator.validate(
                Map.of("components", List.of(Map.of("id", "c1"))));

        assertThat(errorsOf(problems)).singleElement().satisfies(problem ->
                assertThat(problem.path()).isEqualTo("/components/0/type"));
    }

    @Test
    @DisplayName("a {{data.x}} merge tag naming an undeclared source warns and names the source")
    void mergeTagToUndeclaredSourceWarns() {
        Map<String, Object> text = component("t1", "text");
        text.put("props", Map.of("content", "{{data.missing.length}}"));

        List<Problem> problems = validator.validate(Map.of("components", List.of(text)));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.path()).isEqualTo("/components/0/props/content");
            assertThat(problem.severity()).isEqualTo(Severity.WARNING);
            assertThat(problem.message()).contains("missing");
        });
        assertThat(validator.isSaveable(problems)).as("a warning still saves").isTrue();
    }

    @Test
    @DisplayName("a $bind expression naming an undeclared source warns too")
    void bindExpressionToUndeclaredSourceWarns() {
        Map<String, Object> text = component("t1", "text");
        text.put("props", Map.of("content",
                Map.of("$bind", "data.orders.length > 0", "mode", "expr")));

        List<Problem> problems = validator.validate(Map.of("components", List.of(text)));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.severity()).isEqualTo(Severity.WARNING);
            assertThat(problem.message()).contains("orders");
        });
    }

    @Test
    @DisplayName("a binding to a declared source, and a non-data path, are both clean")
    void declaredSourcesAndOtherRootsAreClean() {
        Map<String, Object> text = component("t1", "text");
        text.put("props", Map.of(
                "content", Map.of("$bind", "data.tickets[0].subject"),
                "title", "{{record.name}} / {{item.data.nested}} / {{vars.metadata.x}}"));

        Map<String, Object> config = Map.of(
                "dataSources", List.of(dataSource("tickets", "tickets")),
                "components", List.of(text));

        assertThat(validator.validate(config)).isEmpty();
    }

    @Test
    @DisplayName("more than MAX_PAGE_DATA_SOURCES sources is an error at /dataSources")
    void tooManyDataSourcesIsAnError() {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (int i = 0; i < UiPageConfigValidator.MAX_PAGE_DATA_SOURCES + 1; i++) {
            sources.add(dataSource("source" + i, "tickets"));
        }

        List<Problem> problems = validator.validate(Map.of("dataSources", sources));

        assertThat(errorsOf(problems)).singleElement().satisfies(problem -> {
            assertThat(problem.path()).isEqualTo("/dataSources");
            assertThat(problem.message()).contains("13").contains("12");
        });
    }

    @Test
    @DisplayName("exactly MAX_PAGE_DATA_SOURCES sources is accepted")
    void maxDataSourcesIsAccepted() {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (int i = 0; i < UiPageConfigValidator.MAX_PAGE_DATA_SOURCES; i++) {
            sources.add(dataSource("source" + i, "tickets"));
        }

        assertThat(validator.validate(Map.of("dataSources", sources))).isEmpty();
    }

    @Test
    @DisplayName("a data source limit above the 200-row cap is an error")
    void overCapLimitIsAnError() {
        Map<String, Object> source = dataSource("tickets", "tickets");
        source.put("limit", 500);

        List<Problem> problems = validator.validate(Map.of("dataSources", List.of(source)));

        assertThat(errorsOf(problems)).singleElement().satisfies(problem -> {
            assertThat(problem.path()).isEqualTo("/dataSources/0/limit");
            assertThat(problem.message()).contains("500").contains("200");
        });
    }

    @Test
    @DisplayName("a limit at the cap is accepted")
    void limitAtCapIsAccepted() {
        Map<String, Object> source = dataSource("tickets", "tickets");
        source.put("limit", 200);

        assertThat(validator.validate(Map.of("dataSources", List.of(source)))).isEmpty();
    }

    @Test
    @DisplayName("a filter operator other than EQ is an error")
    void nonEqFilterOperatorIsAnError() {
        Map<String, Object> source = dataSource("tickets", "tickets");
        source.put("filter", Map.of("amount", Map.of("GT", 100)));

        List<Problem> problems = validator.validate(Map.of("dataSources", List.of(source)));

        assertThat(errorsOf(problems)).singleElement().satisfies(problem -> {
            assertThat(problem.path()).isEqualTo("/dataSources/0/filter/amount");
            assertThat(problem.message()).contains("GT").contains("EQ");
        });
    }

    @Test
    @DisplayName("a literal or $bind filter value is EQ and therefore clean")
    void literalAndBoundFilterValuesAreClean() {
        Map<String, Object> source = dataSource("tickets", "tickets");
        source.put("filter", new LinkedHashMap<>(Map.of(
                "status", "open",
                "ownerId", Map.of("$bind", "page.params.userId"))));

        assertThat(validator.validate(Map.of("dataSources", List.of(source)))).isEmpty();
    }

    @Test
    @DisplayName("a nameless or collection-less data source, and an unknown mode, are errors")
    void malformedDataSourceIsAnError() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", "  ");
        source.put("mode", "stream");

        List<Problem> problems = validator.validate(Map.of("dataSources", List.of(source)));

        assertThat(errorsOf(problems)).extracting(Problem::path).containsExactlyInAnyOrder(
                "/dataSources/0/name", "/dataSources/0/collection", "/dataSources/0/mode");
    }

    @Test
    @DisplayName("components/dataSources that are not arrays are errors, not crashes")
    void wrongContainerTypesAreErrors() {
        List<Problem> problems = validator.validate(Map.of(
                "components", "not-an-array",
                "dataSources", "not-an-array"));

        assertThat(errorsOf(problems)).extracting(Problem::path)
                .containsExactlyInAnyOrder("/components", "/dataSources");
    }

    @Test
    @DisplayName("an empty or absent config is clean")
    void emptyConfigIsClean() {
        assertThat(validator.validate(null)).isEmpty();
        assertThat(validator.validate(Map.of())).isEmpty();
        assertThat(validator.validate(Map.of("schemaVersion", 2))).isEmpty();
    }

    @Test
    @DisplayName("a tree nested past the depth cap is an error rather than a stack overflow")
    void overDeepTreeIsAnError() {
        Map<String, Object> node = component("leaf", "container");
        for (int i = 0; i < UiPageConfigValidator.MAX_DEPTH + 2; i++) {
            Map<String, Object> parent = component("n" + i, "container");
            parent.put("children", List.of(node));
            node = parent;
        }

        List<Problem> problems = validator.validate(Map.of("components", List.of(node)));

        assertThat(errorsOf(problems)).isNotEmpty()
                .allSatisfy(problem -> assertThat(problem.message()).contains("nested deeper"));
    }
}
