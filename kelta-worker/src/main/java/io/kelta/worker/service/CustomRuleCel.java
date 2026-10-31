package io.kelta.worker.service;

import dev.cel.common.CelIssue;
import dev.cel.common.CelSourceLocation;
import dev.cel.common.CelValidationResult;
import dev.cel.parser.CelParser;
import dev.cel.parser.CelParserFactory;
import dev.cel.parser.CelStandardMacro;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Turns the stored condition of a profile custom rule ({@code profile_custom_rules}) into CEL,
 * and checks CEL before it reaches a Cerbos policy. It is the one place both the admin API
 * ({@code CustomRuleController}, at save) and the policy sync ({@code CerbosPolicySyncService} /
 * {@code CerbosPolicyGenerator}, at push) do this, so a rule the API accepts is a rule the sync
 * compiles the same way.
 *
 * <ul>
 *   <li><b>Effect</b> — stored as {@code allow} or {@code deny}. {@link #parseEffect} accepts only
 *       those two (any case) at save; {@link #isAllow} maps a stored value for the policy, where
 *       anything that is not {@code allow}/{@code EFFECT_ALLOW} is treated as a deny.</li>
 *   <li><b>Visual condition</b> {@code {field, operator, value}} — {@link #visualToCel}. The field
 *       must be an identifier; values become escaped CEL string literals ({@link #stringLiteral});
 *       {@code in} takes a list or comma-separated text and becomes a CEL list;
 *       {@code greater_than}/{@code less_than} take a number; {@code $CURRENT_USER} compares with
 *       {@code P.attr.userId}. Anything else is an {@link InvalidConditionException}.</li>
 *   <li><b>CEL condition</b> {@code {expression}} — must parse as CEL on its own and inside
 *       parentheses ({@link #parseProblem}), so the generator's {@code guard && (expression)}
 *       always keeps the whole expression under the collection guard. The parser is cel-java,
 *       which cerbos-sdk-java already brings in (through protovalidate). It checks syntax only:
 *       Cerbos compiles the policy, so an unknown function or attribute still surfaces there.</li>
 * </ul>
 */
public final class CustomRuleCel {

    public static final String EFFECT_ALLOW = "allow";
    public static final String EFFECT_DENY = "deny";

    public static final String TYPE_VISUAL = "visual";
    public static final String TYPE_CEL = "cel";

    /** Visual-condition value that compares the field with the caller's user id. */
    public static final String CURRENT_USER = "$CURRENT_USER";

    /** The caller's {@code platform_user.id} UUID on every Cerbos principal (gateway and worker). */
    public static final String PRINCIPAL_USER_ID = "P.attr.userId";

    /** Visual-condition operators, as the admin UI sends them. */
    public static final Set<String> OPERATORS = Set.of(
            "equals", "not_equals", "in", "contains", "greater_than", "less_than");

    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    // CelParser instances are immutable and safe to share across threads.
    private static final CelParser PARSER = CelParserFactory.standardCelParserBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .build();

    private CustomRuleCel() {}

    /**
     * A condition that cannot be compiled. {@link #pointer()} is the JSON Pointer of the offending
     * member of the rule body (for example {@code /condition/value}).
     */
    public static final class InvalidConditionException extends Exception {
        private final String pointer;

        public InvalidConditionException(String pointer, String detail) {
            super(detail);
            this.pointer = pointer;
        }

        public String pointer() {
            return pointer;
        }
    }

    // ------------------------------------------------------------------ effects

    /**
     * The stored form of a requested effect: {@code allow} or {@code deny} for those words in any
     * case, {@code null} for anything else (including {@code EFFECT_ALLOW}/{@code EFFECT_DENY},
     * which the column cannot hold).
     */
    public static String parseEffect(Object raw) {
        if (!(raw instanceof String s)) {
            return null;
        }
        String effect = s.trim().toLowerCase(Locale.ROOT);
        return EFFECT_ALLOW.equals(effect) || EFFECT_DENY.equals(effect) ? effect : null;
    }

    /** True for a stored {@code allow} or {@code EFFECT_ALLOW} (any case). */
    public static boolean isAllow(String stored) {
        return stored != null
                && (EFFECT_ALLOW.equalsIgnoreCase(stored.trim()) || "EFFECT_ALLOW".equalsIgnoreCase(stored.trim()));
    }

    /** True for a stored {@code deny} or {@code EFFECT_DENY} (any case). */
    public static boolean isDeny(String stored) {
        return stored != null
                && (EFFECT_DENY.equalsIgnoreCase(stored.trim()) || "EFFECT_DENY".equalsIgnoreCase(stored.trim()));
    }

    // --------------------------------------------------------------- conditions

    /**
     * Compiles a rule condition to CEL. {@code conditionType} is {@code visual} or {@code cel}
     * (already lower-cased); {@code condition} is the condition object of the rule body.
     */
    public static String compile(String conditionType, Object condition) throws InvalidConditionException {
        if (!(condition instanceof Map<?, ?> map)) {
            throw new InvalidConditionException("/condition", "condition must be an object");
        }
        if (TYPE_CEL.equals(conditionType)) {
            Object expression = map.get("expression");
            if (!(expression instanceof String cel) || cel.isBlank()) {
                throw new InvalidConditionException("/condition/expression",
                        "expression must be a non-empty CEL expression");
            }
            String problem = parseProblem(cel);
            if (problem != null) {
                throw new InvalidConditionException("/condition/expression",
                        "expression is not valid CEL: " + problem);
            }
            return cel;
        }
        if (TYPE_VISUAL.equals(conditionType)) {
            return visualToCel(map.get("field"), map.get("operator"), map.get("value"));
        }
        throw new InvalidConditionException("/conditionType", "conditionType must be 'visual' or 'cel'");
    }

    /** Compiles a visual {@code {field, operator, value}} condition to CEL. */
    public static String visualToCel(Object field, Object operator, Object value) throws InvalidConditionException {
        if (!(field instanceof String name) || !FIELD_NAME.matcher(name).matches()) {
            throw new InvalidConditionException("/condition/field",
                    "field must be a field name: letters, digits and underscores, not starting with a digit");
        }
        if (!(operator instanceof String op) || !OPERATORS.contains(op)) {
            throw new InvalidConditionException("/condition/operator",
                    "operator must be one of " + String.join(", ", new TreeSet<>(OPERATORS)));
        }
        String attr = "R.attr." + name;
        String cel = switch (op) {
            case "equals" -> CURRENT_USER.equals(value)
                    ? attr + " == " + PRINCIPAL_USER_ID
                    : attr + " == " + stringLiteral(scalar(value));
            case "not_equals" -> CURRENT_USER.equals(value)
                    ? attr + " != " + PRINCIPAL_USER_ID
                    : attr + " != " + stringLiteral(scalar(value));
            case "in" -> attr + " in " + listLiteral(value);
            case "contains" -> attr + ".contains(" + stringLiteral(scalar(value)) + ")";
            case "greater_than" -> "double(" + attr + ") > " + number(value);
            case "less_than" -> "double(" + attr + ") < " + number(value);
            default -> throw new IllegalStateException("Unhandled operator " + op);
        };
        if (parseProblem(cel) != null) {
            // Values are escaped, so only the field name can break the expression (a CEL
            // reserved word such as "in" or "null").
            throw new InvalidConditionException("/condition/field",
                    "field '" + name + "' cannot be used in a condition");
        }
        return cel;
    }

    /**
     * Why {@code cel} cannot be placed in a policy, or {@code null} when it can: it must be
     * non-blank, parse as CEL on its own, and still parse inside parentheses (which fails only
     * when it ends in a {@code //} comment that would swallow the closing parenthesis). Together
     * these guarantee that {@code guard && (cel)} keeps all of {@code cel} inside the parentheses.
     */
    public static String parseProblem(String cel) {
        if (cel == null || cel.isBlank()) {
            return "the expression is empty";
        }
        CelValidationResult alone = PARSER.parse(cel);
        if (alone.hasError()) {
            return describe(alone);
        }
        if (PARSER.parse("(" + cel + ")").hasError()) {
            return "the expression must not end with a // comment";
        }
        return null;
    }

    /** True when {@link #parseProblem} finds nothing wrong. */
    public static boolean isWellFormed(String cel) {
        return parseProblem(cel) == null;
    }

    /**
     * A CEL string literal for {@code value}: double-quoted, with backslash, double quote and
     * control characters escaped, so the value can never end the literal early.
     */
    public static String stringLiteral(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------ helpers

    private static String scalar(Object value) throws InvalidConditionException {
        if (value instanceof String s) {
            if (hasUnpairedSurrogate(s)) {
                throw new InvalidConditionException("/condition/value", "value must be valid text");
            }
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new InvalidConditionException("/condition/value",
                value == null ? "value is required" : "value must be a string, number or boolean");
    }

    private static String listLiteral(Object value) throws InvalidConditionException {
        List<String> items = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                items.add(scalar(item));
            }
        } else if (value instanceof String text) {
            for (String part : text.split(",")) {
                String item = part.trim();
                if (!item.isEmpty()) {
                    items.add(scalar(item));
                }
            }
        } else {
            throw new InvalidConditionException("/condition/value",
                    "value for 'in' must be a list or comma-separated text");
        }
        if (items.isEmpty()) {
            throw new InvalidConditionException("/condition/value", "value for 'in' needs at least one item");
        }
        List<String> literals = new ArrayList<>(items.size());
        for (String item : items) {
            literals.add(stringLiteral(item));
        }
        return "[" + String.join(", ", literals) + "]";
    }

    private static String number(Object value) throws InvalidConditionException {
        double d;
        try {
            if (value instanceof Number n) {
                d = new BigDecimal(n.toString()).doubleValue();
            } else if (value instanceof String s && !s.isBlank()) {
                d = new BigDecimal(s.trim()).doubleValue();
            } else {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new InvalidConditionException("/condition/value", "value must be a number");
        }
        if (!Double.isFinite(d)) {
            throw new InvalidConditionException("/condition/value", "value must be a finite number");
        }
        // Double.toString always carries a '.' or an exponent, so CEL reads a double literal.
        return Double.toString(d);
    }

    private static boolean hasUnpairedSurrogate(String s) {
        return s.codePoints().anyMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE);
    }

    private static String describe(CelValidationResult result) {
        if (result.getErrors().isEmpty()) {
            return "it does not parse";
        }
        CelIssue issue = result.getErrors().get(0);
        CelSourceLocation location = issue.getSourceLocation();
        String message = issue.getMessage();
        if (location != null && location.getLine() > 0) {
            return message + " (line " + location.getLine() + ", column " + (location.getColumn() + 1) + ")";
        }
        return message;
    }
}
