package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.events.RecordEventPublisher;
import io.kelta.worker.cache.WorkerCacheManager;
import io.kelta.worker.interceptor.PermissionGatedController;
import io.kelta.worker.listener.SystemFeatureEventPublisher;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.GovernorLimitsRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.TenantTierQuotas;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import static java.util.Collections.emptyList;

/**
 * REST controller for the Governor Limits page.
 *
 * <p>Serves {@code GET} and {@code PUT} at {@code /api/governor-limits}.
 * Spring MVC's exact-path matching gives this controller priority over
 * {@code DynamicCollectionRouter}'s {@code {collectionName}} path variable.
 *
 * <p>Returns JSON:API format consistent with other collection endpoints.
 *
 * <p>{@code /api/governor-limits/**} is a static gateway route, which carries only the blanket
 * {@code API_ACCESS} check, so the writes are gated here on {@code MANAGE_TENANTS} — the
 * platform-admin grant (provisioned tenants' administrators do not hold it). A tenant's tier and
 * limits are the platform operator's to set: without this gate any API user could set their own
 * tenant to {@code UNLIMITED}. Reads stay at {@code API_ACCESS}; the page shows a tenant its own
 * limits and usage.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/governor-limits")
public class GovernorLimitsController implements PermissionGatedController {

    private static final Logger log = LoggerFactory.getLogger(GovernorLimitsController.class);

    // Tier-based defaults live in TenantTierQuotas; this controller resolves
    // them per request via the tenant's edition. Hardcoded fall-throughs below
    // are only used when a customer override is mid-write and the tier lookup
    // already happened.

    private static final String DAILY_KEY_PREFIX = "api-calls-daily:";
    private static final String AI_TOKEN_KEY_PREFIX = "ai-tokens-monthly:";
    static final String WRITE_PERMISSION = "MANAGE_TENANTS";

    /** Reading limits and usage — the governor-limits setup page's gate. */
    static final String READ_PERMISSION = "VIEW_SETUP";

    private final GovernorLimitsRepository repository;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final RecordEventPublisher recordEventPublisher;
    private final WorkerCacheManager cacheManager;
    private final SystemFeatureEventPublisher featureEventPublisher;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public GovernorLimitsController(GovernorLimitsRepository repository, ObjectMapper objectMapper,
                                     StringRedisTemplate redisTemplate,
                                     RecordEventPublisher recordEventPublisher,
                                     WorkerCacheManager cacheManager,
                                     SystemFeatureEventPublisher featureEventPublisher,
                                     CerbosPermissionResolver permissionResolver,
                                     BootstrapRepository bootstrapRepository) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.recordEventPublisher = recordEventPublisher;
        this.cacheManager = cacheManager;
        this.featureEventPublisher = featureEventPublisher;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    /**
     * Gates tier and limit writes on {@value #WRITE_PERMISSION}. Fail-closed: no resolvable
     * profile is rejected. Runs before body validation so a caller without the grant learns
     * nothing about the payload.
     */
    private void requireWritePermission(HttpServletRequest request) {
        requirePermission(request, WRITE_PERMISSION);
    }

    private void requirePermission(HttpServletRequest request, String permission) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> permission.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, permission + " permission required");
        }
    }

    /**
     * Returns the governor limits status for the current tenant.
     *
     * <p>Includes the configured limits and current usage metrics
     * (user count, collection count, daily API call count from Redis).
     *
     * <p>Gated on {@value #READ_PERMISSION}, the setup page's own gate: the usage counts are tenant
     * administration data, not something every API user needs.
     *
     * @param tenantId the tenant ID from the gateway's X-Tenant-ID header
     * @return governor limits status with limits and usage metrics
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getStatus(
            @RequestHeader("X-Tenant-ID") String tenantId,
            HttpServletRequest request) {
        requirePermission(request, READ_PERMISSION);
        return status(tenantId);
    }

    /** The status document; the writes return it too, after their own gate. */
    private ResponseEntity<Map<String, Object>> status(String tenantId) {
        log.debug("GET governor-limits for tenant {}", tenantId);

        TenantLimitsView view = loadTenantLimits(tenantId);
        Map<String, Object> limits = view.limits();
        TenantTierQuotas.Tier tier = view.tier();

        // Count current usage
        int usersUsed = repository.countActiveUsers(tenantId);
        int collectionsUsed = repository.countActiveCollections(tenantId);

        // Read daily API call count from Redis (tracked by gateway's RateLimitFilter)
        int apiCallsUsed = getDailyApiCallCount(tenantId);

        // Read AI token usage from Redis (tracked by kelta-ai service)
        long aiTokensUsed = getMonthlyAiTokenCount(tenantId);

        // Calculate storage usage from file_attachment table
        long storageUsedBytes = repository.sumStorageBytes(tenantId);

        // Build attributes
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tier", tier.name());
        attributes.put("limits", limits);
        attributes.put("apiCallsUsed", apiCallsUsed);
        attributes.put("apiCallsLimit", getNumberOrTierDefault(limits, TenantTierQuotas.KEY_API_CALLS_PER_DAY, tier));
        attributes.put("usersUsed", usersUsed);
        attributes.put("usersLimit", getNumberOrTierDefault(limits, TenantTierQuotas.KEY_MAX_USERS, tier));
        attributes.put("collectionsUsed", collectionsUsed);
        attributes.put("collectionsLimit", getNumberOrTierDefault(limits, TenantTierQuotas.KEY_MAX_COLLECTIONS, tier));
        attributes.put("storageUsedBytes", storageUsedBytes);
        attributes.put("storageGbLimit", getNumberOrTierDefault(limits, TenantTierQuotas.KEY_STORAGE_GB, tier));
        attributes.put("aiTokensUsed", aiTokensUsed);
        attributes.put("aiTokensLimit", getNumberOrTierDefault(limits, TenantTierQuotas.KEY_AI_TOKENS_PER_MONTH, tier));
        attributes.put("aiEnabled", getBooleanOrTierDefault(limits, TenantTierQuotas.KEY_AI_ENABLED, tier));

        log.info("Returning governor-limits for tenant {} (tier {}): {} users, {} collections",
                tenantId, tier, usersUsed, collectionsUsed);

        return ResponseEntity.ok(JsonApiResponseBuilder.single("governor-limits", tenantId, attributes));
    }

    /** Result of resolving tenant tier + effective quotas (defaults merged with overrides). */
    public record TenantLimitsView(TenantTierQuotas.Tier tier, Map<String, Object> limits) {}

    /**
     * Updates the tenant's tier (edition). Triggers a cache eviction so the
     * new tier defaults take effect on the next governor-limits read. Per-tenant
     * overrides in {@code tenant.limits} JSONB still win key-by-key over the
     * new tier defaults.
     *
     * @param tenantId the tenant ID from the gateway's X-Tenant-ID header
     * @param body     {@code {"tier": "FREE" | "PROFESSIONAL" | "ENTERPRISE" | "UNLIMITED"}}
     */
    @PutMapping("/tier")
    public ResponseEntity<Map<String, Object>> updateTier(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requireWritePermission(request);
        Object tierObj = body.get("tier");
        if (!(tierObj instanceof String tierStr) || tierStr.isBlank()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Missing tier",
                            "Body must include {\"tier\": \"FREE|PROFESSIONAL|ENTERPRISE|UNLIMITED\"}"));
        }
        String normalized = tierStr.trim().toUpperCase();
        TenantTierQuotas.Tier validated;
        try {
            validated = TenantTierQuotas.Tier.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Invalid tier",
                            "Tier must be one of FREE, PROFESSIONAL, ENTERPRISE, UNLIMITED"));
        }

        int updated = repository.updateTenantEdition(tenantId, validated.name());
        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }
        cacheManager.evictTenantLimits(tenantId);
        featureEventPublisher.publishUpdated(tenantId, "limits");

        log.info("Updated tenant {} tier to {}", tenantId, validated);
        return status(tenantId);
    }

    /**
     * Updates the governor limits for the current tenant.
     *
     * @param tenantId the tenant ID from the gateway's X-Tenant-ID header
     * @param body     the updated limits
     * @return the updated governor limits status
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateLimits(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requireWritePermission(request);
        log.debug("PUT governor-limits for tenant {}: {}", tenantId, body);

        try {
            String limitsJson = objectMapper.writeValueAsString(body);
            repository.updateTenantLimits(tenantId, limitsJson);
            cacheManager.evictTenantLimits(tenantId);

            log.info("Updated governor-limits for tenant {}", tenantId);

            // Publish a record change event so the gateway refreshes its cache
            publishTenantChangedEvent(tenantId, body);
            // Broadcast a feature change so all pods evict their tenant limits caches
            featureEventPublisher.publishUpdated(tenantId, "limits");

            // Return the updated status (re-read to confirm)
            return status(tenantId);
        } catch (Exception e) {
            log.error("Failed to update governor-limits for tenant {}: {}", tenantId, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Failed to update governor limits", e.getMessage()));
        }
    }

    // =========================================================================
    // Event publishing
    // =========================================================================

    /**
     * Publishes a record change event for the tenant so the gateway
     * can refresh its governor limit cache.
     */
    private void publishTenantChangedEvent(String tenantId, Map<String, Object> newLimits) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("limits", newLimits);

            RecordChangedPayload payload = RecordChangedPayload.updated(
                    "tenants", tenantId, data, null, List.of("limits"));

            PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                    "record.updated", tenantId, null, payload);

            recordEventPublisher.publish(event);
        } catch (Exception e) {
            log.warn("Failed to publish tenant change event for governor limits update (tenant {}): {}",
                    tenantId, e.getMessage());
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    TenantLimitsView loadTenantLimits(String tenantId) {
        Optional<GovernorLimitsRepository.EditionAndLimits> rowOpt = repository.findEditionAndLimits(tenantId);
        TenantTierQuotas.Tier tier;
        Object limitsObj;
        if (rowOpt.isEmpty()) {
            tier = TenantTierQuotas.Tier.PROFESSIONAL;
            limitsObj = null;
        } else {
            tier = TenantTierQuotas.Tier.fromEdition(rowOpt.get().edition());
            limitsObj = rowOpt.get().limits();
        }

        // Check cache; cached map already has tier defaults merged with overrides.
        Optional<Map<String, Object>> cached = cacheManager.getTenantLimits(tenantId);
        if (cached.isPresent()) {
            return new TenantLimitsView(tier, cached.get());
        }

        Map<String, Object> parsedOverrides = parseLimits(tenantId, limitsObj);
        Map<String, Object> merged = TenantTierQuotas.mergeOverrides(tier, parsedOverrides);
        cacheManager.putTenantLimits(tenantId, merged);
        return new TenantLimitsView(tier, merged);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseLimits(String tenantId, Object limitsObj) {
        if (limitsObj == null) {
            return Map.of();
        }
        if (limitsObj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        String limitsStr = limitsObj instanceof String s ? s : limitsObj.toString();
        if (limitsStr == null || limitsStr.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(limitsStr,
                    objectMapper.getTypeFactory().constructMapType(
                            HashMap.class, String.class, Object.class));
        } catch (Exception e) {
            log.warn("Failed to parse limits JSON for tenant {}: {}", tenantId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Reads today's API call count from Redis.
     *
     * <p>The gateway's {@code RateLimitFilter} increments a daily counter
     * in Redis with key {@code api-calls-daily:<tenantId>:<yyyy-MM-dd>}.
     *
     * @param tenantId the tenant ID
     * @return the number of API calls today, or 0 if unavailable
     */
    private int getDailyApiCallCount(String tenantId) {
        try {
            String today = LocalDate.now(ZoneOffset.UTC).toString();
            String key = DAILY_KEY_PREFIX + tenantId + ":" + today;
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                return Integer.parseInt(value);
            }
        } catch (Exception e) {
            log.warn("Failed to read daily API call count from Redis for tenant {}: {}",
                    tenantId, e.getMessage());
        }
        return 0;
    }

    /**
     * Reads the current month's AI token usage from Redis.
     *
     * <p>The kelta-ai service increments a monthly counter in Redis
     * with key {@code ai-tokens-monthly:<tenantId>:<yyyy-MM>}.
     */
    private long getMonthlyAiTokenCount(String tenantId) {
        try {
            String yearMonth = java.time.YearMonth.now().toString();
            String key = AI_TOKEN_KEY_PREFIX + tenantId + ":" + yearMonth;
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                return Long.parseLong(value);
            }
        } catch (Exception e) {
            log.warn("Failed to read monthly AI token count from Redis for tenant {}: {}",
                    tenantId, e.getMessage());
        }
        return 0L;
    }

    private Object getNumberOrTierDefault(Map<String, Object> map, String key, TenantTierQuotas.Tier tier) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return value;
        }
        return TenantTierQuotas.defaultsFor(tier).get(key);
    }

    private boolean getBooleanOrTierDefault(Map<String, Object> map, String key, TenantTierQuotas.Tier tier) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        Object tierDefault = TenantTierQuotas.defaultsFor(tier).get(key);
        return tierDefault instanceof Boolean b && b;
    }
}
