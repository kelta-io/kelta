package io.kelta.worker.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Generates Cerbos policy JSON from profile permission data.
 *
 * <p>Produces per-tenant policies:
 * <ul>
 *   <li>Derived roles — one role per profile</li>
 *   <li>Resource policies for system_feature, collection, field, record</li>
 * </ul>
 */
@Component
public class CerbosPolicyGenerator {

    private static final Logger log = LoggerFactory.getLogger(CerbosPolicyGenerator.class);

    private static final List<String> CRUD_ACTIONS = List.of("create", "read", "edit", "delete");

    private final ObjectMapper objectMapper;

    public CerbosPolicyGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Generates the derived roles definition for a tenant.
     */
    public Map<String, Object> generateDerivedRoles(String tenantId, List<ProfileData> profiles) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("apiVersion", "api.cerbos.dev/v1");

        Map<String, Object> derivedRoles = new LinkedHashMap<>();
        derivedRoles.put("name", "kelta_roles_" + tenantId);

        List<Map<String, Object>> definitions = new ArrayList<>();
        for (ProfileData profile : profiles) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", "profile_" + profile.id());
            def.put("parentRoles", List.of("user"));

            Map<String, Object> condition = new LinkedHashMap<>();
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("expr", "P.attr.profileId == \"" + profile.id() + "\" && P.attr.tenantId == \"" + tenantId + "\"");
            condition.put("match", match);
            def.put("condition", condition);

            definitions.add(def);
        }

        derivedRoles.put("definitions", definitions);
        policy.put("derivedRoles", derivedRoles);

        return policy;
    }

    /**
     * Generates the system_feature resource policy for a tenant.
     */
    public Map<String, Object> generateSystemFeaturePolicy(String tenantId, List<ProfileData> profiles) {
        // Collect which profiles grant which system permissions
        Map<String, List<String>> permissionToRoles = new LinkedHashMap<>();
        for (ProfileData profile : profiles) {
            for (Map.Entry<String, Boolean> entry : profile.systemPermissions().entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    permissionToRoles.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                            .add("profile_" + profile.id());
                }
            }
        }

        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : permissionToRoles.entrySet()) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("actions", List.of(entry.getKey()));
            rule.put("effect", "EFFECT_ALLOW");
            rule.put("derivedRoles", entry.getValue());
            rules.add(rule);
        }

        return buildResourcePolicy("system_feature", tenantId, rules);
    }

    /**
     * Generates the collection resource policy for a tenant.
     *
     * <p><strong>Shape matters for latency.</strong> Cerbos evaluates the CEL condition of
     * every candidate rule on each check, and the cost is roughly linear in the number of
     * conditional rules (~0.3ms each). The previous shape emitted one rule per
     * {@code collection × action} guarded by a per-profile derived role, so a tenant with
     * 22 collections and 17 profiles paid ~105 CEL evaluations (~27ms) per check. This
     * shape instead folds the whole permission matrix into a policy {@code constants}
     * map ({@code profileId → action → [collectionId]}) and emits <em>one</em> rule per
     * action whose condition does a map/list lookup — 1-2 evaluations per check. The
     * {@code VIEW_ALL_DATA} / {@code MODIFY_ALL_DATA} overrides are lists of profile ids
     * in the same constants block. See {@code CerbosGeneratedPolicyIT} for the
     * real-PDP allow/deny matrix that pins the semantics.
     */
    public Map<String, Object> generateCollectionPolicy(String tenantId,
                                                          List<ProfileData> profiles,
                                                          List<String> collectionIds) {
        return buildCrudPolicy("collection", tenantId, profiles, collectionIds, null, List.of());
    }

    /**
     * Generates the field resource policy for a tenant.
     */
    public Map<String, Object> generateFieldPolicy(String tenantId, List<ProfileData> profiles) {
        List<Map<String, Object>> rules = new ArrayList<>();

        // Default: allow all reads and writes
        Map<String, Object> defaultReadRule = new LinkedHashMap<>();
        defaultReadRule.put("actions", List.of("read"));
        defaultReadRule.put("effect", "EFFECT_ALLOW");
        defaultReadRule.put("roles", List.of("user"));
        rules.add(defaultReadRule);

        Map<String, Object> defaultWriteRule = new LinkedHashMap<>();
        defaultWriteRule.put("actions", List.of("write"));
        defaultWriteRule.put("effect", "EFFECT_ALLOW");
        defaultWriteRule.put("roles", List.of("user"));
        rules.add(defaultWriteRule);

        // Default: allow unmask — only fields a profile marks MASKED deny it below.
        Map<String, Object> defaultUnmaskRule = new LinkedHashMap<>();
        defaultUnmaskRule.put("actions", List.of("unmask"));
        defaultUnmaskRule.put("effect", "EFFECT_ALLOW");
        defaultUnmaskRule.put("roles", List.of("user"));
        rules.add(defaultUnmaskRule);

        // Deny rules for HIDDEN, READ_ONLY and MASKED fields per profile
        for (ProfileData profile : profiles) {
            for (Map.Entry<String, Map<String, String>> collEntry : profile.fieldPermissions().entrySet()) {
                String collectionId = collEntry.getKey();
                for (Map.Entry<String, String> fieldEntry : collEntry.getValue().entrySet()) {
                    String fieldId = fieldEntry.getKey();
                    String visibility = fieldEntry.getValue();

                    String fieldExpr = "R.attr.collectionId == \"" + collectionId
                            + "\" && R.attr.fieldId == \"" + fieldId + "\"";

                    if ("HIDDEN".equals(visibility)) {
                        // Deny read and write
                        Map<String, Object> rule = new LinkedHashMap<>();
                        rule.put("actions", List.of("read", "write"));
                        rule.put("effect", "EFFECT_DENY");
                        rule.put("derivedRoles", List.of("profile_" + profile.id()));
                        Map<String, Object> condition = new LinkedHashMap<>();
                        condition.put("match", Map.of("expr", fieldExpr));
                        rule.put("condition", condition);
                        rules.add(rule);
                    } else if ("READ_ONLY".equals(visibility)) {
                        // Deny write only
                        Map<String, Object> rule = new LinkedHashMap<>();
                        rule.put("actions", List.of("write"));
                        rule.put("effect", "EFFECT_DENY");
                        rule.put("derivedRoles", List.of("profile_" + profile.id()));
                        Map<String, Object> condition = new LinkedHashMap<>();
                        condition.put("match", Map.of("expr", fieldExpr));
                        rule.put("condition", condition);
                        rules.add(rule);
                    } else if ("MASKED".equals(visibility)) {
                        // Read stays allowed (the value renders redacted); deny unmask
                        // so the advice masks, and deny write so the write advice strips
                        // an echoed placeholder before it can overwrite the stored value.
                        Map<String, Object> rule = new LinkedHashMap<>();
                        rule.put("actions", List.of("unmask", "write"));
                        rule.put("effect", "EFFECT_DENY");
                        rule.put("derivedRoles", List.of("profile_" + profile.id()));
                        Map<String, Object> condition = new LinkedHashMap<>();
                        condition.put("match", Map.of("expr", fieldExpr));
                        rule.put("condition", condition);
                        rules.add(rule);
                    }
                }
            }
        }

        return buildResourcePolicy("field", tenantId, rules);
    }

    /**
     * Generates the record resource policy for a tenant (base CRUD + custom ABAC rules).
     *
     * <p><strong>Record CEL keys on the collection NAME, not the UUID.</strong> The
     * worker's {@code CerbosRecordAuthorizationAdvice} sets {@code R.attr.collectionId}
     * from the request URL segment (the collection API name), and record-share widening
     * joins by collection name too — so the per-collection record rules must match on the
     * name. Object permissions are still <em>looked up</em> by the collection UUID (the id
     * stored in {@code profile_object_permission}); only the emitted CEL literal is the
     * name, resolved via {@code collectionIdToName}. This differs from
     * {@link #generateCollectionPolicy}, whose CEL stays UUID-keyed because the gateway's
     * route-level {@code checkObjectPermission} passes the collection UUID
     * ({@code route.getId()} = bootstrap {@code collection.id}). A map miss falls back to
     * the id (tolerant of any collection lacking a name entry).
     */
    public Map<String, Object> generateRecordPolicy(String tenantId,
                                                      List<ProfileData> profiles,
                                                      List<String> collectionIds,
                                                      List<CustomRule> customRules,
                                                      Map<String, String> collectionIdToName) {
        return buildCrudPolicy("record", tenantId, profiles, collectionIds, collectionIdToName, customRules);
    }

    /**
     * Shared body of the collection and record policies: a constants-driven CRUD matrix
     * (see {@link #generateCollectionPolicy}) plus, for records, the custom ABAC rules.
     * {@code collectionIdToName} is {@code null} for the UUID-keyed collection policy.
     */
    private Map<String, Object> buildCrudPolicy(String resource,
                                                String tenantId,
                                                List<ProfileData> profiles,
                                                List<String> collectionIds,
                                                Map<String, String> collectionIdToName,
                                                List<CustomRule> customRules) {
        // profileId -> action -> [collection refs]; every profile entry carries all four
        // action keys so the CEL lookup C.perms[P.attr.profileId].<action> never errors.
        Map<String, Map<String, List<String>>> perms = new LinkedHashMap<>();
        for (ProfileData profile : profiles) {
            Map<String, List<String>> byAction = new LinkedHashMap<>();
            for (String action : CRUD_ACTIONS) {
                byAction.put(action, new ArrayList<>());
            }
            boolean any = false;
            for (String collectionId : collectionIds) {
                Map<String, Boolean> objPerms = profile.objectPermissions().get(collectionId);
                if (objPerms == null) continue;
                String ref = resolveCollectionRef(collectionIdToName, collectionId);
                for (String action : CRUD_ACTIONS) {
                    if (isActionAllowed(objPerms, action)) {
                        byAction.get(action).add(ref);
                        any = true;
                    }
                }
            }
            if (any) {
                perms.put(profile.id(), byAction);
            }
        }

        // System permission overrides: VIEW_ALL_DATA -> read, MODIFY_ALL_DATA -> create/edit/delete
        List<String> viewAll = new ArrayList<>();
        List<String> modifyAll = new ArrayList<>();
        for (ProfileData profile : profiles) {
            if (Boolean.TRUE.equals(profile.systemPermissions().get("VIEW_ALL_DATA"))) {
                viewAll.add(profile.id());
            }
            if (Boolean.TRUE.equals(profile.systemPermissions().get("MODIFY_ALL_DATA"))) {
                modifyAll.add(profile.id());
            }
        }

        Map<String, Object> constants = new LinkedHashMap<>();
        constants.put("perms", perms);
        constants.put("viewAll", viewAll);
        constants.put("modifyAll", modifyAll);

        String tenantGuard = "P.attr.tenantId == \"" + tenantId + "\"";
        List<Map<String, Object>> rules = new ArrayList<>();

        if (!perms.isEmpty()) {
            for (String action : CRUD_ACTIONS) {
                rules.add(userRule(List.of(action), "EFFECT_ALLOW",
                        tenantGuard + " && P.attr.profileId in C.perms"
                                + " && R.attr.collectionId in C.perms[P.attr.profileId]." + action));
            }
        }
        if (!viewAll.isEmpty()) {
            rules.add(userRule(List.of("read"), "EFFECT_ALLOW",
                    tenantGuard + " && P.attr.profileId in C.viewAll"));
        }
        if (!modifyAll.isEmpty()) {
            rules.add(userRule(List.of("create", "edit", "delete"), "EFFECT_ALLOW",
                    tenantGuard + " && P.attr.profileId in C.modifyAll"));
        }

        // Custom ABAC rules from admin UI (record policy only). These keep the per-profile
        // derived role — there are few of them and admin-authored CEL may assume it.
        for (CustomRule customRule : customRules) {
            if (!customRule.enabled()) continue;

            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("actions", List.of(customRule.action()));
            rule.put("effect", "EFFECT_DENY".equalsIgnoreCase(customRule.effect())
                    ? "EFFECT_DENY" : "EFFECT_ALLOW");
            rule.put("derivedRoles", List.of("profile_" + customRule.profileId()));

            String collExpr = "R.attr.collectionId == \""
                    + resolveCollectionRef(collectionIdToName, customRule.collectionId()) + "\"";
            String celExpr = customRule.celExpression();
            String combinedExpr = celExpr != null && !celExpr.isBlank()
                    ? collExpr + " && " + celExpr
                    : collExpr;

            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put("match", Map.of("expr", combinedExpr));
            rule.put("condition", condition);

            rules.add(rule);
        }

        return buildResourcePolicy(resource, tenantId, rules, constants);
    }

    /** A rule matched on the static {@code user} role and gated purely by CEL. */
    private static Map<String, Object> userRule(List<String> actions, String effect, String expr) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("actions", actions);
        rule.put("effect", effect);
        rule.put("roles", List.of("user"));
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("match", Map.of("expr", expr));
        rule.put("condition", condition);
        return rule;
    }

    /**
     * Resolves a collection UUID to the API name used in record CEL, falling back to the
     * id itself when the map is null or has no entry (so an unmapped collection still emits
     * a well-formed, if id-keyed, rule rather than a {@code null} literal).
     */
    private static String resolveCollectionRef(Map<String, String> collectionIdToName, String collectionId) {
        return collectionIdToName != null
                ? collectionIdToName.getOrDefault(collectionId, collectionId)
                : collectionId;
    }

    private Map<String, Object> buildResourcePolicy(String resource, String tenantId,
                                                      List<Map<String, Object>> rules) {
        return buildResourcePolicy(resource, tenantId, rules, null);
    }

    private Map<String, Object> buildResourcePolicy(String resource, String tenantId,
                                                      List<Map<String, Object>> rules,
                                                      Map<String, Object> localConstants) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("apiVersion", "api.cerbos.dev/v1");

        Map<String, Object> resourcePolicy = new LinkedHashMap<>();
        resourcePolicy.put("version", "default");
        resourcePolicy.put("scope", tenantId);
        resourcePolicy.put("resource", resource);
        resourcePolicy.put("importDerivedRoles", List.of("kelta_roles_" + tenantId));
        if (localConstants != null) {
            resourcePolicy.put("constants", Map.of("local", localConstants));
        }
        resourcePolicy.put("rules", rules);

        policy.put("resourcePolicy", resourcePolicy);
        return policy;
    }

    private boolean isActionAllowed(Map<String, Boolean> objPerms, String action) {
        return switch (action) {
            case "create" -> Boolean.TRUE.equals(objPerms.get("canCreate"));
            case "read" -> Boolean.TRUE.equals(objPerms.get("canRead"));
            case "edit" -> Boolean.TRUE.equals(objPerms.get("canEdit"));
            case "delete" -> Boolean.TRUE.equals(objPerms.get("canDelete"));
            default -> false;
        };
    }

    /**
     * Generates a base (unscoped) resource policy for a given resource kind.
     *
     * <p>Cerbos requires an ancestor (unscoped) policy to exist before scoped
     * policies can compile. These base policies have no rules, effectively
     * defaulting to deny unless a scoped policy grants access.
     */
    public Map<String, Object> generateBaseResourcePolicy(String resource) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("apiVersion", "api.cerbos.dev/v1");

        Map<String, Object> resourcePolicy = new LinkedHashMap<>();
        resourcePolicy.put("version", "default");
        resourcePolicy.put("resource", resource);
        resourcePolicy.put("rules", List.of());

        policy.put("resourcePolicy", resourcePolicy);
        return policy;
    }

    // =========================================================================
    // Data records for policy generation input
    // =========================================================================

    public record ProfileData(
            String id,
            String name,
            Map<String, Boolean> systemPermissions,
            Map<String, Map<String, Boolean>> objectPermissions,
            Map<String, Map<String, String>> fieldPermissions
    ) {}

    public record CustomRule(
            String id,
            String profileId,
            String collectionId,
            String action,
            String effect,
            String celExpression,
            boolean enabled
    ) {}
}
