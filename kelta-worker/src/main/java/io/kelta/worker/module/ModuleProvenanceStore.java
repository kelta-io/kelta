package io.kelta.worker.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records what a module provisioned, so uninstall and upgrade can act safely.
 *
 * <p>Without this the platform cannot answer two questions it must answer before removing anything:
 * did this module create the resource or merely reuse one that already existed, and has the tenant
 * edited it since? Absent both answers the only safe uninstall is one that removes nothing, which is
 * what the platform does today — leaving a removed module's collections and pages behind with no way
 * to find them.
 *
 * @since 1.0.0
 */
@Component
public class ModuleProvenanceStore {

    private static final Logger log = LoggerFactory.getLogger(ModuleProvenanceStore.class);

    /** The module created this resource. Only these may ever be removed on uninstall. */
    public static final String OWNERSHIP_CREATED = "CREATED";

    /** It already existed and the module reused it. Never removable by the module. */
    public static final String OWNERSHIP_ADOPTED = "ADOPTED";

    private final JdbcTemplate jdbcTemplate;

    public ModuleProvenanceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Records one provisioned resource, upserting on the natural key so a reinstall or upgrade
     * re-states the record rather than accumulating duplicates.
     *
     * <p><b>Ownership never downgrades.</b> Uninstall keeps these rows, so on reinstall the
     * module's own earlier collection "already exists" and is reported {@code ADOPTED}. Overwriting
     * would make it unremovable forever; a row already {@code CREATED} therefore keeps that
     * ownership and its original content hash.
     */
    public void record(String tenantId, String moduleId, String moduleVersion,
                       String resourceType, String naturalKey, String resourceId,
                       String ownership, String contentHash) {
        jdbcTemplate.update(
            "INSERT INTO module_provisioned_resource "
                + "(id, tenant_id, module_id, module_version, resource_type, natural_key, "
                + " resource_id, ownership, content_hash) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (tenant_id, module_id, resource_type, natural_key) DO UPDATE SET "
                + "  module_version = EXCLUDED.module_version, "
                + "  resource_id = COALESCE(EXCLUDED.resource_id, "
                + "module_provisioned_resource.resource_id), "
                + "  ownership = CASE WHEN module_provisioned_resource.ownership = 'CREATED' "
                + "THEN 'CREATED' ELSE EXCLUDED.ownership END, "
                + "  content_hash = CASE WHEN module_provisioned_resource.ownership = 'CREATED' "
                + "THEN module_provisioned_resource.content_hash ELSE EXCLUDED.content_hash END",
            UUID.randomUUID().toString(), tenantId, moduleId, moduleVersion,
            resourceType, naturalKey, resourceId, ownership, contentHash);
    }

    /** Everything recorded for one module, newest first. */
    public List<Map<String, Object>> findByModule(String tenantId, String moduleId) {
        return jdbcTemplate.queryForList(
            "SELECT id, resource_type, natural_key, resource_id, ownership, content_hash, "
                + "module_version, created_at FROM module_provisioned_resource "
                + "WHERE tenant_id = ? AND module_id = ? ORDER BY created_at DESC",
            tenantId, moduleId);
    }

    /**
     * Drops the provenance records for a module.
     *
     * <p>Only the bookkeeping — the resources themselves are untouched. Removing them is a separate,
     * deliberate act, because dropping a tenant's data must never be a side effect of removing a
     * module.
     */
    public void deleteForModule(String tenantId, String moduleId) {
        int removed = jdbcTemplate.update(
            "DELETE FROM module_provisioned_resource WHERE tenant_id = ? AND module_id = ?",
            tenantId, moduleId);
        if (removed > 0) {
            log.info("Cleared {} provenance record(s) for module '{}' in tenant {}",
                removed, moduleId, tenantId);
        }
    }
}
