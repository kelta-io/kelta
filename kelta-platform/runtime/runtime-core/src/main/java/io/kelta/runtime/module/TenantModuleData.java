package io.kelta.runtime.module;

import java.time.Instant;
import java.util.List;

/**
 * Data record representing an installed tenant module from the database.
 *
 * @param id            primary key
 * @param tenantId      owning tenant
 * @param moduleId      module identifier from manifest
 * @param name          human-readable name
 * @param version       semantic version
 * @param description   short description
 * @param sourceUrl     original download URL
 * @param jarChecksum   SHA-256 checksum
 * @param jarSizeBytes  JAR file size
 * @param moduleClass   fully-qualified KeltaModule class name
 * @param manifest      raw manifest JSON
 * @param status        lifecycle status
 * @param installedBy   user who installed the module
 * @param installedAt   installation timestamp
 * @param updatedAt     last update timestamp
 * @param s3Key         S3 storage key for the module JAR (null if no JAR uploaded)
 * @param actions       action handlers declared by this module
 * @param baseUrl       an external module's base URL; {@code null} for a JAR module. This is
 *                      what makes a row an external module ({@link #isExternal()})
 * @param protocolVersion the module protocol version an external module speaks, or {@code null}
 * @param connectedAppId  the external module's service-principal connected app, or {@code null}
 * @param serviceUserId   the external module's SERVICE user, or {@code null}
 * @param uiBundleSha256  SHA-256 of the external module's pinned admin UI bundle, or {@code null}
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
    String jarChecksum,
    Long jarSizeBytes,
    String moduleClass,
    String manifest,
    String status,
    String installedBy,
    Instant installedAt,
    Instant updatedAt,
    String s3Key,
    List<TenantModuleActionData> actions,
    String baseUrl,
    Integer protocolVersion,
    String connectedAppId,
    String serviceUserId,
    String uiBundleSha256,
    String uiBundleKey
) {
    /** A JAR module row (no external-module columns). */
    public TenantModuleData(String id, String tenantId, String moduleId, String name, String version,
                            String description, String sourceUrl, String jarChecksum, Long jarSizeBytes,
                            String moduleClass, String manifest, String status, String installedBy,
                            Instant installedAt, Instant updatedAt, String s3Key,
                            List<TenantModuleActionData> actions) {
        this(id, tenantId, moduleId, name, version, description, sourceUrl, jarChecksum, jarSizeBytes,
            moduleClass, manifest, status, installedBy, installedAt, updatedAt, s3Key, actions,
            null, null, null, null);
    }

    /** An external module row without a pinned UI bundle. */
    public TenantModuleData(String id, String tenantId, String moduleId, String name, String version,
                            String description, String sourceUrl, String jarChecksum, Long jarSizeBytes,
                            String moduleClass, String manifest, String status, String installedBy,
                            Instant installedAt, Instant updatedAt, String s3Key,
                            List<TenantModuleActionData> actions, String baseUrl, Integer protocolVersion,
                            String connectedAppId, String serviceUserId) {
        this(id, tenantId, moduleId, name, version, description, sourceUrl, jarChecksum, jarSizeBytes,
            moduleClass, manifest, status, installedBy, installedAt, updatedAt, s3Key, actions,
            baseUrl, protocolVersion, connectedAppId, serviceUserId, null, null);
    }

    /** True for an external module: an HTTPS service installed by URL, not a JAR. */
    public boolean isExternal() {
        return baseUrl != null;
    }

    /** This row with {@code actions} in place of its own. */
    public TenantModuleData withActions(List<TenantModuleActionData> newActions) {
        return new TenantModuleData(id, tenantId, moduleId, name, version, description, sourceUrl,
            jarChecksum, jarSizeBytes, moduleClass, manifest, status, installedBy, installedAt, updatedAt,
            s3Key, newActions, baseUrl, protocolVersion, connectedAppId, serviceUserId, uiBundleSha256,
            uiBundleKey);
    }

    public static final String STATUS_INSTALLING = "INSTALLING";
    public static final String STATUS_INSTALLED = "INSTALLED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_UNINSTALLING = "UNINSTALLING";

    /**
     * Loading failed — signature, checksum, classloading, or the module's own {@code onStartup}
     * threw. Its action handlers are registered but refuse to run, so a flow step fails with a
     * specific {@code ModuleUnavailable} error rather than succeeding against code that never ran.
     */
    public static final String STATUS_QUARANTINED = "QUARANTINED";

    /**
     * Loaded, but something the module declared is unavailable (a required setting is unset, a
     * declared service port was refused). Real handlers for what loaded; quarantined for the rest.
     */
    public static final String STATUS_DEGRADED = "DEGRADED";

    /**
     * Handlers are manifest-derived stubs that do nothing and say so. Only ever reached by the
     * explicit {@code kelta.modules.stub-mode=true} dev opt-in — never by falling back from an
     * error, which is what made a rejected module look healthy.
     */
    public static final String STATUS_STUB = "STUB";

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
