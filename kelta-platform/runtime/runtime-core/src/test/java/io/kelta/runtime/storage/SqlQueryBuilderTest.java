package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SqlQueryBuilder.buildWhereClause — one SQL mapping per FilterOperator")
class SqlQueryBuilderTest {

    private record Case(FilterCondition filter, String sql, List<Object> params) {}

    /** One row per operator: a missing row fails {@link #everyOperatorRendersItsSql}. */
    private static final Map<FilterOperator, List<Case>> CASES = new EnumMap<>(Map.ofEntries(
            Map.entry(FilterOperator.EQ, List.of(
                    new Case(filter("name", FilterOperator.EQ, "Alpha"), "\"name\" = ?", List.of("Alpha")))),
            Map.entry(FilterOperator.NEQ, List.of(
                    new Case(filter("name", FilterOperator.NEQ, "Alpha"), "\"name\" != ?", List.of("Alpha")))),
            Map.entry(FilterOperator.GT, List.of(
                    new Case(filter("score", FilterOperator.GT, "10"), "\"score\" > ?", List.of(10)))),
            Map.entry(FilterOperator.LT, List.of(
                    new Case(filter("score", FilterOperator.LT, "10"), "\"score\" < ?", List.of(10)))),
            Map.entry(FilterOperator.GTE, List.of(
                    new Case(filter("score", FilterOperator.GTE, "10"), "\"score\" >= ?", List.of(10)))),
            Map.entry(FilterOperator.LTE, List.of(
                    new Case(filter("score", FilterOperator.LTE, "10"), "\"score\" <= ?", List.of(10)))),
            Map.entry(FilterOperator.ISNULL, List.of(
                    new Case(filter("name", FilterOperator.ISNULL, true), "\"name\" IS NULL", List.of()),
                    new Case(filter("name", FilterOperator.ISNULL, "false"), "\"name\" IS NOT NULL", List.of()))),
            Map.entry(FilterOperator.CONTAINS, List.of(
                    new Case(filter("name", FilterOperator.CONTAINS, "lph"), "\"name\" LIKE ?", List.of("%lph%")))),
            Map.entry(FilterOperator.STARTS, List.of(
                    new Case(filter("name", FilterOperator.STARTS, "Al"), "\"name\" LIKE ?", List.of("Al%")))),
            Map.entry(FilterOperator.ENDS, List.of(
                    new Case(filter("name", FilterOperator.ENDS, "ha"), "\"name\" LIKE ?", List.of("%ha")))),
            Map.entry(FilterOperator.ICONTAINS, List.of(
                    new Case(filter("name", FilterOperator.ICONTAINS, "LpH"), "LOWER(\"name\") LIKE ?", List.of("%lph%")))),
            Map.entry(FilterOperator.ISTARTS, List.of(
                    new Case(filter("name", FilterOperator.ISTARTS, "AL"), "LOWER(\"name\") LIKE ?", List.of("al%")))),
            Map.entry(FilterOperator.IENDS, List.of(
                    new Case(filter("name", FilterOperator.IENDS, "HA"), "LOWER(\"name\") LIKE ?", List.of("%ha")))),
            Map.entry(FilterOperator.IEQ, List.of(
                    new Case(filter("name", FilterOperator.IEQ, "ALPHA"), "LOWER(\"name\") = ?", List.of("alpha")))),
            Map.entry(FilterOperator.IN, List.of(
                    new Case(filter("score", FilterOperator.IN, List.of("1", "2", "3")),
                            "\"score\" IN (?, ?, ?)", List.of(1, 2, 3)),
                    new Case(filter("score", FilterOperator.IN, List.of()), "1 = 0", List.of()),
                    new Case(filter("score", FilterOperator.IN, "7"), "\"score\" = ?", List.of(7))))));

    private static final CollectionDefinition ITEMS = new CollectionDefinitionBuilder().name("items")
            .addField(FieldDefinition.string("name"))
            .addField(FieldDefinition.integer("score"))
            .addField(new FieldDefinition("tags", FieldType.MULTI_PICKLIST, true, false, false, null, null, null, null, null))
            .addField(FieldDefinition.json("touches"))
            .build();

    private final SqlQueryBuilder builder = new SqlQueryBuilder((value, type) -> value);

    private static FilterCondition filter(String field, FilterOperator operator, Object value) {
        return new FilterCondition(field, operator, value);
    }

    private String where(List<FilterCondition> filters, List<Object> params) {
        return builder.buildWhereClause(filters, null, ITEMS, params);
    }

    @ParameterizedTest
    @EnumSource(FilterOperator.class)
    @DisplayName("renders the SQL fragment and bound parameters")
    void everyOperatorRendersItsSql(FilterOperator operator) {
        assertThat(CASES).as("expectation for FilterOperator." + operator + " — add one when adding an operator")
                .containsKey(operator);

        for (Case c : CASES.get(operator)) {
            List<Object> params = new ArrayList<>();
            assertThat(where(List.of(c.filter()), params)).as(c.filter().toString()).isEqualTo(c.sql());
            assertThat(params).as(c.filter() + " params").containsExactlyElementsOf(c.params());
        }
    }

    @Test
    @DisplayName("filters and the tenant scope AND together, binding parameters in clause order")
    void filtersAndTenantScopeJoinInOrder() {
        List<Object> params = new ArrayList<>();
        String sql = builder.buildWhereClause(
                List.of(filter("name", FilterOperator.EQ, "Alpha"), filter("score", FilterOperator.GT, "5")),
                new TenantScopePolicy.TenantScope("\"tenant_id\" = ?", List.of("t-1")),
                ITEMS, params);

        assertThat(sql).isEqualTo("\"name\" = ? AND \"score\" > ? AND \"tenant_id\" = ?");
        assertThat(params).containsExactly("Alpha", 5, "t-1");
    }

    @Test
    @DisplayName("array-backed MULTI_PICKLIST columns use ANY/ALL/unnest instead of scalar operators")
    void arrayColumnOperators() {
        List<Object> params = new ArrayList<>();
        assertThat(where(List.of(filter("tags", FilterOperator.EQ, "red")), params)).isEqualTo("? = ANY(\"tags\")");
        assertThat(where(List.of(filter("tags", FilterOperator.NEQ, "red")), params)).isEqualTo("? <> ALL(\"tags\")");
        assertThat(where(List.of(filter("tags", FilterOperator.CONTAINS, "red")), params)).isEqualTo("? = ANY(\"tags\")");
        assertThat(where(List.of(filter("tags", FilterOperator.ICONTAINS, "RED")), params))
                .isEqualTo("EXISTS (SELECT 1 FROM unnest(\"tags\") AS elt WHERE LOWER(elt) = ?)");
        assertThat(params).containsExactly("red", "red", "red", "red");
    }

    @Test
    @DisplayName("JSONB-backed JSON columns match text-pattern operators against their JSON text")
    void jsonColumnTextPatternOperators() {
        List<Object> params = new ArrayList<>();
        assertThat(where(List.of(filter("touches", FilterOperator.CONTAINS, "agents/x.sh")), params))
                .isEqualTo("\"touches\"::text LIKE ?");
        assertThat(where(List.of(filter("touches", FilterOperator.STARTS, "[\"a")), params))
                .isEqualTo("\"touches\"::text LIKE ?");
        assertThat(where(List.of(filter("touches", FilterOperator.ENDS, "\"]")), params))
                .isEqualTo("\"touches\"::text LIKE ?");
        assertThat(where(List.of(filter("touches", FilterOperator.ICONTAINS, "AGENTS")), params))
                .isEqualTo("LOWER(\"touches\"::text) LIKE ?");
        assertThat(where(List.of(filter("touches", FilterOperator.ISTARTS, "[")), params))
                .isEqualTo("LOWER(\"touches\"::text) LIKE ?");
        assertThat(where(List.of(filter("touches", FilterOperator.IENDS, "]")), params))
                .isEqualTo("LOWER(\"touches\"::text) LIKE ?");
        assertThat(where(List.of(filter("touches", FilterOperator.IEQ, "[]")), params))
                .isEqualTo("LOWER(\"touches\"::text) = ?");
        assertThat(params).containsExactly("%agents/x.sh%", "[\"a%", "%\"]", "%agents%", "[%", "%]", "[]");
    }
}
