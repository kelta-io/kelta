package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@link FilterOperator} run through {@link PhysicalTableStorageAdapter#query} against a
 * real Postgres, asserting which seeded rows match. {@link SqlQueryBuilderTest} pins the SQL text;
 * this pins what that SQL selects (LIKE patterns, LOWER() folding, NULL handling, IN lists).
 *
 * <p>Runs only under the integration profile (Testcontainers needs Docker).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("SqlQueryBuilder — every FilterOperator selects the right rows (Postgres)")
class SqlQueryBuilderFilterOperatorIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15");

    private record Case(FilterCondition filter, List<String> expectedIds) {}

    /*
     * Seeded rows:  r1 "Alpha Widget" 10 · r2 "alpha gadget" 20 · r3 "Beta WIDGET" 30 · r4 null 40
     */
    private static final Map<FilterOperator, List<Case>> CASES = new EnumMap<>(Map.ofEntries(
            Map.entry(FilterOperator.EQ, List.of(
                    c("name", FilterOperator.EQ, "Alpha Widget", "r1"),
                    c("name", FilterOperator.EQ, "alpha widget"),
                    c("score", FilterOperator.EQ, "20", "r2"))),
            Map.entry(FilterOperator.NEQ, List.of(
                    c("name", FilterOperator.NEQ, "Alpha Widget", "r2", "r3"),
                    c("score", FilterOperator.NEQ, "20", "r1", "r3", "r4"))),
            Map.entry(FilterOperator.GT, List.of(c("score", FilterOperator.GT, "20", "r3", "r4"))),
            Map.entry(FilterOperator.LT, List.of(c("score", FilterOperator.LT, "20", "r1"))),
            Map.entry(FilterOperator.GTE, List.of(c("score", FilterOperator.GTE, "20", "r2", "r3", "r4"))),
            Map.entry(FilterOperator.LTE, List.of(c("score", FilterOperator.LTE, "20", "r1", "r2"))),
            Map.entry(FilterOperator.ISNULL, List.of(
                    c("name", FilterOperator.ISNULL, true, "r4"),
                    c("name", FilterOperator.ISNULL, false, "r1", "r2", "r3"),
                    c("name", FilterOperator.ISNULL, "true", "r4"))),
            Map.entry(FilterOperator.CONTAINS, List.of(
                    c("name", FilterOperator.CONTAINS, "Widget", "r1"),
                    c("name", FilterOperator.CONTAINS, "a Wi", "r1"))),
            Map.entry(FilterOperator.STARTS, List.of(
                    c("name", FilterOperator.STARTS, "Alpha", "r1"),
                    c("name", FilterOperator.STARTS, "alpha", "r2"))),
            Map.entry(FilterOperator.ENDS, List.of(
                    c("name", FilterOperator.ENDS, "WIDGET", "r3"),
                    c("name", FilterOperator.ENDS, "dget", "r1", "r2"))),
            Map.entry(FilterOperator.ICONTAINS, List.of(
                    c("name", FilterOperator.ICONTAINS, "wIdGeT", "r1", "r3"),
                    c("name", FilterOperator.ICONTAINS, "ALPHA", "r1", "r2"))),
            Map.entry(FilterOperator.ISTARTS, List.of(c("name", FilterOperator.ISTARTS, "ALPHA", "r1", "r2"))),
            Map.entry(FilterOperator.IENDS, List.of(c("name", FilterOperator.IENDS, "Widget", "r1", "r3"))),
            Map.entry(FilterOperator.IEQ, List.of(
                    c("name", FilterOperator.IEQ, "ALPHA WIDGET", "r1"),
                    c("name", FilterOperator.IEQ, "beta widget", "r3"))),
            Map.entry(FilterOperator.IN, List.of(
                    c("score", FilterOperator.IN, List.of("10", "30", "40"), "r1", "r3", "r4"),
                    c("name", FilterOperator.IN, List.of("Alpha Widget", "Beta WIDGET", "nope"), "r1", "r3"),
                    c("name", FilterOperator.IN, List.of())))));

    private PhysicalTableStorageAdapter adapter;
    private CollectionDefinition items;

    private static Case c(String field, FilterOperator operator, Object value, String... expectedIds) {
        return new Case(new FilterCondition(field, operator, value), List.of(expectedIds));
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS filter_operator_items");
        adapter = new PhysicalTableStorageAdapter(jdbc, new SchemaMigrationEngine(jdbc),
                JsonMapper.builder().build());
        items = new CollectionDefinitionBuilder().name("filter_operator_items")
                .addField(FieldDefinition.string("name"))
                .addField(FieldDefinition.integer("score"))
                .addField(FieldDefinition.json("touches"))
                .build();
        adapter.initializeCollection(items);
        insert("r1", "Alpha Widget", 10);
        insert("r2", "alpha gadget", 20);
        insert("r3", "Beta WIDGET", 30);
        insert("r4", null, 40);
    }

    private void insert(String id, String name, int score) {
        insert(id, name, score, null);
    }

    private void insert(String id, String name, int score, List<String> touches) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        if (name != null) {
            data.put("name", name);
        }
        data.put("score", score);
        if (touches != null) {
            data.put("touches", touches);
        }
        data.put("createdBy", "tester");
        data.put("updatedBy", "tester");
        data.put("createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        data.put("updatedAt", Instant.parse("2026-01-01T00:00:00Z"));
        adapter.create(items, data);
    }

    private List<String> ids(FilterCondition filter) {
        QueryRequest request = new QueryRequest(Pagination.defaults(), List.of(), List.of(), List.of(filter));
        return adapter.query(items, request).data().stream().map(r -> (String) r.get("id")).sorted().toList();
    }

    @ParameterizedTest
    @EnumSource(FilterOperator.class)
    @DisplayName("selects exactly the expected seeded rows")
    void operatorSelectsExpectedRows(FilterOperator operator) {
        assertThat(CASES).as("expectation for FilterOperator." + operator + " — add one when adding an operator")
                .containsKey(operator);

        for (Case c : CASES.get(operator)) {
            assertThat(ids(c.filter())).as(c.filter().toString()).containsExactlyElementsOf(c.expectedIds());
        }
    }

    @Test
    @DisplayName("text-pattern operators on a JSONB column match its JSON text instead of failing")
    void jsonColumnTextPatterns() {
        insert("r5", "Gamma", 50, List.of("agents/planner/run.sh", "tenant/apply.sh"));
        insert("r6", "Delta", 60, List.of("docs/README.md"));

        assertThat(ids(new FilterCondition("touches", FilterOperator.CONTAINS, "agents/planner"))).containsExactly("r5");
        assertThat(ids(new FilterCondition("touches", FilterOperator.CONTAINS, "readme"))).isEmpty();
        assertThat(ids(new FilterCondition("touches", FilterOperator.ICONTAINS, "readme"))).containsExactly("r6");
        assertThat(ids(new FilterCondition("touches", FilterOperator.STARTS, "[\"docs/"))).containsExactly("r6");
    }
}
