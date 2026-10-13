package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

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

    private final JdbcTemplate jdbc;

    public PicklistValueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ids of the values whose source is the given global picklist. */
    public List<String> findGlobalValueIds(String tenantId, String picklistId) {
        return jdbc.queryForList(SELECT_GLOBAL_VALUE_IDS, String.class, tenantId, picklistId);
    }
}
