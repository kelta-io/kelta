package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("SqlQueryActionHandler")
class SqlQueryActionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private SqlQueryActionHandler handler;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        transactionTemplate = mock(TransactionTemplate.class);
        // Run the callback synchronously, no real transaction needed.
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        handler = new SqlQueryActionHandler(objectMapper, jdbcTemplate, transactionTemplate);
    }

    @Test
    @DisplayName("Should have correct action type key")
    void shouldHaveCorrectKey() {
        assertEquals("SQL_QUERY", handler.getActionTypeKey());
    }

    @Test
    @DisplayName("Should return records, rowCount, and columns for a SELECT")
    void shouldReturnRecordsForSelect() {
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("id", "a");
        row1.put("name", "Alice");
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("id", "b");
        row2.put("name", "Bob");
        when(jdbcTemplate.queryForList("SELECT id, name FROM contact"))
                .thenReturn(List.of(row1, row2));

        String config = "{\"sql\": \"SELECT id, name FROM contact\"}";
        ActionResult result = TenantContext.callWithTenant("t1", "acme",
                () -> handler.execute(makeContext(config)));

        assertTrue(result.successful());
        assertEquals(2, result.outputData().get("rowCount"));
        assertEquals(List.of("id", "name"), result.outputData().get("columns"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records =
                (List<Map<String, Object>>) result.outputData().get("records");
        assertEquals("Alice", records.get(0).get("name"));
    }

    @Test
    @DisplayName("Should set search_path to the tenant's schema before running SELECT")
    void shouldSetSearchPathBeforeSelect() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        TenantContext.runWithTenant("t1", "acme", () ->
                handler.execute(makeContext("{\"sql\": \"SELECT 1\"}")));

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(ddl.capture());
        assertEquals("SET LOCAL search_path = \"acme\", public", ddl.getValue());
    }

    @Test
    @DisplayName("Should escape embedded double quotes in tenant slug")
    void shouldEscapeQuotesInSlug() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        // Defence-in-depth: tenant slugs are validated upstream, but the handler
        // must double quotes anyway so a hostile slug cannot escape the identifier.
        TenantContext.runWithTenant("t1", "ev\"il", () ->
                handler.execute(makeContext("{\"sql\": \"SELECT 1\"}")));

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(ddl.capture());
        assertEquals("SET LOCAL search_path = \"ev\"\"il\", public", ddl.getValue());
    }

    @Test
    @DisplayName("Should return rowsAffected for an UPDATE")
    void shouldReturnRowsAffectedForUpdate() {
        when(jdbcTemplate.update("UPDATE contact SET status = 'active'")).thenReturn(7);

        ActionResult result = TenantContext.callWithTenant("t1", "acme",
                () -> handler.execute(makeContext(
                        "{\"sql\": \"UPDATE contact SET status = 'active'\"}")));

        assertTrue(result.successful());
        assertEquals(7, result.outputData().get("rowsAffected"));
        assertEquals(true, result.outputData().get("success"));
        verify(jdbcTemplate, never()).queryForList(anyString());
    }

    @Test
    @DisplayName("Should treat INSERT...RETURNING as a result-set statement")
    void shouldHandleInsertReturning() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "x");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));

        ActionResult result = TenantContext.callWithTenant("t1", "acme",
                () -> handler.execute(makeContext(
                        "{\"sql\": \"INSERT INTO contact(name) VALUES('x') RETURNING id\"}")));

        assertTrue(result.successful());
        assertEquals(1, result.outputData().get("rowCount"));
    }

    @Test
    @DisplayName("Should clamp results to maxRows")
    void shouldClampToMaxRows() {
        List<Map<String, Object>> rows = List.of(
                Map.of("id", 1), Map.of("id", 2), Map.of("id", 3),
                Map.of("id", 4), Map.of("id", 5));
        when(jdbcTemplate.queryForList(anyString())).thenReturn(rows);

        ActionResult result = TenantContext.callWithTenant("t1", "acme",
                () -> handler.execute(makeContext(
                        "{\"sql\": \"SELECT id FROM contact\", \"maxRows\": 3}")));

        assertTrue(result.successful());
        assertEquals(3, result.outputData().get("rowCount"));
    }

    @Test
    @DisplayName("Should fail when sql is missing")
    void shouldFailWhenSqlMissing() {
        ActionResult result = TenantContext.callWithTenant("t1", "acme",
                () -> handler.execute(makeContext("{}")));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("sql is required"));
    }

    @Test
    @DisplayName("Should fail when tenant slug is not bound")
    void shouldFailWhenTenantNotBound() {
        ActionResult result = handler.execute(makeContext("{\"sql\": \"SELECT 1\"}"));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("Tenant slug is not bound"));
    }

    @Test
    @DisplayName("Should surface SQL errors as failure with cause message")
    void shouldSurfaceSqlError() {
        when(jdbcTemplate.queryForList(anyString())).thenThrow(
                new BadSqlGrammarException("query", "SELECT * FROM nope",
                        new SQLException("relation \"nope\" does not exist")));

        ActionResult result = TenantContext.callWithTenant("t1", "acme",
                () -> handler.execute(makeContext("{\"sql\": \"SELECT * FROM nope\"}")));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("SQL error"));
        assertTrue(result.errorMessage().contains("nope"));
    }

    @Test
    @DisplayName("validate() rejects blank sql")
    void validateRejectsBlankSql() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.validate("{\"sql\": \"\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> handler.validate("{}"));
    }

    @Test
    @DisplayName("validate() rejects out-of-range maxRows")
    void validateRejectsBadMaxRows() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.validate("{\"sql\": \"SELECT 1\", \"maxRows\": 0}"));
        assertThrows(IllegalArgumentException.class,
                () -> handler.validate("{\"sql\": \"SELECT 1\", \"maxRows\": 99999}"));
    }

    @Test
    @DisplayName("returnsResultSet recognises common variants")
    void returnsResultSetVariants() {
        assertTrue(SqlQueryActionHandler.returnsResultSet("SELECT 1"));
        assertTrue(SqlQueryActionHandler.returnsResultSet("  select 1"));
        assertTrue(SqlQueryActionHandler.returnsResultSet("WITH x AS (SELECT 1) SELECT * FROM x"));
        assertTrue(SqlQueryActionHandler.returnsResultSet("EXPLAIN SELECT 1"));
        assertTrue(SqlQueryActionHandler.returnsResultSet("SHOW search_path"));
        assertTrue(SqlQueryActionHandler.returnsResultSet(
                "INSERT INTO t(a) VALUES (1) RETURNING id"));
        assertTrue(SqlQueryActionHandler.returnsResultSet(
                "-- a comment\n  SELECT 1"));
        assertFalse(SqlQueryActionHandler.returnsResultSet("UPDATE t SET a=1"));
        assertFalse(SqlQueryActionHandler.returnsResultSet("DELETE FROM t"));
        assertFalse(SqlQueryActionHandler.returnsResultSet("INSERT INTO t VALUES (1)"));
    }

    @Test
    @DisplayName("the flow engine leaves sql unresolved for this handler")
    void declaresSqlUnresolved() {
        assertEquals(java.util.Set.of("sql"), handler.unresolvedParameterKeys());
    }

    @Test
    @DisplayName("an injection payload in state is bound as one literal and runs no second statement")
    void injectionPayloadIsBound() {
        String payload = "x'; DELETE FROM t; --";
        AtomicReference<Object[]> call = new AtomicReference<>();
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            call.set(inv.getRawArguments());
            return List.of();
        });

        ActionResult result = TenantContext.callWithTenant("t1", "acme", () -> handler.execute(makeContext(
                "{\"sql\": \"SELECT id FROM contact WHERE name = '${$.input.name}'\"}",
                Map.of("input", Map.of("name", payload)))));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals("SELECT id FROM contact WHERE name = ?", call.get()[0]);
        Object[] args = (Object[]) call.get()[1];
        assertEquals(1, args.length);
        assertBound(args[0], payload);
        verify(jdbcTemplate, never()).queryForList(anyString());
        verify(jdbcTemplate, never()).update(anyString());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        // Only the search_path statement runs besides the bound query.
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    @DisplayName("a DML statement binds values through update(sql, args)")
    void dmlBindsValues() {
        AtomicReference<Object[]> call = new AtomicReference<>();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            call.set(inv.getRawArguments());
            return 2;
        });

        ActionResult result = TenantContext.callWithTenant("t1", "acme", () -> handler.execute(makeContext(
                "{\"sql\": \"UPDATE contact SET status = '${$.input.status}' WHERE score > ${$.input.min}\"}",
                Map.of("input", Map.of("status", "active", "min", 5)))));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals(2, result.outputData().get("rowsAffected"));
        assertEquals("UPDATE contact SET status = ? WHERE score > ?", call.get()[0]);
        Object[] args = (Object[]) call.get()[1];
        assertBound(args[0], "active");
        assertBound(args[1], "5");
    }

    @Test
    @DisplayName("a missing value binds '' when quoted (as the old splice did) and NULL when bare")
    void missingValues() {
        SqlQueryActionHandler.SqlTemplate template = SqlQueryActionHandler.SqlTemplate.parse(
                "SELECT 1 WHERE a = '${$.nope}' AND b = ${$.nope}");
        assertEquals("SELECT 1 WHERE a = ? AND b = ?", template.sql());
        assertTrue(template.placeholders().get(0).quoted());
        assertFalse(template.placeholders().get(1).quoted());

        AtomicReference<Object[]> call = new AtomicReference<>();
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            call.set(inv.getRawArguments());
            return List.of();
        });
        TenantContext.runWithTenant("t1", "acme", () -> handler.execute(makeContext(
                "{\"sql\": \"SELECT 1 WHERE a = '${$.nope}' AND b = ${$.nope}\"}", Map.of())));

        Object[] args = (Object[]) call.get()[1];
        assertBound(args[0], "");
        assertBound(args[1], null);
    }

    @Test
    @DisplayName("placeholders resolve against the full flow state, not the post-InputPath slice")
    void readsFullState() {
        AtomicReference<Object[]> call = new AtomicReference<>();
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            call.set(inv.getRawArguments());
            return List.of();
        });
        ActionContext context = ActionContext.builder()
                .tenantId("t1")
                .actionConfigJson("{\"sql\": \"SELECT 1 WHERE id = ${$.record.id}\"}")
                .resolvedData(Map.of("id", "slice"))
                .stateData(Map.of("record", Map.of("id", "rec-1")))
                .build();

        TenantContext.runWithTenant("t1", "acme", () -> handler.execute(context));

        assertBound(((Object[]) call.get()[1])[0], "rec-1");
    }

    @Test
    @DisplayName("a literal ? outside strings is escaped for the driver; comments and strings are left alone")
    void escapesQuestionMarks() {
        SqlQueryActionHandler.SqlTemplate template = SqlQueryActionHandler.SqlTemplate.parse(
                "-- ${$.a} ?\nSELECT data ? 'k', 'it''s ?' FROM t WHERE id = ${$.id} /* ${$.b} */");
        assertEquals("-- ${$.a} ?\nSELECT data ?? 'k', 'it''s ?' FROM t WHERE id = ? /* ${$.b} */",
                template.sql());
        assertEquals(List.of(new SqlQueryActionHandler.Placeholder("$.id", false)), template.placeholders());
    }

    @Test
    @DisplayName("sql without placeholders runs unchanged through the plain overloads")
    void noPlaceholdersUnchanged() {
        when(jdbcTemplate.queryForList("SELECT data ? 'k' FROM t")).thenReturn(List.of());

        ActionResult result = TenantContext.callWithTenant("t1", "acme", () -> handler.execute(makeContext(
                "{\"sql\": \"SELECT data ? 'k' FROM t\"}")));

        assertTrue(result.successful(), result.errorMessage());
        verify(jdbcTemplate).queryForList("SELECT data ? 'k' FROM t");
    }

    @Test
    @DisplayName("placeholders that cannot be bound fail validation and execution with a message naming the fix")
    void unbindablePlaceholdersRejected() {
        assertRejected("SELECT * FROM ${$.input.table}", "identifier position");
        assertRejected("SELECT * FROM other.${$.input.table}", "identifier position");
        assertRejected("SELECT ${$.input.schema}.name FROM t", "identifier position");
        assertRejected("SELECT \\\"${$.input.col}\\\" FROM t", "identifier position");
        assertRejected("INSERT INTO ${$.input.table} VALUES (1)", "identifier position");
        assertRejected("SELECT * FROM t ORDER BY ${$.input.col}", "identifier position");
        assertRejected("SELECT * FROM t WHERE name LIKE '%${$.input.q}%'", "'%' || ${$.path} || '%'");
        assertRejected("${$.input.sql}", "cannot be a single placeholder");
        assertRejected("SELECT 1 WHERE a = ${$.input.a", "no closing");
    }

    private void assertRejected(String sql, String messagePart) {
        String config = "{\"sql\": \"" + sql + "\"}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> handler.validate(config));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());

        ActionResult result = TenantContext.callWithTenant("t1", "acme",
                () -> handler.execute(makeContext(config, Map.of("input", Map.of()))));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains(messagePart), result.errorMessage());
        verifyNoInteractions(jdbcTemplate);
    }

    private static void assertBound(Object arg, String expected) {
        SqlParameterValue value = assertInstanceOf(SqlParameterValue.class, arg);
        assertEquals(Types.OTHER, value.getSqlType());
        assertEquals(expected, value.getValue());
    }

    private ActionContext makeContext(String configJson, Map<String, Object> state) {
        return ActionContext.builder()
                .tenantId("t1")
                .userId("u1")
                .actionConfigJson(configJson)
                .data(Map.of())
                .resolvedData(Map.of())
                .stateData(state)
                .build();
    }

    private ActionContext makeContext(String configJson) {
        return ActionContext.builder()
                .tenantId("t1")
                .userId("u1")
                .actionConfigJson(configJson)
                .data(Map.of())
                .resolvedData(Map.of())
                .build();
    }
}
