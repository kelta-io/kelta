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
     * Persists the publisher signature verified at install so the JAR can be
     * re-verified on every load. Default no-op for stores without signature
     * persistence.
     *
     * @param moduleRowId     the module primary key
     * @param signatureBase64 the detached base64 signature over the JAR bytes
     */
    default void saveJarSignature(String moduleRowId, String signatureBase64) {
    }

    /**
     * Persists the verified signature along with the fingerprint of the key that verified it.
     *
     * <p>The fingerprint is what makes key rotation safe to perform: retiring a key silently
     * degrades every module signed only by it to inert stub handlers on the next load, while
     * {@code /api/modules} keeps reporting {@code ACTIVE}. Recording the key per module is the
     * only way to answer "what breaks if I retire this key" before doing it.
     *
     * @param moduleRowId     the module primary key
     * @param signatureBase64 the detached base64 signature over the JAR bytes
     * @param keyFingerprint  fingerprint of the verifying key, or {@code null} when signing was
     *                        not enforced for the tenant
     */
    default void saveJarSignature(String moduleRowId, String signatureBase64, String keyFingerprint) {
        saveJarSignature(moduleRowId, signatureBase64);
    }

    /**
     * Loads the publisher signature stored at install time.
     *
     * @param moduleRowId the module primary key
     * @return the base64 signature, or empty when none was stored
     */
    default Optional<String> findJarSignature(String moduleRowId) {
        return Optional.empty();
    }

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
     * Records the outcome of a load attempt.
     *
     * <p>Persisted rather than logged because a load happens at pod startup or on a NATS event,
     * long after whatever the admin did — a log line on one pod is not something they can see. On
     * success pass a null error, which clears any previous one.
     *
     * @param status the resulting status
     * @param error  the failure reason, or null on success
     */
    void recordLoadOutcome(String id, String status, String error);

    /**
     * Records a load attempt that failed for an infrastructure reason — object storage or the
     * database unreachable — <em>without</em> changing the module's status.
     *
     * <p>Kept separate from {@link #recordLoadOutcome} on purpose: a transient failure must never
     * write a status. Writing {@code QUARANTINED} is what turned a storage outage into a five-day,
     * every-tenant module outage (startup only loads {@code ACTIVE} rows); writing {@code ACTIVE}
     * would race an admin's disable. The error, its time and the attempt count are still persisted
     * so the health endpoint shows the failure.
     *
     * @param error the failure reason
     */
    void recordLoadError(String id, String error);

    /**
     * Load diagnostics for one module: {@code lastError}, {@code lastErrorAt}, {@code lastLoadedAt},
     * {@code loadAttempts}. Read separately rather than widening {@link TenantModuleData}, which is
     * constructed in many places that have no interest in them.
     *
     * @return the values, or an empty map when the module is unknown
     */
    java.util.Map<String, Object> findLoadDiagnostics(String id);

    /**
     * Writes a status and the error that caused it ({@code null} clears the error), without
     * touching the load counters. External modules use it for lifecycle outcomes: nothing is
     * loaded on a pod for them, so {@link #recordLoadOutcome} would misreport a load.
     */
    void updateStatusWithError(String id, String status, String error);

    /** Records an external install's service principal (part 9b's connected app and SERVICE user). */
    void updateServicePrincipal(String id, String connectedAppId, String serviceUserId);

    /**
     * Rewrites an external module row for an upgrade: version, name, description, manifest and
     * protocol version. Status, {@code installed_at} and {@code installed_by} are untouched.
     */
    void updateExternalManifest(String id, String version, String name, String description,
                                String manifestJson, int protocolVersion);

    /**
     * Records an external install's pinned admin UI bundle: its SHA-256 and object-storage key,
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
     * Lists all active modules across all tenants (for pod startup loading).
     *
     * @return all active modules
     */
    List<TenantModuleData> findAllActive();

    /**
     * Lists every external module install across all tenants, whatever its status, with its
     * actions — what each pod's endpoint registry reconciles against. Read with no tenant bound
     * (the RLS bypass), like {@link #findAllActive()}.
     */
    default List<TenantModuleData> findAllExternal() {
        return List.of();
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
