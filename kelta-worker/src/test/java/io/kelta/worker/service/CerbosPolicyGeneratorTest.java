package io.kelta.worker.service;

import io.kelta.worker.service.CerbosPolicyGenerator.CustomRule;
import io.kelta.worker.service.CerbosPolicyGenerator.ProfileData;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CerbosPolicyGenerator")
class CerbosPolicyGeneratorTest {

    private CerbosPolicyGenerator generator;

    private static final String TENANT_ID = "tenant-abc";

    @BeforeEach
    void setUp() {
        generator = new CerbosPolicyGenerator(new ObjectMapper());
    }

    private ProfileData adminProfile() {
        return new ProfileData(
                "admin-profile",
                "System Admin",
                Map.of("VIEW_ALL_DATA", true, "MODIFY_ALL_DATA", true, "MANAGE_USERS", true),
                Map.of("col-1", Map.of("canCreate", true, "canRead", true, "canEdit", true, "canDelete", true)),
                Map.of()
        );
    }

    private ProfileData readOnlyProfile() {
        return new ProfileData(
                "readonly-profile",
                "Read Only",
                Map.of("VIEW_ALL_DATA", false, "MODIFY_ALL_DATA", false),
                Map.of("col-1", Map.of("canCreate", false, "canRead", true, "canEdit", false, "canDelete", false)),
                Map.of()
        );
    }

    @Nested
    @DisplayName("generateDerivedRoles")
    class DerivedRolesTests {

        @Test
        @DisplayName("should generate derived roles with correct API version")
        void generatesCorrectApiVersion() {
            Map<String, Object> policy = generator.generateDerivedRoles(TENANT_ID, List.of(adminProfile()));

            assertThat(policy.get("apiVersion")).isEqualTo("api.cerbos.dev/v1");
        }

        @Test
        @DisplayName("should create one role definition per profile")
        @SuppressWarnings("unchecked")
        void createsOneRolePerProfile() {
            Map<String, Object> policy = generator.generateDerivedRoles(
                    TENANT_ID, List.of(adminProfile(), readOnlyProfile()));

            Map<String, Object> derivedRoles = (Map<String, Object>) policy.get("derivedRoles");
            assertThat(derivedRoles.get("name")).isEqualTo("kelta_roles_" + TENANT_ID);

            List<Map<String, Object>> definitions = (List<Map<String, Object>>) derivedRoles.get("definitions");
            assertThat(definitions).hasSize(2);
            assertThat(definitions.get(0).get("name")).isEqualTo("profile_admin-profile");
            assertThat(definitions.get(1).get("name")).isEqualTo("profile_readonly-profile");
        }

        @Test
        @DisplayName("should set parent role to user")
        @SuppressWarnings("unchecked")
        void setsParentRoleToUser() {
            Map<String, Object> policy = generator.generateDerivedRoles(TENANT_ID, List.of(adminProfile()));

            Map<String, Object> derivedRoles = (Map<String, Object>) policy.get("derivedRoles");
            List<Map<String, Object>> definitions = (List<Map<String, Object>>) derivedRoles.get("definitions");
            assertThat(definitions.get(0).get("parentRoles")).isEqualTo(List.of("user"));
        }

        @Test
        @DisplayName("should include profile and tenant condition in CEL expression")
        @SuppressWarnings("unchecked")
        void includesConditionExpression() {
            Map<String, Object> policy = generator.generateDerivedRoles(TENANT_ID, List.of(adminProfile()));

            Map<String, Object> derivedRoles = (Map<String, Object>) policy.get("derivedRoles");
            List<Map<String, Object>> definitions = (List<Map<String, Object>>) derivedRoles.get("definitions");
            Map<String, Object> condition = (Map<String, Object>) definitions.get(0).get("condition");
            Map<String, Object> match = (Map<String, Object>) condition.get("match");
            String expr = (String) match.get("expr");

            assertThat(expr).contains("P.attr.profileId == \"admin-profile\"");
            assertThat(expr).contains("P.attr.tenantId == \"" + TENANT_ID + "\"");
        }

        @Test
        @DisplayName("should handle empty profiles list")
        @SuppressWarnings("unchecked")
        void handlesEmptyProfiles() {
            Map<String, Object> policy = generator.generateDerivedRoles(TENANT_ID, List.of());

            Map<String, Object> derivedRoles = (Map<String, Object>) policy.get("derivedRoles");
            List<Map<String, Object>> definitions = (List<Map<String, Object>>) derivedRoles.get("definitions");
            assertThat(definitions).isEmpty();
        }
    }

    @Nested
    @DisplayName("generateSystemFeaturePolicy")
    class SystemFeaturePolicyTests {

        @Test
        @DisplayName("should create rules for granted system permissions")
        @SuppressWarnings("unchecked")
        void createsRulesForGrantedPermissions() {
            Map<String, Object> policy = generator.generateSystemFeaturePolicy(
                    TENANT_ID, List.of(adminProfile()));

            Map<String, Object> resourcePolicy = (Map<String, Object>) policy.get("resourcePolicy");
            assertThat(resourcePolicy.get("resource")).isEqualTo("system_feature");
            assertThat(resourcePolicy.get("scope")).isEqualTo(TENANT_ID);

            List<Map<String, Object>> rules = (List<Map<String, Object>>) resourcePolicy.get("rules");
            assertThat(rules).isNotEmpty();

            // Admin has VIEW_ALL_DATA, MODIFY_ALL_DATA, MANAGE_USERS — all true
            List<String> ruleActions = rules.stream()
                    .flatMap(r -> ((List<String>) r.get("actions")).stream())
                    .toList();
            assertThat(ruleActions).contains("VIEW_ALL_DATA", "MODIFY_ALL_DATA", "MANAGE_USERS");
        }

        @Test
        @DisplayName("should not create rules for denied permissions")
        @SuppressWarnings("unchecked")
        void skipsdeniedPermissions() {
            Map<String, Object> policy = generator.generateSystemFeaturePolicy(
                    TENANT_ID, List.of(readOnlyProfile()));

            Map<String, Object> resourcePolicy = (Map<String, Object>) policy.get("resourcePolicy");
            List<Map<String, Object>> rules = (List<Map<String, Object>>) resourcePolicy.get("rules");
            // ReadOnly profile has no true system permissions
            assertThat(rules).isEmpty();
        }
    }

    @Nested
    @DisplayName("generateCollectionPolicy")
    class CollectionPolicyTests {

        @Test
        @DisplayName("emits one constants-gated rule per CRUD action, not one per collection")
        @SuppressWarnings("unchecked")
        void emitsOneRulePerAction() {
            Map<String, Object> policy = generator.generateCollectionPolicy(
                    TENANT_ID, List.of(readOnlyProfile()), List.of("col-1", "col-2", "col-3"));

            Map<String, Object> resourcePolicy = (Map<String, Object>) policy.get("resourcePolicy");
            assertThat(resourcePolicy.get("resource")).isEqualTo("collection");

            List<Map<String, Object>> rules = (List<Map<String, Object>>) resourcePolicy.get("rules");
            // 4 CRUD rules regardless of collection count; read-only profile has no overrides
            assertThat(rules).hasSize(4);
            assertThat(rules).allSatisfy(rule -> {
                assertThat(rule.get("roles")).isEqualTo(List.of("user"));
                assertThat(rule).doesNotContainKey("derivedRoles");
            });
            assertThat(rules.stream().map(r -> ((List<String>) r.get("actions")).get(0)))
                    .containsExactly("create", "read", "edit", "delete");
        }

        @Test
        @DisplayName("folds the permission matrix into constants.local.perms keyed by profile then action")
        @SuppressWarnings("unchecked")
        void foldsMatrixIntoConstants() {
            Map<String, Object> policy = generator.generateCollectionPolicy(
                    TENANT_ID, List.of(adminProfile(), readOnlyProfile()), List.of("col-1", "col-2"));

            Map<String, Object> constants = constants(policy);
            Map<String, Map<String, List<String>>> perms =
                    (Map<String, Map<String, List<String>>>) constants.get("perms");

            assertThat(perms).containsOnlyKeys("admin-profile", "readonly-profile");
            assertThat(perms.get("admin-profile")).containsOnlyKeys("create", "read", "edit", "delete");
            assertThat(perms.get("admin-profile").get("delete")).containsExactly("col-1");
            // Every profile entry carries all four action keys so the CEL lookup never errors
            assertThat(perms.get("readonly-profile").get("read")).containsExactly("col-1");
            assertThat(perms.get("readonly-profile").get("create")).isEmpty();
            assertThat(perms.get("readonly-profile").get("edit")).isEmpty();
            assertThat(perms.get("readonly-profile").get("delete")).isEmpty();
            // col-2 has no object permission row for either profile
            assertThat(perms.get("admin-profile").get("read")).doesNotContain("col-2");
        }

        @Test
        @DisplayName("a canRead-only grant on one collection allows read and denies every write")
        @SuppressWarnings("unchecked")
        void readOnlyGrantAllowsReadAndDeniesEveryWrite() {
            // Mirrors the "scope a PAT to read-only on one collection" recipe in
            // docs/authoring/api-access.md: a profile with canRead: true and every
            // other object-permission action false, granted on exactly one collection.
            Map<String, Object> policy = generator.generateCollectionPolicy(
                    TENANT_ID, List.of(readOnlyProfile()), List.of("col-1", "col-2"));

            Map<String, Map<String, List<String>>> perms =
                    (Map<String, Map<String, List<String>>>) constants(policy).get("perms");
            Map<String, List<String>> readOnlyPerms = perms.get("readonly-profile");

            assertThat(readOnlyPerms.get("read")).containsExactly("col-1");
            assertThat(readOnlyPerms.get("create")).isEmpty();
            assertThat(readOnlyPerms.get("edit")).isEmpty();
            assertThat(readOnlyPerms.get("delete")).isEmpty();
            // Never granted on col-2, which the profile has no object-permission row for.
            assertThat(readOnlyPerms.get("read")).doesNotContain("col-2");

            // This exact grant shape (canRead: true, nothing else, one collection) is what
            // CerbosGeneratedPolicyIT#editorGetsExactlyItsObjectPermissions asserts against a
            // real Cerbos PDP for editor-profile/col-b — read allowed, every write denied.
        }

        @Test
        @DisplayName("per-action CEL guards tenant, profile membership and collection membership")
        void perActionCelShape() {
            Map<String, Object> policy = generator.generateCollectionPolicy(
                    TENANT_ID, List.of(readOnlyProfile()), List.of("col-1"));

            assertThat(ruleExpr(policy, "edit", "EFFECT_ALLOW"))
                    .isEqualTo("P.attr.tenantId == \"" + TENANT_ID + "\""
                            + " && P.attr.profileId in C.perms"
                            + " && R.attr.collectionId in C.perms[P.attr.profileId].edit");
        }

        @Test
        @DisplayName("VIEW_ALL_DATA / MODIFY_ALL_DATA become profile-id lists with their own rules")
        @SuppressWarnings("unchecked")
        void addsSystemPermissionOverrides() {
            Map<String, Object> policy = generator.generateCollectionPolicy(
                    TENANT_ID, List.of(adminProfile(), readOnlyProfile()), List.of("col-1"));

            Map<String, Object> constants = constants(policy);
            assertThat((List<String>) constants.get("viewAll")).containsExactly("admin-profile");
            assertThat((List<String>) constants.get("modifyAll")).containsExactly("admin-profile");

            List<Map<String, Object>> rules = rules(policy);
            assertThat(rules).hasSize(6);
            Map<String, Object> viewAllRule = rules.get(4);
            assertThat(viewAllRule.get("actions")).isEqualTo(List.of("read"));
            assertThat(expr(viewAllRule)).isEqualTo(
                    "P.attr.tenantId == \"" + TENANT_ID + "\" && P.attr.profileId in C.viewAll");
            Map<String, Object> modifyAllRule = rules.get(5);
            assertThat(modifyAllRule.get("actions")).isEqualTo(List.of("create", "edit", "delete"));
            assertThat(expr(modifyAllRule)).isEqualTo(
                    "P.attr.tenantId == \"" + TENANT_ID + "\" && P.attr.profileId in C.modifyAll");
        }

        @Test
        @DisplayName("omits the CRUD rules when no profile holds any object permission")
        @SuppressWarnings("unchecked")
        void omitsCrudRulesWithoutObjectPermissions() {
            ProfileData viewer = new ProfileData("viewer", "Viewer",
                    Map.of("VIEW_ALL_DATA", true), Map.of(), Map.of());

            Map<String, Object> policy = generator.generateCollectionPolicy(
                    TENANT_ID, List.of(viewer), List.of("col-1"));

            assertThat((Map<String, Object>) constants(policy).get("perms")).isEmpty();
            List<Map<String, Object>> rules = rules(policy);
            assertThat(rules).hasSize(1);
            assertThat(rules.get(0).get("actions")).isEqualTo(List.of("read"));
        }

        @Test
        @DisplayName("emits no rules at all for a tenant with no grants")
        void noGrantsNoRules() {
            Map<String, Object> policy = generator.generateCollectionPolicy(
                    TENANT_ID, List.of(), List.of("col-1"));

            assertThat(rules(policy)).isEmpty();
        }
    }

    @Nested
    @DisplayName("generateFieldPolicy")
    class FieldPolicyTests {

        @Test
        @DisplayName("should create default allow rules for all users")
        @SuppressWarnings("unchecked")
        void createsDefaultAllowRules() {
            Map<String, Object> policy = generator.generateFieldPolicy(TENANT_ID, List.of());

            Map<String, Object> resourcePolicy = (Map<String, Object>) policy.get("resourcePolicy");
            List<Map<String, Object>> rules = (List<Map<String, Object>>) resourcePolicy.get("rules");

            assertThat(rules).hasSizeGreaterThanOrEqualTo(2);
            assertThat(rules.get(0).get("actions")).isEqualTo(List.of("read"));
            assertThat(rules.get(0).get("effect")).isEqualTo("EFFECT_ALLOW");
            assertThat(rules.get(1).get("actions")).isEqualTo(List.of("write"));
            assertThat(rules.get(1).get("effect")).isEqualTo("EFFECT_ALLOW");
        }

        @Test
        @DisplayName("should add deny rules for HIDDEN fields")
        @SuppressWarnings("unchecked")
        void addsDenyRulesForHiddenFields() {
            ProfileData profileWithHiddenField = new ProfileData(
                    "p1", "Profile 1",
                    Map.of(), Map.of(),
                    Map.of("col-1", Map.of("field-secret", "HIDDEN"))
            );

            Map<String, Object> policy = generator.generateFieldPolicy(
                    TENANT_ID, List.of(profileWithHiddenField));

            Map<String, Object> resourcePolicy = (Map<String, Object>) policy.get("resourcePolicy");
            List<Map<String, Object>> rules = (List<Map<String, Object>>) resourcePolicy.get("rules");

            boolean hasDenyRule = rules.stream().anyMatch(rule ->
                    "EFFECT_DENY".equals(rule.get("effect"))
                            && ((List<?>) rule.get("actions")).containsAll(List.of("read", "write")));
            assertThat(hasDenyRule).isTrue();
        }

        @Test
        @DisplayName("should add write-only deny for READ_ONLY fields")
        @SuppressWarnings("unchecked")
        void addsWriteDenyForReadOnlyFields() {
            ProfileData profileWithReadOnlyField = new ProfileData(
                    "p1", "Profile 1",
                    Map.of(), Map.of(),
                    Map.of("col-1", Map.of("field-locked", "READ_ONLY"))
            );

            Map<String, Object> policy = generator.generateFieldPolicy(
                    TENANT_ID, List.of(profileWithReadOnlyField));

            Map<String, Object> resourcePolicy = (Map<String, Object>) policy.get("resourcePolicy");
            List<Map<String, Object>> rules = (List<Map<String, Object>>) resourcePolicy.get("rules");

            boolean hasWriteDeny = rules.stream().anyMatch(rule ->
                    "EFFECT_DENY".equals(rule.get("effect"))
                            && ((List<?>) rule.get("actions")).equals(List.of("write")));
            assertThat(hasWriteDeny).isTrue();
        }

        @Test
        @DisplayName("should create a default unmask allow rule for all users")
        @SuppressWarnings("unchecked")
        void createsDefaultUnmaskAllowRule() {
            Map<String, Object> policy = generator.generateFieldPolicy(TENANT_ID, List.of());

            Map<String, Object> resourcePolicy = (Map<String, Object>) policy.get("resourcePolicy");
            List<Map<String, Object>> rules = (List<Map<String, Object>>) resourcePolicy.get("rules");

            assertThat(rules).hasSizeGreaterThanOrEqualTo(3);
            assertThat(rules.get(2).get("actions")).isEqualTo(List.of("unmask"));
            assertThat(rules.get(2).get("effect")).isEqualTo("EFFECT_ALLOW");
            assertThat(rules.get(2).get("roles")).isEqualTo(List.of("user"));
        }

        @Test
        @DisplayName("should add a single unmask+write deny rule for MASKED fields")
        @SuppressWarnings("unchecked")
        void addsUnmaskWriteDenyForMaskedFields() {
            ProfileData profileWithMaskedField = new ProfileData(
                    "p1", "Profile 1",
                    Map.of(), Map.of(),
                    Map.of("col-1", Map.of("field-ssn", "MASKED"))
            );

            Map<String, Object> policy = generator.generateFieldPolicy(
                    TENANT_ID, List.of(profileWithMaskedField));

            Map<String, Object> resourcePolicy = (Map<String, Object>) policy.get("resourcePolicy");
            List<Map<String, Object>> rules = (List<Map<String, Object>>) resourcePolicy.get("rules");

            List<Map<String, Object>> denyRules = rules.stream()
                    .filter(rule -> "EFFECT_DENY".equals(rule.get("effect")))
                    .toList();
            assertThat(denyRules).hasSize(1);

            Map<String, Object> denyRule = denyRules.get(0);
            assertThat(denyRule.get("actions")).isEqualTo(List.of("unmask", "write"));
            assertThat(denyRule.get("derivedRoles")).isEqualTo(List.of("profile_p1"));

            Map<String, Object> condition = (Map<String, Object>) denyRule.get("condition");
            Map<String, Object> match = (Map<String, Object>) condition.get("match");
            assertThat(match.get("expr")).isEqualTo(
                    "R.attr.collectionId == \"col-1\" && R.attr.fieldId == \"field-ssn\"");
        }

        @Test
        @DisplayName("MASKED must not deny read — the value renders redacted, not hidden")
        @SuppressWarnings("unchecked")
        void maskedDoesNotDenyRead() {
            ProfileData profileWithMaskedField = new ProfileData(
                    "p1", "Profile 1",
                    Map.of(), Map.of(),
                    Map.of("col-1", Map.of("field-ssn", "MASKED"))
            );

            Map<String, Object> policy = generator.generateFieldPolicy(
                    TENANT_ID, List.of(profileWithMaskedField));

            Map<String, Object> resourcePolicy = (Map<String, Object>) policy.get("resourcePolicy");
            List<Map<String, Object>> rules = (List<Map<String, Object>>) resourcePolicy.get("rules");

            boolean hasReadDeny = rules.stream().anyMatch(rule ->
                    "EFFECT_DENY".equals(rule.get("effect"))
                            && ((List<?>) rule.get("actions")).contains("read"));
            assertThat(hasReadDeny).isFalse();
        }
    }

    @Nested
    @DisplayName("generateRecordPolicy")
    class RecordPolicyTests {

        @Test
        @DisplayName("should include custom ABAC rules")
        @SuppressWarnings("unchecked")
        void includesCustomAbacRules() {
            CustomRule customRule = new CustomRule(
                    "rule-1", "admin-profile", "col-1",
                    "delete", "EFFECT_DENY",
                    "R.attr.status == \"locked\"", true);

            Map<String, Object> policy = generator.generateRecordPolicy(
                    TENANT_ID, List.of(adminProfile()), List.of("col-1"), List.of(customRule),
                    Map.of("col-1", "col-1"));

            Map<String, Object> resourcePolicy = (Map<String, Object>) policy.get("resourcePolicy");
            List<Map<String, Object>> rules = (List<Map<String, Object>>) resourcePolicy.get("rules");

            boolean hasCustomDeny = rules.stream().anyMatch(rule -> {
                if (!"EFFECT_DENY".equals(rule.get("effect"))) return false;
                List<String> actions = (List<String>) rule.get("actions");
                if (!actions.contains("delete")) return false;
                Map<String, Object> condition = (Map<String, Object>) rule.get("condition");
                if (condition == null) return false;
                Map<String, Object> match = (Map<String, Object>) condition.get("match");
                String expr = (String) match.get("expr");
                return expr.contains("R.attr.status == \"locked\"");
            });
            assertThat(hasCustomDeny).isTrue();
        }

        @Test
        @DisplayName("record CRUD CEL keys on the collection NAME, while collection policy stays UUID-keyed")
        @SuppressWarnings("unchecked")
        void recordCelUsesCollectionName() {
            // A profile that can read collection UUID "col-uuid-1" (name "contacts").
            ProfileData reader = new ProfileData(
                    "reader-profile", "Reader",
                    Map.of(),
                    Map.of("col-uuid-1", Map.of("canRead", true)),
                    Map.of());
            List<String> collectionIds = List.of("col-uuid-1");
            Map<String, String> idToName = Map.of("col-uuid-1", "contacts");

            Map<String, Object> recordPolicy = generator.generateRecordPolicy(
                    TENANT_ID, List.of(reader), collectionIds, List.of(), idToName);
            Map<String, Object> collectionPolicy = generator.generateCollectionPolicy(
                    TENANT_ID, List.of(reader), collectionIds);

            // Record perms list the NAME (what CerbosRecordAuthorizationAdvice passes).
            assertThat(readPerms(recordPolicy, "reader-profile")).containsExactly("contacts");
            // Collection perms still list the UUID (what the gateway passes).
            assertThat(readPerms(collectionPolicy, "reader-profile")).containsExactly("col-uuid-1");
        }

        @Test
        @DisplayName("record CEL falls back to the id when no name mapping exists")
        @SuppressWarnings("unchecked")
        void recordCelFallsBackToId() {
            ProfileData reader = new ProfileData(
                    "reader-profile", "Reader",
                    Map.of(), Map.of("col-uuid-9", Map.of("canRead", true)), Map.of());

            Map<String, Object> policy = generator.generateRecordPolicy(
                    TENANT_ID, List.of(reader), List.of("col-uuid-9"), List.of(), Map.of());

            assertThat(readPerms(policy, "reader-profile")).containsExactly("col-uuid-9");
        }

        @Test
        @DisplayName("custom ABAC rules keep the per-profile derived role and name-keyed collection guard")
        @SuppressWarnings("unchecked")
        void customRulesKeepDerivedRole() {
            CustomRule customRule = new CustomRule(
                    "rule-1", "admin-profile", "col-1",
                    "edit", "EFFECT_DENY", "R.attr.status == \"locked\"", true);

            Map<String, Object> policy = generator.generateRecordPolicy(
                    TENANT_ID, List.of(adminProfile()), List.of("col-1"), List.of(customRule),
                    Map.of("col-1", "accounts"));

            Map<String, Object> custom = rules(policy).get(rules(policy).size() - 1);
            assertThat(custom.get("derivedRoles")).isEqualTo(List.of("profile_admin-profile"));
            assertThat(expr(custom)).isEqualTo("R.attr.collectionId == \"accounts\" && R.attr.status == \"locked\"");
        }

        @SuppressWarnings("unchecked")
        private List<String> readPerms(Map<String, Object> policy, String profileId) {
            Map<String, Map<String, List<String>>> perms =
                    (Map<String, Map<String, List<String>>>) constants(policy).get("perms");
            return perms.get(profileId).get("read");
        }

        @Test
        @DisplayName("should skip disabled custom rules")
        @SuppressWarnings("unchecked")
        void skipsDisabledCustomRules() {
            CustomRule disabledRule = new CustomRule(
                    "rule-1", "admin-profile", "col-1",
                    "delete", "EFFECT_DENY", "true", false);

            Map<String, Object> policy = generator.generateRecordPolicy(
                    TENANT_ID, List.of(), List.of(), List.of(disabledRule), Map.of());

            Map<String, Object> resourcePolicy = (Map<String, Object>) policy.get("resourcePolicy");
            List<Map<String, Object>> rules = (List<Map<String, Object>>) resourcePolicy.get("rules");

            boolean hasCustomRule = rules.stream().anyMatch(rule ->
                    "EFFECT_DENY".equals(rule.get("effect")));
            assertThat(hasCustomRule).isFalse();
        }
    }

    @Nested
    @DisplayName("generateBaseResourcePolicy")
    class BaseResourcePolicyTests {

        @Test
        @DisplayName("should generate empty unscoped base policy")
        @SuppressWarnings("unchecked")
        void generatesEmptyBasePolicy() {
            Map<String, Object> policy = generator.generateBaseResourcePolicy("collection");

            assertThat(policy.get("apiVersion")).isEqualTo("api.cerbos.dev/v1");
            Map<String, Object> resourcePolicy = (Map<String, Object>) policy.get("resourcePolicy");
            assertThat(resourcePolicy.get("version")).isEqualTo("default");
            assertThat(resourcePolicy.get("resource")).isEqualTo("collection");
            assertThat(resourcePolicy.get("rules")).isEqualTo(List.of());
            assertThat(resourcePolicy).doesNotContainKey("scope");
        }
    }

    // -------------------------------------------------------------------------
    // Golden fixture — the harness IT (CerbosGeneratedPolicyIT) pushes these files
    // into a real Cerbos PDP and asserts an allow/deny matrix, so the generator output
    // must stay byte-for-byte in sync with src/test/resources/cerbos/golden/*.json.
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("golden fixture")
    class GoldenFixtureTests {

        static final String GOLDEN_TENANT = "tenant-golden";

        @Test
        @DisplayName("generated policies match the golden files consumed by CerbosGeneratedPolicyIT")
        void matchesGoldenFiles() throws Exception {
            List<ProfileData> profiles = goldenProfiles();
            List<String> collectionIds = List.of("col-a", "col-b", "col-c");
            Map<String, String> idToName = new java.util.LinkedHashMap<>();
            idToName.put("col-a", "accounts");
            idToName.put("col-b", "bookings");
            idToName.put("col-c", "contacts");
            List<CustomRule> customRules = List.of(
                    new CustomRule("rule-locked", "editor-profile", "col-a", "edit", "EFFECT_DENY",
                            "R.attr.status == \"locked\"", true),
                    new CustomRule("rule-disabled", "editor-profile", "col-a", "delete", "EFFECT_ALLOW",
                            "true", false));

            assertGolden("derived_roles.json", generator.generateDerivedRoles(GOLDEN_TENANT, profiles));
            assertGolden("collection.json",
                    generator.generateCollectionPolicy(GOLDEN_TENANT, profiles, collectionIds));
            assertGolden("record.json",
                    generator.generateRecordPolicy(GOLDEN_TENANT, profiles, collectionIds, customRules, idToName));
            assertGolden("base_collection.json", generator.generateBaseResourcePolicy("collection"));
            assertGolden("base_record.json", generator.generateBaseResourcePolicy("record"));
        }

        private List<ProfileData> goldenProfiles() {
            // admin: VIEW_ALL + MODIFY_ALL, full CRUD on col-a only via object perms
            ProfileData admin = new ProfileData("admin-profile", "Admin",
                    Map.of("VIEW_ALL_DATA", true, "MODIFY_ALL_DATA", true),
                    Map.of("col-a", Map.of("canCreate", true, "canRead", true, "canEdit", true, "canDelete", true)),
                    Map.of());
            // editor: read+edit col-a, read col-b, nothing on col-c, no system overrides
            Map<String, Map<String, Boolean>> editorPerms = new java.util.LinkedHashMap<>();
            editorPerms.put("col-a", Map.of("canCreate", false, "canRead", true, "canEdit", true, "canDelete", false));
            editorPerms.put("col-b", Map.of("canRead", true));
            ProfileData editor = new ProfileData("editor-profile", "Editor", Map.of(), editorPerms, Map.of());
            // viewer: VIEW_ALL only, no object perms
            ProfileData viewer = new ProfileData("viewer-profile", "Viewer",
                    Map.of("VIEW_ALL_DATA", true), Map.of(), Map.of());
            // nobody: no grants at all
            ProfileData nobody = new ProfileData("nobody-profile", "Nobody", Map.of(), Map.of(), Map.of());
            return List.of(admin, editor, viewer, nobody);
        }

        private void assertGolden(String file, Map<String, Object> policy) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            java.nio.file.Path path = java.nio.file.Path.of("src/test/resources/cerbos/golden", file);
            tools.jackson.databind.JsonNode actual = mapper.valueToTree(policy);
            tools.jackson.databind.JsonNode expected = mapper.readTree(java.nio.file.Files.readString(path));
            assertThat(actual)
                    .as("%s drifted from generator output — regenerate with:%n%s", path,
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual))
                    .isEqualTo(expected);
        }
    }

    // -------------------------------------------------------------------------
    // Shared accessors
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resourcePolicy(Map<String, Object> policy) {
        return (Map<String, Object>) policy.get("resourcePolicy");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rules(Map<String, Object> policy) {
        return (List<Map<String, Object>>) resourcePolicy(policy).get("rules");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> constants(Map<String, Object> policy) {
        Map<String, Object> constants = (Map<String, Object>) resourcePolicy(policy).get("constants");
        return (Map<String, Object>) constants.get("local");
    }

    @SuppressWarnings("unchecked")
    private static String expr(Map<String, Object> rule) {
        Map<String, Object> condition = (Map<String, Object>) rule.get("condition");
        Map<String, Object> match = (Map<String, Object>) condition.get("match");
        return (String) match.get("expr");
    }

    @SuppressWarnings("unchecked")
    private static String ruleExpr(Map<String, Object> policy, String action, String effect) {
        return rules(policy).stream()
                .filter(rule -> effect.equals(rule.get("effect")))
                .filter(rule -> ((List<String>) rule.get("actions")).contains(action))
                .map(CerbosPolicyGeneratorTest::expr)
                .findFirst()
                .orElseThrow();
    }
}
