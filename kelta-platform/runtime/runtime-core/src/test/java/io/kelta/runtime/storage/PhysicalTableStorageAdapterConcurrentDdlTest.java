package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinitionBuilder;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ReferenceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Worker pods consume the same NATS {@code collection.changed} event in parallel, so they all
 * run the same idempotent DDL for a new collection at once. Postgres' {@code IF NOT EXISTS}
 * checks are not atomic against a concurrent creator, and the loser fails in one of several
 * shapes. Every shape that only means "another pod created it first" must be treated as
 * success; anything else — notably a unique index over rows that hold duplicates — must not.
 *
 * <p>Before this, only the 23505 shape of the CREATE TABLE race was tolerated; production logged
 * {@code type "..." already exists} and {@code constraint "..." already exists} as
 * "Failed to initialize collection", leaving the losing pod without the collection registered.
 */
@DisplayName("PhysicalTableStorageAdapter — concurrent DDL from several worker pods")
class PhysicalTableStorageAdapterConcurrentDdlTest {

    private JdbcTemplate jdbcTemplate;
    private SchemaMigrationEngine migrationEngine;
    private PhysicalTableStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        migrationEngine = mock(SchemaMigrationEngine.class);
        adapter = new PhysicalTableStorageAdapter(
                jdbcTemplate, migrationEngine, new tools.jackson.databind.ObjectMapper());
        // FK target tables resolve, so the ADD CONSTRAINT path runs.
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenReturn(true);
    }

    private static DataAccessException pgError(String sqlState, String message) {
        return new UncategorizedSQLException("ddl", "sql", new SQLException(message, sqlState));
    }

    private void failWhen(String sqlFragment, DataAccessException error) {
        doThrow(error).when(jdbcTemplate).execute(argThat((String sql) ->
                sql != null && sql.contains(sqlFragment)));
    }

    private static CollectionDefinition orders() {
        return new CollectionDefinitionBuilder()
                .name("orders")
                // EXTERNAL_ID is what emits a post-create CREATE UNIQUE INDEX.
                .addField(new FieldDefinitionBuilder().name("code").type(FieldType.EXTERNAL_ID).build())
                .addField(new FieldDefinitionBuilder().name("customer").type(FieldType.LOOKUP)
                        .referenceConfig(ReferenceConfig.lookup("customers", "Customer")).build())
                .build();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
        "42710 | type \"orders\" already exists",
        "42P07 | relation \"orders\" already exists",
        "23505 | duplicate key value violates unique constraint \"pg_type_typname_nsp_index\"",
    })
    @DisplayName("a lost CREATE TABLE race is success, and the schema is still reconciled")
    void createTableRace(String sqlState, String message) {
        failWhen("CREATE TABLE IF NOT EXISTS", pgError(sqlState, message));

        assertDoesNotThrow(() -> adapter.initializeCollection(orders()));

        verify(migrationEngine).reconcileSchema(any(), any());
    }

    @Test
    @DisplayName("a lost CREATE UNIQUE INDEX race is success")
    void uniqueIndexRace() {
        failWhen("CREATE UNIQUE INDEX", pgError("23505",
                "duplicate key value violates unique constraint \"pg_class_relname_nsp_index\""));

        assertDoesNotThrow(() -> adapter.initializeCollection(orders()));

        verify(jdbcTemplate).execute(argThat((String sql) ->
                sql != null && sql.contains("CREATE UNIQUE INDEX")));
    }

    @Test
    @DisplayName("a lost ADD CONSTRAINT race is success, and the constraint is still validated")
    void foreignKeyRace() {
        failWhen("ADD CONSTRAINT", pgError("42710",
                "constraint \"fk_orders_customer\" for relation \"orders\" already exists"));

        assertDoesNotThrow(() -> adapter.initializeCollection(orders()));

        verify(jdbcTemplate).execute(argThat((String sql) ->
                sql != null && sql.contains("VALIDATE CONSTRAINT")));
    }

    @Test
    @DisplayName("a unique index over duplicate rows is a real failure, not a race")
    void uniqueIndexOverDuplicateDataStillFails() {
        failWhen("CREATE UNIQUE INDEX", pgError("23505",
                "could not create unique index \"idx_orders_code\" Detail: Key (code)=(A1) is duplicated."));

        assertThatThrownBy(() -> adapter.initializeCollection(orders()))
                .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("an unrelated DDL failure still fails the initialization")
    void otherFailuresStillFail() {
        failWhen("CREATE TABLE IF NOT EXISTS",
                new BadSqlGrammarException("ddl", "sql", new SQLException("permission denied", "42501")));

        assertThatThrownBy(() -> adapter.initializeCollection(orders()))
                .isInstanceOf(StorageException.class);
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @CsvSource(delimiter = '|', value = {
        "42710 | constraint \"x\" already exists | true",
        "42P07 | relation \"x\" already exists | true",
        "23505 | violates unique constraint \"pg_type_typname_nsp_index\" | true",
        "23505 | violates unique constraint \"pg_class_relname_nsp_index\" | true",
        "23505 | could not create unique index \"idx\" | false",
        "42501 | permission denied | false",
    })
    @DisplayName("isConcurrentDdlDuplicate classifies by SQLSTATE and catalog index")
    void classification(String sqlState, String message, boolean expected) {
        assertThat(PhysicalTableStorageAdapter.isConcurrentDdlDuplicate(pgError(sqlState, message)))
                .isEqualTo(expected);
    }
}
