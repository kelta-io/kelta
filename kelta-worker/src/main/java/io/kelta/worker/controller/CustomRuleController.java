package io.kelta.worker.controller;

import io.kelta.worker.service.CerbosPolicySyncService;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Admin API for managing custom ABAC rules on profiles.
 *
 * <p>Custom rules are stored in the {@code profile_custom_rules} table and
 * converted to CEL conditions in generated Cerbos policies.
 */
@RestController
@RequestMapping("/api/admin/profiles/{profileId}/custom-rules")
public class CustomRuleController {

    private static final String PERMISSION = "MANAGE_USERS";

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
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT * FROM profile_custom_rules WHERE profile_id = ? ORDER BY created_at",
                profileId);
        return ResponseEntity.ok(rules);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRule(
            @PathVariable String profileId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        permissionGuard.require(request, PERMISSION);
        String id = UUID.randomUUID().toString();
        String tenantId = body.get("tenantId") != null
                ? (String) body.get("tenantId")
                : request.getHeader("X-Cerbos-Scope");
        String collectionId = (String) body.get("collectionId");
        String action = (String) body.get("action");
        String effect = (String) body.get("effect");
        String conditionType = (String) body.get("conditionType");
        Object conditionJson = body.get("condition");

        String conditionStr;
        try {
            conditionStr = objectMapper.writeValueAsString(conditionJson);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        Boolean enabled = body.get("enabled") != null ? (Boolean) body.get("enabled") : true;

        jdbcTemplate.update("""
                INSERT INTO profile_custom_rules
                    (id, tenant_id, profile_id, collection_id, action, effect, condition_type, condition_json, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                id, tenantId, profileId, collectionId, action, effect, conditionType, conditionStr, enabled);

        syncService.syncTenant(tenantId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("profileId", profileId);
        response.put("tenantId", tenantId);
        response.put("collectionId", collectionId);
        response.put("action", action);
        response.put("effect", effect);
        response.put("conditionType", conditionType);
        response.put("condition", conditionJson);
        response.put("enabled", enabled);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<Map<String, Object>> updateRule(
            HttpServletRequest request,
            @PathVariable String profileId,
            @PathVariable String ruleId,
            @RequestBody Map<String, Object> body) {
        permissionGuard.require(request, PERMISSION);
        String action = (String) body.get("action");
        String effect = (String) body.get("effect");
        String conditionType = (String) body.get("conditionType");
        Object conditionJson = body.get("condition");
        Boolean enabled = body.get("enabled") != null ? (Boolean) body.get("enabled") : true;

        String conditionStr;
        try {
            conditionStr = objectMapper.writeValueAsString(conditionJson);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        int updated = jdbcTemplate.update("""
                UPDATE profile_custom_rules
                SET action = ?, effect = ?, condition_type = ?, condition_json = ?::jsonb,
                    enabled = ?, updated_at = NOW()
                WHERE id = ? AND profile_id = ?
                """,
                action, effect, conditionType, conditionStr, enabled, ruleId, profileId);

        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }

        // Look up tenant ID from the rule
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT tenant_id FROM profile_custom_rules WHERE id = ?", ruleId);
        if (!rows.isEmpty()) {
            syncService.syncTenant((String) rows.get(0).get("tenant_id"));
        }

        body.put("id", ruleId);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> deleteRule(
            HttpServletRequest request,
            @PathVariable String profileId,
            @PathVariable String ruleId) {
        permissionGuard.require(request, PERMISSION);
        // Look up tenant ID before deleting
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT tenant_id FROM profile_custom_rules WHERE id = ? AND profile_id = ?",
                ruleId, profileId);

        int deleted = jdbcTemplate.update(
                "DELETE FROM profile_custom_rules WHERE id = ? AND profile_id = ?",
                ruleId, profileId);

        if (deleted == 0) {
            return ResponseEntity.notFound().build();
        }

        if (!rows.isEmpty()) {
            syncService.syncTenant((String) rows.get(0).get("tenant_id"));
        }

        return ResponseEntity.noContent().build();
    }
}
