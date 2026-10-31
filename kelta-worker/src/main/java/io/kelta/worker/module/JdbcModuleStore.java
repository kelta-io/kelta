package io.kelta.worker.module;

import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed implementation of {@link ModuleStore}.
 *
 * @since 1.0.0
 */
public class JdbcModuleStore implements ModuleStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcModuleStore.class);

    private final JdbcTemplate jdbcTemplate;

    public JdbcModuleStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public String createModule(TenantModuleData data) {
        String id = data.id() != null ? data.id() : UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO tenant_module
                (id, tenant_id, module_id, name, version, description, source_url,
                 jar_checksum, jar_size_bytes, module_class, manifest, status,
                 installed_by, s3_key, base_url, protocol_version, connected_app_id,
                 service_user_id, installed_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """,
            id, data.tenantId(), data.moduleId(), data.name(), data.version(),
            data.description(), data.sourceUrl(), data.jarChecksum(), data.jarSizeBytes(),
            data.moduleClass(), data.manifest(), data.status(), data.installedBy(),
            data.s3Key(), data.baseUrl(), data.protocolVersion(), data.connectedAppId(),
            data.serviceUserId());
        return id;
    }

    @Override
    public void saveJarSignature(String moduleRowId, String signatureBase64) {
        jdbcTemplate.update(
                "UPDATE tenant_module SET jar_signature = ?, updated_at = NOW() WHERE id = ?",
                signatureBase64, moduleRowId);
    }

    @Override
    public void saveJarSignature(String moduleRowId, String signatureBase64, String keyFingerprint) {
        jdbcTemplate.update("""
            UPDATE tenant_module
               SET jar_signature = ?, jar_signature_key_fingerprint = ?, updated_at = NOW()
             WHERE id = ?
            """,
            signatureBase64, keyFingerprint, moduleRowId);
    }

    @Override
    public Optional<String> findJarSignature(String moduleRowId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT jar_signature FROM tenant_module WHERE id = ?",
                (rs, rowNum) -> rs.getString("jar_signature"), moduleRowId);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    @Override
    public void createActions(List<TenantModuleData.TenantModuleActionData> actions) {
        for (var action : actions) {
            String id = action.id() != null ? action.id() : UUID.randomUUID().toString();
            jdbcTemplate.update("""
                INSERT INTO tenant_module_action
                    (id, tenant_module_id, tenant_id, action_key, name, category, description,
                     config_schema, input_schema, output_schema, created_at, updated_at)
                VALUES (?, ?,
                    (SELECT tenant_id FROM tenant_module WHERE id = ?),
                    ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, NOW(), NOW())
                """,
                id, action.tenantModuleId(), action.tenantModuleId(),
                action.actionKey(), action.name(),
                action.category(), action.description(), action.configSchema(),
                action.inputSchema(), action.outputSchema());
        }
    }

    @Override
    public Optional<TenantModuleData> findByTenantAndModuleId(String tenantId, String moduleId) {
        List<TenantModuleData> results = jdbcTemplate.query(
            "SELECT * FROM tenant_module WHERE tenant_id = ? AND module_id = ?",
            this::mapModule, tenantId, moduleId);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        TenantModuleData module = results.get(0);
        return Optional.of(withActions(module));
    }

    @Override
    public Optional<TenantModuleData> findById(String id) {
        List<TenantModuleData> results = jdbcTemplate.query(
            "SELECT * FROM tenant_module WHERE id = ?",
            this::mapModule, id);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withActions(results.get(0)));
    }

    @Override
    public List<TenantModuleData> findByTenant(String tenantId) {
        List<TenantModuleData> modules = jdbcTemplate.query(
            "SELECT * FROM tenant_module WHERE tenant_id = ? ORDER BY installed_at DESC",
            this::mapModule, tenantId);
        return modules.stream().map(this::withActions).toList();
    }

    @Override
    public List<TenantModuleData> findByTenantAndStatus(String tenantId, String status) {
        List<TenantModuleData> modules = jdbcTemplate.query(
            "SELECT * FROM tenant_module WHERE tenant_id = ? AND status = ? ORDER BY installed_at DESC",
            this::mapModule, tenantId, status);
        return modules.stream().map(this::withActions).toList();
    }

    @Override
    public void updateStatus(String id, String status) {
        jdbcTemplate.update(
            "UPDATE tenant_module SET status = ?, updated_at = NOW() WHERE id = ?",
            status, id);
    }

    @Override
    public void recordLoadOutcome(String id, String status, String error) {
        jdbcTemplate.update(
            "UPDATE tenant_module SET status = ?, last_error = ?, "
                + "last_error_at = CASE WHEN ?::text IS NULL THEN last_error_at ELSE NOW() END, "
                + "last_loaded_at = CASE WHEN ?::text IS NULL THEN NOW() ELSE last_loaded_at END, "
                + "load_attempts = load_attempts + 1, updated_at = NOW() WHERE id = ?",
            status, error, error, error, id);
    }

    @Override
    public void recordLoadError(String id, String error) {
        jdbcTemplate.update(
            "UPDATE tenant_module SET last_error = ?, last_error_at = NOW(), "
                + "load_attempts = load_attempts + 1, updated_at = NOW() WHERE id = ?",
            error, id);
    }

    @Override
    public java.util.Map<String, Object> findLoadDiagnostics(String id) {
        var rows = jdbcTemplate.queryForList(
            "SELECT last_error, last_error_at, last_loaded_at, load_attempts "
                + "FROM tenant_module WHERE id = ?", id);
        if (rows.isEmpty()) {
            return java.util.Map.of();
        }
        var row = rows.get(0);
        var out = new java.util.LinkedHashMap<String, Object>();
        out.put("lastError", row.get("last_error"));
        out.put("lastErrorAt", row.get("last_error_at"));
        out.put("lastLoadedAt", row.get("last_loaded_at"));
        out.put("loadAttempts", row.get("load_attempts"));
        return out;
    }

    @Override
    public void updateStatusWithError(String id, String status, String error) {
        jdbcTemplate.update(
            "UPDATE tenant_module SET status = ?, last_error = ?, "
                + "last_error_at = CASE WHEN ?::text IS NULL THEN last_error_at ELSE NOW() END, "
                + "updated_at = NOW() WHERE id = ?",
            status, error, error, id);
    }

    @Override
    public void updateServicePrincipal(String id, String connectedAppId, String serviceUserId) {
        jdbcTemplate.update(
            "UPDATE tenant_module SET connected_app_id = ?, service_user_id = ?, updated_at = NOW() "
                + "WHERE id = ?",
            connectedAppId, serviceUserId, id);
    }

    @Override
    public void updateExternalManifest(String id, String version, String name, String description,
                                       String manifestJson, int protocolVersion) {
        jdbcTemplate.update("""
            UPDATE tenant_module
               SET version = ?, name = ?, description = ?, manifest = ?::jsonb,
                   protocol_version = ?, updated_at = NOW()
             WHERE id = ?
            """,
            version, name, description, manifestJson, protocolVersion, id);
    }

    @Override
    public void replaceActions(String moduleRowId, List<TenantModuleData.TenantModuleActionData> actions) {
        jdbcTemplate.update("DELETE FROM tenant_module_action WHERE tenant_module_id = ?", moduleRowId);
        createActions(actions);
    }

    @Override
    public void deleteModule(String id) {
        // Actions are cascade-deleted via FK
        jdbcTemplate.update("DELETE FROM tenant_module WHERE id = ?", id);
    }

    @Override
    public List<TenantModuleData> findAllActive() {
        List<TenantModuleData> modules = jdbcTemplate.query(
            "SELECT * FROM tenant_module WHERE status = 'ACTIVE' ORDER BY tenant_id, module_id",
            this::mapModule);
        return modules.stream().map(this::withActions).toList();
    }

    private TenantModuleData withActions(TenantModuleData module) {
        List<TenantModuleData.TenantModuleActionData> actions = jdbcTemplate.query(
            "SELECT * FROM tenant_module_action WHERE tenant_module_id = ? ORDER BY action_key",
            this::mapAction, module.id());
        return module.withActions(actions);
    }

    private TenantModuleData mapModule(ResultSet rs, int rowNum) throws SQLException {
        return new TenantModuleData(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("module_id"),
            rs.getString("name"),
            rs.getString("version"),
            rs.getString("description"),
            rs.getString("source_url"),
            rs.getString("jar_checksum"),
            rs.getObject("jar_size_bytes") != null ? rs.getLong("jar_size_bytes") : null,
            rs.getString("module_class"),
            rs.getString("manifest"),
            rs.getString("status"),
            rs.getString("installed_by"),
            toInstant(rs.getTimestamp("installed_at")),
            toInstant(rs.getTimestamp("updated_at")),
            rs.getString("s3_key"),
            List.of(), // Actions loaded separately
            rs.getString("base_url"),
            rs.getObject("protocol_version") != null ? rs.getInt("protocol_version") : null,
            rs.getString("connected_app_id"),
            rs.getString("service_user_id")
        );
    }

    private TenantModuleData.TenantModuleActionData mapAction(ResultSet rs, int rowNum)
            throws SQLException {
        return new TenantModuleData.TenantModuleActionData(
            rs.getString("id"),
            rs.getString("tenant_module_id"),
            rs.getString("action_key"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("description"),
            rs.getString("config_schema"),
            rs.getString("input_schema"),
            rs.getString("output_schema")
        );
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
