package io.kelta.worker.service;

import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.worker.cache.WorkerCacheManager;
import io.kelta.worker.repository.BootstrapRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.Base64;

/**
 * Synchronizes Cerbos policies from profile data.
 *
 * <p>When profiles change, this service:
 * <ol>
 *   <li>Loads all profiles and their permissions for the tenant</li>
 *   <li>Generates Cerbos policy JSON via {@link CerbosPolicyGenerator}</li>
 *   <li>Pushes policies to Cerbos Admin API</li>
 * </ol>
 */
@Service
public class CerbosPolicySyncService {

    private static final Logger log = LoggerFactory.getLogger(CerbosPolicySyncService.class);
    /** Store id Cerbos assigns the unscoped {@code collection} resource policy. */
    static final String BASE_COLLECTION_POLICY_ID = "resource.collection.vdefault";

    private static final String SUBJECT_PREFIX = "kelta.cerbos.policies.changed.";

    private final JdbcTemplate jdbcTemplate;
    private final BootstrapRepository bootstrapRepository;
    private final CerbosPolicyGenerator policyGenerator;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final PlatformEventPublisher eventPublisher;
    private final WorkerCacheManager cacheManager;
    private final String cerbosAdminUrl;
    private final String cerbosAdminAuth;

    public CerbosPolicySyncService(
            JdbcTemplate jdbcTemplate,
            BootstrapRepository bootstrapRepository,
            CerbosPolicyGenerator policyGenerator,
            ObjectMapper objectMapper,
            PlatformEventPublisher eventPublisher,
            WorkerCacheManager cacheManager,
            @Value("${kelta.worker.cerbos.host:cerbos.emf.svc.cluster.local}") String cerbosHost,
            @Value("${kelta.worker.cerbos.http-port:3592}") int cerbosHttpPort,
            @Value("${kelta.worker.cerbos.admin-username:cerbos}") String adminUsername,
            @Value("${kelta.worker.cerbos.admin-password:cerbosAdmin2026}") String adminPassword) {
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrapRepository = bootstrapRepository;
        this.policyGenerator = policyGenerator;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.cacheManager = cacheManager;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
        this.cerbosAdminUrl = "http://" + cerbosHost + ":" + cerbosHttpPort;
        this.cerbosAdminAuth = "Basic " + Base64.getEncoder()
                .encodeToString((adminUsername + ":" + adminPassword).getBytes());
    }

    /**
     * Syncs all Cerbos policies for a tenant.
     */
    public void syncTenant(String tenantId) {
        TenantContext.set(tenantId);
        try {
            log.info("Syncing Cerbos policies for tenant {}", tenantId);

            List<CerbosPolicyGenerator.ProfileData> profiles = loadProfilesForTenant(tenantId);
            if (profiles.isEmpty()) {
                log.info("No profiles found for tenant {} — skipping Cerbos policy sync", tenantId);
                return;
            }
            List<Map<String, Object>> activeCollections = bootstrapRepository.findActiveCollections();
            List<String> collectionIds = activeCollections.stream()
                    .map(row -> (String) row.get("id"))
                    .filter(Objects::nonNull)
                    .toList();
            // id -> API name, so the record policy CEL can key on the collection name the
            // worker record advice passes (the collection policy stays UUID-keyed for the
            // gateway). See generateRecordPolicy.
            Map<String, String> collectionIdToName = new LinkedHashMap<>();
            for (Map<String, Object> row : activeCollections) {
                String id = (String) row.get("id");
                String name = (String) row.get("name");
                if (id != null && name != null) {
                    collectionIdToName.put(id, name);
                }
            }
            List<CerbosPolicyGenerator.CustomRule> customRules = loadCustomRulesForTenant(tenantId);

            // Generate policies
            Map<String, Object> derivedRoles = policyGenerator.generateDerivedRoles(tenantId, profiles);
            Map<String, Object> systemPolicy = policyGenerator.generateSystemFeaturePolicy(tenantId, profiles);
            Map<String, Object> collectionPolicy = policyGenerator.generateCollectionPolicy(tenantId, profiles, collectionIds);
            Map<String, Object> fieldPolicy = policyGenerator.generateFieldPolicy(tenantId, profiles);
            Map<String, Object> recordPolicy = policyGenerator.generateRecordPolicy(
                    tenantId, profiles, collectionIds, customRules, collectionIdToName);

            // Push to Cerbos Admin API
            pushPolicy(derivedRoles);
            pushPolicy(systemPolicy);
            pushPolicy(collectionPolicy);
            pushPolicy(fieldPolicy);
            pushPolicy(recordPolicy);

            log.info("Cerbos policies synced for tenant {} ({} profiles, {} collections)",
                    tenantId, profiles.size(), collectionIds.size());

            // Evict cached permissions — profile permissions may have changed
            cacheManager.evictAllPermissions();

            publishPolicyChangedEvent(tenantId);
        } catch (Exception e) {
            log.error("Failed to sync Cerbos policies for tenant {}: {}", tenantId, e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Seeds base (unscoped) resource policies that Cerbos requires as ancestors
     * for scoped per-tenant policies. Without these, scoped policies fail to compile.
     */
    public void seedBasePolicies() {
        try {
            log.info("Seeding base (ancestor) Cerbos policies for resource kinds");
            for (String resource : List.of("system_feature", "collection", "field", "record")) {
                Map<String, Object> basePolicy = policyGenerator.generateBaseResourcePolicy(resource);
                pushPolicy(basePolicy);
            }
            log.info("Base Cerbos policies seeded successfully");
        } catch (Exception e) {
            log.error("Failed to seed base Cerbos policies: {}", e.getMessage(), e);
        }
    }

    /**
     * Syncs policies for all active tenants.
     */
    public void syncAllTenants() {
        List<Map<String, Object>> tenants = bootstrapRepository.findRoutableTenants();
        log.info("Syncing Cerbos policies for {} tenants", tenants.size());

        for (Map<String, Object> tenant : tenants) {
            String tenantId = (String) tenant.get("id");
            if (tenantId != null) {
                syncTenant(tenantId);
            }
        }
    }

    /**
     * Probes the Cerbos Admin API for the unscoped {@code collection} base policy, the
     * first thing {@link #seedBasePolicies()} writes. {@code false} means the store is
     * empty (fresh/restored Cerbos) or unreachable — either way the caller should seed.
     */
    public boolean basePoliciesPresent() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cerbosAdminUrl + "/admin/policies"))
                    .header("Authorization", cerbosAdminAuth)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Cerbos Admin API policy list returned {}: {}", response.statusCode(), response.body());
                return false;
            }
            for (tools.jackson.databind.JsonNode id : objectMapper.readTree(response.body()).path("policyIds")) {
                if (BASE_COLLECTION_POLICY_ID.equals(id.asText())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Cerbos Admin API policy list failed: {}", e.getMessage());
            return false;
        }
    }

    private void pushPolicy(Map<String, Object> policy) throws Exception {
        // Cerbos Admin API expects {"policies": [...]} wrapper
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("policies", List.of(policy));
        String json = objectMapper.writeValueAsString(wrapper);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cerbosAdminUrl + "/admin/policy"))
                .header("Content-Type", "application/json")
                .header("Authorization", cerbosAdminAuth)
                .timeout(java.time.Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.debug("Cerbos policy pushed successfully");
        } else {
            log.warn("Cerbos Admin API returned {}: {}", response.statusCode(), response.body());
        }
    }

    private List<CerbosPolicyGenerator.ProfileData> loadProfilesForTenant(String tenantId) {
        String sql = "SELECT id, name FROM profile WHERE tenant_id = ?";
        List<Map<String, Object>> profileRows = jdbcTemplate.queryForList(sql, tenantId);

        List<CerbosPolicyGenerator.ProfileData> profiles = new ArrayList<>();
        for (Map<String, Object> row : profileRows) {
            String profileId = (String) row.get("id");
            String name = (String) row.get("name");

            Map<String, Boolean> systemPerms = loadProfileSystemPerms(profileId);
            Map<String, Map<String, Boolean>> objectPerms = loadProfileObjectPerms(profileId);
            Map<String, Map<String, String>> fieldPerms = loadProfileFieldPerms(profileId);

            profiles.add(new CerbosPolicyGenerator.ProfileData(
                    profileId, name, systemPerms, objectPerms, fieldPerms));
        }

        return profiles;
    }

    private Map<String, Boolean> loadProfileSystemPerms(String profileId) {
        Map<String, Boolean> perms = new LinkedHashMap<>();
        for (Map<String, Object> row : bootstrapRepository.findProfileSystemPermissions(profileId)) {
            String name = (String) row.get("permission_name");
            Boolean granted = toBoolean(row.get("granted"));
            if (name != null && Boolean.TRUE.equals(granted)) {
                perms.put(name, true);
            }
        }
        return perms;
    }

    private Map<String, Map<String, Boolean>> loadProfileObjectPerms(String profileId) {
        Map<String, Map<String, Boolean>> perms = new LinkedHashMap<>();
        for (Map<String, Object> row : bootstrapRepository.findProfileObjectPermissions(profileId)) {
            String collectionId = (String) row.get("collection_id");
            if (collectionId == null) continue;

            Map<String, Boolean> objPerms = new LinkedHashMap<>();
            objPerms.put("canCreate", toBoolean(row.get("can_create")));
            objPerms.put("canRead", toBoolean(row.get("can_read")));
            objPerms.put("canEdit", toBoolean(row.get("can_edit")));
            objPerms.put("canDelete", toBoolean(row.get("can_delete")));
            perms.put(collectionId, objPerms);
        }
        return perms;
    }

    private Map<String, Map<String, String>> loadProfileFieldPerms(String profileId) {
        Map<String, Map<String, String>> perms = new LinkedHashMap<>();
        for (Map<String, Object> row : bootstrapRepository.findProfileFieldPermissions(profileId)) {
            String collectionId = (String) row.get("collection_id");
            String fieldId = (String) row.get("field_id");
            String visibility = (String) row.get("visibility");
            if (collectionId == null || fieldId == null || visibility == null) continue;

            perms.computeIfAbsent(collectionId, k -> new LinkedHashMap<>())
                    .put(fieldId, visibility);
        }
        return perms;
    }

    private List<CerbosPolicyGenerator.CustomRule> loadCustomRulesForTenant(String tenantId) {
        try {
            String sql = "SELECT id, profile_id, collection_id, action, effect, condition_json, enabled " +
                    "FROM profile_custom_rules WHERE tenant_id = ?";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tenantId);

            List<CerbosPolicyGenerator.CustomRule> rules = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String conditionJson = row.get("condition_json") != null
                        ? row.get("condition_json").toString() : null;
                String celExpression = extractCelExpression(conditionJson);

                rules.add(new CerbosPolicyGenerator.CustomRule(
                        (String) row.get("id"),
                        (String) row.get("profile_id"),
                        (String) row.get("collection_id"),
                        (String) row.get("action"),
                        (String) row.get("effect"),
                        celExpression,
                        toBoolean(row.get("enabled"))
                ));
            }
            return rules;
        } catch (Exception e) {
            // Table may not exist yet during migration
            log.debug("Could not load custom rules (table may not exist yet): {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private String extractCelExpression(String conditionJson) {
        if (conditionJson == null || conditionJson.isBlank()) return null;
        try {
            Map<String, Object> condition = objectMapper.readValue(conditionJson, Map.class);
            // CEL type: {expression: "..."}
            if (condition.containsKey("expression")) {
                return (String) condition.get("expression");
            }
            // Visual type: {field, operator, value} — convert to CEL
            String field = (String) condition.get("field");
            String operator = (String) condition.get("operator");
            Object value = condition.get("value");
            if (field != null && operator != null) {
                return convertVisualToCel(field, operator, value);
            }
        } catch (Exception e) {
            log.warn("Failed to parse condition JSON: {}", e.getMessage());
        }
        return null;
    }

    private String convertVisualToCel(String field, String operator, Object value) {
        String attrRef = "R.attr." + field;
        return switch (operator) {
            case "equals" -> {
                if ("$CURRENT_USER".equals(value)) yield attrRef + " == P.id";
                yield attrRef + " == \"" + value + "\"";
            }
            case "not_equals" -> attrRef + " != \"" + value + "\"";
            case "in" -> {
                if (value instanceof List<?> list) {
                    String items = list.stream()
                            .map(v -> "\"" + v + "\"")
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");
                    yield attrRef + " in [" + items + "]";
                }
                yield attrRef + " in " + value;
            }
            case "greater_than" -> "double(" + attrRef + ") > " + value;
            case "less_than" -> "double(" + attrRef + ") < " + value;
            case "contains" -> attrRef + ".contains(\"" + value + "\")";
            default -> attrRef + " == \"" + value + "\"";
        };
    }

    private void publishPolicyChangedEvent(String tenantId) {
        Map<String, String> payload = Map.of(
                "tenantId", tenantId,
                "syncedAt", Instant.now().toString()
        );
        PlatformEvent<Map<String, String>> event =
                EventFactory.createEvent("kelta.cerbos.policies.changed", payload);
        event.setTenantId(tenantId);
        String subject = SUBJECT_PREFIX + tenantId;
        log.debug("Publishing policy changed event for tenant {}", tenantId);
        eventPublisher.publish(subject, event);
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }
}
