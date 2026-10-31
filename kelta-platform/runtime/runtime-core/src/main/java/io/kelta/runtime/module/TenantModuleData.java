package io.kelta.runtime.module;

import java.time.Instant;
import java.util.List;

/**
 * Data record representing an installed tenant module from the database. Every module is an
 * external HTTPS service installed by URL ({@code specs/module-platform/9-external-modules.md});
 * the in-process JAR path and its columns were removed in part 9g (V225).
 *
 * @param id            primary key
 * @param tenantId      owning tenant
 * @param moduleId      module identifier from manifest
 * @param name          human-readable name
 * @param version       semantic version
 * @param description   short description
 * @param sourceUrl     the URL the module was installed from (its base URL)
 * @param manifest      raw manifest JSON (manifest v2)
 * @param status        lifecycle status
 * @param installedBy   user who installed the module
 * @param installedAt   installation timestamp
 * @param updatedAt     last update timestamp
 * @param actions       action handlers declared by this module
 * @param baseUrl       the module's base URL; never {@code null} ({@code tenant_module.base_url} is
 *                      {@code NOT NULL})
 * @param protocolVersion the module protocol version the module speaks
 * @param connectedAppId  the module's service-principal connected app, or {@code null} before the
 *                        install created it
 * @param serviceUserId   the module's SERVICE user, or {@code null} before the install created it
 * @param uiBundleSha256  SHA-256 of the module's pinned admin UI bundle, or {@code null}
 *                        when none is pinned ({@code specs/module-platform/9-external-modules.md} §3.9)
 * @param uiBundleKey     object-storage key of that pinned bundle, or {@code null}
 * @since 1.0.0
 */
public record TenantModuleData(
    String id,
    String tenantId,
    String moduleId,
    String name,
    String version,
    String description,
    String sourceUrl,
    String manifest,
    String status,
    String installedBy,
    Instant installedAt,
    Instant updatedAt,
    List<TenantModuleActionData> actions,
    String baseUrl,
    Integer protocolVersion,
    String connectedAppId,
    String serviceUserId,
    String uiBundleSha256,
    String uiBundleKey
) {
    /** A module row without a pinned UI bundle. */
    public TenantModuleData(String id, String tenantId, String moduleId, String name, String version,
                            String description, String sourceUrl, String manifest, String status,
                            String installedBy, Instant installedAt, Instant updatedAt,
                            List<TenantModuleActionData> actions, String baseUrl, Integer protocolVersion,
                            String connectedAppId, String serviceUserId) {
        this(id, tenantId, moduleId, name, version, description, sourceUrl, manifest, status, installedBy,
            installedAt, updatedAt, actions, baseUrl, protocolVersion, connectedAppId, serviceUserId, null, null);
    }

    /** This row with {@code actions} in place of its own. */
    public TenantModuleData withActions(List<TenantModuleActionData> newActions) {
        return new TenantModuleData(id, tenantId, moduleId, name, version, description, sourceUrl, manifest,
            status, installedBy, installedAt, updatedAt, newActions, baseUrl, protocolVersion, connectedAppId,
            serviceUserId, uiBundleSha256, uiBundleKey);
    }

    public static final String STATUS_INSTALLING = "INSTALLING";
    public static final String STATUS_INSTALLED = "INSTALLED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_UNINSTALLING = "UNINSTALLING";

    /**
     * The module stopped answering: this pod's circuit breaker for it opened (part 9d). Calls fail
     * fast until a probe or a call succeeds, which writes {@code ACTIVE} again.
     */
    public static final String STATUS_DEGRADED = "DEGRADED";

    /**
     * Data record for an action handler provided by a module.
     */
    public record TenantModuleActionData(
        String id,
        String tenantModuleId,
        String actionKey,
        String name,
        String category,
        String description,
        String configSchema,
        String inputSchema,
        String outputSchema
    ) {}
}
