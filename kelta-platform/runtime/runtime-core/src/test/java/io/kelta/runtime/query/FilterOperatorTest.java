package io.kelta.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link FilterOperator#parse(String)} — the single vocabulary shared by
 * the API filter grammar ({@link FilterCondition#fromParams}), dashboard
 * widgets ({@code DashboardDataService.mapOperator}), and the MCP
 * {@code query_collection} tool.
 */
class FilterOperatorTest {

    @Test
    void resolvesCanonicalNamesCaseInsensitively() {
        assertEquals(FilterOperator.EQ, FilterOperator.parse("eq"));
        assertEquals(FilterOperator.EQ, FilterOperator.parse("EQ"));
        assertEquals(FilterOperator.IN, FilterOperator.parse("in"));
        assertEquals(FilterOperator.ICONTAINS, FilterOperator.parse("icontains"));
    }

    @Test
    void resolvesAnyAliasToIn() {
        assertEquals(FilterOperator.IN, FilterOperator.parse("any"));
        assertEquals(FilterOperator.IN, FilterOperator.parse("ANY"));
    }

    @Test
    void resolvesUiAliases() {
        assertEquals(FilterOperator.EQ, FilterOperator.parse("equals"));
        assertEquals(FilterOperator.NEQ, FilterOperator.parse("not_equals"));
        assertEquals(FilterOperator.GT, FilterOperator.parse("greater_than"));
        assertEquals(FilterOperator.LT, FilterOperator.parse("less_than"));
        assertEquals(FilterOperator.GTE, FilterOperator.parse("greater_than_or_equal"));
        assertEquals(FilterOperator.LTE, FilterOperator.parse("less_than_or_equal"));
        assertEquals(FilterOperator.STARTS, FilterOperator.parse("starts_with"));
        assertEquals(FilterOperator.ENDS, FilterOperator.parse("ends_with"));
    }

    @Test
    void unknownTokenThrowsWithAcceptedNames() {
        InvalidFilterException ex = assertThrows(InvalidFilterException.class,
                () -> FilterOperator.parse("nope"));
        assertTrue(ex.getMessage().contains("nope"));
        assertTrue(ex.getMessage().contains("eq"));
        assertTrue(ex.getMessage().contains("in"));
    }

    @Test
    void blankTokenThrows() {
        assertThrows(InvalidFilterException.class, () -> FilterOperator.parse(""));
        assertThrows(InvalidFilterException.class, () -> FilterOperator.parse(null));
    }
}
