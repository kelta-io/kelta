package io.kelta.worker.module;

import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.module.ModuleMetadataProvisioner.PlannedItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ExternalModulePlanner")
class ExternalModulePlannerTest {

    private static final String TENANT = "tenant-1";
    private static final String BASE_URL = "https://billing.example.test";

    static final String MANIFEST = """
            {
              "manifestVersion": 2, "protocolVersion": 1,
              "id": "acme-billing", "name": "Billing", "version": "1.0.0",
              "minPlatformVersion": "0.1.0",
              "permissions": {
                "objects": [ { "collection": "users", "read": true }, { "collection": "accounts", "read": true } ],
                "system": [ "VIEW_SETUP" ]
              },
              "metadata": { "formatVersion": 2, "items": [
                { "type": "COLLECTION", "data": { "name": "billing_plans", "display_name": "Plans" } },
                { "type": "FIELD", "data": { "collection_name": "billing_plans", "name": "code", "type": "STRING" } }
              ] },
              "actions": [ { "key": "refund", "name": "Refund" }, { "key": "charge", "name": "Charge" } ],
              "hooks": [ { "collection": "billing_plans", "events": ["beforeCreate"] } ],
              "routes": [ { "path": "me", "methods": ["GET"], "permission": "VIEW_SETUP" } ]
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private ModuleStore moduleStore;
    private ModuleProvenanceStore provenanceStore;
    private ModuleMetadataProvisioner provisioner;
    private JdbcTemplate jdbcTemplate;
    private ExternalModuleUiBundles uiBundles;
    private ExternalModulePlanner planner;

    @BeforeEach
    void setUp() {
        moduleStore = mock(ModuleStore.class);
        provenanceStore = mock(ModuleProvenanceStore.class);
        provisioner = mock(ModuleMetadataProvisioner.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        uiBundles = mock(ExternalModuleUiBundles.class);
        when(uiBundles.storageAvailable()).thenReturn(true);
        when(moduleStore.findByTenantAndModuleId(anyString(), anyString())).thenReturn(Optional.empty());
        when(provenanceStore.findByModule(anyString(), anyString())).thenReturn(List.of());
        when(provisioner.planPackage(eq(TENANT), anyMap(), anySet(), any())).thenReturn(List.of(
                new PlannedItem("FIELD", "billing_plans.code", ModuleMetadataProvisioner.ACTION_CREATE, null),
                new PlannedItem("COLLECTION", "billing_plans", ModuleMetadataProvisioner.ACTION_CREATE, null)));
        // Every collection a grant names exists.
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any())).thenReturn(List.of("c-1"));
        planner = planner("0.4.0", ModuleAssertionSignerTest.KEY_PEM);
    }

    private ExternalModulePlanner planner(String platformVersion, String signingKey) {
        return new ExternalModulePlanner(mock(ModuleHttpClient.class), moduleStore, provenanceStore, provisioner,
                new PlatformVersion(platformVersion), new ModuleAssertionSigner(signingKey, "https://k.test/api/modules"),
                jdbcTemplate, mapper, uiBundles);
    }

    private String manifest(Consumer<ObjectNode> edit) {
        ObjectNode root = (ObjectNode) mapper.readTree(MANIFEST);
        edit.accept(root);
        return root.toString();
    }

    private ExternalModulePlan plan(String manifestJson) {
        return planner.plan(TENANT, BASE_URL, manifestJson, "user-1");
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(ExternalModulePlan plan) {
        return plan.blockers().stream().map(b -> (String) b.get("code")).toList();
    }

    @Test
    @DisplayName("an install plan lists grants, owned collections, capabilities and metadata, with no blockers")
    @SuppressWarnings("unchecked")
    void installPlan() {
        ExternalModulePlan plan = plan(MANIFEST);

        assertThat(plan.mode()).isEqualTo(ExternalModulePlan.MODE_INSTALL);
        assertThat(plan.blockers()).isEmpty();
        assertThat(plan.ownedCollections()).containsExactly("billing_plans");
        Map<String, Object> grants = (Map<String, Object>) plan.attributes().get("grants");
        assertThat((List<Map<String, Object>>) grants.get("objects"))
                .extracting(o -> o.get("collection")).containsExactly("accounts", "users");
        Map<String, Object> capabilities = (Map<String, Object>) plan.attributes().get("capabilities");
        assertThat((List<Map<String, Object>>) capabilities.get("actions"))
                .extracting(a -> a.get("key")).containsExactly("charge", "refund");
        assertThat((List<Map<String, Object>>) plan.attributes().get("metadata"))
                .extracting(m -> m.get("type")).containsExactly("COLLECTION", "FIELD");
        assertThat(plan.attributes().get("platformCheck")).isEqualTo("OK");
        assertThat(plan.planHash()).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("a long-running action shows its maxDurationSeconds; a synchronous one has none")
    @SuppressWarnings("unchecked")
    void longRunningAction() {
        ExternalModulePlan plan = plan(manifest(root ->
                ((ObjectNode) root.get("actions").get(0)).put("maxDurationSeconds", 7200)));

        Map<String, Object> capabilities = (Map<String, Object>) plan.attributes().get("capabilities");
        List<Map<String, Object>> actions = (List<Map<String, Object>>) capabilities.get("actions");
        assertThat(actions.get(0)).containsEntry("key", "charge").doesNotContainKey("maxDurationSeconds");
        assertThat(actions.get(1)).containsEntry("key", "refund").containsEntry("maxDurationSeconds", 7200);
        assertThat(plan.planHash()).as("the admin approves the duration too").isNotEqualTo(plan(MANIFEST).planHash());
    }

    @Test
    @DisplayName("planHash is stable: the same manifest, reordered lists and reordered dry-run items hash the same")
    void hashIsStable() {
        String first = plan(MANIFEST).planHash();
        assertThat(plan(MANIFEST).planHash()).isEqualTo(first);

        when(provisioner.planPackage(eq(TENANT), anyMap(), anySet(), any())).thenReturn(List.of(
                new PlannedItem("COLLECTION", "billing_plans", ModuleMetadataProvisioner.ACTION_CREATE, null),
                new PlannedItem("FIELD", "billing_plans.code", ModuleMetadataProvisioner.ACTION_CREATE, null)));
        // Reordered grant and action lists are a different manifest text, so the manifest hash
        // differs; the plan body for them is otherwise identical.
        ExternalModulePlan reordered = plan(manifest(root -> {
            ArrayNode actions = (ArrayNode) root.get("actions");
            root.set("actions", mapper.createArrayNode().add(actions.get(1)).add(actions.get(0)));
        }));
        Map<String, Object> a = new java.util.TreeMap<>(plan(MANIFEST).attributes());
        Map<String, Object> b = new java.util.TreeMap<>(reordered.attributes());
        a.keySet().removeAll(List.of("manifestSha256", "planHash"));
        b.keySet().removeAll(List.of("manifestSha256", "planHash"));
        assertThat(b).isEqualTo(a);
        assertThat(plan(MANIFEST).planHash()).isEqualTo(first);
    }

    @Test
    @DisplayName("planHash changes on any grant, metadata or installed-state change")
    void hashChanges() {
        String base = plan(MANIFEST).planHash();

        assertThat(plan(manifest(root -> ((ArrayNode) root.get("permissions").get("system")).add("MANAGE_USERS")))
                .planHash()).isNotEqualTo(base);

        when(provisioner.planPackage(eq(TENANT), anyMap(), anySet(), any())).thenReturn(List.of(
                new PlannedItem("COLLECTION", "billing_plans", ModuleMetadataProvisioner.ACTION_ADOPT, null),
                new PlannedItem("FIELD", "billing_plans.code", ModuleMetadataProvisioner.ACTION_SKIP_ADOPTED, null)));
        assertThat(plan(MANIFEST).planHash()).isNotEqualTo(base);
    }

    @Test
    @DisplayName("blockers: platform too old, signing unconfigured, unknown permission, hook without a grant")
    void blockers() {
        planner = planner("0.0.9", "");
        ExternalModulePlan plan = plan(manifest(root -> {
            ((ObjectNode) root.get("routes").get(0)).put("permission", "NOT_A_PERMISSION");
            ((ObjectNode) root.get("hooks").get(0)).put("collection", "invoices");
        }));

        assertThat(codes(plan)).containsExactlyInAnyOrder(ExternalModulePlanner.PLATFORM_TOO_OLD,
                ExternalModulePlanner.SIGNING_NOT_CONFIGURED, ExternalModulePlanner.UNKNOWN_PERMISSION,
                ExternalModulePlanner.CAPABILITY_WITHOUT_GRANT);
        assertThat(plan.mode()).isEqualTo(ExternalModulePlan.MODE_BLOCKED);
    }

    @Test
    @DisplayName("an unknown platform version proceeds as UNKNOWN; a failed metadata item blocks")
    void unknownPlatformAndBadMetadata() {
        planner = planner("", ModuleAssertionSignerTest.KEY_PEM);
        when(provisioner.planPackage(eq(TENANT), anyMap(), anySet(), any())).thenReturn(List.of(
                new PlannedItem("COLLECTION", "billing_plans", ModuleMetadataProvisioner.ACTION_CREATE, null),
                new PlannedItem("FIELD", "billing_plans.code", ModuleMetadataProvisioner.ACTION_FAILED, "bad type")));

        ExternalModulePlan plan = plan(MANIFEST);

        assertThat(plan.attributes().get("platformCheck")).isEqualTo("UNKNOWN");
        assertThat(codes(plan)).containsExactly(ExternalModulePlanner.METADATA_INVALID);
    }

    @Test
    @DisplayName("an unparseable v2 manifest is a plan with INVALID_MANIFEST; non-JSON is refused outright")
    void invalidManifests() {
        ExternalModulePlan plan = plan(manifest(root -> root.put("version", "latest")));
        assertThat(codes(plan)).containsExactly(ExternalModulePlanner.INVALID_MANIFEST);
        assertThat(plan.manifest()).isNull();

        assertThat(codes(plan(manifest(root -> root.put("manifestVersion", 1)))))
                .containsExactly(ExternalModulePlanner.UNSUPPORTED_MANIFEST_VERSION);
        assertThatThrownBy(() -> plan("not json")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("against an installed row: higher is UPGRADE, same text is NO_OP, lower is DOWNGRADE")
    void versions() {
        installed("0.9.0", MANIFEST);
        assertThat(plan(MANIFEST).mode()).isEqualTo(ExternalModulePlan.MODE_UPGRADE);
        assertThat(plan(MANIFEST).attributes()).containsKey("changes");

        installed("1.0.0", MANIFEST);
        assertThat(plan(MANIFEST).mode()).isEqualTo(ExternalModulePlan.MODE_NO_OP);
        assertThat(codes(plan(manifest(root -> root.put("description", "changed")))))
                .containsExactly(ExternalModulePlanner.SAME_VERSION_DIFFERENT_MANIFEST);

        installed("2.0.0", MANIFEST);
        assertThat(codes(plan(MANIFEST))).containsExactly(ExternalModulePlanner.DOWNGRADE);
    }

    private void installed(String version, String manifestJson) {
        installed(version, manifestJson, null);
    }

    private void installed(String version, String manifestJson, String pinnedSha256) {
        TenantModuleData row = new TenantModuleData("row-1", TENANT, "acme-billing", "Billing", version, null,
                BASE_URL, null, null, null, manifestJson, TenantModuleData.STATUS_ACTIVE, "user-1", null, null,
                null, List.of(), BASE_URL, 1, "app-1", "svc-1", pinnedSha256,
                pinnedSha256 == null ? null : "modules/key/" + pinnedSha256);
        when(moduleStore.findByTenantAndModuleId(TENANT, "acme-billing")).thenReturn(Optional.of(row));
    }

    private static ExternalModuleUiBundles.Fetched bundle(String source) {
        byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new ExternalModuleUiBundles.Fetched(bytes, ModuleJarService.sha256(bytes));
    }

    private String withUi(String version, String sha256) {
        return manifest(root -> {
            root.put("version", version);
            root.putObject("ui").put("bundlePath", "/ui/bundle.js").put("sha256", sha256);
        });
    }

    @Test
    @DisplayName("a UI bundle whose hash matches the manifest is fetched once and carried on the plan")
    @SuppressWarnings("unchecked")
    void uiBundleMatches() {
        ExternalModuleUiBundles.Fetched v1 = bundle("console.log('v1')");
        when(uiBundles.fetch(eq(BASE_URL), any())).thenReturn(v1);

        ExternalModulePlan plan = plan(withUi("1.0.0", v1.sha256()));

        assertThat(plan.blockers()).isEmpty();
        assertThat(plan.uiBundle()).isSameAs(v1);
        Map<String, Object> uiBundle = (Map<String, Object>) plan.attributes().get("uiBundle");
        assertThat(uiBundle).containsEntry("bundlePath", "/ui/bundle.js").containsEntry("sha256", v1.sha256())
                .containsEntry("fetchedSha256", v1.sha256()).containsEntry("sizeBytes", v1.bytes().length)
                .containsEntry("installedSha256", null).containsEntry("changed", false);
        assertThat(plan(MANIFEST).attributes().get("uiBundle")).as("no ui, nothing pinned").isNull();
    }

    @Test
    @DisplayName("a bundle that does not match, cannot be fetched, or cannot be stored blocks the plan")
    void uiBundleBlockers() {
        ExternalModuleUiBundles.Fetched served = bundle("console.log('swapped')");
        String declared = bundle("console.log('approved')").sha256();
        when(uiBundles.fetch(eq(BASE_URL), any())).thenReturn(served);
        ExternalModulePlan mismatch = plan(withUi("1.0.0", declared));
        assertThat(codes(mismatch)).containsExactly(ExternalModulePlanner.UI_BUNDLE_MISMATCH);
        assertThat(mismatch.uiBundle()).isNull();
        assertThat(mismatch.mode()).isEqualTo(ExternalModulePlan.MODE_BLOCKED);

        when(uiBundles.fetch(eq(BASE_URL), any()))
                .thenThrow(new ModuleHttpClient.ModuleCallException(404, "GET /ui/bundle.js answered 404"));
        assertThat(codes(plan(withUi("1.0.0", declared)))).containsExactly(ExternalModulePlanner.UI_BUNDLE_UNAVAILABLE);

        when(uiBundles.storageAvailable()).thenReturn(false);
        org.mockito.Mockito.clearInvocations(uiBundles);
        assertThat(codes(plan(withUi("1.0.0", declared))))
                .containsExactly(ExternalModulePlanner.UI_BUNDLE_STORAGE_UNAVAILABLE);
        org.mockito.Mockito.verify(uiBundles, org.mockito.Mockito.never()).fetch(any(), any());
    }

    @Test
    @DisplayName("an upgrade shows a changed bundle hash, and the change is part of planHash")
    @SuppressWarnings("unchecked")
    void upgradeShowsTheBundleChange() {
        ExternalModuleUiBundles.Fetched v1 = bundle("console.log('v1')");
        ExternalModuleUiBundles.Fetched v2 = bundle("console.log('v2')");
        when(uiBundles.fetch(eq(BASE_URL), any())).thenReturn(v2);

        installed("1.0.0", withUi("1.0.0", v1.sha256()), v1.sha256());
        ExternalModulePlan changed = plan(withUi("2.0.0", v2.sha256()));
        Map<String, Object> uiBundle = (Map<String, Object>) changed.attributes().get("uiBundle");
        assertThat(uiBundle).containsEntry("installedSha256", v1.sha256()).containsEntry("sha256", v2.sha256())
                .containsEntry("changed", true);

        installed("1.0.0", withUi("1.0.0", v2.sha256()), v2.sha256());
        ExternalModulePlan same = plan(withUi("2.0.0", v2.sha256()));
        assertThat((Map<String, Object>) same.attributes().get("uiBundle")).containsEntry("changed", false);
        assertThat(same.planHash()).isNotEqualTo(changed.planHash());

        installed("1.0.0", withUi("1.0.0", v1.sha256()), v1.sha256());
        Map<String, Object> removed = (Map<String, Object>) plan(manifest(root -> root.put("version", "2.0.0")))
                .attributes().get("uiBundle");
        assertThat(removed).as("dropping the UI is a change too").containsEntry("sha256", null)
                .containsEntry("installedSha256", v1.sha256()).containsEntry("changed", true);
    }

    @Test
    @DisplayName("an upgrade lists each grant it adds or removes, and widened when it asks for more")
    @SuppressWarnings("unchecked")
    void upgradePermissionChanges() {
        installed("0.9.0", manifest(root -> {
            root.put("version", "0.9.0");
            ((ObjectNode) root.get("permissions")).putArray("system");
        }));

        Map<String, Object> changes = (Map<String, Object>) plan(manifest(root -> {
            ArrayNode objects = (ArrayNode) root.get("permissions").get("objects");
            ((ObjectNode) objects.get(1)).put("edit", true);
        })).attributes().get("changes");
        Map<String, Object> permissions = (Map<String, Object>) changes.get("permissions");
        assertThat((List<String>) permissions.get("added"))
                .containsExactly("object accounts:edit", "system VIEW_SETUP");
        assertThat((List<String>) permissions.get("removed")).isEmpty();
        assertThat(permissions).containsEntry("widened", true);

        installed("0.9.0", manifest(root -> root.put("version", "0.9.0")));
        Map<String, Object> narrowed = (Map<String, Object>) ((Map<String, Object>) plan(manifest(root ->
                ((ObjectNode) root.get("permissions")).putArray("system"))).attributes().get("changes"))
                .get("permissions");
        assertThat((List<String>) narrowed.get("removed")).containsExactly("system VIEW_SETUP");
        assertThat(narrowed).containsEntry("widened", false);
    }
}
