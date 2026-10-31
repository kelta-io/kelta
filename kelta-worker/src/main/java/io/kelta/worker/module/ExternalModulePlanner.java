package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.ModuleManifestParser;
import io.kelta.runtime.module.ModuleManifestParser.ModuleManifestException;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.ModuleVersion;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.listener.TenantProvisioningHook;
import io.kelta.worker.module.ModuleMetadataProvisioner.PlannedItem;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Plans an external module install or upgrade without writing anything
 * ({@code specs/module-platform/9-external-modules.md} §3.5, reusing slice 3's {@code planHash}
 * design, {@code 3-lifecycle.md} §3.3).
 *
 * <p>The plan lists everything the admin consents to: the service principal's grants (object,
 * field and system permissions, credentials, and full access to the collections the module will
 * own), the module's capabilities (actions, hooks, event subscriptions, services, routes,
 * schedules, webhook, UI bundle), what its metadata package would create, adopt, keep or leave
 * alone, and every blocker. A plan with blockers is still a plan: the admin is shown why.
 *
 * <p><b>{@code planHash}</b> is the SHA-256 of the plan as canonical JSON (keys sorted at every
 * level, every list sorted, {@code planHash} itself excluded). It covers the manifest's SHA-256,
 * the installed version and the platform version, so any change to what would happen changes it.
 * Nothing is stored: the apply re-plans and compares, so a plan cannot go stale in a table.
 */
public class ExternalModulePlanner {

    /** Blocker codes. */
    static final String UNSUPPORTED_MANIFEST_VERSION = "UNSUPPORTED_MANIFEST_VERSION";
    static final String INVALID_MANIFEST = "INVALID_MANIFEST";
    static final String PLATFORM_TOO_OLD = "PLATFORM_TOO_OLD";
    static final String DOWNGRADE = "DOWNGRADE";
    static final String SAME_VERSION_DIFFERENT_MANIFEST = "SAME_VERSION_DIFFERENT_MANIFEST";
    static final String INSTALLED_AS_JAR = "INSTALLED_AS_JAR";
    static final String BASE_URL_MISMATCH = "BASE_URL_MISMATCH";
    static final String SIGNING_NOT_CONFIGURED = "SIGNING_NOT_CONFIGURED";
    static final String METADATA_INVALID = "METADATA_INVALID";
    static final String UNKNOWN_COLLECTION = "UNKNOWN_COLLECTION";
    static final String UNKNOWN_FIELD = "UNKNOWN_FIELD";
    static final String UNKNOWN_PERMISSION = "UNKNOWN_PERMISSION";
    static final String CAPABILITY_WITHOUT_GRANT = "CAPABILITY_WITHOUT_GRANT";

    private static final String SELECT_VISIBLE_COLLECTION_ID = """
            SELECT id FROM collection
            WHERE name = ? AND active = true AND (tenant_id = ? OR system_collection = true)
            ORDER BY system_collection ASC LIMIT 1
            """;
    private static final String SELECT_FIELD =
            "SELECT 1 FROM field WHERE collection_id = ? AND name = ? AND active = true";

    private final ModuleHttpClient http;
    private final ModuleManifestParser parser;
    private final ModuleStore moduleStore;
    private final ModuleProvenanceStore provenanceStore;
    private final ModuleMetadataProvisioner provisioner;
    private final PlatformVersion platformVersion;
    private final ModuleAssertionSigner signer;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExternalModulePlanner(ModuleHttpClient http, ModuleStore moduleStore,
                                 ModuleProvenanceStore provenanceStore, ModuleMetadataProvisioner provisioner,
                                 PlatformVersion platformVersion, ModuleAssertionSigner signer,
                                 JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.http = Objects.requireNonNull(http);
        this.parser = new ModuleManifestParser(objectMapper);
        this.moduleStore = Objects.requireNonNull(moduleStore);
        this.provenanceStore = Objects.requireNonNull(provenanceStore);
        this.provisioner = Objects.requireNonNull(provisioner);
        this.platformVersion = Objects.requireNonNull(platformVersion);
        this.signer = Objects.requireNonNull(signer);
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * Plans installing (or upgrading, when the module is already installed from the same URL) the
     * module served at {@code baseUrl}.
     *
     * @throws IllegalArgumentException when {@code baseUrl} is unacceptable or the manifest is not JSON
     * @throws ModuleHttpClient.ModuleCallException when the manifest cannot be fetched
     */
    public ExternalModulePlan plan(String tenantId, String baseUrl, String userId) {
        String normalized = http.normalizeBaseUrl(baseUrl);
        return plan(tenantId, normalized, http.fetchManifest(normalized), userId);
    }

    /** Plans upgrading an installed external module from its stored base URL. */
    public ExternalModulePlan planUpgrade(String tenantId, TenantModuleData installed, String userId) {
        return plan(tenantId, installed.baseUrl(), userId);
    }

    ExternalModulePlan plan(String tenantId, String baseUrl, String manifestJson, String userId) {
        List<Map<String, Object>> blockers = new ArrayList<>();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("baseUrl", baseUrl);
        attributes.put("manifestSha256", sha256(manifestJson));
        attributes.put("platformVersion", platformVersion.describe());

        int manifestVersion;
        try {
            manifestVersion = parser.manifestVersion(manifestJson);
        } catch (ModuleManifestException e) {
            throw new IllegalArgumentException("The module manifest is not valid JSON: " + e.getMessage());
        }
        attributes.put("manifestVersion", manifestVersion);
        ExternalModuleManifest manifest = null;
        if (manifestVersion != ModuleManifestParser.EXTERNAL_MANIFEST_VERSION) {
            blockers.add(blocker(UNSUPPORTED_MANIFEST_VERSION, "manifestVersion " + manifestVersion
                    + " is not an external module manifest (expected "
                    + ModuleManifestParser.EXTERNAL_MANIFEST_VERSION + ")"));
        } else {
            try {
                manifest = parser.parseExternal(manifestJson);
            } catch (ModuleManifestException e) {
                blockers.add(blocker(INVALID_MANIFEST, e.getMessage()));
            }
        }
        if (manifest == null) {
            attributes.put("moduleId", null);
            attributes.put("mode", ExternalModulePlan.MODE_BLOCKED);
            return finish(attributes, blockers, null, manifestJson, baseUrl, null);
        }

        TenantModuleData installed = moduleStore.findByTenantAndModuleId(tenantId, manifest.id()).orElse(null);
        attributes.put("moduleId", manifest.id());
        attributes.put("name", manifest.name());
        attributes.put("description", manifest.description());
        attributes.put("author", manifest.author());
        attributes.put("protocolVersion", manifest.protocolVersion());
        attributes.put("toVersion", manifest.version());
        attributes.put("fromVersion", installed == null ? null : installed.version());
        attributes.put("mode", mode(installed, manifest, manifestJson, baseUrl, blockers));
        attributes.put("minPlatformVersion", manifest.minPlatformVersion());
        attributes.put("platformCheck", platformCheck(manifest, blockers));
        if (!signer.isConfigured()) {
            blockers.add(blocker(SIGNING_NOT_CONFIGURED, "This platform cannot sign module requests: "
                    + "KELTA_MODULE_SIGNING_KEY is not set"));
        }

        Set<String> ownedKeys = ownedKeys(tenantId, manifest.id());
        List<PlannedItem> metadata = provisioner.planPackage(tenantId, manifest.metadata(), ownedKeys, userId);
        List<String> ownedCollections = new ArrayList<>();
        Set<String> packageFields = new HashSet<>();
        for (PlannedItem item : metadata) {
            if (ModuleMetadataProvisioner.ACTION_FAILED.equals(item.action())) {
                blockers.add(blocker(METADATA_INVALID, item.type() + " " + item.naturalKey() + ": " + item.error()));
            } else if (ModuleMetadataProvisioner.COLLECTION.equals(item.type())
                    && (ModuleMetadataProvisioner.ACTION_CREATE.equals(item.action())
                        || ModuleMetadataProvisioner.ACTION_KEEP.equals(item.action()))) {
                ownedCollections.add(item.naturalKey());
            } else if (ModuleMetadataProvisioner.FIELD.equals(item.type())
                    && !ModuleMetadataProvisioner.ACTION_SKIP_ADOPTED.equals(item.action())) {
                packageFields.add(item.naturalKey());
            }
        }
        attributes.put("grants", grants(tenantId, manifest, ownedCollections, packageFields, blockers));
        attributes.put("capabilities", capabilities(manifest, ownedCollections, blockers));
        attributes.put("metadata", metadata.stream()
                .sorted(Comparator.comparing(PlannedItem::type).thenComparing(PlannedItem::naturalKey))
                .map(item -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("type", item.type());
                    entry.put("naturalKey", item.naturalKey());
                    entry.put("action", item.action());
                    return entry;
                }).toList());
        if (installed != null && installed.isExternal()) {
            attributes.put("changes", changes(installed, manifest));
            attributes.put("orphanedByUpgrade", orphaned(ownedKeys, metadata));
        }
        if (!blockers.isEmpty()) {
            attributes.put("mode", ExternalModulePlan.MODE_BLOCKED);
        }
        return finish(attributes, blockers, manifest, manifestJson, baseUrl, installed);
    }

    private ExternalModulePlan finish(Map<String, Object> attributes, List<Map<String, Object>> blockers,
                                      ExternalModuleManifest manifest, String manifestJson, String baseUrl,
                                      TenantModuleData installed) {
        attributes.put("blockers", blockers.stream()
                .sorted(Comparator.comparing((Map<String, Object> b) -> String.valueOf(b.get("code")))
                        .thenComparing(b -> String.valueOf(b.get("message"))))
                .toList());
        attributes.remove("planHash");
        attributes.put("planHash", planHash(attributes));
        return new ExternalModulePlan(attributes, manifest, manifestJson, baseUrl, installed);
    }

    private static String mode(TenantModuleData installed, ExternalModuleManifest manifest, String manifestJson,
                               String baseUrl, List<Map<String, Object>> blockers) {
        if (installed == null) {
            return ExternalModulePlan.MODE_INSTALL;
        }
        if (!installed.isExternal()) {
            blockers.add(blocker(INSTALLED_AS_JAR, "Module '" + manifest.id() + "' is installed as a JAR "
                    + "module in this tenant; uninstall it first"));
            return ExternalModulePlan.MODE_UPGRADE;
        }
        if (!baseUrl.equals(installed.baseUrl())) {
            blockers.add(blocker(BASE_URL_MISMATCH, "Module '" + manifest.id() + "' is installed from "
                    + installed.baseUrl() + "; uninstall it to install it from another URL"));
        }
        int order;
        try {
            order = ModuleVersion.parse(manifest.version()).compareTo(ModuleVersion.parse(installed.version()));
        } catch (IllegalArgumentException e) {
            // The installed version predates semver validation; any manifest version is an upgrade.
            return ExternalModulePlan.MODE_UPGRADE;
        }
        if (order < 0) {
            blockers.add(blocker(DOWNGRADE, "Version " + manifest.version() + " is older than the installed "
                    + installed.version() + "; uninstall and install the older version instead"));
            return ExternalModulePlan.MODE_UPGRADE;
        }
        if (order == 0) {
            if (sha256(manifestJson).equals(sha256(installed.manifest()))) {
                return ExternalModulePlan.MODE_NO_OP;
            }
            blockers.add(blocker(SAME_VERSION_DIFFERENT_MANIFEST, "The module serves a different manifest under "
                    + "the installed version " + installed.version() + "; a publisher must bump the version"));
        }
        return ExternalModulePlan.MODE_UPGRADE;
    }

    private String platformCheck(ExternalModuleManifest manifest, List<Map<String, Object>> blockers) {
        if (manifest.minPlatformVersion() == null) {
            return "OK";
        }
        Optional<ModuleVersion> current = platformVersion.current();
        if (current.isEmpty()) {
            return "UNKNOWN";
        }
        if (current.get().compareTo(ModuleVersion.parse(manifest.minPlatformVersion())) < 0) {
            blockers.add(blocker(PLATFORM_TOO_OLD, "The module needs Kelta " + manifest.minPlatformVersion()
                    + " or later; this platform is " + current.get()));
            return "TOO_OLD";
        }
        return "OK";
    }

    private Map<String, Object> grants(String tenantId, ExternalModuleManifest manifest, List<String> owned,
                                       Set<String> packageFields, List<Map<String, Object>> blockers) {
        ExternalModuleManifest.Permissions permissions = manifest.permissions();
        List<Map<String, Object>> objects = new ArrayList<>();
        for (ExternalModuleManifest.ObjectPermission grant : permissions.objects()) {
            if (!owned.contains(grant.collection()) && collectionId(tenantId, grant.collection()) == null) {
                blockers.add(blocker(UNKNOWN_COLLECTION, "permissions.objects names collection '"
                        + grant.collection() + "', which does not exist and the module does not provision"));
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("collection", grant.collection());
            entry.put("create", grant.create());
            entry.put("read", grant.read());
            entry.put("edit", grant.edit());
            entry.put("delete", grant.delete());
            objects.add(entry);
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (ExternalModuleManifest.FieldPermission grant : permissions.fields()) {
            String key = grant.collection() + "." + grant.field();
            if (!packageFields.contains(key) && !fieldExists(tenantId, grant.collection(), grant.field())) {
                blockers.add(blocker(UNKNOWN_FIELD, "permissions.fields names field '" + key
                        + "', which does not exist and the module does not provision"));
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("collection", grant.collection());
            entry.put("field", grant.field());
            entry.put("visibility", grant.visibility());
            fields.add(entry);
        }
        for (String permission : permissions.system()) {
            requirePermission(permission, "permissions.system", blockers);
        }
        Map<String, Object> grants = new LinkedHashMap<>();
        grants.put("ownedCollections", sorted(owned));
        grants.put("objects", sortedBy(objects, "collection"));
        grants.put("fields", sortedBy(fields, "collection", "field"));
        grants.put("system", sorted(permissions.system()));
        grants.put("credentials", sorted(permissions.credentials()));
        return grants;
    }

    private Map<String, Object> capabilities(ExternalModuleManifest manifest, List<String> owned,
                                             List<Map<String, Object>> blockers) {
        Set<String> readable = new HashSet<>(owned);
        for (ExternalModuleManifest.ObjectPermission grant : manifest.permissions().objects()) {
            if (grant.read()) {
                readable.add(grant.collection());
            }
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (ExternalModuleManifest.Action action : manifest.actions()) {
            actions.add(entry("key", action.key(), "name", action.name(), "timeoutMs", action.timeoutMs()));
        }
        List<Map<String, Object>> hooks = new ArrayList<>();
        for (ExternalModuleManifest.Hook hook : manifest.hooks()) {
            if (!ModuleManifestParser.HOOK_WILDCARD.equals(hook.collection()) && !readable.contains(hook.collection())) {
                blockers.add(blocker(CAPABILITY_WITHOUT_GRANT, "hooks[] on '" + hook.collection()
                        + "' needs read access to it (provision it or grant read in permissions.objects)"));
            }
            hooks.add(entry("collection", hook.collection(), "events", sorted(hook.events()),
                    "order", hook.order(), "timeoutMs", hook.timeoutMs(), "failurePolicy", hook.failurePolicy()));
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (ExternalModuleManifest.EventSubscription event : manifest.events()) {
            if (!readable.contains(event.collection())) {
                blockers.add(blocker(CAPABILITY_WITHOUT_GRANT, "events[] on '" + event.collection()
                        + "' needs read access to it (provision it or grant read in permissions.objects)"));
            }
            events.add(entry("collection", event.collection(), "types", sorted(event.types())));
        }
        List<Map<String, Object>> services = new ArrayList<>();
        for (ExternalModuleManifest.Service service : manifest.services()) {
            services.add(entry("port", service.port(), "cacheTtlSeconds", service.cacheTtlSeconds()));
        }
        List<Map<String, Object>> routes = new ArrayList<>();
        for (ExternalModuleManifest.Route route : manifest.routes()) {
            if (route.permission() != null) {
                requirePermission(route.permission(), "routes[" + route.path() + "].permission", blockers);
            }
            routes.add(entry("path", route.path(), "methods", sorted(route.methods()),
                    "permission", route.permission()));
        }
        List<Map<String, Object>> schedules = new ArrayList<>();
        for (ExternalModuleManifest.Schedule schedule : manifest.schedules()) {
            schedules.add(entry("action", schedule.action(), "cron", schedule.cron()));
        }
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("actions", sortedBy(actions, "key"));
        capabilities.put("hooks", sortedBy(hooks, "collection", "order"));
        capabilities.put("events", sortedBy(events, "collection"));
        capabilities.put("services", sortedBy(services, "port"));
        capabilities.put("routes", sortedBy(routes, "path"));
        capabilities.put("schedules", sortedBy(schedules, "action", "cron"));
        capabilities.put("webhook", manifest.webhookEnabled());
        capabilities.put("ui", manifest.ui() == null ? null
                : entry("bundlePath", manifest.ui().bundlePath(), "sha256", manifest.ui().sha256()));
        return capabilities;
    }

    /** What an upgrade adds and removes, by action key, route, service port, hook and event. */
    private Map<String, Object> changes(TenantModuleData installed, ExternalModuleManifest next) {
        ExternalModuleManifest previous;
        try {
            previous = parser.parseExternal(installed.manifest());
        } catch (ModuleManifestException e) {
            return Map.of();
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("actions", diff(signatures(previous.actions(), ExternalModuleManifest.Action::key),
                signatures(next.actions(), ExternalModuleManifest.Action::key)));
        changes.put("routes", diff(signatures(previous.routes(), r -> String.join(",", sorted(r.methods())) + " " + r.path()),
                signatures(next.routes(), r -> String.join(",", sorted(r.methods())) + " " + r.path())));
        changes.put("services", diff(signatures(previous.services(), ExternalModuleManifest.Service::port),
                signatures(next.services(), ExternalModuleManifest.Service::port)));
        changes.put("hooks", diff(signatures(previous.hooks(), h -> h.collection() + " " + sorted(h.events())),
                signatures(next.hooks(), h -> h.collection() + " " + sorted(h.events()))));
        changes.put("events", diff(signatures(previous.events(), e -> e.collection() + " " + sorted(e.types())),
                signatures(next.events(), e -> e.collection() + " " + sorted(e.types()))));
        changes.put("schedules", diff(signatures(previous.schedules(), s -> s.action() + " " + s.cron()),
                signatures(next.schedules(), s -> s.action() + " " + s.cron())));
        return changes;
    }

    /** This module's {@code CREATED} resources the new package no longer declares; informational. */
    private static List<Map<String, Object>> orphaned(Set<String> ownedKeys, List<PlannedItem> metadata) {
        Set<String> declared = new HashSet<>();
        for (PlannedItem item : metadata) {
            declared.add(item.type() + ":" + item.naturalKey());
        }
        List<Map<String, Object>> orphaned = new ArrayList<>();
        for (String key : new TreeSet<>(ownedKeys)) {
            if (!declared.contains(key)) {
                String[] parts = key.split(":", 2);
                orphaned.add(entry("type", parts[0], "naturalKey", parts[1]));
            }
        }
        return orphaned;
    }

    private Set<String> ownedKeys(String tenantId, String moduleId) {
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : provenanceStore.findByModule(tenantId, moduleId)) {
            if (ModuleProvenanceStore.OWNERSHIP_CREATED.equals(row.get("ownership"))) {
                keys.add(row.get("resource_type") + ":" + row.get("natural_key"));
            }
        }
        return keys;
    }

    private String collectionId(String tenantId, String collection) {
        List<String> ids = TenantContext.callWithTenant(tenantId, () ->
                jdbcTemplate.queryForList(SELECT_VISIBLE_COLLECTION_ID, String.class, collection, tenantId));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private boolean fieldExists(String tenantId, String collection, String field) {
        String collectionId = collectionId(tenantId, collection);
        return collectionId != null && !TenantContext.callWithTenant(tenantId, () ->
                jdbcTemplate.queryForList(SELECT_FIELD, Integer.class, collectionId, field)).isEmpty();
    }

    private static void requirePermission(String permission, String where, List<Map<String, Object>> blockers) {
        if (!TenantProvisioningHook.ALL_PERMISSIONS.contains(permission)) {
            blockers.add(blocker(UNKNOWN_PERMISSION, where + " names '" + permission
                    + "', which is not a platform permission"));
        }
    }

    // ── canonical JSON ──────────────────────────────────────────────────────────────────

    /** SHA-256 (lowercase hex) of {@code attributes} as canonical JSON, {@code planHash} excluded. */
    String planHash(Map<String, Object> attributes) {
        Map<String, Object> copy = new TreeMap<>(attributes);
        copy.remove("planHash");
        return ModuleJarService.sha256(objectMapper.writeValueAsBytes(canonical(copy)));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ExternalModulePlanner::canonical).toList();
        }
        return value;
    }

    static String sha256(String text) {
        return text == null ? null : ModuleJarService.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> blocker(String code, String message) {
        return entry("code", code, "message", message);
    }

    private static Map<String, Object> entry(Object... keyValues) {
        Map<String, Object> entry = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            entry.put((String) keyValues[i], keyValues[i + 1]);
        }
        return entry;
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    private static List<Map<String, Object>> sortedBy(List<Map<String, Object>> entries, String... keys) {
        Comparator<Map<String, Object>> order = Comparator.comparing(e -> String.valueOf(e.get(keys[0])));
        for (int i = 1; i < keys.length; i++) {
            String key = keys[i];
            order = order.thenComparing(e -> String.valueOf(e.get(key)));
        }
        return entries.stream().sorted(order).toList();
    }

    private static <T> Set<String> signatures(List<T> items, java.util.function.Function<T, String> signature) {
        Set<String> signatures = new TreeSet<>();
        for (T item : items) {
            signatures.add(signature.apply(item));
        }
        return signatures;
    }

    private static Map<String, Object> diff(Set<String> before, Set<String> after) {
        Set<String> added = new TreeSet<>(after);
        added.removeAll(before);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(after);
        return entry("added", List.copyOf(added), "removed", List.copyOf(removed));
    }
}
