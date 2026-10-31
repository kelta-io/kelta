package io.kelta.runtime.module;

import io.kelta.runtime.module.ModuleManifestParser.ModuleManifestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every rule of {@code specs/module-platform/9-external-modules.md} §3.1 that the parser owns. */
@DisplayName("ModuleManifestParser — external manifest (v2)")
class ExternalModuleManifestParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleManifestParser parser = new ModuleManifestParser(mapper);

    static final String FULL = """
            {
              "manifestVersion": 2,
              "protocolVersion": 1,
              "id": "kelta-billing",
              "name": "Billing",
              "version": "2.0.0",
              "description": "Stripe-backed portal billing",
              "author": "Example Ltd",
              "minPlatformVersion": "0.4.0",
              "unknownFutureField": {"ignored": true},
              "permissions": {
                "objects": [ { "collection": "users", "read": true } ],
                "fields":  [ { "collection": "users", "field": "email", "visibility": "READ_ONLY" } ],
                "system":  [ "VIEW_SETUP" ],
                "credentials": [ "stripe" ]
              },
              "metadata": { "formatVersion": 2, "items": [
                { "type": "COLLECTION", "data": { "name": "billing_plans", "display_name": "Plans" } },
                { "type": "FIELD", "data": { "collection_name": "billing_plans", "name": "code", "type": "STRING" } }
              ] },
              "settings": { "type": "object", "properties": { "allowedReturnOrigins": { "type": "array" } } },
              "actions":  [ { "key": "create-checkout-session", "name": "Create checkout session",
                              "timeoutMs": 15000, "inputSchema": {"type": "object"} },
                            { "key": "expire-passes", "name": "Expire passes" } ],
              "hooks":    [ { "collection": "*", "events": ["beforeCreate"], "order": 500 } ],
              "events":   [ { "collection": "billing_plans", "types": ["created", "updated"] } ],
              "services": [ { "port": "entitlements" } ],
              "routes":   [ { "path": "me", "methods": ["GET"], "permission": null },
                            { "path": "/checkout/sessions", "methods": ["post"] } ],
              "schedules":[ { "action": "expire-passes", "cron": "0 0 * * * *" } ],
              "webhook":  { "enabled": true },
              "ui":       { "bundlePath": "/kelta/v1/ui-bundle.js",
                            "sha256": "3f9a000000000000000000000000000000000000000000000000000000000c21" }
            }
            """;

    private ObjectNode full() {
        return (ObjectNode) mapper.readTree(FULL);
    }

    private ExternalModuleManifest parse(Consumer<ObjectNode> edit) {
        ObjectNode root = full();
        edit.accept(root);
        return parser.parseExternal(root.toString());
    }

    private void refused(Consumer<ObjectNode> edit, String messagePart) {
        assertThatThrownBy(() -> parse(edit))
                .isInstanceOf(ModuleManifestException.class)
                .hasMessageContaining(messagePart);
    }

    @Test
    @DisplayName("parses every section, applying the documented defaults")
    void parsesEverySection() {
        ExternalModuleManifest m = parser.parseExternal(FULL);

        assertThat(m.manifestVersion()).isEqualTo(2);
        assertThat(m.protocolVersion()).isEqualTo(1);
        assertThat(m.id()).isEqualTo("kelta-billing");
        assertThat(m.version()).isEqualTo("2.0.0");
        assertThat(m.minPlatformVersion()).isEqualTo("0.4.0");
        assertThat(m.permissions().objects()).containsExactly(
                new ExternalModuleManifest.ObjectPermission("users", true, false, false, false));
        assertThat(m.permissions().fields()).containsExactly(
                new ExternalModuleManifest.FieldPermission("users", "email", "READ_ONLY"));
        assertThat(m.permissions().system()).containsExactly("VIEW_SETUP");
        assertThat(m.permissions().credentials()).containsExactly("stripe");
        assertThat(m.metadataItems()).hasSize(2);
        assertThat(m.provisionedCollections()).containsExactly("billing_plans");
        assertThat(m.settings()).containsKey("properties");

        assertThat(m.actions()).extracting(ExternalModuleManifest.Action::timeoutMs)
                .containsExactly(15000, ModuleManifestParser.ACTION_TIMEOUT_DEFAULT_MS);
        assertThat(m.actions().get(0).inputSchema()).isEqualTo("{\"type\":\"object\"}");
        ExternalModuleManifest.Hook hook = m.hooks().get(0);
        assertThat(hook.collection()).isEqualTo("*");
        assertThat(hook.order()).isEqualTo(500);
        assertThat(hook.timeoutMs()).isEqualTo(ModuleManifestParser.HOOK_TIMEOUT_DEFAULT_MS);
        assertThat(hook.failurePolicy()).isEqualTo("fail");
        assertThat(m.services()).containsExactly(new ExternalModuleManifest.Service("entitlements", 60));
        assertThat(m.routes()).containsExactly(
                new ExternalModuleManifest.Route("me", List.of("GET"), null),
                new ExternalModuleManifest.Route("checkout/sessions", List.of("POST"), null));
        assertThat(m.schedules()).containsExactly(
                new ExternalModuleManifest.Schedule("expire-passes", "0 0 * * * *"));
        assertThat(m.webhookEnabled()).isTrue();
        assertThat(m.ui().bundlePath()).isEqualTo("/kelta/v1/ui-bundle.js");
    }

    @Test
    @DisplayName("a minimal manifest needs only the five required fields")
    void minimal() {
        ExternalModuleManifest m = parser.parseExternal("""
                {"manifestVersion": 2, "protocolVersion": 1, "id": "tiny", "name": "Tiny", "version": "0.1.0"}
                """);
        assertThat(m.permissions()).isEqualTo(ExternalModuleManifest.Permissions.NONE);
        assertThat(m.metadata()).isNull();
        assertThat(m.metadataItems()).isEmpty();
        assertThat(m.actions()).isEmpty();
        assertThat(m.webhookEnabled()).isFalse();
        assertThat(m.ui()).isNull();
    }

    @Nested
    @DisplayName("versions")
    class Versions {

        @Test
        @DisplayName("a JAR (v1) manifest is not an external manifest")
        void v1IsRefused() {
            refused(r -> r.remove("manifestVersion"), "manifestVersion");
            refused(r -> r.put("manifestVersion", 1), "manifestVersion must be 2");
        }

        @Test
        @DisplayName("the JAR parser refuses a v2 manifest and points at install by URL")
        void jarParserRefusesV2() {
            assertThatThrownBy(() -> parser.parse(FULL.replace("\"id\"", "\"moduleClass\": \"x.Y\", \"id\"")))
                    .isInstanceOf(ModuleManifestException.class)
                    .hasMessageContaining("install it by URL");
            assertThat(parser.manifestVersion(FULL)).isEqualTo(2);
            assertThat(parser.manifestVersion("{\"id\":\"jar\"}")).isEqualTo(1);
        }

        @Test
        @DisplayName("protocolVersion must be one the platform speaks")
        void protocolVersion() {
            refused(r -> r.put("protocolVersion", 2), "protocolVersion 2 is not supported");
            refused(r -> r.remove("protocolVersion"), "protocolVersion");
        }

        @ParameterizedTest
        @ValueSource(strings = {"2", "2.0", "latest", "2.0.0.0"})
        @DisplayName("version and minPlatformVersion must be semantic versions")
        void semver(String bad) {
            refused(r -> r.put("version", bad), "version is not a semantic version");
            refused(r -> r.put("minPlatformVersion", bad), "minPlatformVersion is not a semantic version");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Billing", "1billing", "b", "billing_x", "a-very-long-id-that-goes-on-and-on-and-on-past-"
            + "sixty-three-characters-x"})
    @DisplayName("id must match ^[a-z][a-z0-9-]{1,62}$")
    void id(String bad) {
        refused(r -> r.put("id", bad), "id must match");
    }

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @ParameterizedTest
        @ValueSource(strings = {"FLOW", "PAGE_LAYOUT", "LIST_VIEW", "DASHBOARD", "REPORT", "RECORD"})
        @DisplayName("item types outside slice 3's allowlist are refused")
        void allowlist(String type) {
            refused(r -> ((ArrayNode) r.get("metadata").get("items"))
                    .addObject().put("type", type).putObject("data").put("name", "x"), "is not allowed");
        }

        @Test
        @DisplayName("every allowlisted type is accepted")
        void everyAllowedType() {
            assertThat(ModuleManifestParser.METADATA_ITEM_TYPES).containsExactlyInAnyOrder(
                    "COLLECTION", "FIELD", "GLOBAL_PICKLIST", "PICKLIST_VALUE", "VALIDATION_RULE",
                    "UI_PAGE", "UI_MENU", "UI_MENU_ITEM");
        }

        @Test
        @DisplayName("formatVersion must be 2 and items an array of {type, data}")
        void shape() {
            refused(r -> ((ObjectNode) r.get("metadata")).put("formatVersion", 1), "formatVersion must be 2");
            refused(r -> ((ObjectNode) r.get("metadata")).put("items", "x"), "metadata.items must be an array");
            refused(r -> ((ArrayNode) r.get("metadata").get("items")).addObject().put("type", "COLLECTION"),
                    "has no data object");
        }

        @Test
        @DisplayName("collection names are safe identifiers and field names are not reserved")
        void names() {
            refused(r -> ((ObjectNode) r.get("metadata").get("items").get(0).get("data")).put("name", "Bad-Name"),
                    "collection name must match");
            refused(r -> ((ObjectNode) r.get("metadata").get("items").get(1).get("data")).put("name", "createdAt"),
                    "reserved");
        }
    }

    @Nested
    @DisplayName("uniqueness")
    class Uniqueness {

        @Test
        @DisplayName("actions[].key, routes[].path, events[].collection and hooks[].collection are unique")
        void unique() {
            refused(r -> ((ArrayNode) r.get("actions")).addObject().put("key", "expire-passes").put("name", "Again"),
                    "actions[].key must be unique");
            refused(r -> ((ArrayNode) r.get("routes")).addObject().put("path", "/me"),
                    "routes[].path must be unique");
            refused(r -> ((ArrayNode) r.get("events")).addObject().put("collection", "billing_plans")
                    .putArray("types").add("deleted"), "events[].collection must be unique");
            refused(r -> ((ArrayNode) r.get("hooks")).addObject().put("collection", "*")
                    .putArray("events").add("beforeUpdate"), "hooks[].collection must be unique");
        }

        @Test
        @DisplayName("permission entries are unique")
        void permissions() {
            refused(r -> ((ArrayNode) r.get("permissions").get("objects")).addObject().put("collection", "users"),
                    "permissions.objects[].collection must be unique");
            refused(r -> ((ArrayNode) r.get("permissions").get("credentials")).add("stripe"),
                    "permissions.credentials must be unique");
        }
    }

    @Nested
    @DisplayName("timeouts and bounds")
    class Bounds {

        @ParameterizedTest
        @CsvSource({"0", "120001", "-5"})
        @DisplayName("action timeoutMs is 1–120000")
        void actionTimeout(int value) {
            refused(r -> ((ObjectNode) r.get("actions").get(0)).put("timeoutMs", value), "must be between 1 and 120000");
        }

        @Test
        @DisplayName("action timeoutMs accepts both bounds")
        void actionTimeoutBounds() {
            assertThat(parse(r -> ((ObjectNode) r.get("actions").get(0)).put("timeoutMs", 1)).actions().get(0)
                    .timeoutMs()).isEqualTo(1);
            assertThat(parse(r -> ((ObjectNode) r.get("actions").get(0)).put("timeoutMs", 120000)).actions().get(0)
                    .timeoutMs()).isEqualTo(120000);
        }

        @Test
        @DisplayName("an action without maxDurationSeconds is synchronous")
        void actionSynchronousByDefault() {
            ExternalModuleManifest.Action action = parse(r -> { }).actions().get(0);
            assertThat(action.maxDurationSeconds()).isNull();
            assertThat(action.asynchronous()).isFalse();
        }

        @Test
        @DisplayName("action maxDurationSeconds accepts both bounds and makes the action asynchronous")
        void actionMaxDurationBounds() {
            ExternalModuleManifest.Action shortest = parse(r -> ((ObjectNode) r.get("actions").get(0))
                    .put("maxDurationSeconds", 1)).actions().get(0);
            assertThat(shortest.maxDurationSeconds()).isEqualTo(1);
            assertThat(shortest.asynchronous()).isTrue();
            assertThat(parse(r -> ((ObjectNode) r.get("actions").get(0)).put("maxDurationSeconds", 604800))
                    .actions().get(0).maxDurationSeconds()).isEqualTo(604800);
        }

        @ParameterizedTest
        @CsvSource({"0", "604801", "-1"})
        @DisplayName("action maxDurationSeconds is 1–604800")
        void actionMaxDuration(int value) {
            refused(r -> ((ObjectNode) r.get("actions").get(0)).put("maxDurationSeconds", value),
                    "must be between 1 and 604800");
        }

        @Test
        @DisplayName("action maxDurationSeconds must be an integer")
        void actionMaxDurationType() {
            refused(r -> ((ObjectNode) r.get("actions").get(0)).put("maxDurationSeconds", "1h"),
                    "maxDurationSeconds");
        }

        @ParameterizedTest
        @CsvSource({"99", "5001"})
        @DisplayName("hook timeoutMs is 100–5000")
        void hookTimeout(int value) {
            refused(r -> ((ObjectNode) r.get("hooks").get(0)).put("timeoutMs", value), "must be between 100 and 5000");
        }

        @Test
        @DisplayName("service cacheTtlSeconds is 0–300")
        void cacheTtl() {
            refused(r -> ((ObjectNode) r.get("services").get(0)).put("cacheTtlSeconds", 301), "between 0 and 300");
            assertThat(parse(r -> ((ObjectNode) r.get("services").get(0)).put("cacheTtlSeconds", 0))
                    .services().get(0).cacheTtlSeconds()).isZero();
        }
    }

    @Nested
    @DisplayName("declarations")
    class Declarations {

        @Test
        @DisplayName("no wildcards in permissions")
        void noWildcards() {
            refused(r -> ((ObjectNode) r.get("permissions").get("objects").get(0)).put("collection", "*"),
                    "must not contain a wildcard");
            refused(r -> ((ObjectNode) r.get("permissions").get("fields").get(0)).put("field", "*"),
                    "must not contain a wildcard");
            refused(r -> ((ArrayNode) r.get("permissions").get("system")).add("*"), "must not contain a wildcard");
            refused(r -> ((ArrayNode) r.get("permissions").get("credentials")).add("stripe*"),
                    "must not contain a wildcard");
        }

        @Test
        @DisplayName("events may not use the * collection; hooks may")
        void eventWildcard() {
            refused(r -> ((ObjectNode) r.get("events").get(0)).put("collection", "*"), "must not contain a wildcard");
        }

        @Test
        @DisplayName("enumerated values are checked")
        void enums() {
            refused(r -> ((ArrayNode) r.get("hooks").get(0).get("events")).add("afterCreate"), "hooks[*].events");
            refused(r -> ((ObjectNode) r.get("hooks").get(0)).put("failurePolicy", "retry"), "failurePolicy");
            refused(r -> ((ObjectNode) r.get("hooks").get(0)).putArray("events"), "must not be empty");
            refused(r -> ((ArrayNode) r.get("events").get(0).get("types")).add("archived"), "types");
            refused(r -> ((ObjectNode) r.get("services").get(0)).put("port", "payments"), "services[].port");
            refused(r -> ((ArrayNode) r.get("routes").get(0).get("methods")).add("TRACE"), "methods");
            refused(r -> ((ObjectNode) r.get("permissions").get("fields").get(0)).put("visibility", "WRITE"),
                    "visibility");
        }

        @ParameterizedTest
        @ValueSource(strings = {"a/b/c/d", "a/../b", "/", "a//b", "a b", "a?x=1"})
        @DisplayName("route paths are 1–3 plain segments")
        void routePaths(String path) {
            refused(r -> ((ObjectNode) r.get("routes").get(0)).put("path", path), "routes[].path");
        }

        @Test
        @DisplayName("a schedule names a declared action and a valid 6-field cron")
        void schedules() {
            refused(r -> ((ObjectNode) r.get("schedules").get(0)).put("action", "missing"), "names no declared action");
            refused(r -> ((ObjectNode) r.get("schedules").get(0)).put("cron", "0 * * * *"), "cron");
        }

        @Test
        @DisplayName("action keys follow ^[a-z][a-z0-9-]{0,62}$")
        void actionKeys() {
            refused(r -> ((ObjectNode) r.get("actions").get(0)).put("key", "Create"), "actions[].key must match");
            refused(r -> ((ObjectNode) r.get("actions").get(0)).put("key", "billing:create"), "actions[].key must match");
        }

        @Test
        @DisplayName("the UI bundle is a path on the module with a 64-hex sha256")
        void ui() {
            refused(r -> ((ObjectNode) r.get("ui")).put("bundlePath", "https://cdn.example.com/x.js"), "ui.bundlePath");
            refused(r -> ((ObjectNode) r.get("ui")).put("bundlePath", "/a/../b.js"), "ui.bundlePath");
            refused(r -> ((ObjectNode) r.get("ui")).put("sha256", "ABC"), "ui.sha256");
            refused(r -> ((ObjectNode) r.get("ui")).remove("sha256"), "sha256");
        }

        @Test
        @DisplayName("text fields are bounded by their column widths")
        void lengths() {
            refused(r -> r.put("name", "n".repeat(201)), "name is longer than 200");
            refused(r -> r.put("description", "d".repeat(1001)), "description is longer than 1000");
            refused(r -> ((ObjectNode) r.get("actions").get(0)).put("category", "c".repeat(51)), "category");
        }

        @Test
        @DisplayName("invalid JSON and non-object documents are refused")
        void invalidJson() {
            assertThatThrownBy(() -> parser.parseExternal("{not json"))
                    .isInstanceOf(ModuleManifestException.class).hasMessageContaining("Invalid manifest JSON");
            assertThatThrownBy(() -> parser.parseExternal("[]"))
                    .isInstanceOf(ModuleManifestException.class).hasMessageContaining("JSON object");
        }
    }
}
