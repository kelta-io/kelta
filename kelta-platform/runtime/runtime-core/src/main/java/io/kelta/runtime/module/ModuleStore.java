package io.kelta.runtime.module;

import java.util.List;
import java.util.Optional;

/**
 * Storage interface for tenant module data.
 * Implemented by {@code JdbcModuleStore} in the worker service.
 *
 * @since 1.0.0
 */
public interface ModuleStore {

    /**
     * Creates a new tenant module record.
     *
     * @param data the module data to persist
     * @return the persisted module ID
     */
    String createModule(TenantModuleData data);

    /**
     * Creates action records for a module.
     *
     * @param actions the actions to persist
     */
    void createActions(List<TenantModuleData.TenantModuleActionData> actions);

    /**
     * Loads a module by tenant and module identifier.
     *
     * @param tenantId the tenant ID
     * @param moduleId the module identifier from the manifest
     * @return the module data, or empty if not found
     */
    Optional<TenantModuleData> findByTenantAndModuleId(String tenantId, String moduleId);

    /**
     * Loads a module by its primary key.
     *
     * @param id the primary key
     * @return the module data, or empty if not found
     */
    Optional<TenantModuleData> findById(String id);

    /**
     * Lists all modules for a tenant.
     *
     * @param tenantId the tenant ID
     * @return all modules for the tenant
     */
    List<TenantModuleData> findByTenant(String tenantId);

    /**
     * Lists all modules for a tenant with a specific status.
     *
     * @param tenantId the tenant ID
     * @param status the status filter
     * @return matching modules
     */
    List<TenantModuleData> findByTenantAndStatus(String tenantId, String status);

    /**
     * Updates the status of a module.
     *
     * @param id the module primary key
     * @param status the new status
     */
    void updateStatus(String id, String status);

    /**
     * The last error recorded for one module, for the admin list and health endpoint:
     * {@code lastError} and {@code lastErrorAt}. Read separately rather than widening
     * {@link TenantModuleData}, which is constructed in many places that have no interest in them.
     *
     * @return the values, or an empty map when the module is unknown
     */
    java.util.Map<String, Object> findLastError(String id);

    /**
     * Writes a status and the error that caused it ({@code null} clears the error): the outcome of
     * an install, enable or lifecycle call.
     */
    void updateStatusWithError(String id, String status, String error);

    /** Records an install's service principal (part 9b's connected app and SERVICE user). */
    void updateServicePrincipal(String id, String connectedAppId, String serviceUserId);

    /**
     * Rewrites a module row for an upgrade: version, name, description, manifest and
     * protocol version. Status, {@code installed_at} and {@code installed_by} are untouched.
     */
    void updateExternalManifest(String id, String version, String name, String description,
                                String manifestJson, int protocolVersion);

    /**
     * Records an install's pinned admin UI bundle: its SHA-256 and object-storage key,
     * or {@code null} for both when the module ships no UI.
     */
    void updateUiBundle(String id, String sha256, String key);

    /** Replaces a module's action rows with {@code actions}. */
    void replaceActions(String moduleRowId, List<TenantModuleData.TenantModuleActionData> actions);

    /**
     * Deletes a module and its associated actions.
     *
     * @param id the module primary key
     */
    void deleteModule(String id);

    /**
     * Lists every module install across all tenants, whatever its status, with its actions — what
     * each pod's endpoint registry reconciles against. Read with no tenant bound (the RLS bypass).
     */
    default List<TenantModuleData> findAllExternal() {
        return List.of();
    }

    /**
     * The install whose service principal is the connected app {@code connectedAppId}, in
     * {@code tenantId} — how a module's own call back to the platform names its install. Empty
     * unless exactly one row matches.
     */
    default Optional<TenantModuleData> findByConnectedAppId(String tenantId, String connectedAppId) {
        return Optional.empty();
    }

    /**
     * Writes {@code status} and {@code error} only while the row still holds {@code expected}, so
     * a pod's circuit breaker (ACTIVE ⇄ DEGRADED) can never overwrite an admin's disable or an
     * uninstall in flight.
     *
     * @return true when the row was changed
     */
    default boolean compareAndSetStatus(String id, String expected, String status, String error) {
        return false;
    }
}
