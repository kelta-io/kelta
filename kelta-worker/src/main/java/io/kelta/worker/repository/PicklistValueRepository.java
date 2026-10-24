package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads {@code picklist_value} rows by their source. Writes go through the query engine so the
 * record-changed events (cache eviction, search index) fire as for any other delete.
 */
@Repository
public class PicklistValueRepository {

    static final String SELECT_GLOBAL_VALUE_IDS = """
            SELECT id FROM picklist_value
            WHERE tenant_id = ? AND picklist_source_type = 'GLOBAL' AND picklist_source_id = ?
            """;

    static final String COUNT_GLOBAL_VALUE = """
            SELECT COUNT(*) FROM picklist_value
            WHERE tenant_id = ? AND picklist_source_type = 'GLOBAL' AND picklist_source_id = ? AND value = ?
            """;

    static final String SELECT_GLOBAL_PICKLIST_NAME = """
            SELECT name FROM global_picklist WHERE tenant_id = ? AND id = ?
            """;

    /**
     * Active values of every field of one collection, whether the field owns its values
     * ({@code FIELD} source, keyed by field id) or is bound to a global picklist through
     * {@code fieldTypeConfig.globalPicklistId} ({@code GLOBAL} source).
     */
    static final String SELECT_ACTIVE_VALUES_BY_COLLECTION = """
            SELECT f.name AS field_name, pv.value, pv.label, pv.is_default, pv.sort_order
            FROM collection c
            JOIN field f ON f.collection_id = c.id
            JOIN picklist_value pv ON pv.tenant_id = c.tenant_id
                AND ((pv.picklist_source_type = 'FIELD' AND pv.picklist_source_id = f.id)
                  OR (pv.picklist_source_type = 'GLOBAL'
                      AND pv.picklist_source_id = f.field_type_config->>'globalPicklistId'))
            WHERE c.tenant_id = ? AND c.name = ? AND f.active = true AND pv.is_active = true
            ORDER BY f.name, pv.sort_order, pv.value
            """;

    /** One selectable value of a picklist field. */
    public record PicklistOption(String value, String label, boolean isDefault, int sortOrder) {
    }

    private final JdbcTemplate jdbc;

    public PicklistValueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ids of the values whose source is the given global picklist. */
    public List<String> findGlobalValueIds(String tenantId, String picklistId) {
        return jdbc.queryForList(SELECT_GLOBAL_VALUE_IDS, String.class, tenantId, picklistId);
    }

    /** Whether the given global picklist already has a value equal to {@code value}. */
    public boolean existsGlobalValue(String tenantId, String picklistId, String value) {
        Integer count = jdbc.queryForObject(COUNT_GLOBAL_VALUE, Integer.class, tenantId, picklistId, value);
        return count != null && count > 0;
    }

    /** Name of the global picklist, if it exists in the tenant. */
    public Optional<String> findGlobalPicklistName(String tenantId, String picklistId) {
        return jdbc.queryForList(SELECT_GLOBAL_PICKLIST_NAME, String.class, tenantId, picklistId)
                .stream().findFirst();
    }

    /**
     * Active picklist values of a collection's fields, keyed by field name, each list in
     * {@code sort_order}. Fields without values are absent from the map.
     */
    public Map<String, List<PicklistOption>> findActiveValuesByCollection(String tenantId, String collectionName) {
        Map<String, List<PicklistOption>> byField = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(SELECT_ACTIVE_VALUES_BY_COLLECTION, tenantId, collectionName)) {
            Number sortOrder = (Number) row.get("sort_order");
            byField.computeIfAbsent((String) row.get("field_name"), k -> new ArrayList<>())
                    .add(new PicklistOption(
                            (String) row.get("value"),
                            (String) row.get("label"),
                            Boolean.TRUE.equals(row.get("is_default")),
                            sortOrder == null ? 0 : sortOrder.intValue()));
        }
        return byField;
    }
}
