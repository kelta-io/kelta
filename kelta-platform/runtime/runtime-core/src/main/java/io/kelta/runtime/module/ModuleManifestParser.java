package io.kelta.runtime.module;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.scheduling.support.CronExpression;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses module manifest JSON: the JAR manifest (v1) into {@link ModuleManifest} with
 * {@link #parse(String)}, and the external-module manifest (v2) into {@link ExternalModuleManifest}
 * with {@link #parseExternal(String)}. Each refuses the other's manifest, so a JAR install endpoint
 * can never install an external module or the reverse.
 *
 * @since 1.0.0
 */
public class ModuleManifestParser {

    /** {@code manifestVersion} of an external module's manifest. */
    public static final int EXTERNAL_MANIFEST_VERSION = 2;

    /** Module protocol versions this platform speaks. */
    public static final Set<Integer> SUPPORTED_PROTOCOL_VERSIONS = Set.of(1);

    /**
     * Metadata package item types an external module may provision (slice 3's allowlist). Each has
     * a natural key that never involves a source-tenant id. Layouts key on sort orders; {@code FLOW}
     * would run with platform powers on a schedule the admin did not author.
     */
    public static final Set<String> METADATA_ITEM_TYPES = Set.of(
        "COLLECTION", "FIELD", "GLOBAL_PICKLIST", "PICKLIST_VALUE", "VALIDATION_RULE",
        "UI_PAGE", "UI_MENU", "UI_MENU_ITEM");

    /** The package format {@code PackageService} exports and {@code PackageImportService} reads. */
    public static final int METADATA_FORMAT_VERSION = 2;

    public static final int ACTION_TIMEOUT_MIN_MS = 1;
    public static final int ACTION_TIMEOUT_MAX_MS = 120_000;
    public static final int ACTION_TIMEOUT_DEFAULT_MS = 30_000;
    public static final int HOOK_TIMEOUT_MIN_MS = 100;
    public static final int HOOK_TIMEOUT_MAX_MS = 5_000;
    public static final int HOOK_TIMEOUT_DEFAULT_MS = 2_000;
    public static final int HOOK_ORDER_DEFAULT = 1000;
    public static final int SERVICE_CACHE_TTL_MAX_SECONDS = 300;
    public static final int SERVICE_CACHE_TTL_DEFAULT_SECONDS = 60;
    public static final String HOOK_WILDCARD = "*";

    private static final Pattern MODULE_ID = Pattern.compile("^[a-z][a-z0-9-]{1,62}$");
    private static final Pattern ACTION_KEY = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");
    /** User collections use underscores, system collections hyphens. */
    private static final Pattern COLLECTION_REF = Pattern.compile("^[a-z][a-z0-9_-]{0,99}$");
    /** A provisioned collection's name is also its route segment and table name. */
    private static final Pattern PROVISIONED_COLLECTION = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Pattern FIELD_REF = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,99}$");
    private static final Pattern ROUTE_SEGMENT = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> RESERVED_FIELD_NAMES =
        Set.of("id", "createdat", "updatedat", "createdby", "updatedby", "tenantid");
    private static final Set<String> HOOK_EVENTS = Set.of("beforeCreate", "beforeUpdate", "beforeDelete");
    private static final Set<String> EVENT_TYPES = Set.of("created", "updated", "deleted");
    private static final Set<String> SERVICE_PORTS = Set.of("entitlements");
    private static final Set<String> ROUTE_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> FIELD_VISIBILITIES = Set.of("VISIBLE", "READ_ONLY", "HIDDEN", "MASKED");
    private static final Set<String> FAILURE_POLICIES = Set.of("fail", "ignore");

    /** Column widths of {@code tenant_module} / {@code tenant_module_action}. */
    private static final int MAX_NAME = 200;
    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_VERSION = 64;
    private static final int MAX_ACTION_DESCRIPTION = 500;
    private static final int MAX_ACTION_CATEGORY = 50;

    private final ObjectMapper objectMapper;

    public ModuleManifestParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null")
            .rebuild()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    }

    /**
     * Parses manifest JSON into a ModuleManifest.
     *
     * @param json the manifest JSON string
     * @return the parsed manifest
     * @throws ModuleManifestException if parsing fails or required fields are missing
     */
    public ModuleManifest parse(String json) {
        Objects.requireNonNull(json, "manifest JSON cannot be null");

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode manifestVersion = root.get("manifestVersion");
            if (manifestVersion != null && !manifestVersion.isNull()
                    && !(manifestVersion.isIntegralNumber() && manifestVersion.asInt() == 1)) {
                throw new ModuleManifestException("manifestVersion " + manifestVersion
                    + " is an external module manifest; install it by URL (POST /api/modules/plan)");
            }

            String id = requireString(root, "id");
            String name = requireString(root, "name");
            String version = requireString(root, "version");
            String moduleClass = requireString(root, "moduleClass");

            String description = optionalString(root, "description");
            String author = optionalString(root, "author");
            String minPlatformVersion = optionalString(root, "minPlatformVersion");

            List<String> permissions = parseStringList(root, "permissions");
            List<ModuleManifest.ActionHandlerManifest> handlers = parseActionHandlers(root);
            List<ModuleManifest.CollectionManifest> collections = parseCollections(root);
            String webhookHandlerKey = optionalString(root, "webhookHandlerKey");
            String uiBundlePath = optionalString(root, "uiBundlePath");
            List<String> services = parseStringList(root, "services");
            List<ModuleManifest.RouteManifest> routes = parseRoutes(root);

            return new ModuleManifest(
                id, name, version, description, author,
                moduleClass, minPlatformVersion, permissions, handlers, collections,
                webhookHandlerKey, uiBundlePath, services, routes
            );
        } catch (ModuleManifestException e) {
            throw e;
        } catch (JacksonException e) {
            throw new ModuleManifestException("Invalid manifest JSON: " + e.getMessage(), e);
        }
    }

    private List<ModuleManifest.ActionHandlerManifest> parseActionHandlers(JsonNode root) {
        JsonNode handlersNode = root.get("actionHandlers");
        if (handlersNode == null || !handlersNode.isArray()) {
            return List.of();
        }

        List<ModuleManifest.ActionHandlerManifest> handlers = new ArrayList<>();
        for (JsonNode node : handlersNode) {
            String key = requireString(node, "key");
            String name = requireString(node, "name");
            String category = optionalString(node, "category");
            String description = optionalString(node, "description");
            String icon = optionalString(node, "icon");
            String configSchema = optionalJsonString(node, "configSchema");
            String inputSchema = optionalJsonString(node, "inputSchema");
            String outputSchema = optionalJsonString(node, "outputSchema");

            handlers.add(new ModuleManifest.ActionHandlerManifest(
                key, name, category, description, icon,
                configSchema, inputSchema, outputSchema
            ));
        }
        return List.copyOf(handlers);
    }

    private List<ModuleManifest.CollectionManifest> parseCollections(JsonNode root) {
        JsonNode collectionsNode = root.get("collections");
        if (collectionsNode == null || !collectionsNode.isArray()) {
            return List.of();
        }

        List<ModuleManifest.CollectionManifest> collections = new ArrayList<>();
        for (JsonNode node : collectionsNode) {
            String name = requireString(node, "name");
            String displayName = optionalString(node, "displayName");

            List<ModuleManifest.CollectionManifest.FieldManifest> fields = new ArrayList<>();
            JsonNode fieldsNode = node.get("fields");
            if (fieldsNode != null && fieldsNode.isArray()) {
                for (JsonNode fieldNode : fieldsNode) {
                    fields.add(new ModuleManifest.CollectionManifest.FieldManifest(
                        requireString(fieldNode, "name"),
                        optionalString(fieldNode, "displayName"),
                        requireString(fieldNode, "type"),
                        optionalBoolean(fieldNode, "required")
                    ));
                }
            }

            collections.add(new ModuleManifest.CollectionManifest(
                name, displayName, List.copyOf(fields)));
        }
        return List.copyOf(collections);
    }

    private String requireString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            throw new ModuleManifestException("Required field '" + field + "' is missing or blank");
        }
        return value.asText();
    }

    private String optionalString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private boolean optionalBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() && value.asBoolean();
    }

    private String optionalJsonString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.toString();
    }

    /**
     * Reads the {@code routes} array.
     *
     * <p>A malformed route is rejected rather than skipped. Silently dropping one would leave the
     * module installed and apparently healthy while an endpoint its UI depends on simply 404s,
     * which is a far harder failure to diagnose than a refused install.
     */
    private List<ModuleManifest.RouteManifest> parseRoutes(JsonNode root) {
        JsonNode value = root.get("routes");
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<ModuleManifest.RouteManifest> routes = new ArrayList<>();
        for (JsonNode item : value) {
            String path = item.path("path").asText(null);
            String handlerKey = item.path("handlerKey").asText(null);
            if (path == null || path.isBlank() || !path.startsWith("/")) {
                throw new ModuleManifestException(
                    "Module route 'path' is required and must start with '/': " + path);
            }
            if (path.contains("..")) {
                throw new ModuleManifestException(
                    "Module route path must not contain '..': " + path);
            }
            if (handlerKey == null || handlerKey.isBlank()) {
                throw new ModuleManifestException(
                    "Module route '" + path + "' declares no handlerKey");
            }
            List<String> methods = new ArrayList<>();
            for (JsonNode m : item.path("methods")) {
                if (m.isTextual()) {
                    methods.add(m.asText().toUpperCase(java.util.Locale.ROOT));
                }
            }
            if (methods.isEmpty()) {
                methods.add("GET");
            }
            routes.add(new ModuleManifest.RouteManifest(path, List.copyOf(methods), handlerKey));
        }
        return List.copyOf(routes);
    }

    private List<String> parseStringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isTextual()) {
                result.add(item.asText());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Reads {@code manifestVersion} without validating anything else.
     *
     * @return the declared version, or 1 when the field is absent (a JAR manifest)
     * @throws ModuleManifestException when the JSON does not parse
     */
    public int manifestVersion(String json) {
        try {
            JsonNode value = objectMapper.readTree(json).get("manifestVersion");
            return value == null || value.isNull() ? 1 : value.asInt(-1);
        } catch (JacksonException e) {
            throw new ModuleManifestException("Invalid manifest JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Parses and validates an external module's manifest (v2,
     * {@code specs/module-platform/9-external-modules.md} §3.1).
     *
     * <p>Every rule here is structural: it needs nothing but the document. Rules that need the
     * tenant (a hook on a collection the module can neither provision nor read, a route permission
     * that does not exist, metadata the importer rejects, {@code minPlatformVersion} against the
     * running release) are checked when the install is planned. A malformed entry is refused rather
     * than skipped: silently dropping a declaration would leave an install that looks healthy while
     * something the module relies on is missing.
     *
     * @throws ModuleManifestException naming the first rule the manifest breaks
     */
    public ExternalModuleManifest parseExternal(String json) {
        Objects.requireNonNull(json, "manifest JSON cannot be null");
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new ModuleManifestException("Invalid manifest JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new ModuleManifestException("The manifest must be a JSON object");
        }
        int manifestVersion = requireInt(root, "manifestVersion");
        if (manifestVersion != EXTERNAL_MANIFEST_VERSION) {
            throw new ModuleManifestException("manifestVersion must be " + EXTERNAL_MANIFEST_VERSION
                + " for an external module, was " + manifestVersion);
        }
        int protocolVersion = requireInt(root, "protocolVersion");
        if (!SUPPORTED_PROTOCOL_VERSIONS.contains(protocolVersion)) {
            throw new ModuleManifestException("protocolVersion " + protocolVersion
                + " is not supported; this platform speaks " + SUPPORTED_PROTOCOL_VERSIONS);
        }

        String id = requireString(root, "id");
        if (!MODULE_ID.matcher(id).matches()) {
            throw new ModuleManifestException("id must match ^[a-z][a-z0-9-]{1,62}$: " + id);
        }
        String name = bounded(requireString(root, "name"), MAX_NAME, "name");
        String version = bounded(requireString(root, "version"), MAX_VERSION, "version");
        requireSemver(version, "version");
        String description = bounded(optionalString(root, "description"), MAX_DESCRIPTION, "description");
        String author = bounded(optionalString(root, "author"), MAX_NAME, "author");
        String minPlatformVersion = optionalString(root, "minPlatformVersion");
        if (minPlatformVersion != null) {
            requireSemver(minPlatformVersion, "minPlatformVersion");
        }

        List<ExternalModuleManifest.Action> actions = parseExternalActions(root);
        Set<String> actionKeys = new HashSet<>();
        actions.forEach(a -> actionKeys.add(a.key()));

        Map<String, Object> settings = optionalObject(root, "settings");
        JsonNode webhook = root.get("webhook");
        boolean webhookEnabled = webhook != null && webhook.isObject() && optionalBoolean(webhook, "enabled");

        return new ExternalModuleManifest(
            manifestVersion, protocolVersion, id, name, version, description, author,
            minPlatformVersion,
            parsePermissions(root.get("permissions")),
            parseMetadata(root.get("metadata")),
            settings,
            actions,
            parseHooks(root),
            parseEvents(root),
            parseServices(root),
            parseExternalRoutes(root),
            parseSchedules(root, actionKeys),
            webhookEnabled,
            parseUi(root.get("ui")));
    }

    private ExternalModuleManifest.Permissions parsePermissions(JsonNode node) {
        if (node == null || node.isNull()) {
            return ExternalModuleManifest.Permissions.NONE;
        }
        if (!node.isObject()) {
            throw new ModuleManifestException("permissions must be an object");
        }
        List<ExternalModuleManifest.ObjectPermission> objects = new ArrayList<>();
        Set<String> objectKeys = new HashSet<>();
        for (JsonNode item : array(node, "objects", "permissions.objects")) {
            String collection = collectionRef(requireString(item, "collection"), "permissions.objects[].collection");
            unique(objectKeys, collection, "permissions.objects[].collection");
            objects.add(new ExternalModuleManifest.ObjectPermission(collection,
                optionalBoolean(item, "read"), optionalBoolean(item, "create"),
                optionalBoolean(item, "edit"), optionalBoolean(item, "delete")));
        }
        List<ExternalModuleManifest.FieldPermission> fields = new ArrayList<>();
        Set<String> fieldKeys = new HashSet<>();
        for (JsonNode item : array(node, "fields", "permissions.fields")) {
            String collection = collectionRef(requireString(item, "collection"), "permissions.fields[].collection");
            String field = requireString(item, "field");
            noWildcard(field, "permissions.fields[].field");
            if (!FIELD_REF.matcher(field).matches()) {
                throw new ModuleManifestException("permissions.fields[].field is not a field name: " + field);
            }
            String visibility = requireString(item, "visibility");
            if (!FIELD_VISIBILITIES.contains(visibility)) {
                throw new ModuleManifestException("permissions.fields[].visibility must be one of "
                    + FIELD_VISIBILITIES + ": " + visibility);
            }
            unique(fieldKeys, collection + "." + field, "permissions.fields[]");
            fields.add(new ExternalModuleManifest.FieldPermission(collection, field, visibility));
        }
        return new ExternalModuleManifest.Permissions(List.copyOf(objects), List.copyOf(fields),
            uniqueStrings(node, "system", "permissions.system"),
            uniqueStrings(node, "credentials", "permissions.credentials"));
    }

    private Map<String, Object> parseMetadata(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new ModuleManifestException("metadata must be a metadata package object");
        }
        int formatVersion = requireInt(node, "formatVersion");
        if (formatVersion != METADATA_FORMAT_VERSION) {
            throw new ModuleManifestException("metadata.formatVersion must be " + METADATA_FORMAT_VERSION
                + ", was " + formatVersion);
        }
        JsonNode items = node.get("items");
        if (items == null || !items.isArray()) {
            throw new ModuleManifestException("metadata.items must be an array");
        }
        for (JsonNode item : items) {
            String type = requireString(item, "type");
            if (!METADATA_ITEM_TYPES.contains(type)) {
                throw new ModuleManifestException("metadata item type " + type
                    + " is not allowed for a module; allowed: " + METADATA_ITEM_TYPES);
            }
            JsonNode data = item.get("data");
            if (data == null || !data.isObject()) {
                throw new ModuleManifestException("metadata item of type " + type + " has no data object");
            }
            if ("COLLECTION".equals(type)) {
                String collection = requireString(data, "name");
                if (!PROVISIONED_COLLECTION.matcher(collection).matches()) {
                    throw new ModuleManifestException(
                        "metadata collection name must match ^[a-z][a-z0-9_]*$: " + collection);
                }
            } else if ("FIELD".equals(type)) {
                String field = requireString(data, "name");
                if (RESERVED_FIELD_NAMES.contains(field.toLowerCase(Locale.ROOT))) {
                    throw new ModuleManifestException("metadata field name is reserved by the platform: " + field);
                }
            }
        }
        return toMap(node);
    }

    private List<ExternalModuleManifest.Action> parseExternalActions(JsonNode root) {
        List<ExternalModuleManifest.Action> actions = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode node : array(root, "actions", "actions")) {
            String key = requireString(node, "key");
            if (!ACTION_KEY.matcher(key).matches()) {
                throw new ModuleManifestException("actions[].key must match ^[a-z][a-z0-9-]{0,62}$: " + key);
            }
            unique(keys, key, "actions[].key");
            actions.add(new ExternalModuleManifest.Action(
                key,
                bounded(requireString(node, "name"), MAX_NAME, "actions[].name"),
                bounded(optionalString(node, "description"), MAX_ACTION_DESCRIPTION, "actions[].description"),
                bounded(optionalString(node, "category"), MAX_ACTION_CATEGORY, "actions[].category"),
                optionalString(node, "icon"),
                boundedInt(node, "timeoutMs", ACTION_TIMEOUT_DEFAULT_MS, ACTION_TIMEOUT_MIN_MS,
                    ACTION_TIMEOUT_MAX_MS, "actions[" + key + "].timeoutMs"),
                optionalJsonString(node, "configSchema"),
                optionalJsonString(node, "inputSchema"),
                optionalJsonString(node, "outputSchema")));
        }
        return List.copyOf(actions);
    }

    private List<ExternalModuleManifest.Hook> parseHooks(JsonNode root) {
        List<ExternalModuleManifest.Hook> hooks = new ArrayList<>();
        Set<String> collections = new HashSet<>();
        for (JsonNode node : array(root, "hooks", "hooks")) {
            String collection = requireString(node, "collection");
            if (!HOOK_WILDCARD.equals(collection) && !COLLECTION_REF.matcher(collection).matches()) {
                throw new ModuleManifestException("hooks[].collection must be a collection name or *: " + collection);
            }
            unique(collections, collection, "hooks[].collection");
            List<String> events = enumList(node, "events", HOOK_EVENTS, "hooks[" + collection + "].events");
            if (events.isEmpty()) {
                throw new ModuleManifestException("hooks[" + collection + "].events must not be empty");
            }
            String failurePolicy = optionalString(node, "failurePolicy");
            if (failurePolicy == null) {
                failurePolicy = "fail";
            } else if (!FAILURE_POLICIES.contains(failurePolicy)) {
                throw new ModuleManifestException("hooks[" + collection + "].failurePolicy must be fail or ignore: "
                    + failurePolicy);
            }
            hooks.add(new ExternalModuleManifest.Hook(collection, events,
                optionalInt(node, "order", HOOK_ORDER_DEFAULT, "hooks[" + collection + "].order"),
                boundedInt(node, "timeoutMs", HOOK_TIMEOUT_DEFAULT_MS, HOOK_TIMEOUT_MIN_MS, HOOK_TIMEOUT_MAX_MS,
                    "hooks[" + collection + "].timeoutMs"),
                failurePolicy));
        }
        return List.copyOf(hooks);
    }

    private List<ExternalModuleManifest.EventSubscription> parseEvents(JsonNode root) {
        List<ExternalModuleManifest.EventSubscription> events = new ArrayList<>();
        Set<String> collections = new HashSet<>();
        for (JsonNode node : array(root, "events", "events")) {
            String collection = collectionRef(requireString(node, "collection"), "events[].collection");
            unique(collections, collection, "events[].collection");
            List<String> types = enumList(node, "types", EVENT_TYPES, "events[" + collection + "].types");
            if (types.isEmpty()) {
                throw new ModuleManifestException("events[" + collection + "].types must not be empty");
            }
            events.add(new ExternalModuleManifest.EventSubscription(collection, types));
        }
        return List.copyOf(events);
    }

    private List<ExternalModuleManifest.Service> parseServices(JsonNode root) {
        List<ExternalModuleManifest.Service> services = new ArrayList<>();
        Set<String> ports = new HashSet<>();
        for (JsonNode node : array(root, "services", "services")) {
            String port = requireString(node, "port");
            if (!SERVICE_PORTS.contains(port)) {
                throw new ModuleManifestException("services[].port must be one of " + SERVICE_PORTS + ": " + port);
            }
            unique(ports, port, "services[].port");
            services.add(new ExternalModuleManifest.Service(port,
                boundedInt(node, "cacheTtlSeconds", SERVICE_CACHE_TTL_DEFAULT_SECONDS, 0,
                    SERVICE_CACHE_TTL_MAX_SECONDS, "services[" + port + "].cacheTtlSeconds")));
        }
        return List.copyOf(services);
    }

    private List<ExternalModuleManifest.Route> parseExternalRoutes(JsonNode root) {
        List<ExternalModuleManifest.Route> routes = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (JsonNode node : array(root, "routes", "routes")) {
            String raw = requireString(node, "path");
            String path = raw.startsWith("/") ? raw.substring(1) : raw;
            String[] segments = path.split("/", -1);
            if (segments.length > 3) {
                throw new ModuleManifestException("routes[].path has more than 3 segments: " + raw);
            }
            for (String segment : segments) {
                if (!ROUTE_SEGMENT.matcher(segment).matches()) {
                    throw new ModuleManifestException(
                        "routes[].path segments must match [A-Za-z0-9_-]+: " + raw);
                }
            }
            unique(paths, path, "routes[].path");
            Set<String> methods = new LinkedHashSet<>();
            for (JsonNode m : array(node, "methods", "routes[" + path + "].methods")) {
                String method = m.isTextual() ? m.asText().toUpperCase(Locale.ROOT) : null;
                if (!ROUTE_METHODS.contains(method)) {
                    throw new ModuleManifestException("routes[" + path + "].methods entries must be one of "
                        + ROUTE_METHODS + ": " + m);
                }
                methods.add(method);
            }
            String permission = optionalString(node, "permission");
            if (permission != null) {
                noWildcard(permission, "routes[].permission");
            }
            routes.add(new ExternalModuleManifest.Route(path, methods.isEmpty() ? List.of("GET") : List.copyOf(methods),
                permission == null || permission.isBlank() ? null : permission));
        }
        return List.copyOf(routes);
    }

    private List<ExternalModuleManifest.Schedule> parseSchedules(JsonNode root, Set<String> actionKeys) {
        List<ExternalModuleManifest.Schedule> schedules = new ArrayList<>();
        for (JsonNode node : array(root, "schedules", "schedules")) {
            String action = requireString(node, "action");
            if (!actionKeys.contains(action)) {
                throw new ModuleManifestException("schedules[].action names no declared action: " + action);
            }
            String cron = requireString(node, "cron");
            if (!CronExpression.isValidExpression(cron)) {
                throw new ModuleManifestException("schedules[" + action + "].cron is not a 6-field cron expression: "
                    + cron);
            }
            schedules.add(new ExternalModuleManifest.Schedule(action, cron));
        }
        return List.copyOf(schedules);
    }

    private ExternalModuleManifest.Ui parseUi(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new ModuleManifestException("ui must be an object");
        }
        String bundlePath = requireString(node, "bundlePath");
        if (!bundlePath.startsWith("/") || bundlePath.startsWith("//") || bundlePath.contains("..")
                || bundlePath.contains("://")) {
            throw new ModuleManifestException("ui.bundlePath must be an absolute path on the module: " + bundlePath);
        }
        String sha256 = requireString(node, "sha256");
        if (!SHA256_HEX.matcher(sha256).matches()) {
            throw new ModuleManifestException("ui.sha256 must be 64 lowercase hex characters");
        }
        return new ExternalModuleManifest.Ui(bundlePath, sha256);
    }

    private Iterable<JsonNode> array(JsonNode node, String field, String label) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new ModuleManifestException(label + " must be an array");
        }
        return value;
    }

    private List<String> enumList(JsonNode node, String field, Set<String> allowed, String label) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : array(node, field, label)) {
            if (!item.isTextual() || !allowed.contains(item.asText())) {
                throw new ModuleManifestException(label + " entries must be one of " + allowed + ": " + item);
            }
            values.add(item.asText());
        }
        return List.copyOf(values);
    }

    private List<String> uniqueStrings(JsonNode node, String field, String label) {
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : array(node, field, label)) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new ModuleManifestException(label + " entries must be non-blank strings");
            }
            noWildcard(item.asText(), label);
            unique(seen, item.asText(), label);
            values.add(item.asText());
        }
        return List.copyOf(values);
    }

    private String collectionRef(String collection, String label) {
        noWildcard(collection, label);
        if (!COLLECTION_REF.matcher(collection).matches()) {
            throw new ModuleManifestException(label + " is not a collection name: " + collection);
        }
        return collection;
    }

    private static void noWildcard(String value, String label) {
        if (value.contains("*")) {
            throw new ModuleManifestException(label + " must not contain a wildcard: " + value);
        }
    }

    private static void unique(Set<String> seen, String value, String label) {
        if (!seen.add(value)) {
            throw new ModuleManifestException(label + " must be unique; '" + value + "' appears twice");
        }
    }

    private static void requireSemver(String value, String label) {
        if (!ModuleVersion.isValid(value)) {
            throw new ModuleManifestException(label + " is not a semantic version (MAJOR.MINOR.PATCH): " + value);
        }
    }

    private static String bounded(String value, int max, String label) {
        if (value != null && value.length() > max) {
            throw new ModuleManifestException(label + " is longer than " + max + " characters");
        }
        return value;
    }

    private int requireInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new ModuleManifestException("Required integer field '" + field + "' is missing");
        }
        return value.asInt();
    }

    private int optionalInt(JsonNode node, String field, int defaultValue, String label) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new ModuleManifestException(label + " must be an integer");
        }
        return value.asInt();
    }

    private int boundedInt(JsonNode node, String field, int defaultValue, int min, int max, String label) {
        int value = optionalInt(node, field, defaultValue, label);
        if (value < min || value > max) {
            throw new ModuleManifestException(label + " must be between " + min + " and " + max + ", was " + value);
        }
        return value;
    }

    private Map<String, Object> optionalObject(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new ModuleManifestException(field + " must be an object");
        }
        return toMap(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        return objectMapper.convertValue(node, LinkedHashMap.class);
    }

    /**
     * Exception thrown when a module manifest cannot be parsed.
     */
    public static class ModuleManifestException extends RuntimeException {
        public ModuleManifestException(String message) {
            super(message);
        }

        public ModuleManifestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
