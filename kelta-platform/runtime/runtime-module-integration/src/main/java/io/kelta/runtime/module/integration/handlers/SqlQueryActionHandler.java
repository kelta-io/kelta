package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.flow.StateDataResolver;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Action handler that executes a raw SQL statement against the tenant's
 * PostgreSQL schema and returns either a result set or an affected-row count.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "sql": "SELECT id, name FROM orders WHERE customer_id = '${$.customerId}'",
 *   "maxRows": 1000
 * }
 * </pre>
 *
 * <p>{@code ${...}} placeholders in {@code sql} are never spliced into the statement. The
 * flow engine passes {@code sql} unresolved ({@link #unresolvedParameterKeys()}); each
 * placeholder becomes a JDBC {@code ?} bound to the value read from the flow state, sent
 * untyped so Postgres infers the type from where it is used, as it does for a literal.
 * A placeholder written as a whole quoted literal ({@code '${$.customerId}'}, the form
 * above) has its quotes stripped and binds the value as text, a missing value as {@code ''}.
 * Rejected, by {@link #validate} and at run time: a placeholder inside a larger literal
 * ({@code '%${$.q}%'}; write {@code '%' || ${$.q} || '%'}), in an identifier position
 * (a table or column name, or inside double quotes), or making up the whole statement.
 *
 * <p>Tenant scope is enforced by setting {@code search_path} to the current
 * tenant's schema (plus {@code public}) inside a transaction, so unqualified
 * table names cannot reach another tenant's data.
 *
 * <p>Output for SELECT-like statements:
 * <pre>
 * { "records": [...], "rowCount": N, "columns": [...] }
 * </pre>
 *
 * <p>Output for DML / DDL:
 * <pre>
 * { "rowsAffected": N, "success": true }
 * </pre>
 */
public class SqlQueryActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryActionHandler.class);

    private static final int DEFAULT_MAX_ROWS = 1000;
    private static final int HARD_MAX_ROWS = 10_000;

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final StateDataResolver stateDataResolver;

    public SqlQueryActionHandler(ObjectMapper objectMapper,
                                 JdbcTemplate jdbcTemplate,
                                 TransactionTemplate transactionTemplate) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.stateDataResolver = new StateDataResolver(objectMapper);
    }

    @Override
    public String getActionTypeKey() {
        return "SQL_QUERY";
    }

    @Override
    public Set<String> unresolvedParameterKeys() {
        return Set.of("sql");
    }

    @Override
    public ActionResult execute(ActionContext context) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                    context.actionConfigJson(), new TypeReference<>() {});

            String sql = config.get("sql") instanceof String s ? s.trim() : null;
            if (sql == null || sql.isEmpty()) {
                return ActionResult.failure("sql is required");
            }

            SqlTemplate template;
            try {
                template = SqlTemplate.parse(sql);
            } catch (IllegalArgumentException e) {
                return ActionResult.failure(e.getMessage());
            }
            Object[] args = bindArguments(template, context);
            String statement = args.length == 0 ? sql : template.sql();

            int maxRows = clampMaxRows(config.get("maxRows"));

            String tenantSlug = TenantContext.getSlug();
            if (tenantSlug == null || tenantSlug.isBlank()) {
                return ActionResult.failure(
                        "Tenant slug is not bound — SQL_QUERY cannot run outside a tenant scope");
            }

            boolean returnsResultSet = returnsResultSet(sql);

            Map<String, Object> output = transactionTemplate.execute(status -> {
                jdbcTemplate.execute("SET LOCAL search_path = " + quoteIdent(tenantSlug) + ", public");

                if (returnsResultSet) {
                    List<Map<String, Object>> rows = args.length == 0
                            ? jdbcTemplate.queryForList(statement)
                            : jdbcTemplate.queryForList(statement, args);
                    if (rows.size() > maxRows) {
                        rows = new ArrayList<>(rows.subList(0, maxRows));
                    }

                    List<String> columns = rows.isEmpty()
                            ? List.of()
                            : new ArrayList<>(rows.get(0).keySet());

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("records", rows);
                    result.put("rowCount", rows.size());
                    result.put("columns", columns);
                    return result;
                } else {
                    int rowsAffected = args.length == 0
                            ? jdbcTemplate.update(statement)
                            : jdbcTemplate.update(statement, args);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("rowsAffected", rowsAffected);
                    result.put("success", true);
                    return result;
                }
            });

            log.info("SQL_QUERY executed: tenantSlug={}, returnsResultSet={}, boundParameters={}, output={}",
                    tenantSlug, returnsResultSet, args.length, summarize(output));

            return ActionResult.success(output);
        } catch (DataAccessException dae) {
            String message = dae.getMostSpecificCause() != null
                    ? dae.getMostSpecificCause().getMessage()
                    : dae.getMessage();
            log.warn("SQL_QUERY failed: {}", message);
            return ActionResult.failure("SQL error: " + message);
        } catch (Exception e) {
            log.error("SQL_QUERY action failed: {}", e.getMessage(), e);
            return ActionResult.failure(e);
        }
    }

    @Override
    public void validate(String configJson) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                    configJson, new TypeReference<>() {});

            Object sql = config.get("sql");
            if (!(sql instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException("Config must contain non-blank 'sql'");
            }
            SqlTemplate.parse(s);

            Object maxRows = config.get("maxRows");
            if (maxRows instanceof Number n) {
                int v = n.intValue();
                if (v < 1 || v > HARD_MAX_ROWS) {
                    throw new IllegalArgumentException(
                            "maxRows must be between 1 and " + HARD_MAX_ROWS);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid config JSON: " + e.getMessage(), e);
        }
    }

    /**
     * True if executing this SQL is expected to produce a result set.
     * Recognises SELECT, WITH, SHOW, EXPLAIN, VALUES, TABLE, and any DML
     * that has a {@code RETURNING} clause.
     */
    static boolean returnsResultSet(String sql) {
        String stripped = stripLeadingCommentsAndWhitespace(sql).toUpperCase();
        if (stripped.startsWith("SELECT")
                || stripped.startsWith("WITH")
                || stripped.startsWith("SHOW")
                || stripped.startsWith("EXPLAIN")
                || stripped.startsWith("VALUES")
                || stripped.startsWith("TABLE ")) {
            return true;
        }
        // INSERT/UPDATE/DELETE ... RETURNING
        return stripped.contains(" RETURNING ") || stripped.contains("\nRETURNING ");
    }

    private Object[] bindArguments(SqlTemplate template, ActionContext context) {
        Map<String, Object> state = context.stateData() != null ? context.stateData()
                : context.resolvedData() != null ? context.resolvedData() : Map.of();
        Object[] args = new Object[template.placeholders().size()];
        for (int i = 0; i < args.length; i++) {
            Placeholder placeholder = template.placeholders().get(i);
            Object value = stateDataResolver.readPath(state, placeholder.path());
            args[i] = new SqlParameterValue(Types.OTHER, toText(value, placeholder.quoted()));
        }
        return args;
    }

    private String toText(Object value, boolean quoted) {
        if (value == null) {
            return quoted ? "" : null;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return objectMapper.writeValueAsString(value);
        }
        return String.valueOf(value);
    }

    /** One {@code ${path}} in the template; {@code quoted} when written as {@code '${path}'}. */
    record Placeholder(String path, boolean quoted) {}

    /**
     * The template with every {@code ${...}} replaced by {@code ?} (and each literal
     * {@code ?} outside strings and comments escaped as {@code ??} for the JDBC driver).
     */
    record SqlTemplate(String sql, List<Placeholder> placeholders) {

        private static final Set<String> IDENTIFIER_KEYWORDS =
                Set.of("FROM", "JOIN", "INTO", "UPDATE", "TABLE", "BY");

        static SqlTemplate parse(String template) {
            StringBuilder out = new StringBuilder(template.length());
            List<Placeholder> placeholders = new ArrayList<>();
            int n = template.length();
            int i = 0;
            while (i < n) {
                char c = template.charAt(i);
                if (c == '-' && i + 1 < n && template.charAt(i + 1) == '-') {
                    int end = template.indexOf('\n', i);
                    end = end < 0 ? n : end;
                    out.append(template, i, end);
                    i = end;
                } else if (c == '/' && i + 1 < n && template.charAt(i + 1) == '*') {
                    int end = template.indexOf("*/", i + 2);
                    end = end < 0 ? n : end + 2;
                    out.append(template, i, end);
                    i = end;
                } else if (c == '\'') {
                    int close = placeholderEnd(template, i + 1);
                    if (close > 0 && close + 1 < n && template.charAt(close + 1) == '\'') {
                        placeholders.add(new Placeholder(template.substring(i + 3, close), true));
                        out.append('?');
                        i = close + 2;
                        continue;
                    }
                    int end = quotedEnd(template, i, '\'');
                    String literal = template.substring(i, end);
                    if (literal.contains("${")) {
                        throw new IllegalArgumentException("SQL_QUERY placeholder inside the literal " + literal
                                + " is not bound: a placeholder must be the whole quoted literal ('${$.path}'),"
                                + " so build the text in SQL instead, e.g. '%' || ${$.path} || '%'");
                    }
                    out.append(literal);
                    i = end;
                } else if (c == '"') {
                    int end = quotedEnd(template, i, '"');
                    String identifier = template.substring(i, end);
                    if (identifier.contains("${")) {
                        throw identifierError(identifier);
                    }
                    out.append(identifier);
                    i = end;
                } else if (c == '$' && i + 1 < n && template.charAt(i + 1) == '{') {
                    int close = placeholderEnd(template, i);
                    if (close < 0) {
                        throw new IllegalArgumentException("SQL_QUERY placeholder at offset " + i + " has no closing '}'");
                    }
                    String placeholder = template.substring(i, close + 1);
                    if (inIdentifierPosition(out, template, close + 1)) {
                        throw identifierError(placeholder);
                    }
                    placeholders.add(new Placeholder(template.substring(i + 2, close), false));
                    out.append('?');
                    i = close + 1;
                } else if (c == '?') {
                    out.append("??");
                    i++;
                } else {
                    out.append(c);
                    i++;
                }
            }
            if (!placeholders.isEmpty() && stripLeadingCommentsAndWhitespace(out.toString()).trim().equals("?")) {
                throw new IllegalArgumentException(
                        "SQL_QUERY sql cannot be a single placeholder: the statement must be written in the flow,"
                                + " with ${$.path} placeholders only where values go");
            }
            return new SqlTemplate(out.toString(), List.copyOf(placeholders));
        }

        /** Index of the '}' closing a {@code ${} starting at {@code start}, or -1. */
        private static int placeholderEnd(String template, int start) {
            if (!template.startsWith("${", start)) {
                return -1;
            }
            return template.indexOf('}', start + 2);
        }

        /** Index just past the quote closing the literal opened at {@code start}; doubled quotes escape. */
        private static int quotedEnd(String template, int start, char quote) {
            int i = start + 1;
            while (i < template.length()) {
                if (template.charAt(i) == quote) {
                    if (i + 1 < template.length() && template.charAt(i + 1) == quote) {
                        i += 2;
                        continue;
                    }
                    return i + 1;
                }
                i++;
            }
            return template.length();
        }

        private static boolean inIdentifierPosition(CharSequence before, String template, int after) {
            int j = before.length() - 1;
            while (j >= 0 && Character.isWhitespace(before.charAt(j))) {
                j--;
            }
            if (j >= 0 && before.charAt(j) == '.') {
                return true;
            }
            if (after < template.length() && template.charAt(after) == '.') {
                return true;
            }
            int wordEnd = j + 1;
            while (j >= 0 && Character.isLetter(before.charAt(j))) {
                j--;
            }
            String previousWord = before.subSequence(j + 1, wordEnd).toString().toUpperCase(Locale.ROOT);
            return IDENTIFIER_KEYWORDS.contains(previousWord);
        }

        private static IllegalArgumentException identifierError(String placeholder) {
            return new IllegalArgumentException("SQL_QUERY placeholder " + placeholder
                    + " is in an identifier position (a table or column name): placeholders bind values only,"
                    + " so write the name literally in the sql");
        }
    }

    private static String stripLeadingCommentsAndWhitespace(String sql) {
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i + 2);
                if (end < 0) return "";
                i = end + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) return "";
                i = end + 2;
            } else {
                break;
            }
        }
        return sql.substring(i);
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static int clampMaxRows(Object value) {
        if (value instanceof Number n) {
            int v = n.intValue();
            if (v < 1) return 1;
            if (v > HARD_MAX_ROWS) return HARD_MAX_ROWS;
            return v;
        }
        return DEFAULT_MAX_ROWS;
    }

    private static String summarize(Map<String, Object> output) {
        if (output == null) return "null";
        if (output.containsKey("rowCount")) {
            return "rowCount=" + output.get("rowCount");
        }
        if (output.containsKey("rowsAffected")) {
            return "rowsAffected=" + output.get("rowsAffected");
        }
        return output.toString();
    }
}
