package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.service.CerbosPolicySyncService;
import io.kelta.worker.service.CustomRuleCel;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Admin API for managing custom ABAC rules on profiles.
 *
 * <p>Custom rules are stored in the {@code profile_custom_rules} table and
 * converted to CEL conditions in generated Cerbos policies.
 *
 * <p>Every handler works in the tenant the request is bound to ({@link TenantContext}, set by
 * the worker's tenant filter from the gateway's headers): rows are written with that tenant id and
 * every statement filters on it. A {@code tenantId} in a request body is ignored.
 *
 * <p>Create and update validate the rule before anything is stored, and answer
 * {@code 400 VALIDATION_FAILED} with a {@code source.pointer} into the request body:
 * <ul>
 *   <li>{@code action} — {@code create}, {@code read}, {@code edit} or {@code delete};</li>
 *   <li>{@code effect} — {@code allow} or {@code deny} (any case, stored lower-case);</li>
 *   <li>{@code conditionType} — {@code visual} or {@code cel} (stored lower-case);</li>
 *   <li>{@code condition} — must compile with {@link CustomRuleCel#compile}: a known operator, an
 *       identifier field name and a value of the right shape, or CEL that parses;</li>
 *   <li>{@code enabled} — a boolean, default {@code true}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/profiles/{profileId}/custom-rules")
public class CustomRuleController {

    private static final String PERMISSION = "MANAGE_USERS";

    /** Record actions a custom rule can target — the actions the record policy checks. */
    static final List<String> ACTIONS = List.of("create", "read", "edit", "delete");

    private static final Logger log = LoggerFactory.getLogger(CustomRuleController.class);

    private final JdbcTemplate jdbcTemplate;
    private final CerbosPolicySyncService syncService;
    private final ObjectMapper objectMapper;
    private final AdminPermissionGuard permissionGuard;

    public CustomRuleController(JdbcTemplate jdbcTemplate,
                                 CerbosPolicySyncService syncService,
                                 ObjectMapper objectMapper,
                                 AdminPermissionGuard permissionGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.syncService = syncService;
        this.objectMapper = objectMapper;
        this.permissionGuard = permissionGuard;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listRules(HttpServletRequest request, @PathVariable String profileId) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = requireTenant();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, tenant_id, profile_id, collection_id, action, effect, condition_type,
                       condition_json, enabled, created_at, updated_at
                FROM profile_custom_rules WHERE profile_id = ? AND tenant_id = ? ORDER BY created_at
                """,
                profileId, tenantId);
        return ResponseEntity.ok(rows.stream().map(this::toResponse).toList());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRule(
            @PathVariable String profileId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        permissionGuard.require(request, PERMISSION);
        // The bound tenant, never body.tenantId: the body cannot choose where the rule lands.
        String tenantId = requireTenant();

        List<Map<String, Object>> errors = new ArrayList<>();
        Object rawCollectionId = body.get("collectionId");
        String collectionId = rawCollectionId instanceof String s && !s.isBlank() ? s.trim() : null;
        if (collectionId == null) {
            errors.add(validationError("/collectionId", "collectionId is required"));
        }
        RuleFields fields = validate(body, errors);
        if (!errors.isEmpty()) {
            return badRequest(errors);
        }

        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO profile_custom_rules
                    (id, tenant_id, profile_id, collection_id, action, effect, condition_type, condition_json, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                id, tenantId, profileId, collectionId, fields.action(), fields.effect(), fields.conditionType(),
                fields.conditionJson(), fields.enabled());

        syncService.syncTenant(tenantId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("profileId", profileId);
        response.put("tenantId", tenantId);
        response.put("collectionId", collectionId);
        putFields(response, fields);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<Map<String, Object>> updateRule(
            HttpServletRequest request,
            @PathVariable String profileId,
            @PathVariable String ruleId,
            @RequestBody Map<String, Object> body) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = requireTenant();

        List<Map<String, Object>> errors = new ArrayList<>();
        RuleFields fields = validate(body, errors);
        if (!errors.isEmpty()) {
            return badRequest(errors);
        }

        int updated = jdbcTemplate.update("""
                UPDATE profile_custom_rules
                SET action = ?, effect = ?, condition_type = ?, condition_json = ?::jsonb,
                    enabled = ?, updated_at = NOW()
                WHERE id = ? AND profile_id = ? AND tenant_id = ?
                """,
                fields.action(), fields.effect(), fields.conditionType(), fields.conditionJson(), fields.enabled(),
                ruleId, profileId, tenantId);

        if (updated == 0) {
            return notFound(ruleId);
        }

        syncService.syncTenant(tenantId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", ruleId);
        response.put("profileId", profileId);
        response.put("tenantId", tenantId);
        putFields(response, fields);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Map<String, Object>> deleteRule(
            HttpServletRequest request,
            @PathVariable String profileId,
            @PathVariable String ruleId) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = requireTenant();

        int deleted = jdbcTemplate.update(
                "DELETE FROM profile_custom_rules WHERE id = ? AND profile_id = ? AND tenant_id = ?",
                ruleId, profileId, tenantId);

        if (deleted == 0) {
            return notFound(ruleId);
        }

        syncService.syncTenant(tenantId);

        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ helpers

    /** A validated rule body, in its stored form. */
    private record RuleFields(String action, String effect, String conditionType, Object condition,
                              String conditionJson, boolean enabled) {}

    /**
     * Validates the fields create and update share, adding one error per problem to {@code errors};
     * returns {@code null} when any was found.
     */
    private RuleFields validate(Map<String, Object> body, List<Map<String, Object>> errors) {
        int before = errors.size();

        String action = lowerCase(body.get("action"));
        if (action == null || !ACTIONS.contains(action)) {
            errors.add(validationError("/action", "action must be one of " + String.join(", ", ACTIONS)));
        }

        String effect = CustomRuleCel.parseEffect(body.get("effect"));
        if (effect == null) {
            errors.add(validationError("/effect", "effect must be 'allow' or 'deny'"));
        }

        String conditionType = lowerCase(body.get("conditionType"));
        Object condition = body.get("condition");
        if (!CustomRuleCel.TYPE_VISUAL.equals(conditionType) && !CustomRuleCel.TYPE_CEL.equals(conditionType)) {
            errors.add(validationError("/conditionType", "conditionType must be 'visual' or 'cel'"));
        } else {
            try {
                CustomRuleCel.compile(conditionType, condition);
            } catch (CustomRuleCel.InvalidConditionException e) {
                errors.add(validationError(e.pointer(), e.getMessage()));
            }
        }

        Object rawEnabled = body.get("enabled");
        boolean enabled = true;
        if (rawEnabled instanceof Boolean b) {
            enabled = b;
        } else if (rawEnabled != null) {
            errors.add(validationError("/enabled", "enabled must be true or false"));
        }

        if (errors.size() > before) {
            return null;
        }
        String conditionJson;
        try {
            conditionJson = objectMapper.writeValueAsString(condition);
        } catch (Exception e) {
            log.warn("Could not serialize custom rule condition: {}", e.getMessage());
            errors.add(validationError("/condition", "condition could not be stored"));
            return null;
        }
        return new RuleFields(action, effect, conditionType, condition, conditionJson, enabled);
    }

    private static void putFields(Map<String, Object> response, RuleFields fields) {
        response.put("action", fields.action());
        response.put("effect", fields.effect());
        response.put("conditionType", fields.conditionType());
        response.put("condition", fields.condition());
        response.put("enabled", fields.enabled());
    }

    /** A stored row in the shape the admin UI reads (camelCase, condition as an object). */
    private Map<String, Object> toResponse(Map<String, Object> row) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", row.get("id"));
        rule.put("profileId", row.get("profile_id"));
        rule.put("tenantId", row.get("tenant_id"));
        rule.put("collectionId", row.get("collection_id"));
        rule.put("action", row.get("action"));
        rule.put("effect", row.get("effect"));
        rule.put("conditionType", row.get("condition_type"));
        rule.put("condition", readCondition(row.get("condition_json")));
        rule.put("enabled", row.get("enabled"));
        rule.put("createdAt", row.get("created_at"));
        rule.put("updatedAt", row.get("updated_at"));
        return rule;
    }

    /** The jsonb column arrives as a driver object whose {@code toString()} is the JSON text. */
    private Object readCondition(Object stored) {
        if (stored == null || stored instanceof Map<?, ?>) {
            return stored;
        }
        try {
            return objectMapper.readValue(stored.toString(), Object.class);
        } catch (Exception e) {
            log.warn("Could not read a stored custom rule condition: {}", e.getMessage());
            return null;
        }
    }

    private static String lowerCase(Object value) {
        return value instanceof String s ? s.trim().toLowerCase(Locale.ROOT) : null;
    }

    private static String requireTenant() {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tenant context");
        }
        return tenantId;
    }

    private static Map<String, Object> validationError(String pointer, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", "400");
        error.put("code", "VALIDATION_FAILED");
        error.put("title", "Validation Error");
        error.put("detail", detail);
        error.put("source", Map.of("pointer", pointer));
        return error;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(List<Map<String, Object>> errors) {
        return ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String ruleId) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", "404");
        error.put("code", "NOT_FOUND");
        error.put("title", "Not Found");
        error.put("detail", "Custom rule " + ruleId + " not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("errors", List.of(error)));
    }
}
