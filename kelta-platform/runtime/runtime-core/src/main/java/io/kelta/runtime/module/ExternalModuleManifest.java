package io.kelta.runtime.module;

import java.util.List;
import java.util.Map;

/**
 * Manifest v2: the manifest of an <b>external</b> module, a separate HTTPS service the platform
 * calls over the module protocol ({@code docs/module-protocol/openapi.yaml},
 * {@code specs/module-platform/9-external-modules.md} §3.1). Served by the module at
 * {@code GET {baseUrl}/.well-known/kelta-module.json} and parsed by
 * {@link ModuleManifestParser#parseExternal(String)}, which enforces every rule documented on the
 * components below. The JAR manifest (v1) is {@link ModuleManifest}.
 *
 * <p>Everything here is a declaration shown to the tenant admin for consent; nothing in it is
 * trusted beyond that. The collections, fields and the like the module needs travel as a metadata
 * package in {@link #metadata()}, applied through the same importer an admin's package import uses.
 *
 * @param manifestVersion    always {@value ModuleManifestParser#EXTERNAL_MANIFEST_VERSION}
 * @param protocolVersion    the module protocol version the module speaks; one of
 *                           {@link ModuleManifestParser#SUPPORTED_PROTOCOL_VERSIONS}
 * @param id                 {@code ^[a-z][a-z0-9-]{1,62}$}, stable forever (the assertion {@code aud})
 * @param name               human-readable name
 * @param version            semantic version; an upgrade requires a strictly higher one
 * @param description        short description, or {@code null}
 * @param author             publisher, or {@code null}
 * @param minPlatformVersion lowest platform release the module runs on, or {@code null}
 * @param permissions        what the module's service principal may do through the public API
 * @param metadata           a metadata package ({@code formatVersion: 2}, {@code items}) whose item
 *                           types are limited to {@link ModuleManifestParser#METADATA_ITEM_TYPES};
 *                           {@code null} when the module provisions nothing
 * @param settings           JSON Schema for per-install settings, or {@code null}
 * @param actions            flow actions, unique by {@code key}
 * @param hooks              before-save hook registrations, unique by {@code collection}
 * @param events             record-change subscriptions, unique by {@code collection}
 * @param services           platform service ports the module implements, unique by {@code port}
 * @param routes             HTTP routes under {@code /api/modules/{id}/x/}, unique by {@code path}
 * @param schedules          actions run on a cron schedule
 * @param webhookEnabled     whether {@code /api/modules/webhooks/{tenantId}/{id}} is forwarded
 * @param ui                 the admin UI bundle, or {@code null}
 * @since 1.0.0
 */
public record ExternalModuleManifest(
        int manifestVersion,
        int protocolVersion,
        String id,
        String name,
        String version,
        String description,
        String author,
        String minPlatformVersion,
        Permissions permissions,
        Map<String, Object> metadata,
        Map<String, Object> settings,
        List<Action> actions,
        List<Hook> hooks,
        List<EventSubscription> events,
        List<Service> services,
        List<Route> routes,
        List<Schedule> schedules,
        boolean webhookEnabled,
        Ui ui
) {

    /** The {@code metadata.items} list, or an empty list when the module provisions nothing. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> metadataItems() {
        if (metadata == null || !(metadata.get("items") instanceof List<?> items)) {
            return List.of();
        }
        return (List<Map<String, Object>>) items;
    }

    /** Names of the collections the metadata package declares, in package order. */
    public List<String> provisionedCollections() {
        return metadataItems().stream()
                .filter(item -> "COLLECTION".equals(item.get("type")))
                .map(item -> item.get("data") instanceof Map<?, ?> data ? data.get("name") : null)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    /**
     * {@code permissions}. No wildcards anywhere: collections the module provisions get full
     * object access implicitly and need no entry here.
     *
     * @param objects     CRUD grants by collection name, unique by collection
     * @param fields      field visibility grants, unique by collection + field
     * @param system      existing system permission names
     * @param credentials tenant credential names the module may read
     */
    public record Permissions(List<ObjectPermission> objects, List<FieldPermission> fields,
                              List<String> system, List<String> credentials) {

        public static final Permissions NONE = new Permissions(List.of(), List.of(), List.of(), List.of());
    }

    /** {@code permissions.objects[]}. */
    public record ObjectPermission(String collection, boolean read, boolean create, boolean edit,
                                   boolean delete) {}

    /** {@code permissions.fields[]}; {@code visibility} is VISIBLE, READ_ONLY, HIDDEN or MASKED. */
    public record FieldPermission(String collection, String field, String visibility) {}

    /**
     * {@code actions[]}. Schemas are kept as JSON text, as the JAR manifest keeps them.
     *
     * @param timeoutMs 1–120000, default 30000
     */
    public record Action(String key, String name, String description, String category, String icon,
                         int timeoutMs, String configSchema, String inputSchema, String outputSchema) {}

    /**
     * {@code hooks[]}.
     *
     * @param collection    a collection name, or {@code *} for every collection
     * @param events        a non-empty subset of {@code beforeCreate}, {@code beforeUpdate},
     *                      {@code beforeDelete}
     * @param order         registration order, default 1000
     * @param timeoutMs     100–5000, default 2000
     * @param failurePolicy {@code fail} (default) or {@code ignore}
     */
    public record Hook(String collection, List<String> events, int order, int timeoutMs,
                       String failurePolicy) {}

    /** {@code events[]}: {@code types} is a non-empty subset of created, updated, deleted. */
    public record EventSubscription(String collection, List<String> types) {}

    /** {@code services[]}: {@code cacheTtlSeconds} 0–300, default 60. */
    public record Service(String port, int cacheTtlSeconds) {}

    /**
     * {@code routes[]}.
     *
     * @param path       one to three segments under {@code /api/modules/{id}/x/}, no leading slash
     * @param methods    HTTP methods, default {@code [GET]}
     * @param permission an existing system permission the caller must hold, or {@code null}
     */
    public record Route(String path, List<String> methods, String permission) {}

    /** {@code schedules[]}: an {@code actions[].key} run on a Spring 6-field cron expression. */
    public record Schedule(String action, String cron) {}

    /** {@code ui}: the bundle's path on the module and its lowercase hex SHA-256. */
    public record Ui(String bundlePath, String sha256) {}
}
