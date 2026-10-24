package io.kelta.worker.repository;

import io.kelta.worker.repository.PicklistValueRepository.PicklistOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PicklistValueRepository")
class PicklistValueRepositoryTest {

    private JdbcTemplate jdbc;
    private PicklistValueRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = new PicklistValueRepository(jdbc);
    }

    private static Map<String, Object> row(String field, String value, String label, Boolean isDefault, Integer sortOrder) {
        Map<String, Object> row = new HashMap<>();
        row.put("field_name", field);
        row.put("value", value);
        row.put("label", label);
        row.put("is_default", isDefault);
        row.put("sort_order", sortOrder);
        return row;
    }

    @Test
    @DisplayName("Active-values query is tenant-scoped, active-only, covers FIELD and GLOBAL sources and orders by sort_order")
    void activeValuesQueryShape() {
        String sql = PicklistValueRepository.SELECT_ACTIVE_VALUES_BY_COLLECTION;

        assertThat(sql)
                .contains("WHERE c.tenant_id = ? AND c.name = ?")
                .contains("pv.tenant_id = c.tenant_id")
                .contains("pv.is_active = true")
                .contains("f.active = true")
                .contains("pv.picklist_source_type = 'FIELD' AND pv.picklist_source_id = f.id")
                .contains("pv.picklist_source_type = 'GLOBAL'")
                .contains("pv.picklist_source_id = f.field_type_config->>'globalPicklistId'")
                .contains("ORDER BY f.name, pv.sort_order");
    }

    @Test
    @DisplayName("findActiveValuesByCollection binds tenant then collection name and groups rows by field")
    void groupsRowsByField() {
        when(jdbc.queryForList(anyString(), eq("t-1"), eq("tickets"))).thenReturn(List.of(
                row("priority", "LOW", "Low", false, 1),
                row("priority", "HIGH", "High", true, 2),
                row("tags", "BUG", "Bug", null, null)));

        Map<String, List<PicklistOption>> byField = repository.findActiveValuesByCollection("t-1", "tickets");

        verify(jdbc).queryForList(PicklistValueRepository.SELECT_ACTIVE_VALUES_BY_COLLECTION, "t-1", "tickets");
        assertThat(byField).containsOnlyKeys("priority", "tags");
        assertThat(byField.get("priority")).containsExactly(
                new PicklistOption("LOW", "Low", false, 1),
                new PicklistOption("HIGH", "High", true, 2));
        assertThat(byField.get("tags")).containsExactly(new PicklistOption("BUG", "Bug", false, 0));
    }
}
