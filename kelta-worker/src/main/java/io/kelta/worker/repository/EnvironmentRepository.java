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
public class EnvironmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public EnvironmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    private static final String SELECT_COLUMNS =
            "SELECT id, tenant_id, name, description, type, status, source_env_id, " +
                    "sandbox_tenant_id, remote_base_url, remote_tenant_slug, credential_ref, " +
                    "config, created_by, created_at, updated_at, " +
                    // Sandboxes created before cloneMode was recorded were all full clones.
                    "COALESCE(config->>'cloneMode', CASE WHEN sandbox_tenant_id IS NOT NULL THEN 'CLONED' END) " +
                    "AS clone_mode ";

    public String create(String tenantId, String name, String description, String type,
                         String sourceEnvId, String config, String createdBy) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO environment (id, tenant_id, name, description, type, status, " +
                        "source_env_id, config, created_by, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'CREATING', ?, ?::jsonb, ?, ?, ?)",
                id, tenantId, name, description, type, sourceEnvId, config, createdBy, now, now
        );
        return id;
    }

    /** Env row for a tenant-backed sandbox: points at the real sandbox tenant. */
    public String createWithSandboxTenant(String tenantId, String name, String description, String type,
                                          String sourceEnvId, String sandboxTenantId, String config,
                                          String createdBy) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO environment (id, tenant_id, name, description, type, status, " +
                        "source_env_id, sandbox_tenant_id, config, created_by, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'CREATING', ?, ?, ?::jsonb, ?, ?, ?)",
                id, tenantId, name, description, type, sourceEnvId, sandboxTenantId, config, createdBy, now, now
        );
        return id;
    }

    /** Env row describing a remote promotion target on another cluster. */
    public String createRemote(String tenantId, String name, String description, String type,
                               String remoteBaseUrl, String remoteTenantSlug, String credentialRef,
                               String createdBy) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO environment (id, tenant_id, name, description, type, status, " +
                        "remote_base_url, remote_tenant_slug, credential_ref, created_by, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)",
                id, tenantId, name, description, type, remoteBaseUrl, remoteTenantSlug,
                credentialRef, createdBy, now, now
        );
        return id;
    }

    public Optional<Map<String, Object>> findByIdAndTenant(String envId, String tenantId) {
        var results = jdbcTemplate.queryForList(
                SELECT_COLUMNS + "FROM environment WHERE id = ? AND tenant_id = ?",
                envId, tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<Map<String, Object>> findBySandboxTenantId(String sandboxTenantId) {
        var results = jdbcTemplate.queryForList(
                SELECT_COLUMNS + "FROM environment WHERE sandbox_tenant_id = ?",
                sandboxTenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Map<String, Object>> findByTenant(String tenantId) {
        return jdbcTemplate.queryForList(
                SELECT_COLUMNS + "FROM environment WHERE tenant_id = ? ORDER BY created_at DESC",
                tenantId
        );
    }

    public List<Map<String, Object>> findByTenantAndType(String tenantId, String type) {
        return jdbcTemplate.queryForList(
                SELECT_COLUMNS + "FROM environment WHERE tenant_id = ? AND type = ? ORDER BY created_at DESC",
                tenantId, type
        );
    }

    public void updateStatus(String envId, String tenantId, String status) {
        jdbcTemplate.update(
                "UPDATE environment SET status = ?, updated_at = NOW() WHERE id = ? AND tenant_id = ?",
                status, envId, tenantId
        );
    }

    public void update(String envId, String tenantId, String name, String description, String config) {
        jdbcTemplate.update(
                "UPDATE environment SET name = ?, description = ?, config = ?::jsonb, " +
                        "updated_at = NOW() WHERE id = ? AND tenant_id = ?",
                name, description, config, envId, tenantId
        );
    }

    public boolean existsByTenantAndName(String tenantId, String name) {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM environment WHERE tenant_id = ? AND name = ?",
                Integer.class, tenantId, name
        );
        return count != null && count > 0;
    }

    public Optional<Map<String, Object>> findProductionByTenant(String tenantId) {
        var results = jdbcTemplate.queryForList(
                SELECT_COLUMNS + "FROM environment WHERE tenant_id = ? AND type = 'PRODUCTION' " +
                        "AND sandbox_tenant_id IS NULL AND remote_base_url IS NULL LIMIT 1",
                tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // --- Snapshot methods ---

    public String createSnapshot(String tenantId, String environmentId, String name,
                                 String snapshotData, int itemCount, String createdBy) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO metadata_snapshot (id, tenant_id, environment_id, name, " +
                        "snapshot_data, item_count, created_by, created_at) " +
                        "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, NOW())",
                id, tenantId, environmentId, name, snapshotData, itemCount, createdBy
        );
        return id;
    }

    public Optional<Map<String, Object>> findSnapshotById(String snapshotId, String tenantId) {
        var results = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, environment_id, name, snapshot_data, item_count, " +
                        "created_by, created_at " +
                        "FROM metadata_snapshot WHERE id = ? AND tenant_id = ?",
                snapshotId, tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Map<String, Object>> findSnapshotsByEnvironment(String environmentId, String tenantId) {
        return jdbcTemplate.queryForList(
                "SELECT id, tenant_id, environment_id, name, item_count, " +
                        "created_by, created_at " +
                        "FROM metadata_snapshot WHERE environment_id = ? AND tenant_id = ? " +
                        "ORDER BY created_at DESC",
                environmentId, tenantId
        );
    }
}
