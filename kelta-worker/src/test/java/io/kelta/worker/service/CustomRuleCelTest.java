package io.kelta.worker.service;

import io.kelta.worker.service.CustomRuleCel.InvalidConditionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Custom-rule conditions become CEL inside a Cerbos policy, so what an admin types must reach
 * the policy as data, never as expression syntax.
 */
@DisplayName("CustomRuleCel")
class CustomRuleCelTest {

    @Nested
    @DisplayName("stringLiteral")
    class StringLiteralTests {

        @Test
        @DisplayName("escapes quotes, backslashes and control characters")
        void escapes() {
            assertThat(CustomRuleCel.stringLiteral("plain")).isEqualTo("\"plain\"");
            assertThat(CustomRuleCel.stringLiteral("a\"b")).isEqualTo("\"a\\\"b\"");
            assertThat(CustomRuleCel.stringLiteral("a\\b")).isEqualTo("\"a\\\\b\"");
            assertThat(CustomRuleCel.stringLiteral("a\nb\tc\rd")).isEqualTo("\"a\\nb\\tc\\rd\"");
            assertThat(CustomRuleCel.stringLiteral("a\u0001b")).isEqualTo("\"a\\u0001b\"");
            assertThat(CustomRuleCel.stringLiteral("Zürich")).isEqualTo("\"Zürich\"");
        }

        @ParameterizedTest
        @ValueSource(strings = {"x\" || true || \"", "\\", "\"", "a\\\" || true", "line\nbreak", "\u0000"})
        @DisplayName("always yields a single well-formed literal")
        void alwaysOneLiteral(String value) {
            String cel = "R.attr.status == " + CustomRuleCel.stringLiteral(value);
            assertThat(CustomRuleCel.isWellFormed(cel)).isTrue();
            assertThat(CustomRuleCel.isWellFormed(cel + " && false")).isTrue();
        }
    }

    @Nested
    @DisplayName("visualToCel")
    class VisualTests {

        @Test
        @DisplayName("equals / not_equals / contains quote the value as a CEL string")
        void scalarOperators() throws Exception {
            assertThat(CustomRuleCel.visualToCel("status", "equals", "open"))
                    .isEqualTo("R.attr.status == \"open\"");
            assertThat(CustomRuleCel.visualToCel("title", "equals", "Say \"hi\" \\ bye"))
                    .isEqualTo("R.attr.title == \"Say \\\"hi\\\" \\\\ bye\"");
            assertThat(CustomRuleCel.visualToCel("status", "not_equals", "x\" || true || \""))
                    .isEqualTo("R.attr.status != \"x\\\" || true || \\\"\"");
            assertThat(CustomRuleCel.visualToCel("name", "contains", "a\"b"))
                    .isEqualTo("R.attr.name.contains(\"a\\\"b\")");
            assertThat(CustomRuleCel.visualToCel("count", "equals", 5)).isEqualTo("R.attr.count == \"5\"");
        }

        @Test
        @DisplayName("$CURRENT_USER compares with the caller's user id for equals and not_equals")
        void currentUser() throws Exception {
            assertThat(CustomRuleCel.visualToCel("createdBy", "equals", "$CURRENT_USER"))
                    .isEqualTo("R.attr.createdBy == P.attr.userId");
            assertThat(CustomRuleCel.visualToCel("createdBy", "not_equals", "$CURRENT_USER"))
                    .isEqualTo("R.attr.createdBy != P.attr.userId");
        }

        @Test
        @DisplayName("in splits comma-separated text into a CEL list")
        void inSplitsText() throws Exception {
            assertThat(CustomRuleCel.visualToCel("region", "in", "US,EU"))
                    .isEqualTo("R.attr.region in [\"US\", \"EU\"]");
            assertThat(CustomRuleCel.visualToCel("region", "in", " US , EU ,, "))
                    .isEqualTo("R.attr.region in [\"US\", \"EU\"]");
            assertThat(CustomRuleCel.visualToCel("region", "in", "single"))
                    .isEqualTo("R.attr.region in [\"single\"]");
        }

        @Test
        @DisplayName("in takes a JSON list as is, each item escaped")
        void inTakesList() throws Exception {
            assertThat(CustomRuleCel.visualToCel("region", "in", List.of("US", "E\"U", "a,b")))
                    .isEqualTo("R.attr.region in [\"US\", \"E\\\"U\", \"a,b\"]");
        }

        @Test
        @DisplayName("greater_than / less_than take a number and compare as double")
        void numericOperators() throws Exception {
            assertThat(CustomRuleCel.visualToCel("amount", "greater_than", "100"))
                    .isEqualTo("double(R.attr.amount) > 100.0");
            assertThat(CustomRuleCel.visualToCel("amount", "less_than", 2.5))
                    .isEqualTo("double(R.attr.amount) < 2.5");
            assertThat(CustomRuleCel.visualToCel("amount", "less_than", "-3"))
                    .isEqualTo("double(R.attr.amount) < -3.0");
        }

        @Test
        @DisplayName("every compiled condition parses")
        void compiledConditionsParse() throws Exception {
            for (String op : CustomRuleCel.OPERATORS) {
                Object value = op.endsWith("_than") ? "1" : "x\"y";
                assertThat(CustomRuleCel.isWellFormed(CustomRuleCel.visualToCel("f", op, value)))
                        .as(op).isTrue();
            }
        }

        @Test
        @DisplayName("refuses an unknown operator")
        void unknownOperator() {
            assertInvalid(() -> CustomRuleCel.visualToCel("status", "matches", "x"), "/condition/operator");
            assertInvalid(() -> CustomRuleCel.visualToCel("status", null, "x"), "/condition/operator");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "1abc", "status == \"x\" || true", "a.b", "a b", "in", "null"})
        @DisplayName("refuses a field that is not a usable identifier")
        void badField(String field) {
            assertInvalid(() -> CustomRuleCel.visualToCel(field, "equals", "x"), "/condition/field");
        }

        @Test
        @DisplayName("refuses values of the wrong shape")
        void badValues() {
            assertInvalid(() -> CustomRuleCel.visualToCel("amount", "greater_than", "abc"), "/condition/value");
            assertInvalid(() -> CustomRuleCel.visualToCel("amount", "greater_than", "1) || true"), "/condition/value");
            assertInvalid(() -> CustomRuleCel.visualToCel("amount", "greater_than", "NaN"), "/condition/value");
            assertInvalid(() -> CustomRuleCel.visualToCel("amount", "less_than", ""), "/condition/value");
            assertInvalid(() -> CustomRuleCel.visualToCel("amount", "less_than", "1e999"), "/condition/value");
            assertInvalid(() -> CustomRuleCel.visualToCel("region", "in", ", ,"), "/condition/value");
            assertInvalid(() -> CustomRuleCel.visualToCel("region", "in", List.of()), "/condition/value");
            assertInvalid(() -> CustomRuleCel.visualToCel("region", "in", Map.of("a", 1)), "/condition/value");
            assertInvalid(() -> CustomRuleCel.visualToCel("status", "equals", null), "/condition/value");
            assertInvalid(() -> CustomRuleCel.visualToCel("status", "equals", List.of("x")), "/condition/value");
            assertInvalid(() -> CustomRuleCel.visualToCel("status", "equals", "bad\uD800"), "/condition/value");
        }
    }

    @Nested
    @DisplayName("compile")
    class CompileTests {

        @Test
        @DisplayName("passes CEL that parses through unchanged")
        void celPassesThrough() throws Exception {
            String cel = "R.attr.status == \"open\" || has(R.attr.owner) && R.attr.tags.exists(t, t == \"x\")";
            assertThat(CustomRuleCel.compile("cel", Map.of("expression", cel))).isEqualTo(cel);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "R.attr.region in US,EU", "R.attr.a == \"x\") || (true",
                "R.attr.a == \"x\" // note", "R.attr.a ==", "\"unterminated"})
        @DisplayName("refuses CEL that is empty, does not parse, or ends in a comment")
        void refusesBadCel(String cel) {
            assertInvalid(() -> CustomRuleCel.compile("cel", Map.of("expression", cel)), "/condition/expression");
        }

        @Test
        @DisplayName("refuses a missing expression, a non-object condition and an unknown type")
        void refusesShape() {
            assertInvalid(() -> CustomRuleCel.compile("cel", Map.of("expr", "true")), "/condition/expression");
            assertInvalid(() -> CustomRuleCel.compile("cel", "true"), "/condition");
            assertInvalid(() -> CustomRuleCel.compile("visual", null), "/condition");
            assertInvalid(() -> CustomRuleCel.compile("sql", Map.of()), "/conditionType");
        }

        @Test
        @DisplayName("compiles a visual condition")
        void visual() throws Exception {
            assertThat(CustomRuleCel.compile("visual", Map.of("field", "region", "operator", "in", "value", "US,EU")))
                    .isEqualTo("R.attr.region in [\"US\", \"EU\"]");
        }

        @Test
        @DisplayName("reports where CEL fails to parse")
        void reportsPosition() {
            InvalidConditionException e = catchThrowableOfType(InvalidConditionException.class,
                    () -> CustomRuleCel.compile("cel", Map.of("expression", "a) || (b")));
            assertThat(e.getMessage()).startsWith("expression is not valid CEL: ").contains("column 2");
        }
    }

    @Nested
    @DisplayName("effects")
    class EffectTests {

        @Test
        @DisplayName("parseEffect accepts allow and deny in any case and nothing else")
        void parseEffect() {
            assertThat(CustomRuleCel.parseEffect("deny")).isEqualTo("deny");
            assertThat(CustomRuleCel.parseEffect("DENY")).isEqualTo("deny");
            assertThat(CustomRuleCel.parseEffect(" Allow ")).isEqualTo("allow");
            assertThat(CustomRuleCel.parseEffect("EFFECT_DENY")).isNull();
            assertThat(CustomRuleCel.parseEffect("block")).isNull();
            assertThat(CustomRuleCel.parseEffect(null)).isNull();
            assertThat(CustomRuleCel.parseEffect(true)).isNull();
        }

        @Test
        @DisplayName("isAllow / isDeny read both the stored and the Cerbos spelling")
        void readStored() {
            assertThat(CustomRuleCel.isAllow("allow")).isTrue();
            assertThat(CustomRuleCel.isAllow("EFFECT_ALLOW")).isTrue();
            assertThat(CustomRuleCel.isAllow("deny")).isFalse();
            assertThat(CustomRuleCel.isAllow(null)).isFalse();
            assertThat(CustomRuleCel.isDeny("DENY")).isTrue();
            assertThat(CustomRuleCel.isDeny("effect_deny")).isTrue();
            assertThat(CustomRuleCel.isDeny("allow")).isFalse();
        }
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static void assertInvalid(ThrowingCall call, String pointer) {
        InvalidConditionException e = catchThrowableOfType(InvalidConditionException.class, call::run);
        assertThat(e).as("expected an invalid condition at %s", pointer).isNotNull();
        assertThat(e.pointer()).isEqualTo(pointer);
        assertThat(e.getMessage()).isNotBlank();
    }
}
