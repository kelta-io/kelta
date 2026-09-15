package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies the Postgres SQLSTATE -> HTTP status table {@code PhysicalTableStorageAdapter}
 * enforces: class 22 (bad literal / out-of-range / truncation) and the two 42xxx "doesn't
 * exist for this schema" codes are the caller's mistake ({@link StorageQueryException}, 400
 * via {@code GlobalExceptionHandler}); everything else — connection loss, deadlock, an
 * internal syntax error — stays a plain {@link StorageException} (500). Before this, every
 * {@code DataAccessException} became a 500 regardless of cause.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PhysicalTableStorageAdapter SQLSTATE classification")
class PhysicalTableStorageAdapterSqlErrorClassificationTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SchemaMigrationEngine migrationEngine;

    private PhysicalTableStorageAdapter adapter;

    private static CollectionDefinition widgets() {
        return new CollectionDefinitionBuilder().name("widgets")
                .addField(FieldDefinition.requiredString("name"))
                .build();
    }

    @BeforeEach
    void setUp() {
        adapter = new PhysicalTableStorageAdapter(jdbcTemplate, migrationEngine, JsonMapper.builder().build());
    }

    private void simulateDbFailure(String sqlState, String driverMessage) {
        SQLException sqlException = new SQLException(driverMessage, sqlState);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("query failed", sqlException));
    }

    @ParameterizedTest(name = "SQLSTATE {0} ({1}) -> {2}")
    @CsvSource({
        "22P02, invalid_text_representation, 400",
        "22007, invalid_datetime_format,     400",
        "22003, numeric_value_out_of_range,  400",
        "22001, string_data_right_truncation,400",
        "42703, undefined_column,            400",
        "42883, undefined_function,          400",
        "08006, connection_failure,          500",
        "40001, serialization_failure,       500",
        "57014, query_canceled,              500",
        "XX000, internal_error,              500",
    })
    void classifiesEverySqlStateToTheDocumentedStatus(String sqlState, String label, int expectedStatus) {
        simulateDbFailure(sqlState, "simulated " + label);

        Throwable thrown = catchThrowable(() -> adapter.getById(widgets(), "abc"));

        if (expectedStatus == 400) {
            assertThat(thrown).isInstanceOf(StorageQueryException.class);
            assertThat(((StorageQueryException) thrown).getSqlState()).isEqualTo(sqlState);
        } else {
            assertThat(thrown)
                    .isInstanceOf(StorageException.class)
                    .isNotInstanceOf(StorageQueryException.class);
        }
    }

    @Test
    @DisplayName("a simulated connection failure still throws StorageException, never a client error")
    void connectionFailureStaysAStorageFault() {
        simulateDbFailure("08006", "Connection refused");

        assertThatThrownBy(() -> adapter.getById(widgets(), "abc"))
                .isExactlyInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("SQLSTATE with no driver exception in the cause chain stays a StorageException")
    void nonSqlFailureStaysAStorageFault() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("pool exhausted"));

        assertThatThrownBy(() -> adapter.getById(widgets(), "abc"))
                .isExactlyInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("names the offending field from the value Postgres echoes back")
    void namesOffendingFieldFromEchoedValue() {
        simulateDbFailure("22P02", "invalid input syntax for type uuid: \"not-a-uuid\"");

        Throwable thrown = catchThrowable(() -> adapter.getById(widgets(), "not-a-uuid"));

        assertThat(thrown).isInstanceOfSatisfying(StorageQueryException.class, ex -> {
            assertThat(ex.getFieldName()).isEqualTo("id");
            assertThat(ex.getReason()).contains("not-a-uuid");
            assertThat(ex.getSqlState()).isEqualTo("22P02");
        });
    }

    @Test
    @DisplayName("still 400s without a field pointer when the value can't be matched in the message")
    void classifiesEvenWithoutAnIdentifiableField() {
        simulateDbFailure("22P02", "invalid input syntax for type uuid");

        Throwable thrown = catchThrowable(() -> adapter.getById(widgets(), "not-a-uuid"));

        assertThat(thrown).isInstanceOfSatisfying(StorageQueryException.class, ex -> {
            assertThat(ex.getFieldName()).isNull();
            assertThat(ex.getSqlState()).isEqualTo("22P02");
        });
    }
}
