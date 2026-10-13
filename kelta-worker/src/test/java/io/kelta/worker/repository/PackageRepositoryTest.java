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

    private String capturedIdSql(Object... params) {
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq(String.class), eq(params));
        return sql.getValue();
    }

    @Test
    @DisplayName("Collection-scoped export finds the collections' rules and layouts by collection id")
    void findsRulesAndLayoutsByCollectionIds() {
        repository.findValidationRuleIdsByCollectionIds("t1", List.of("c1", "c2"));
        assertThat(capturedIdSql("t1", "c1", "c2"))
                .isEqualTo("SELECT id FROM validation_rule WHERE tenant_id = ? AND collection_id IN (?,?)");

        reset(jdbcTemplate);
        repository.findPageLayoutIdsByCollectionIds("t1", List.of("c1"));
        assertThat(capturedIdSql("t1", "c1"))
                .isEqualTo("SELECT id FROM page_layout WHERE tenant_id = ? AND collection_id IN (?)");
    }

    @Test
    @DisplayName("Collection-scoped export finds flows by trigger_config.collection name")
    void findsFlowsByTriggerCollection() {
        repository.findFlowIdsByTriggerCollections("t1", List.of("sites", "inspections"));
        assertThat(capturedIdSql("t1", "sites", "inspections"))
                .contains("FROM flow WHERE tenant_id = ?")
                .contains("trigger_config->>'collection' IN (?,?)");
    }

    @Test
    @DisplayName("Collection-scoped export finds global picklists bound by the collections' fields")
    void findsReferencedGlobalPicklists() {
        repository.findGlobalPicklistIdsReferencedByCollections("t1", List.of("c1"));
        assertThat(capturedIdSql("t1", "c1"))
                .contains("SELECT DISTINCT gp.id FROM field f")
                .contains("JOIN global_picklist gp ON gp.tenant_id = c.tenant_id "
                        + "AND gp.id = f.field_type_config->>'globalPicklistId'")
                .contains("WHERE c.tenant_id = ? AND f.collection_id IN (?)");
    }

    @Test
    @DisplayName("Collection-scoped export finds list views by collection id and dashboards by widget collection")
    void findsListViewsAndDashboards() {
        repository.findListViewIdsByCollectionIds("t1", List.of("c1"));
        assertThat(capturedIdSql("t1", "c1"))
                .isEqualTo("SELECT id FROM list_view WHERE tenant_id = ? AND collection_id IN (?)");

        reset(jdbcTemplate);
        repository.findDashboardIdsByComponentCollections("t1", List.of("sites", "inspections"));
        assertThat(capturedIdSql("t1", "sites", "inspections"))
                .contains("JOIN dashboard_component dc ON dc.dashboard_id = d.id")
                .contains("WHERE d.tenant_id = ? AND dc.config->>'collectionName' IN (?,?)");
    }

    @Test
    @DisplayName("List view export carries the owning collection's name; widgets come in display order")
    void listViewAndWidgetExportQueries() {
        repository.findListViewsByIds("t1", List.of("lv1"));
        repository.findDashboardComponentsByDashboardIds("t1", List.of("d1"));

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues().get(0))
                .contains("SELECT lv.*, c.name AS collection_name FROM list_view lv")
                .contains("WHERE lv.tenant_id = ? AND lv.id IN (?)");
        assertThat(sql.getAllValues().get(1))
                .contains("FROM dashboard_component WHERE tenant_id = ? AND dashboard_id IN (?)")
                .endsWith("ORDER BY sort_order, id");
    }

    @Test
    @DisplayName("Collection-scoped lookups skip the query for an empty list")
    void collectionScopedLookupsSkipEmptyLists() {
        assertThat(repository.findValidationRuleIdsByCollectionIds("t1", List.of())).isEmpty();
        assertThat(repository.findPageLayoutIdsByCollectionIds("t1", List.of())).isEmpty();
        assertThat(repository.findFlowIdsByTriggerCollections("t1", List.of())).isEmpty();
        assertThat(repository.findGlobalPicklistIdsReferencedByCollections("t1", List.of())).isEmpty();
        assertThat(repository.findListViewIdsByCollectionIds("t1", List.of())).isEmpty();
        assertThat(repository.findDashboardIdsByComponentCollections("t1", List.of())).isEmpty();
        assertThat(repository.findListViewsByIds("t1", List.of())).isEmpty();
        assertThat(repository.findDashboardsByIds("t1", List.of())).isEmpty();
        assertThat(repository.findDashboardComponentsByDashboardIds("t1", List.of())).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("Should expose JdbcTemplate")
    void shouldExposeJdbcTemplate() {
        assertThat(repository.getJdbcTemplate()).isSameAs(jdbcTemplate);
    }
}
