package io.kelta.runtime.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link FilterCondition#fromParams(MultiValueMap)} — the only path that
 * understands the {@code in}/{@code any} operator and merges repeated query
 * params. The single-value {@link FilterCondition#fromParams(java.util.Map)}
 * adapter is exercised via {@link QueryRequestWithFiltersTest}.
 */
class FilterConditionTest {

    private static MultiValueMap<String, String> params() {
        return new LinkedMultiValueMap<>();
    }

    @Nested
    @DisplayName("IN operator (and ANY alias)")
    class InOperator {

        @Test
        void csvProducesSingleConditionWithListValue() {
            MultiValueMap<String, String> p = params();
            p.add("filter[id][in]", "a,b,c");

            List<FilterCondition> filters = FilterCondition.fromParams(p);

            assertEquals(1, filters.size());
            FilterCondition c = filters.get(0);
            assertEquals("id", c.fieldName());
            assertEquals(FilterOperator.IN, c.operator());
            Collection<?> value = assertInstanceOf(Collection.class, c.value());
            assertEquals(List.of("a", "b", "c"), List.copyOf(value));
        }

        @Test
        void repeatedParamsAreMerged() {
            MultiValueMap<String, String> p = params();
            p.add("filter[id][in]", "a");
            p.add("filter[id][in]", "b");

            List<FilterCondition> filters = FilterCondition.fromParams(p);

            assertEquals(1, filters.size());
            Collection<?> value = (Collection<?>) filters.get(0).value();
            assertEquals(List.of("a", "b"), List.copyOf(value));
        }

        @Test
        void csvAndRepeatedParamsCombine() {
            MultiValueMap<String, String> p = params();
            p.add("filter[id][in]", "a,b");
            p.add("filter[id][in]", "c");

            Collection<?> value = (Collection<?>) FilterCondition.fromParams(p).get(0).value();
            assertEquals(List.of("a", "b", "c"), List.copyOf(value));
        }

        @Test
        void duplicatesAreDeduped() {
            MultiValueMap<String, String> p = params();
            p.add("filter[id][in]", "a,b,a");
            p.add("filter[id][in]", "b");

            Collection<?> value = (Collection<?>) FilterCondition.fromParams(p).get(0).value();
            assertEquals(List.of("a", "b"), List.copyOf(value));
        }

        @Test
        void anyAliasResolvesToIn() {
            MultiValueMap<String, String> p = params();
            p.add("filter[id][any]", "a,b");

            FilterCondition c = FilterCondition.fromParams(p).get(0);
            assertEquals(FilterOperator.IN, c.operator());
            assertEquals(List.of("a", "b"), List.copyOf((Collection<?>) c.value()));
        }

        @Test
        void uppercaseOperatorTokenAccepted() {
            MultiValueMap<String, String> p = params();
            p.add("filter[id][IN]", "a,b");

            FilterCondition c = FilterCondition.fromParams(p).get(0);
            assertEquals(FilterOperator.IN, c.operator());
        }

        @Test
        void blankAndWhitespaceValuesDropped() {
            MultiValueMap<String, String> p = params();
            p.add("filter[id][in]", " a , , b ");

            Collection<?> value = (Collection<?>) FilterCondition.fromParams(p).get(0).value();
            assertEquals(List.of("a", "b"), List.copyOf(value));
        }

        @Test
        void emptyValueThrows400() {
            MultiValueMap<String, String> p = params();
            p.add("filter[id][in]", "");

            InvalidFilterException ex = assertThrows(
                    InvalidFilterException.class,
                    () -> FilterCondition.fromParams(p));
            assertTrue(ex.getMessage().contains("id"));
            assertTrue(ex.getMessage().contains("in"));
        }

        @Test
        void overCapThrows400() {
            MultiValueMap<String, String> p = params();
            StringBuilder csv = new StringBuilder();
            for (int i = 0; i < FilterCondition.MAX_IN_LIST_SIZE + 1; i++) {
                if (i > 0) csv.append(',');
                csv.append(UUID.randomUUID());
            }
            p.add("filter[id][in]", csv.toString());

            InvalidFilterException ex = assertThrows(
                    InvalidFilterException.class,
                    () -> FilterCondition.fromParams(p));
            assertTrue(ex.getMessage().contains(String.valueOf(FilterCondition.MAX_IN_LIST_SIZE)));
        }

        @Test
        void exactlyAtCapIsAccepted() {
            MultiValueMap<String, String> p = params();
            StringBuilder csv = new StringBuilder();
            for (int i = 0; i < FilterCondition.MAX_IN_LIST_SIZE; i++) {
                if (i > 0) csv.append(',');
                csv.append(UUID.randomUUID());
            }
            p.add("filter[id][in]", csv.toString());

            Collection<?> value = (Collection<?>) FilterCondition.fromParams(p).get(0).value();
            assertEquals(FilterCondition.MAX_IN_LIST_SIZE, value.size());
        }
    }

    @Nested
    @DisplayName("Other operators")
    class OtherOperators {

        @Test
        void eqWithCommaIsTreatedAsSingleLiteral() {
            // Documented behavior — values containing commas (e.g. "Smith, John")
            // must round-trip through EQ unchanged. Callers wanting multi-value
            // semantics use the IN operator.
            MultiValueMap<String, String> p = params();
            p.add("filter[name][eq]", "Smith, John");

            List<FilterCondition> filters = FilterCondition.fromParams(p);
            assertEquals(1, filters.size());
            assertEquals(FilterOperator.EQ, filters.get(0).operator());
            assertEquals("Smith, John", filters.get(0).value());
        }

        @Test
        void unknownOperatorThrows400() {
            MultiValueMap<String, String> p = params();
            p.add("filter[name][eqq]", "x");

            InvalidFilterException ex = assertThrows(
                    InvalidFilterException.class,
                    () -> FilterCondition.fromParams(p));
            assertTrue(ex.getMessage().contains("eqq"));
            assertTrue(ex.getMessage().contains("name"));
        }

        @Test
        void repeatedScalarOperatorTakesLastValue() {
            // Matches Spring's @RequestParam Map<String, String> semantics that
            // existing callers were built against.
            MultiValueMap<String, String> p = params();
            p.add("filter[name][eq]", "first");
            p.add("filter[name][eq]", "second");

            FilterCondition c = FilterCondition.fromParams(p).get(0);
            assertEquals("second", c.value());
        }

        @Test
        void nonFilterKeysAreIgnored() {
            MultiValueMap<String, String> p = params();
            p.add("page[size]", "10");
            p.add("sort", "name");
            p.add("filter[name][eq]", "x");

            List<FilterCondition> filters = FilterCondition.fromParams(p);
            assertEquals(1, filters.size());
            assertEquals("name", filters.get(0).fieldName());
        }

        @Test
        void emptyMapReturnsEmpty() {
            assertEquals(List.of(), FilterCondition.fromParams(params()));
        }

        @Test
        void nullMapReturnsEmpty() {
            assertEquals(List.of(), FilterCondition.fromParams((MultiValueMap<String, String>) null));
        }
    }

    @Nested
    @DisplayName("EQ shorthand and malformed keys")
    class ShorthandAndMalformedKeys {

        @Test
        void fieldOnlyShorthandBehavesAsEq() {
            MultiValueMap<String, String> p = params();
            p.add("filter[status]", "a");

            List<FilterCondition> filters = FilterCondition.fromParams(p);

            assertEquals(1, filters.size());
            FilterCondition c = filters.get(0);
            assertEquals("status", c.fieldName());
            assertEquals(FilterOperator.EQ, c.operator());
            assertEquals("a", c.value());
        }

        @Test
        void repeatedKeyInBracketsThrows400WithGrammar() {
            // filter[status][in][]=a — a shape browsers/HTML forms commonly send
            // for array params. Previously silently dropped (whole collection
            // returned unfiltered); must now be rejected.
            MultiValueMap<String, String> p = params();
            p.add("filter[status][in][]", "a");

            InvalidFilterException ex = assertThrows(
                    InvalidFilterException.class,
                    () -> FilterCondition.fromParams(p));
            assertTrue(ex.getMessage().contains("filter[field][op]=value | filter[field]=value"));
        }

        @Test
        void indexedBracketKeyThrows400WithGrammar() {
            MultiValueMap<String, String> p = params();
            p.add("filter[status][in][0]", "a");

            InvalidFilterException ex = assertThrows(
                    InvalidFilterException.class,
                    () -> FilterCondition.fromParams(p));
            assertTrue(ex.getMessage().contains("filter[field][op]=value | filter[field]=value"));
        }
    }

    @Nested
    @DisplayName("Single-value Map adapter (legacy callers)")
    class SingleValueMapAdapter {

        @Test
        void delegatesToMultiValuePath() {
            List<FilterCondition> filters = FilterCondition.fromParams(
                    java.util.Map.of("filter[id][in]", "a,b,c"));

            assertEquals(1, filters.size());
            assertEquals(FilterOperator.IN, filters.get(0).operator());
            assertEquals(List.of("a", "b", "c"),
                    List.copyOf((Collection<?>) filters.get(0).value()));
        }

        @Test
        void unknownOperatorStillThrows() {
            assertThrows(InvalidFilterException.class,
                    () -> FilterCondition.fromParams(java.util.Map.of("filter[x][nope]", "y")));
        }
    }
}
