package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.interceptor.PermissionGatedController;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.MetadataPromotionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Promotion workflow: create → approve (approver ≠ creator) → execute
 * (APPROVED only, async) → rollback (local targets, to the pre-promotion
 * target snapshot).
 *
 * <p>Lives on the {@code /api/promotions/**} static route and is gated
 * in-controller on {@code MANAGE_SANDBOXES}. Creator/approver/executor
 * identity always comes from the gateway-forwarded {@code X-User-Id} — never
 * from the request body.
 */
@RestController
@RequestMapping("/api/promotions")
public class PromotionController implements PermissionGatedController {

    private static final Logger log = LoggerFactory.getLogger(PromotionController.class);
    private static final String PERMISSION = "MANAGE_SANDBOXES";

    private final MetadataPromotionService promotionService;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public PromotionController(MetadataPromotionService promotionService,
                               CerbosPermissionResolver permissionResolver,
                               BootstrapRepository bootstrapRepository) {
        this.promotionService = promotionService;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    @GetMapping
    public ResponseEntity<?> listPromotions(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        String tenantId = requireTenant();
        requirePermission(request);

        List<Map<String, Object>> promotions = promotionService.listPromotions(tenantId, limit, offset);
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("promotions", promotions));
    }

    @GetMapping("/{promotionId}")
    public ResponseEntity<?> getPromotion(@PathVariable String promotionId, HttpServletRequest request) {
        String tenantId = requireTenant();
        requirePermission(request);

        var promotion = promotionService.getPromotion(promotionId, tenantId);
        if (promotion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(JsonApiResponseBuilder.single("promotions", extractId(promotion.get()), promotion.get()));
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> createPromotion(@RequestBody Map<String, Object> body,
                                             HttpServletRequest request,
                                             @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String tenantId = requireTenant();
        requirePermission(request);

        Map<String, Object> attrs = unwrapJsonApiBody(body);
        String sourceEnvId = firstNonBlank((String) attrs.get("sourceEnvId"),
                (String) attrs.get("sourceEnvironmentId"));
        String targetEnvId = firstNonBlank((String) attrs.get("targetEnvId"),
                (String) attrs.get("targetEnvironmentId"));
        String promotionType = (String) attrs.getOrDefault("promotionType", "FULL");
        String conflictMode = (String) attrs.getOrDefault("conflictMode", "SKIP");

        if (sourceEnvId == null || targetEnvId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Both sourceEnvId and targetEnvId are required"));
        }

        List<Map<String, Object>> items = null;
        if (attrs.get("items") instanceof List<?> list) {
            items = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    items.add((Map<String, Object>) m);
                }
            }
        }

        try {
            Map<String, Object> promotion = promotionService.createPromotion(
                    tenantId, sourceEnvId, targetEnvId, promotionType, conflictMode, items, userId);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(JsonApiResponseBuilder.single("promotions", extractId(promotion), promotion));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Cross-tenant diff of the sandbox against its parent tenant. */
    @GetMapping("/preview")
    public ResponseEntity<?> previewPromotion(@RequestParam String sourceEnvId,
                                              HttpServletRequest request) {
        String tenantId = requireTenant();
        requirePermission(request);

        try {
            return ResponseEntity.ok(promotionService.previewPromotion(tenantId, sourceEnvId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{promotionId}/approve")
    public ResponseEntity<?> approvePromotion(@PathVariable String promotionId,
                                              HttpServletRequest request,
                                              @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String tenantId = requireTenant();
        requirePermission(request);

        try {
            Map<String, Object> promotion = promotionService.approvePromotion(promotionId, tenantId, userId);
            return ResponseEntity.ok(JsonApiResponseBuilder.single("promotions", extractId(promotion), promotion));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{promotionId}/execute")
    public ResponseEntity<?> executePromotion(@PathVariable String promotionId,
                                              HttpServletRequest request,
                                              @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String tenantId = requireTenant();
        requirePermission(request);

        var promotion = promotionService.getPromotion(promotionId, tenantId);
        if (promotion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!"APPROVED".equals(promotion.get().get("status"))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Promotion must be APPROVED before execution"));
        }

        promotionService.executePromotion(promotionId, tenantId, userId);
        return ResponseEntity.accepted().body(Map.of("status", "executing", "promotionId", promotionId));
    }

    @PostMapping("/{promotionId}/rollback")
    public ResponseEntity<?> rollbackPromotion(@PathVariable String promotionId,
                                               HttpServletRequest request,
                                               @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String tenantId = requireTenant();
        requirePermission(request);

        try {
            Map<String, Object> promotion = promotionService.rollbackPromotion(promotionId, tenantId, userId);
            return ResponseEntity.ok(JsonApiResponseBuilder.single("promotions", extractId(promotion), promotion));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{promotionId}/items")
    public ResponseEntity<?> getPromotionItems(@PathVariable String promotionId, HttpServletRequest request) {
        String tenantId = requireTenant();
        requirePermission(request);

        // Verify promotion belongs to tenant
        var promotion = promotionService.getPromotion(promotionId, tenantId);
        if (promotion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> items = promotionService.getPromotionItems(promotionId);
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("promotion-items", items));
    }

    private String requireTenant() {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tenant context");
        }
        return tenantId;
    }

    private void requirePermission(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, PERMISSION + " permission required");
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private String extractId(Map<String, Object> data) {
        Object id = data.get("id");
        return id != null ? id.toString() : "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapJsonApiBody(Map<String, Object> body) {
        Object dataObj = body.get("data");
        if (dataObj instanceof Map<?, ?> data) {
            Object attrObj = data.get("attributes");
            if (attrObj instanceof Map<?, ?> attributes) {
                return new LinkedHashMap<>((Map<String, Object>) attributes);
            }
        }
        return body;
    }
}
