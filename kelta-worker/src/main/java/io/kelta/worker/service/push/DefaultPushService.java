package io.kelta.worker.service.push;

import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.worker.repository.PushRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Push notification service: device management and notification routing.
 *
 * <p>Loads per-tenant push settings from the tenant.settings JSONB column
 * and passes them to the active {@link PushProvider} for credential resolution.
 *
 * @since 1.0.0
 */
@Service
public class DefaultPushService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPushService.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    private static final Set<String> VALID_PLATFORMS = Set.of("ios", "android", "web");
    private static final int MAX_TOKEN_LENGTH = 500;

    private final PushProvider pushProvider;
    private final java.util.List<PushProvider> providers;
    private final PushRepository pushRepository;
    private final OutboundUrlGuard urlGuard;

    public DefaultPushService(PushProvider pushProvider, PushRepository pushRepository,
                              java.util.List<PushProvider> allProviders, OutboundUrlGuard urlGuard) {
        this.pushProvider = pushProvider;
        this.pushRepository = pushRepository;
        this.urlGuard = urlGuard;
        // Browser push runs ALONGSIDE the property-selected mobile provider — a
        // tenant can have both kinds of subscriber — so routing is per device
        // rather than one provider taking everything.
        this.providers = allProviders == null ? java.util.List.of() : allProviders;
    }

    /**
     * Picks the provider for a device's platform, falling back to the configured
     * default so single-provider deployments behave exactly as before.
     */
    private PushProvider providerFor(String platform) {
        for (PushProvider candidate : providers) {
            // A platform-specific provider wins over the configured default; the
            // default's supports() returns true for everything, so checking it
            // first would always shadow the specialist.
            if (candidate != pushProvider && candidate.supports(platform)) {
                return candidate;
            }
        }
        return pushProvider;
    }

    public String registerDevice(String userId, String tenantId, String platform,
                                  String deviceToken, String deviceName) {
        // Validate
        if (deviceToken == null || deviceToken.isBlank() || deviceToken.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Invalid device token");
        }
        if (!VALID_PLATFORMS.contains(platform)) {
            throw new IllegalArgumentException("Platform must be one of: " + VALID_PLATFORMS);
        }

        // Upsert (unique on tenant_id + device_token)
        var existingId = pushRepository.findDeviceIdByToken(tenantId, deviceToken);

        String deviceId;
        if (existingId.isEmpty()) {
            deviceId = pushRepository.insertDevice(userId, tenantId, platform, deviceToken, deviceName);
        } else {
            deviceId = existingId.get();
            pushRepository.updateDevice(deviceId, userId, platform, deviceName);
        }

        log.info("Device registered: id={} user={} platform={}", deviceId, userId, platform);
        return deviceId;
    }

    /**
     * Registers a browser subscription.
     *
     * <p>The device token is {@code sha256(endpoint)} rather than the endpoint
     * itself: endpoints run well past the 500-char column and the token carries a
     * per-tenant UNIQUE constraint, so hashing keeps both intact while staying
     * stable and unique per subscription. The subscription JSON is what is
     * actually addressable, so it is stored alongside.
     */
    public String registerWebDevice(String userId, String tenantId, String subscriptionJson,
                                    String deviceName) {
        if (subscriptionJson == null || subscriptionJson.isBlank()) {
            throw new IllegalArgumentException("subscription is required for web devices");
        }
        // The endpoint is browser-supplied and is where every later push is sent.
        try {
            WebPushProvider.checkEndpoint(urlGuard, endpointOf(subscriptionJson));
        } catch (OutboundUrlGuard.RejectedUrlException e) {
            throw new IllegalArgumentException("subscription endpoint refused: " + e.getMessage());
        }
        String deviceId = registerDevice(userId, tenantId, "web",
                webDeviceToken(subscriptionJson), deviceName);
        pushRepository.updateWebPushSubscription(deviceId, subscriptionJson);
        return deviceId;
    }

    /** Stable per-subscription token: the SHA-256 of its endpoint, hex-encoded. */
    static String webDeviceToken(String subscriptionJson) {
        String endpoint = endpointOf(subscriptionJson);
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(endpoint.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The subscription's {@code endpoint}. */
    static String endpointOf(String subscriptionJson) {
        try {
            var node = new tools.jackson.databind.ObjectMapper().readTree(subscriptionJson);
            String endpoint = node.path("endpoint").stringValue();
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException("subscription has no endpoint");
            }
            return endpoint;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("unreadable web push subscription");
        }
    }

    public void removeDevice(String deviceId, String tenantId) {
        pushRepository.deleteDevice(deviceId, tenantId);
    }

    public List<Map<String, Object>> listDevices(String userId, String tenantId) {
        return pushRepository.findDevicesByUser(userId, tenantId);
    }

    public int sendToUser(String userId, String tenantId, String title, String body, Map<String, String> data) {
        var devices = pushRepository.findDevicesForUser(userId, tenantId);
        TenantPushSettings tenantSettings = loadTenantPushSettings(tenantId);
        return sendToDevices(devices, title, body, data, tenantSettings);
    }

    public int sendToTenant(String tenantId, String title, String body, Map<String, String> data) {
        var devices = pushRepository.findDevicesForTenant(tenantId);

        securityLog.info("security_event=PUSH_BROADCAST tenant={} devices={} title={}",
                tenantId, devices.size(), title);
        TenantPushSettings tenantSettings = loadTenantPushSettings(tenantId);
        return sendToDevices(devices, title, body, data, tenantSettings);
    }

    private int sendToDevices(List<Map<String, Object>> devices, String title, String body,
                               Map<String, String> data, TenantPushSettings tenantSettings) {
        int delivered = 0;
        for (var device : devices) {
            String token = (String) device.get("device_token");
            String platform = (String) device.get("platform");
            String deviceId = (String) device.get("id");

            try {
                String subscription = (String) device.get("web_push_subscription");
                providerFor(platform).send(
                        new PushMessage(token, platform, title, body, data, subscription),
                        tenantSettings);
                delivered++;
            } catch (PushDeliveryException e) {
                if (e.isInvalidToken()) {
                    log.info("Removing stale device token: id={}", deviceId);
                    pushRepository.deleteDeviceById(deviceId);
                } else {
                    log.warn("Push delivery failed for device {}: {}", deviceId, e.getMessage());
                }
            }
        }
        return delivered;
    }

    private TenantPushSettings loadTenantPushSettings(String tenantId) {
        try {
            var settings = pushRepository.getTenantSettings(tenantId);
            return settings != null ? TenantPushSettings.fromJsonNode(settings) : null;
        } catch (Exception e) {
            log.debug("Could not load tenant push settings for {}: {}", tenantId, e.getMessage());
            return null;
        }
    }
}
