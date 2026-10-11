package io.kelta.worker.repository;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PackageRepository")
class PackageRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private PackageRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new PackageRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("Should find all history by tenant ID")
    void shouldFindAllByTenantId() {
        when(jdbcTemplate.queryForList(contains("package_history"), eq("t1")))
                .thenReturn(List.of(Map.of("id", "pkg-1", "name", "test")));

        var result = repository.findAllByTenantId("t1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("name")).isEqualTo("test");
    }

    @Test
    @DisplayName("Should save package history and return ID")
    void shouldSave() {
        when(jdbcTemplate.update(contains("INSERT INTO package_history"), any(Object[].class)))
                .thenReturn(1);

        String id = repository.save("t1", "export-1", "1.0.0", "desc", "export", "success", "[]");
        assertThat(id).isNotNull().hasSize(36); // UUID format
        verify(jdbcTemplate).update(contains("INSERT INTO package_history"), any(Object[].class));
    }

    @Test
    @DisplayName("Should update status")
    void shouldUpdateStatus() {
        repository.updateStatus("pkg-1", "failed", "Connection error");
        verify(jdbcTemplate).update(contains("UPDATE package_history"), eq("failed"),
                eq("Connection error"), any(), eq("pkg-1"));
    }

    @Test
    @DisplayName("Should return empty list for empty ID list")
    void shouldReturnEmptyForEmptyIds() {
        var result = repository.findCollectionsByIds("t1", List.of());
        assertThat(result).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("Should find collections by IDs")
    void shouldFindCollectionsByIds() {
        when(jdbcTemplate.queryForList(contains("collection"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", "col-1", "name", "users")));

        var result = repository.findCollectionsByIds("t1", List.of("col-1"));
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("Field export carries the bound global picklist's name (BUILD-LOG error 14)")
    void fieldExportJoinsGlobalPicklistName() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("name", "stage", "global_picklist_name", "crm-deal-stage")));

        var result = repository.findFieldsWithNamesByCollectionIds("t1", List.of("col-1"));

        assertThat(result.get(0)).containsEntry("global_picklist_name", "crm-deal-stage");
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("gp.name AS global_picklist_name")
                .contains("LEFT JOIN global_picklist gp ON gp.tenant_id = c.tenant_id "
                        + "AND gp.id = f.field_type_config->>'globalPicklistId'");
    }

    @Test
    @DisplayName("Should expose JdbcTemplate")
    void shouldExposeJdbcTemplate() {
        assertThat(repository.getJdbcTemplate()).isSameAs(jdbcTemplate);
    }
}
