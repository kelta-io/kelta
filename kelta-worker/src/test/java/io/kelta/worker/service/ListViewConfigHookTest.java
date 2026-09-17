package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.runtime.workflow.HookConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ListViewConfigHook")
class ListViewConfigHookTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String TARGET_COLLECTION_ID = "a4f55cde-d3bd-43f4-b6c5-b95e59e01926";

    private CollectionRegistry collectionRegistry;
    private JdbcTemplate jdbcTemplate;
    private ListViewConfigHook hook;

    @BeforeEach
    void setUp() {
        collectionRegistry = mock(CollectionRegistry.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        hook = new ListViewConfigHook(collectionRegistry, jdbcTemplate);

        CollectionDefinition opportunities = CollectionDefinition.builder()
                .name("opportunities")
                .addField(FieldDefinition.requiredString("status", 50))
                .addField(FieldDefinition.string("owner", 50))
                .build();
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(TARGET_COLLECTION_ID)))
                .thenReturn(List.of("opportunities"));
        when(collectionRegistry.get("opportunities")).thenReturn(opportunities);
    }

    private Map<String, Object> baseRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("collectionId", TARGET_COLLECTION_ID);
        record.put("name", "My View");
        record.put("visibility", "PRIVATE");
        record.put("columns", new ArrayList<>(List.of("status")));
        record.put("filters", new ArrayList<>());
        return record;
    }

    @Test
    @DisplayName("rejects an unknown column with pointer columns/<index>")
    void rejectsUnknownColumn() {
        Map<String, Object> record = baseRecord();
        record.put("columns", List.of("nope"));

        BeforeSaveResult result = hook.beforeCreate(record, TENANT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).field()).isEqualTo("columns/0");
    }

    @Test
    @DisplayName("rejects an unknown sortField")
    void rejectsUnknownSortField() {
        Map<String, Object> record = baseRecord();
        record.put("sortField", "nope");

        BeforeSaveResult result = hook.beforeCreate(record, TENANT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).field()).isEqualTo("sortField");
    }

    @Test
    @DisplayName("rejects a rowLimit outside {10,25,50,100}, listing the allowed values")
    void rejectsInvalidRowLimit() {
        Map<String, Object> record = baseRecord();
        record.put("rowLimit", 30);

        BeforeSaveResult result = hook.beforeCreate(record, TENANT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).field()).isEqualTo("rowLimit");
        assertThat(result.getErrors().get(0).message()).contains("10", "25", "50", "100");
        assertThat(result.getErrors().get(0).code()).isEqualTo("INVALID_ROW_LIMIT");
    }

    @Test
    @DisplayName("accepts a valid rowLimit")
    void acceptsValidRowLimit() {
        Map<String, Object> record = baseRecord();
        record.put("rowLimit", 100);

        BeforeSaveResult result = hook.beforeCreate(record, TENANT);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("accepts an 'in' filter with a CSV string value")
    void acceptsInFilterWithCsvValue() {
        Map<String, Object> record = baseRecord();
        record.put("filters", List.of(
                new LinkedHashMap<>(Map.of("field", "status", "operator", "in", "value", "a,b"))));

        BeforeSaveResult result = hook.beforeCreate(record, TENANT);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("canonicalizes the 'equals' alias to 'eq'")
    void canonicalizesOperatorAlias() {
        Map<String, Object> record = baseRecord();
        record.put("filters", List.of(
                new LinkedHashMap<>(Map.of("field", "status", "operator", "equals", "value", "OPEN"))));

        BeforeSaveResult result = hook.beforeCreate(record, TENANT);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasFieldUpdates()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filters = (List<Map<String, Object>>) result.getFieldUpdates().get("filters");
        assertThat(filters.get(0).get("operator")).isEqualTo("eq");
    }

    @Test
    @DisplayName("rejects an unknown filter operator")
    void rejectsUnknownOperator() {
        Map<String, Object> record = baseRecord();
        record.put("filters", List.of(
                new LinkedHashMap<>(Map.of("field", "status", "operator", "bogus", "value", "OPEN"))));

        BeforeSaveResult result = hook.beforeCreate(record, TENANT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).field()).isEqualTo("filters/0/operator");
    }

    @Test
    @DisplayName("a second isDefault for the same collection+visibility raises 409 DEFAULT_VIEW_EXISTS")
    void rejectsSecondDefault() {
        Map<String, Object> record = baseRecord();
        record.put("isDefault", true);
        when(jdbcTemplate.queryForList(
                anyString(), eq(String.class), eq(TARGET_COLLECTION_ID), eq("PRIVATE")))
                .thenReturn(List.of("existing-view-id"));

        assertThatThrownBy(() -> hook.beforeCreate(record, TENANT))
                .isInstanceOf(HookConflictException.class)
                .satisfies(e -> {
                    HookConflictException conflict = (HookConflictException) e;
                    assertThat(conflict.getCode()).isEqualTo("DEFAULT_VIEW_EXISTS");
                    assertThat(conflict.getMeta()).containsEntry("existingId", "existing-view-id");
                });
    }

    @Test
    @DisplayName("PATCHing the current default (excluding itself) succeeds")
    void patchingCurrentDefaultSucceeds() {
        String id = "existing-view-id";
        Map<String, Object> previous = baseRecord();
        previous.put("isDefault", true);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("name", "Renamed");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class),
                eq(TARGET_COLLECTION_ID), eq("PRIVATE"), eq(id)))
                .thenReturn(List.of());

        BeforeSaveResult result = hook.beforeUpdate(id, patch, previous, TENANT);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("no-ops when the target collection can't be resolved (fails open on field checks)")
    void skipsFieldChecksWhenCollectionUnresolvable() {
        Map<String, Object> record = baseRecord();
        record.put("collectionId", "00000000-0000-0000-0000-000000000000");
        record.put("columns", List.of("anything"));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class),
                eq("00000000-0000-0000-0000-000000000000")))
                .thenReturn(List.of());

        BeforeSaveResult result = hook.beforeCreate(record, TENANT);

        assertThat(result.isSuccess()).isTrue();
    }
}
