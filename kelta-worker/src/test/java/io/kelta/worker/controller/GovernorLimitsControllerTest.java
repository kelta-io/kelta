package io.kelta.worker.controller;

import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.events.RecordEventPublisher;
import io.kelta.worker.cache.WorkerCacheManager;
import io.kelta.worker.interceptor.PermissionGatedControllers;
import io.kelta.worker.listener.SystemFeatureEventPublisher;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.GovernorLimitsRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GovernorLimitsController}.
 *
 * <p>Verifies the governor-limits endpoint that returns tenant limits
 * and current usage metrics (users, collections, API calls) in JSON:API format.
 */
class GovernorLimitsControllerTest {

    private GovernorLimitsRepository repository;
    private ObjectMapper objectMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private RecordEventPublisher recordEventPublisher;
    private PlatformEventPublisher platformEventPublisher;
    private SystemFeatureEventPublisher featureEventPublisher;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private HttpServletRequest request;
    private GovernorLimitsController controller;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        repository = mock(GovernorLimitsRepository.class);
        objectMapper = new ObjectMapper();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        recordEventPublisher = mock(RecordEventPublisher.class);
        platformEventPublisher = mock(PlatformEventPublisher.class);
        featureEventPublisher = new SystemFeatureEventPublisher(platformEventPublisher);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Default: Redis returns null (0 API calls)
        when(valueOps.get(anyString())).thenReturn(null);
        WorkerCacheManager cacheManager = new WorkerCacheManager(new SimpleMeterRegistry());
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        request = mock(HttpServletRequest.class);
        controller = new GovernorLimitsController(repository, objectMapper, redisTemplate, recordEventPublisher,
                cacheManager, featureEventPublisher, permissionResolver, bootstrapRepository);
        // The setup page's reader: VIEW_SETUP for the status read, MANAGE_TENANTS for writes.
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "VIEW_SETUP", "granted", true),
                Map.of("permission_name", "MANAGE_TENANTS", "granted", true)));
    }

    private void grantPermission(String permissionName, boolean granted) {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", permissionName, "granted", granted)));
    }

    @Test
    @DisplayName("gates the read on VIEW_SETUP and writes on MANAGE_TENANTS (PermissionGatedController promise)")
    void everyHandlerIsPermissionGated() {
        grantPermission("API_ACCESS", true);

        PermissionGatedControllers.assertEveryHandlerForbidden(controller, request);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("the status read needs VIEW_SETUP — MANAGE_TENANTS alone does not admit it")
    void statusReadNeedsViewSetup() {
        grantPermission("MANAGE_TENANTS", true);

        assertThatThrownBy(() -> controller.getStatus("tenant-1", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("VIEW_SETUP");
        verifyNoInteractions(repository);
    }

    /** Extracts the attributes map from a JSON:API single-resource response body. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getAttributes(Map<String, Object> body) {
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return (Map<String, Object>) data.get("attributes");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(Map<String, Object> body) {
        return (Map<String, Object>) body.get("data");
    }

    // ==================== GET Tests ====================

    @Nested
    @DisplayName("GET /api/governor-limits")
    class GetStatusTests {

        @Test
        @DisplayName("Should return JSON:API envelope with type and id")
        void returnsJsonApiEnvelope() {
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", "{}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(0);
            when(repository.countActiveCollections("tenant-1")).thenReturn(0);

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            Map<String, Object> body = response.getBody();
            assertThat(body).containsKey("data");
            Map<String, Object> data = getData(body);
            assertThat(data.get("type")).isEqualTo("governor-limits");
            assertThat(data.get("id")).isEqualTo("tenant-1");
            assertThat(data).containsKey("attributes");
        }

        @Test
        @DisplayName("Should return default limits when tenant has empty limits")
        void returnsDefaultLimitsWhenEmpty() {
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", "{}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(5);
            when(repository.countActiveCollections("tenant-1")).thenReturn(12);

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            Map<String, Object> attrs = getAttributes(response.getBody());

            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) attrs.get("limits");
            assertThat(limits.get("apiCallsPerDay")).isEqualTo(100_000);
            assertThat(limits.get("storageGb")).isEqualTo(10);
            assertThat(limits.get("maxUsers")).isEqualTo(100);
            assertThat(limits.get("maxCollections")).isEqualTo(200);
            assertThat(limits.get("maxFieldsPerCollection")).isEqualTo(500);
            assertThat(limits.get("maxWorkflows")).isEqualTo(50);
            assertThat(limits.get("maxReports")).isEqualTo(200);

            assertThat(attrs.get("apiCallsUsed")).isEqualTo(0);
            assertThat(attrs.get("apiCallsLimit")).isEqualTo(100_000);
            assertThat(attrs.get("usersUsed")).isEqualTo(5);
            assertThat(attrs.get("usersLimit")).isEqualTo(100);
            assertThat(attrs.get("collectionsUsed")).isEqualTo(12);
            assertThat(attrs.get("collectionsLimit")).isEqualTo(200);
        }

        @Test
        @DisplayName("Should return configured limits from tenant")
        void returnsConfiguredLimits() {
            String limitsJson = "{\"apiCallsPerDay\":50000,\"storageGb\":50,\"maxUsers\":500,\"maxCollections\":100,\"maxFieldsPerCollection\":1000,\"maxWorkflows\":200,\"maxReports\":500}";
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", limitsJson)));
            when(repository.countActiveUsers("tenant-1")).thenReturn(25);
            when(repository.countActiveCollections("tenant-1")).thenReturn(8);

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            Map<String, Object> attrs = getAttributes(response.getBody());

            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) attrs.get("limits");
            assertThat(limits.get("apiCallsPerDay")).isEqualTo(50_000);
            assertThat(limits.get("storageGb")).isEqualTo(50);
            assertThat(limits.get("maxUsers")).isEqualTo(500);
            assertThat(limits.get("maxCollections")).isEqualTo(100);

            assertThat(attrs.get("usersUsed")).isEqualTo(25);
            assertThat(attrs.get("usersLimit")).isEqualTo(500);
            assertThat(attrs.get("collectionsUsed")).isEqualTo(8);
            assertThat(attrs.get("collectionsLimit")).isEqualTo(100);
        }

        @Test
        @DisplayName("Should fill defaults for missing limit keys")
        void fillsDefaultsForMissingKeys() {
            String limitsJson = "{\"apiCallsPerDay\":75000}";
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", limitsJson)));
            when(repository.countActiveUsers("tenant-1")).thenReturn(0);
            when(repository.countActiveCollections("tenant-1")).thenReturn(0);

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) getAttributes(response.getBody()).get("limits");
            assertThat(limits.get("apiCallsPerDay")).isEqualTo(75_000);
            assertThat(limits.get("storageGb")).isEqualTo(10);
            assertThat(limits.get("maxUsers")).isEqualTo(100);
            assertThat(limits.get("maxCollections")).isEqualTo(200);
        }

        @Test
        @DisplayName("Should handle tenant not found")
        void handlesTenantNotFound() {
            when(repository.findEditionAndLimits("nonexistent")).thenReturn(Optional.empty());
            when(repository.countActiveUsers("nonexistent")).thenReturn(0);
            when(repository.countActiveCollections("nonexistent")).thenReturn(0);

            ResponseEntity<Map<String, Object>> response = controller.getStatus("nonexistent", request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) getAttributes(response.getBody()).get("limits");
            assertThat(limits.get("apiCallsPerDay")).isEqualTo(100_000);
        }

        @Test
        @DisplayName("Should handle malformed limits JSON")
        void handlesMalformedJson() {
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", "{invalid}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(3);
            when(repository.countActiveCollections("tenant-1")).thenReturn(5);

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) getAttributes(response.getBody()).get("limits");
            assertThat(limits.get("apiCallsPerDay")).isEqualTo(100_000);
            assertThat(limits.get("maxUsers")).isEqualTo(100);
        }

        @Test
        @DisplayName("Should handle limits as Map object (JDBC driver returns Map)")
        void handlesLimitsAsMap() {
            Map<String, Object> limitsMap = new HashMap<>();
            limitsMap.put("apiCallsPerDay", 60000);
            limitsMap.put("maxUsers", 250);

            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", limitsMap)));
            when(repository.countActiveUsers("tenant-1")).thenReturn(10);
            when(repository.countActiveCollections("tenant-1")).thenReturn(20);

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) getAttributes(response.getBody()).get("limits");
            assertThat(limits.get("apiCallsPerDay")).isEqualTo(60_000);
            assertThat(limits.get("maxUsers")).isEqualTo(250);
            assertThat(limits.get("storageGb")).isEqualTo(10);
        }

        @Test
        @DisplayName("Should parse limits from PGobject-like type (JSONB column)")
        void handlesLimitsAsPGobject() {
            // PostgreSQL JDBC driver returns JSONB columns as PGobject,
            // which is not a String or Map but has a valid toString()
            Object pgObjectLike = new Object() {
                @Override
                public String toString() {
                    return "{\"apiCallsPerDay\":10000000,\"storageGb\":50,\"maxUsers\":500}";
                }
            };

            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", pgObjectLike)));
            when(repository.countActiveUsers("tenant-1")).thenReturn(10);
            when(repository.countActiveCollections("tenant-1")).thenReturn(5);

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) getAttributes(response.getBody()).get("limits");
            assertThat(limits.get("apiCallsPerDay")).isEqualTo(10_000_000);
            assertThat(limits.get("storageGb")).isEqualTo(50);
            assertThat(limits.get("maxUsers")).isEqualTo(500);
            // Defaults filled for missing keys
            assertThat(limits.get("maxCollections")).isEqualTo(200);
        }

        @Test
        @DisplayName("Should return FREE-tier defaults and tier=FREE in response")
        void returnsFreeTierDefaults() {
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(
                    Optional.of(new GovernorLimitsRepository.EditionAndLimits("FREE", "{}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(2);
            when(repository.countActiveCollections("tenant-1")).thenReturn(3);

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            Map<String, Object> attrs = getAttributes(response.getBody());
            assertThat(attrs.get("tier")).isEqualTo("FREE");
            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) attrs.get("limits");
            assertThat(limits.get("maxUsers")).isEqualTo(5);
            assertThat(limits.get("maxCollections")).isEqualTo(10);
            assertThat(limits.get("aiEnabled")).isEqualTo(false);
            assertThat(attrs.get("aiEnabled")).isEqualTo(false);
        }

        @Test
        @DisplayName("Should return ENTERPRISE-tier defaults and tier=ENTERPRISE in response")
        void returnsEnterpriseTierDefaults() {
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(
                    Optional.of(new GovernorLimitsRepository.EditionAndLimits("ENTERPRISE", "{}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(0);
            when(repository.countActiveCollections("tenant-1")).thenReturn(0);

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            Map<String, Object> attrs = getAttributes(response.getBody());
            assertThat(attrs.get("tier")).isEqualTo("ENTERPRISE");
            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) attrs.get("limits");
            assertThat(limits.get("maxUsers")).isEqualTo(1_000);
            assertThat(limits.get("maxCollections")).isEqualTo(2_000);
            assertThat(limits.get("aiTokensPerMonth")).isEqualTo(10_000_000L);
        }

        @Test
        @DisplayName("Should let tenant overrides win over tier defaults")
        void tenantOverrideWinsOverTierDefault() {
            // FREE tier default for maxUsers is 5; override to 50 should win
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(
                    Optional.of(new GovernorLimitsRepository.EditionAndLimits("FREE", "{\"maxUsers\":50}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(0);
            when(repository.countActiveCollections("tenant-1")).thenReturn(0);

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            Map<String, Object> attrs = getAttributes(response.getBody());
            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) attrs.get("limits");
            // Override wins
            assertThat(limits.get("maxUsers")).isEqualTo(50);
            // Untouched key falls back to FREE default
            assertThat(limits.get("maxCollections")).isEqualTo(10);
        }

        @Test
        @DisplayName("Should return zero apiCallsUsed when Redis key is absent")
        void returnsZeroApiCallsUsedWhenRedisEmpty() {
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", "{}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(0);
            when(repository.countActiveCollections("tenant-1")).thenReturn(0);

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            assertThat(getAttributes(response.getBody()).get("apiCallsUsed")).isEqualTo(0);
        }

        @Test
        @DisplayName("Should return daily API call count from Redis")
        void returnsDailyApiCallCountFromRedis() {
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", "{}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(0);
            when(repository.countActiveCollections("tenant-1")).thenReturn(0);

            String today = LocalDate.now(ZoneOffset.UTC).toString();
            when(valueOps.get("api-calls-daily:tenant-1:" + today)).thenReturn("4523");

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            assertThat(getAttributes(response.getBody()).get("apiCallsUsed")).isEqualTo(4523);
        }

        @Test
        @DisplayName("Should return zero when Redis throws exception")
        void returnsZeroWhenRedisThrows() {
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", "{}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(0);
            when(repository.countActiveCollections("tenant-1")).thenReturn(0);

            when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis unavailable"));

            ResponseEntity<Map<String, Object>> response = controller.getStatus("tenant-1", request);

            assertThat(getAttributes(response.getBody()).get("apiCallsUsed")).isEqualTo(0);
        }
    }

    // ==================== Tier Tests ====================

    @Nested
    @DisplayName("Write authorization (MANAGE_TENANTS)")
    class WriteAuthorizationTests {

        @Test
        @DisplayName("refuses a tier change without MANAGE_TENANTS, before touching the tenant")
        void tierRequiresManageTenants() {
            grantPermission("MANAGE_TENANTS", false);

            assertThatThrownBy(() -> controller.updateTier("tenant-1", Map.of("tier", "UNLIMITED"), request))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            verify(repository, never()).updateTenantEdition(anyString(), anyString());
        }

        @Test
        @DisplayName("refuses a limits change without MANAGE_TENANTS, before touching the tenant")
        void limitsRequireManageTenants() {
            grantPermission("CUSTOMIZE_APPLICATION", true);

            assertThatThrownBy(() -> controller.updateLimits("tenant-1", Map.of("apiCallsPerDay", 1_000_000), request))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            verify(repository, never()).updateTenantLimits(anyString(), anyString());
        }

        @Test
        @DisplayName("refuses a write from a caller with no identity")
        void writesFailClosedWithoutIdentity() {
            when(permissionResolver.getProfileId(request)).thenReturn(null);

            assertThatThrownBy(() -> controller.updateTier("tenant-1", Map.of("tier", "FREE"), request))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(bootstrapRepository);
            verify(repository, never()).updateTenantEdition(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("PUT /api/governor-limits/tier")
    class UpdateTierTests {

        @Test
        @DisplayName("rejects request with missing tier field")
        void rejectsMissingTier() {
            ResponseEntity<Map<String, Object>> response = controller.updateTier(
                    "tenant-1", Map.of(), request);

            assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        }

        @Test
        @DisplayName("rejects request with invalid tier value")
        void rejectsInvalidTier() {
            ResponseEntity<Map<String, Object>> response = controller.updateTier(
                    "tenant-1", Map.of("tier", "DELUXE"), request);

            assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        }

        @Test
        @DisplayName("normalises lowercase tier to canonical form")
        void normalisesLowercase() {
            when(repository.updateTenantEdition("tenant-1", "ENTERPRISE")).thenReturn(1);
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(
                    Optional.of(new GovernorLimitsRepository.EditionAndLimits("ENTERPRISE", "{}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(0);
            when(repository.countActiveCollections("tenant-1")).thenReturn(0);

            ResponseEntity<Map<String, Object>> response = controller.updateTier(
                    "tenant-1", Map.of("tier", "enterprise"), request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            verify(repository).updateTenantEdition("tenant-1", "ENTERPRISE");
        }

        @Test
        @DisplayName("a MANAGE_TENANTS writer without VIEW_SETUP still gets the updated status back")
        void writerWithoutViewSetupGetsStatus() {
            grantPermission("MANAGE_TENANTS", true);
            when(repository.updateTenantEdition("tenant-1", "ENTERPRISE")).thenReturn(1);
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(
                    Optional.of(new GovernorLimitsRepository.EditionAndLimits("ENTERPRISE", "{}")));

            ResponseEntity<Map<String, Object>> response = controller.updateTier(
                    "tenant-1", Map.of("tier", "ENTERPRISE"), request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).containsKey("data");
        }

        @Test
        @DisplayName("returns 404 when tenant not found")
        void notFoundOnMissingTenant() {
            when(repository.updateTenantEdition("missing", "FREE")).thenReturn(0);

            ResponseEntity<Map<String, Object>> response = controller.updateTier(
                    "missing", Map.of("tier", "FREE"), request);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("evicts cache and broadcasts feature event on success")
        void evictsCacheAndBroadcasts() {
            when(repository.updateTenantEdition("tenant-1", "FREE")).thenReturn(1);
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(
                    Optional.of(new GovernorLimitsRepository.EditionAndLimits("FREE", "{}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(0);
            when(repository.countActiveCollections("tenant-1")).thenReturn(0);

            ResponseEntity<Map<String, Object>> response = controller.updateTier(
                    "tenant-1", Map.of("tier", "FREE"), request);

            Map<String, Object> attrs = getAttributes(response.getBody());
            assertThat(attrs.get("tier")).isEqualTo("FREE");
            verify(platformEventPublisher).publish(anyString(), any());
        }
    }

    // ==================== PUT Tests ====================

    @Nested
    @DisplayName("PUT /api/governor-limits")
    class UpdateLimitsTests {

        @Test
        @DisplayName("Should update tenant limits")
        void updatesTenantLimits() {
            Map<String, Object> newLimits = new LinkedHashMap<>();
            newLimits.put("apiCallsPerDay", 200_000);
            newLimits.put("storageGb", 100);
            newLimits.put("maxUsers", 1000);
            newLimits.put("maxCollections", 500);
            newLimits.put("maxFieldsPerCollection", 1000);
            newLimits.put("maxWorkflows", 100);
            newLimits.put("maxReports", 400);

            // Mock re-read for response
            String updatedJson = "{\"apiCallsPerDay\":200000,\"storageGb\":100,\"maxUsers\":1000,\"maxCollections\":500,\"maxFieldsPerCollection\":1000,\"maxWorkflows\":100,\"maxReports\":400}";
            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", updatedJson)));
            when(repository.countActiveUsers("tenant-1")).thenReturn(50);
            when(repository.countActiveCollections("tenant-1")).thenReturn(30);

            ResponseEntity<Map<String, Object>> response = controller.updateLimits("tenant-1", newLimits, request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

            verify(repository).updateTenantLimits(eq("tenant-1"), anyString());

            Map<String, Object> attrs = getAttributes(response.getBody());
            assertThat(attrs).isNotNull();
            assertThat(attrs.get("usersUsed")).isEqualTo(50);
            assertThat(attrs.get("collectionsUsed")).isEqualTo(30);
        }

        @Test
        @DisplayName("Should publish NATS event when limits are updated")
        @SuppressWarnings("unchecked")
        void publishesNatsEventOnUpdate() {
            Map<String, Object> newLimits = new LinkedHashMap<>();
            newLimits.put("apiCallsPerDay", 200_000);

            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", "{\"apiCallsPerDay\":200000}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(0);
            when(repository.countActiveCollections("tenant-1")).thenReturn(0);

            controller.updateLimits("tenant-1", newLimits, request);

            ArgumentCaptor<PlatformEvent<RecordChangedPayload>> captor =
                    ArgumentCaptor.forClass(PlatformEvent.class);
            verify(recordEventPublisher).publish(captor.capture());

            PlatformEvent<RecordChangedPayload> event = captor.getValue();
            assertThat(event.getTenantId()).isEqualTo("tenant-1");
            assertThat(event.getEventType()).isEqualTo("record.updated");
            assertThat(event.getPayload().getCollectionName()).isEqualTo("tenants");
            assertThat(event.getPayload().getRecordId()).isEqualTo("tenant-1");
            assertThat(event.getPayload().getChangedFields()).containsExactly("limits");
        }

        @Test
        @DisplayName("Should not fail update if NATS event publishing fails")
        void doesNotFailIfNatsPublishFails() {
            Map<String, Object> newLimits = new LinkedHashMap<>();
            newLimits.put("apiCallsPerDay", 200_000);

            doThrow(new RuntimeException("NATS is down"))
                    .when(recordEventPublisher).publish(any());

            when(repository.findEditionAndLimits("tenant-1")).thenReturn(Optional.of(new GovernorLimitsRepository.EditionAndLimits("PROFESSIONAL", "{\"apiCallsPerDay\":200000}")));
            when(repository.countActiveUsers("tenant-1")).thenReturn(0);
            when(repository.countActiveCollections("tenant-1")).thenReturn(0);

            ResponseEntity<Map<String, Object>> response = controller.updateLimits("tenant-1", newLimits, request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            verify(repository).updateTenantLimits(eq("tenant-1"), anyString());
        }
    }
}
