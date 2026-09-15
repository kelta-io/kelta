package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.StorageConfig;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.runtime.workflow.DuplicateDefaultException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ListViewConfigHook")
class ListViewConfigHookTest {

    private static final String COLLECTION_ID = "col-1";

    private CollectionRegistry collectionRegistry;
    private JdbcTemplate jdbcTemplate;
    private ListViewConfigHook hook;

    @BeforeEach
    void setUp() {
        collectionRegistry = mock(CollectionRegistry.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        hook = new ListViewConfigHook(collectionRegistry, jdbcTemplate);

        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(COLLECTION_ID)))
                .thenReturn(List.of("projects"));
        when(collectionRegistry.get("projects")).thenReturn(projectsDef());
    }

    private static CollectionDefinition projectsDef() {
        return CollectionDefinition.builder()
                .name("projects")
                .storageConfig(StorageConfig.physicalTable("projects"))
                .addField(FieldDefinition.string("name"))
                .addField(FieldDefinition.string("status"))
                .build();
    }

    private static Map<String, Object> baseRecord() {
        Map<String, Object> record = new HashMap<>();
        record.put("collectionId", COLLECTION_ID);
        record.put("visibility", "PRIVATE");
        record.put("columns", List.of("name", "status"));
        record.put("filters", List.of());
        return record;
    }

    @Test
    @DisplayName("rejects a column that doesn't exist on the target collection")
    void rejectsUnknownColumn() {
        Map<String, Object> record = baseRecord();
        record.put("columns", List.of("nope"));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors().getFirst().field()).isEqualTo("columns/0");
    }

    @Test
    @DisplayName("rejects a sortField that doesn't exist on the target collection")
    void rejectsUnknownSortField() {
        Map<String, Object> record = baseRecord();
        record.put("sortField", "nope");

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors().getFirst().field()).isEqualTo("sortField");
    }

    @Test
    @DisplayName("rejects a rowLimit outside the allowed page sizes")
    void rejectsBadRowLimit() {
        Map<String, Object> record = baseRecord();
        record.put("rowLimit", 30);

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors().getFirst().field()).isEqualTo("rowLimit");
        assertThat(result.getErrors().getFirst().message()).contains("10", "25", "50", "100");
    }

    @Test
    @DisplayName("accepts an in filter with a CSV value and canonicalizes the operator")
    void acceptsInFilterAndCanonicalizesOperator() {
        Map<String, Object> record = baseRecord();
        record.put("filters", List.of(Map.of(
                "field", "status", "operator", "in", "value", "a,b")));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filters = (List<Map<String, Object>>) result.getFieldUpdates().get("filters");
        assertThat(filters).hasSize(1);
        assertThat(filters.getFirst().get("operator")).isEqualTo("in");
        assertThat(filters.getFirst().get("value")).isEqualTo("a,b");
    }

    @Test
    @DisplayName("accepts an in filter with an array value, canonicalized to a CSV string")
    void acceptsInFilterWithArrayValue() {
        Map<String, Object> record = baseRecord();
        record.put("filters", List.of(Map.of(
                "field", "status", "operator", "in", "value", List.of("a", "b"))));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filters = (List<Map<String, Object>>) result.getFieldUpdates().get("filters");
        assertThat(filters.getFirst().get("value")).isEqualTo("a,b");
    }

    @Test
    @DisplayName("accepts the 'equals' alias and stores the canonical 'eq' operator")
    void canonicalizesEqualsAlias() {
        Map<String, Object> record = baseRecord();
        record.put("filters", List.of(Map.of(
                "field", "status", "operator", "equals", "value", "OPEN")));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filters = (List<Map<String, Object>>) result.getFieldUpdates().get("filters");
        assertThat(filters.getFirst().get("operator")).isEqualTo("eq");
    }

    @Test
    @DisplayName("rejects a filter operator outside the grammar")
    void rejectsUnknownOperator() {
        Map<String, Object> record = baseRecord();
        record.put("filters", List.of(Map.of(
                "field", "status", "operator", "bogus", "value", "OPEN")));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors().getFirst().field()).isEqualTo("filters/0/operator");
    }

    @Test
    @DisplayName("rejects a filter field that doesn't exist on the target collection")
    void rejectsUnknownFilterField() {
        Map<String, Object> record = baseRecord();
        record.put("filters", List.of(Map.of(
                "field", "nope", "operator", "eq", "value", "x")));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors().getFirst().field()).isEqualTo("filters/0/field");
    }

    @Test
    @DisplayName("a second default for the same collection/visibility throws a 409-mapped conflict")
    void rejectsSecondDefault() {
        Map<String, Object> record = baseRecord();
        record.put("isDefault", true);

        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(COLLECTION_ID), eq("PRIVATE")))
                .thenReturn(List.of("existing-view-id"));

        assertThatThrownBy(() -> hook.beforeCreate(record, "t1"))
                .isInstanceOf(DuplicateDefaultException.class)
                .satisfies(ex -> {
                    DuplicateDefaultException conflict = (DuplicateDefaultException) ex;
                    assertThat(conflict.getCode()).isEqualTo("DEFAULT_VIEW_EXISTS");
                    assertThat(conflict.getExistingId()).isEqualTo("existing-view-id");
                });
    }

    @Test
    @DisplayName("PATCHing the current default (excluded from the conflict lookup) succeeds")
    void allowsPatchingTheCurrentDefault() {
        Map<String, Object> patch = new HashMap<>();
        patch.put("isDefault", true);
        Map<String, Object> previous = baseRecord();
        previous.put("isDefault", true);

        when(jdbcTemplate.queryForList(anyString(), eq(String.class),
                eq(COLLECTION_ID), eq("PRIVATE"), eq("view-1")))
                .thenReturn(List.of());

        BeforeSaveResult result = hook.beforeUpdate("view-1", patch, previous, "t1");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("no conflict when no default view exists yet")
    void allowsFirstDefault() {
        Map<String, Object> record = baseRecord();
        record.put("isDefault", true);

        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(COLLECTION_ID), eq("PRIVATE")))
                .thenReturn(List.of());

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isTrue();
    }
}
