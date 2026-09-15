package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.InvalidFilterException;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Temporal filter binding against a real Postgres. H2 tolerates a {@code java.time.Instant}
 * bind parameter and coerces varchar to timestamp, so only Postgres reproduces what every
 * dashboard time range hit: {@code Can't infer the SQL type to use for an instance of
 * java.time.Instant} on a DATETIME field, and {@code operator does not exist: timestamp with
 * time zone >= character varying} on the audit columns, which have no FieldDefinition.
 *
 * <p>Runs only under the integration profile (Testcontainers needs Docker).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PhysicalTableStorageAdapter — temporal filters bind as SQL timestamps (Postgres)")
class PhysicalTableStorageAdapterTemporalFilterIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    private PhysicalTableStorageAdapter adapter;
    private CollectionDefinition events;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS temporal_events");
        adapter = new PhysicalTableStorageAdapter(jdbc, new SchemaMigrationEngine(jdbc),
                JsonMapper.builder().build());
        events = new CollectionDefinitionBuilder().name("temporal_events")
                .addField(FieldDefinition.requiredString("name"))
                .addField(FieldDefinition.datetime("dueAt"))
                .addField(FieldDefinition.date("dueOn"))
                .build();
        adapter.initializeCollection(events);
        insert("1", "January", "2026-01-01T10:00:00Z", "2026-01-01");
        insert("2", "February", "2026-02-01T10:00:00Z", "2026-02-01");
        insert("3", "March", "2026-03-01T10:00:00Z", "2026-03-01");
    }

    private void insert(String id, String name, String dueAt, String dueOn) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("name", name);
        data.put("dueAt", Instant.parse(dueAt));
        data.put("dueOn", LocalDate.parse(dueOn));
        data.put("createdBy", "tester");
        data.put("updatedBy", "tester");
        data.put("createdAt", Instant.parse(dueAt));
        data.put("updatedAt", Instant.parse(dueAt));
        adapter.create(events, data);
    }

    private List<String> names(FilterCondition... filters) {
        QueryRequest request = new QueryRequest(Pagination.defaults(), List.of(), List.of(), List.of(filters));
        return adapter.query(events, request).data().stream().map(r -> (String) r.get("name")).sorted().toList();
    }

    @Test
    @DisplayName("the audit timestamps filter from an ISO-8601 string (the dashboard time range)")
    void auditTimestampsBindAsTimestamps() {
        assertThat(names(new FilterCondition("createdAt", FilterOperator.GTE, "2026-02-01T00:00:00Z")))
                .containsExactly("February", "March");
        assertThat(names(new FilterCondition("updatedAt", FilterOperator.LT, "2026-02-01T00:00:00Z")))
                .containsExactly("January");
    }

    @Test
    @DisplayName("a DATETIME field filters with every comparison operator, scalar and IN")
    void datetimeFieldBindsAsTimestamp() {
        assertThat(names(new FilterCondition("dueAt", FilterOperator.EQ, "2026-02-01T10:00:00Z"))).containsExactly("February");
        assertThat(names(new FilterCondition("dueAt", FilterOperator.NEQ, "2026-02-01T10:00:00Z"))).containsExactly("January", "March");
        assertThat(names(new FilterCondition("dueAt", FilterOperator.GT, "2026-02-01T10:00:00Z"))).containsExactly("March");
        assertThat(names(new FilterCondition("dueAt", FilterOperator.LTE, "2026-02-01T10:00:00Z"))).containsExactly("February", "January");
        assertThat(names(new FilterCondition("dueAt", FilterOperator.IN,
                List.of("2026-01-01T10:00:00Z", "2026-03-01T10:00:00Z")))).containsExactly("January", "March");
    }

    @Test
    @DisplayName("a DATE field filters with yyyy-MM-dd values")
    void dateFieldBindsAsDate() {
        assertThat(names(new FilterCondition("dueOn", FilterOperator.EQ, "2026-02-01"))).containsExactly("February");
        assertThat(names(new FilterCondition("dueOn", FilterOperator.GTE, "2026-02-01"))).containsExactly("February", "March");
        assertThat(names(new FilterCondition("dueOn", FilterOperator.IN, List.of("2026-01-01", "2026-03-01"))))
                .containsExactly("January", "March");
    }

    @Test
    @DisplayName("an unparseable value is rejected as InvalidFilterException (400), not a database error")
    void unparseableValueIsBadRequest() {
        assertThatThrownBy(() -> names(new FilterCondition("createdAt", FilterOperator.GTE, "last week")))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("createdAt");
    }

    @Test
    @DisplayName("ISNULL on a temporal column is untouched")
    void isNullUntouched() {
        assertThat(names(new FilterCondition("dueAt", FilterOperator.ISNULL, false)))
                .containsExactly("February", "January", "March");
        assertThat(names(new FilterCondition("dueAt", FilterOperator.ISNULL, true))).isEmpty();
    }
}
