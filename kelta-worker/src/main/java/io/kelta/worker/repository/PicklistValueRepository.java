package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
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
}
