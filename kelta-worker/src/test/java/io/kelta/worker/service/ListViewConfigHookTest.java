package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.validation.DuplicateDefaultException;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ListViewConfigHook")
class ListViewConfigHookTest {

    private static final String COLLECTION_ID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private CollectionRegistry collectionRegistry;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ListViewConfigHook hook;

    @BeforeEach
    void setUp() {
        hook = new ListViewConfigHook(collectionRegistry, jdbcTemplate);

        CollectionDefinition projects = new CollectionDefinitionBuilder()
                .name("projects")
                .addField(FieldDefinition.requiredString("name"))
                .addField(FieldDefinition.string("status"))
                .addField(FieldDefinition.string("owner"))
                .build();

        lenient().when(jdbcTemplate.queryForList(
                eq("SELECT name FROM collection WHERE id = ?::uuid"), eq(String.class), eq(COLLECTION_ID)))
                .thenReturn(List.of("projects"));
        lenient().when(collectionRegistry.get("projects")).thenReturn(projects);
    }

    private static Map<String, Object> baseRecord() {
        Map<String, Object> record = new HashMap<>();
        record.put("collectionId", COLLECTION_ID);
        record.put("name", "My view");
        record.put("columns", new ArrayList<>(List.of("name", "status")));
        return record;
    }

    @Test
    @DisplayName("registration: targets list-views")
    void registration() {
        assertThat(hook.getCollectionName()).isEqualTo("list-views");
    }

    @Test
    @DisplayName("unknown column -> error with pointer field columns/<index>")
    void unknownColumn() {
        Map<String, Object> record = baseRecord();
        record.put("columns", List.of("nope"));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).field()).isEqualTo("columns/0");
    }

    @Test
    @DisplayName("unknown sortField -> error with pointer field sortField")
    void unknownSortField() {
        Map<String, Object> record = baseRecord();
        record.put("sortField", "nope");

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).field()).isEqualTo("sortField");
    }

    @Test
    @DisplayName("rowLimit outside {10,25,50,100} -> error listing the allowed values")
    void invalidRowLimit() {
        Map<String, Object> record = baseRecord();
        record.put("rowLimit", 30);

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).field()).isEqualTo("rowLimit");
        assertThat(result.getErrors().get(0).message()).contains("10").contains("25").contains("50").contains("100");
    }

    @Test
    @DisplayName("rowLimit in the allowed set -> ok")
    void validRowLimit() {
        Map<String, Object> record = baseRecord();
        record.put("rowLimit", 25);

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("filter with a known field and 'in' operator -> ok, value passed through")
    void inFilterAccepted() {
        Map<String, Object> record = baseRecord();
        record.put("filters", List.of(mapOf("field", "status", "operator", "in", "value", "a,b")));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("filter operator 'equals' is accepted and canonicalized to 'eq'")
    void equalsOperatorCanonicalized() {
        Map<String, Object> record = baseRecord();
        record.put("filters", List.of(mapOf("field", "status", "operator", "equals", "value", "OPEN")));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasFieldUpdates()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filters = (List<Map<String, Object>>) result.getFieldUpdates().get("filters");
        assertThat(filters).hasSize(1);
        assertThat(filters.get(0).get("operator")).isEqualTo("eq");
    }

    @Test
    @DisplayName("unknown filter operator -> error")
    void unknownFilterOperator() {
        Map<String, Object> record = baseRecord();
        record.put("filters", List.of(mapOf("field", "status", "operator", "nope", "value", "OPEN")));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors().get(0).field()).isEqualTo("filters/0/operator");
    }

    @Test
    @DisplayName("filter referencing an unknown field -> error")
    void unknownFilterField() {
        Map<String, Object> record = baseRecord();
        record.put("filters", List.of(mapOf("field", "nope", "operator", "eq", "value", "x")));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors().get(0).field()).isEqualTo("filters/0/field");
    }

    @Test
    @DisplayName("second isDefault=true for the same collection+visibility -> 409-mapped exception with existingId")
    void duplicateDefaultRejected() {
        Map<String, Object> record = baseRecord();
        record.put("isDefault", true);
        record.put("visibility", "PRIVATE");

        when(jdbcTemplate.queryForList(
                eq("SELECT id FROM list_view WHERE collection_id = ?::uuid AND visibility = ? AND is_default = true"),
                eq(String.class), any(), any()))
                .thenReturn(List.of("existing-view-id"));

        assertThatThrownBy(() -> hook.beforeCreate(record, "tenant-1"))
                .isInstanceOf(DuplicateDefaultException.class)
                .satisfies(e -> {
                    DuplicateDefaultException ex = (DuplicateDefaultException) e;
                    assertThat(ex.getCode()).isEqualTo("DEFAULT_VIEW_EXISTS");
                    assertThat(ex.getExistingId()).isEqualTo("existing-view-id");
                });
    }

    @Test
    @DisplayName("PATCHing the current default (excluded by id) -> ok, no conflict")
    void patchingCurrentDefaultIsOk() {
        Map<String, Object> record = new HashMap<>();
        record.put("name", "Renamed");
        Map<String, Object> previous = baseRecord();
        previous.put("isDefault", true);
        previous.put("visibility", "PRIVATE");

        when(jdbcTemplate.queryForList(
                eq("SELECT id FROM list_view WHERE collection_id = ?::uuid AND visibility = ? AND is_default = true "
                        + "AND id <> ?::uuid"),
                eq(String.class), any(), any(), any()))
                .thenReturn(List.of());

        BeforeSaveResult result = hook.beforeUpdate("self-id", record, previous, "tenant-1");

        assertThat(result.isSuccess()).isTrue();
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
