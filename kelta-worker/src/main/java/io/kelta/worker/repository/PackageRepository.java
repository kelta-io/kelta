package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PackageRepository {

    private final JdbcTemplate jdbcTemplate;

    public PackageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    public List<Map<String, Object>> findAllByTenantId(String tenantId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM package_history WHERE tenant_id = ? ORDER BY created_at DESC",
                tenantId
        );
    }

    public Optional<Map<String, Object>> findByIdAndTenantId(String id, String tenantId) {
        var results = jdbcTemplate.queryForList(
                "SELECT * FROM package_history WHERE id = ? AND tenant_id = ?",
                id, tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public String save(String tenantId, String name, String version, String description,
                       String type, String status, String itemsJson) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO package_history (id, tenant_id, name, version, description, type, status, items, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, name, version, description, type, status, itemsJson, now, now
        );
        return id;
    }

    public void updateStatus(String id, String status, String errorMessage) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "UPDATE package_history SET status = ?, error_message = ?, updated_at = ? WHERE id = ?",
                status, errorMessage, now, id
        );
    }

    /** Collections enriched with the display field's name for cross-tenant remap (KLT-284). */
    public List<Map<String, Object>> findCollectionsByIds(String tenantId, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        Object[] params = new Object[ids.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < ids.size(); i++) params[i + 1] = ids.get(i);
        return jdbcTemplate.queryForList(
                "SELECT c.*, df.name AS display_field_name FROM collection c " +
                        "LEFT JOIN field df ON c.display_field_id = df.id " +
                        "WHERE c.tenant_id = ? AND c.id IN (" + placeholders + ")",
                params
        );
    }

    public List<Map<String, Object>> findFieldsByCollectionIds(String tenantId, List<String> collectionIds) {
        if (collectionIds.isEmpty()) return List.of();
        String placeholders = String.join(",", collectionIds.stream().map(i -> "?").toList());
        Object[] params = new Object[collectionIds.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < collectionIds.size(); i++) params[i + 1] = collectionIds.get(i);
        return jdbcTemplate.queryForList(
                "SELECT f.* FROM field f JOIN collection c ON f.collection_id = c.id " +
                        "WHERE c.tenant_id = ? AND f.collection_id IN (" + placeholders + ")",
                params
        );
    }


    public List<Map<String, Object>> findUiPagesByIds(String tenantId, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        Object[] params = new Object[ids.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < ids.size(); i++) params[i + 1] = ids.get(i);
        return jdbcTemplate.queryForList(
                "SELECT * FROM ui_page WHERE tenant_id = ? AND id IN (" + placeholders + ")",
                params
        );
    }

    public List<Map<String, Object>> findUiMenusByIds(String tenantId, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        Object[] params = new Object[ids.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < ids.size(); i++) params[i + 1] = ids.get(i);
        return jdbcTemplate.queryForList(
                "SELECT * FROM ui_menu WHERE tenant_id = ? AND id IN (" + placeholders + ")",
                params
        );
    }

    public List<Map<String, Object>> findUiMenuItemsByMenuIds(String tenantId, List<String> menuIds) {
        if (menuIds.isEmpty()) return List.of();
        String placeholders = String.join(",", menuIds.stream().map(i -> "?").toList());
        Object[] params = new Object[menuIds.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < menuIds.size(); i++) params[i + 1] = menuIds.get(i);
        return jdbcTemplate.queryForList(
                "SELECT * FROM ui_menu_item WHERE tenant_id = ? AND menu_id IN (" + placeholders + ") ORDER BY display_order ASC",
                params
        );
    }

    public List<Map<String, Object>> findCollectionsByNames(String tenantId, List<String> names) {
        if (names.isEmpty()) return List.of();
        String placeholders = String.join(",", names.stream().map(i -> "?").toList());
        Object[] params = new Object[names.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < names.size(); i++) params[i + 1] = names.get(i);
        return jdbcTemplate.queryForList(
                "SELECT * FROM collection WHERE tenant_id = ? AND name IN (" + placeholders + ")",
                params
        );
    }

    public List<Map<String, Object>> findUiPagesByPaths(String tenantId, List<String> paths) {
        if (paths.isEmpty()) return List.of();
        String placeholders = String.join(",", paths.stream().map(i -> "?").toList());
        Object[] params = new Object[paths.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < paths.size(); i++) params[i + 1] = paths.get(i);
        return jdbcTemplate.queryForList(
                "SELECT * FROM ui_page WHERE tenant_id = ? AND path IN (" + placeholders + ")",
                params
        );
    }

    public List<Map<String, Object>> findUiMenusByNames(String tenantId, List<String> names) {
        if (names.isEmpty()) return List.of();
        String placeholders = String.join(",", names.stream().map(i -> "?").toList());
        Object[] params = new Object[names.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < names.size(); i++) params[i + 1] = names.get(i);
        return jdbcTemplate.queryForList(
                "SELECT * FROM ui_menu WHERE tenant_id = ? AND name IN (" + placeholders + ")",
                params
        );
    }

    // ------------------------------------------------------------------
    // Package format v2: provenance + extended type coverage
    // ------------------------------------------------------------------

    /** Stable per-installation id for cross-cluster package provenance (V157). */
    public Optional<String> findInstanceId() {
        var rows = jdbcTemplate.queryForList("SELECT id FROM platform_instance LIMIT 1");
        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.get(0).get("id"));
    }

    public Optional<String> findTenantSlug(String tenantId) {
        var rows = jdbcTemplate.queryForList("SELECT slug FROM tenant WHERE id = ?", tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.get(0).get("slug"));
    }

    public List<String> findAllIds(String table, String tenantId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM " + table + " WHERE tenant_id = ?", String.class, tenantId);
    }

    /** Fields enriched with owning + referenced collection names for cross-tenant remap. */
    public List<Map<String, Object>> findFieldsWithNamesByCollectionIds(String tenantId, List<String> collectionIds) {
        if (collectionIds.isEmpty()) return List.of();
        String placeholders = String.join(",", collectionIds.stream().map(i -> "?").toList());
        Object[] params = new Object[collectionIds.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < collectionIds.size(); i++) params[i + 1] = collectionIds.get(i);
        return jdbcTemplate.queryForList(
                "SELECT f.*, c.name AS collection_name, rc.name AS reference_collection_name " +
                        "FROM field f JOIN collection c ON f.collection_id = c.id " +
                        "LEFT JOIN collection rc ON f.reference_collection_id = rc.id " +
                        "WHERE c.tenant_id = ? AND f.collection_id IN (" + placeholders + ")",
                params
        );
    }

    public List<Map<String, Object>> findFlowsByIds(String tenantId, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        Object[] params = new Object[ids.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < ids.size(); i++) params[i + 1] = ids.get(i);
        return jdbcTemplate.queryForList(
                "SELECT * FROM flow WHERE tenant_id = ? AND id IN (" + placeholders + ")",
                params
        );
    }

    public List<Map<String, Object>> findValidationRulesByIds(String tenantId, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        Object[] params = new Object[ids.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < ids.size(); i++) params[i + 1] = ids.get(i);
        return jdbcTemplate.queryForList(
                "SELECT vr.*, c.name AS collection_name FROM validation_rule vr " +
                        "JOIN collection c ON vr.collection_id = c.id " +
                        "WHERE vr.tenant_id = ? AND vr.id IN (" + placeholders + ")",
                params
        );
    }

    public List<Map<String, Object>> findPageLayoutsByIds(String tenantId, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        Object[] params = new Object[ids.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < ids.size(); i++) params[i + 1] = ids.get(i);
        return jdbcTemplate.queryForList(
                "SELECT pl.*, c.name AS collection_name FROM page_layout pl " +
                        "JOIN collection c ON pl.collection_id = c.id " +
                        "WHERE pl.tenant_id = ? AND pl.id IN (" + placeholders + ")",
                params
        );
    }

    /** Sections enriched with the layout natural key (layout name + collection name). */
    public List<Map<String, Object>> findLayoutSectionsByLayoutIds(String tenantId, List<String> layoutIds) {
        if (layoutIds.isEmpty()) return List.of();
        String placeholders = String.join(",", layoutIds.stream().map(i -> "?").toList());
        Object[] params = new Object[layoutIds.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < layoutIds.size(); i++) params[i + 1] = layoutIds.get(i);
        return jdbcTemplate.queryForList(
                "SELECT ls.*, pl.name AS layout_name, c.name AS collection_name " +
                        "FROM layout_section ls JOIN page_layout pl ON ls.layout_id = pl.id " +
                        "JOIN collection c ON pl.collection_id = c.id " +
                        "WHERE pl.tenant_id = ? AND ls.layout_id IN (" + placeholders + ") " +
                        "ORDER BY ls.sort_order ASC",
                params
        );
    }

    /** Layout fields enriched with section/layout/field natural keys. */
    public List<Map<String, Object>> findLayoutFieldsByLayoutIds(String tenantId, List<String> layoutIds) {
        if (layoutIds.isEmpty()) return List.of();
        String placeholders = String.join(",", layoutIds.stream().map(i -> "?").toList());
        Object[] params = new Object[layoutIds.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < layoutIds.size(); i++) params[i + 1] = layoutIds.get(i);
        return jdbcTemplate.queryForList(
                "SELECT lf.*, ls.sort_order AS section_sort_order, pl.name AS layout_name, " +
                        "c.name AS collection_name, f.name AS field_name, fc.name AS field_collection_name " +
                        "FROM layout_field lf " +
                        "JOIN layout_section ls ON lf.section_id = ls.id " +
                        "JOIN page_layout pl ON ls.layout_id = pl.id " +
                        "JOIN collection c ON pl.collection_id = c.id " +
                        "JOIN field f ON lf.field_id = f.id " +
                        "JOIN collection fc ON f.collection_id = fc.id " +
                        "WHERE pl.tenant_id = ? AND ls.layout_id IN (" + placeholders + ") " +
                        "ORDER BY lf.sort_order ASC",
                params
        );
    }

    /** Related lists enriched with layout/collection/related-collection/field natural keys. */
    public List<Map<String, Object>> findLayoutRelatedListsByLayoutIds(String tenantId, List<String> layoutIds) {
        if (layoutIds.isEmpty()) return List.of();
        String placeholders = String.join(",", layoutIds.stream().map(i -> "?").toList());
        Object[] params = new Object[layoutIds.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < layoutIds.size(); i++) params[i + 1] = layoutIds.get(i);
        return jdbcTemplate.queryForList(
                "SELECT rl.*, pl.name AS layout_name, c.name AS collection_name, " +
                        "rc.name AS related_collection_name, rf.name AS relationship_field_name, " +
                        "rfc.name AS relationship_field_collection_name " +
                        "FROM layout_related_list rl " +
                        "JOIN page_layout pl ON rl.layout_id = pl.id " +
                        "JOIN collection c ON pl.collection_id = c.id " +
                        "JOIN collection rc ON rl.related_collection_id = rc.id " +
                        "JOIN field rf ON rl.relationship_field_id = rf.id " +
                        "JOIN collection rfc ON rf.collection_id = rfc.id " +
                        "WHERE pl.tenant_id = ? AND rl.layout_id IN (" + placeholders + ") " +
                        "ORDER BY rl.sort_order ASC",
                params
        );
    }

    public List<Map<String, Object>> findGlobalPicklistsByIds(String tenantId, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        Object[] params = new Object[ids.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < ids.size(); i++) params[i + 1] = ids.get(i);
        return jdbcTemplate.queryForList(
                "SELECT * FROM global_picklist WHERE tenant_id = ? AND id IN (" + placeholders + ")",
                params
        );
    }

    /** GLOBAL-source picklist values enriched with the picklist name. */
    public List<Map<String, Object>> findGlobalPicklistValues(String tenantId, List<String> picklistIds) {
        if (picklistIds.isEmpty()) return List.of();
        String placeholders = String.join(",", picklistIds.stream().map(i -> "?").toList());
        Object[] params = new Object[picklistIds.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < picklistIds.size(); i++) params[i + 1] = picklistIds.get(i);
        return jdbcTemplate.queryForList(
                "SELECT pv.*, gp.name AS picklist_name FROM picklist_value pv " +
                        "JOIN global_picklist gp ON pv.picklist_source_id = gp.id " +
                        "WHERE pv.picklist_source_type = 'GLOBAL' AND gp.tenant_id = ? " +
                        "AND pv.picklist_source_id IN (" + placeholders + ") " +
                        "ORDER BY pv.sort_order ASC",
                params
        );
    }

    /** FIELD-source picklist values enriched with field + collection natural keys. */
    public List<Map<String, Object>> findFieldPicklistValues(String tenantId, List<String> collectionIds) {
        if (collectionIds.isEmpty()) return List.of();
        String placeholders = String.join(",", collectionIds.stream().map(i -> "?").toList());
        Object[] params = new Object[collectionIds.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < collectionIds.size(); i++) params[i + 1] = collectionIds.get(i);
        return jdbcTemplate.queryForList(
                "SELECT pv.*, f.name AS field_name, c.name AS field_collection_name " +
                        "FROM picklist_value pv " +
                        "JOIN field f ON pv.picklist_source_id = f.id " +
                        "JOIN collection c ON f.collection_id = c.id " +
                        "WHERE pv.picklist_source_type = 'FIELD' AND c.tenant_id = ? " +
                        "AND f.collection_id IN (" + placeholders + ") " +
                        "ORDER BY pv.sort_order ASC",
                params
        );
    }

    /** Menu items enriched with the owning menu + parent labels (natural-key remap on import). */
    public List<Map<String, Object>> findUiMenuItemsWithMenuNames(String tenantId, List<String> menuIds) {
        if (menuIds.isEmpty()) return List.of();
        String placeholders = String.join(",", menuIds.stream().map(i -> "?").toList());
        Object[] params = new Object[menuIds.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < menuIds.size(); i++) params[i + 1] = menuIds.get(i);
        return jdbcTemplate.queryForList(
                "SELECT mi.*, m.name AS menu_name, p.label AS parent_label FROM ui_menu_item mi " +
                        "JOIN ui_menu m ON mi.menu_id = m.id " +
                        "LEFT JOIN ui_menu_item p ON mi.parent_id = p.id " +
                        "WHERE mi.tenant_id = ? AND mi.menu_id IN (" + placeholders + ") " +
                        "ORDER BY mi.display_order ASC",
                params
        );
    }
}
