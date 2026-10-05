package io.kelta.worker.filter;

import io.kelta.runtime.context.GeoHeaders;
import io.kelta.runtime.context.GeoStamp;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.kelta.runtime.module.integration.spi.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servlet filter that tracks user login activity by recording entries in the
 * {@code login_history} table and updating {@code platform_user} login metadata.
 *
 * <p>The gateway forwards authenticated user identity via headers:
 * <ul>
 *   <li>{@code X-User-Id} — email address from JWT subject claim</li>
 *   <li>{@code X-Tenant-ID} — resolved tenant UUID</li>
 * </ul>
 *
 * <p>To avoid excessive database writes, tracking is throttled to once per
 * {@value #TRACKING_INTERVAL_SECONDS} seconds per user per tenant. This filter
 * is designed to be non-blocking — any tracking failures are logged as warnings
 * and never interrupt the request processing.
 *
 * <p>If no {@code platform_user} record exists for the authenticated email,
 * this filter auto-provisions one using the available header information.
 * This ensures that users authenticating via OIDC for the first time are
 * automatically registered in the platform.
 *
 * <p>On each tracked request, this filter:
 * <ol>
 *   <li>Auto-provisions a {@code platform_user} if none exists for the email</li>
 *   <li>Updates {@code platform_user.last_login_at} and increments {@code login_count}</li>
 *   <li>Inserts a row into {@code login_history}</li>
 *   <li>Inserts a {@code LOGIN_SUCCESS} event into {@code security_audit_log}</li>
 * </ol>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class LoginTrackingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginTrackingFilter.class);

    /** Minimum interval between tracking writes for the same user, in seconds. */
    static final long TRACKING_INTERVAL_SECONDS = 1800; // 30 minutes

    /** Maximum number of cache entries before triggering eviction. */
    private static final int MAX_CACHE_SIZE = 1000;

    /** Maximum length for user agent strings stored in the database. */
    private static final int MAX_USER_AGENT_LENGTH = 500;

    /** Client IP resolved and stamped by the gateway ({@code ClientIpForwardingFilter}). */
    static final String CLIENT_IP_HEADER = "X-Kelta-Client-Ip";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final EmailService emailService;
    private final String uiBaseUrl;

    /** Maps "tenantId:email" to the epoch-second of the last tracking write. */
    private final Map<String, Long> lastTrackedAt;

    @Autowired
    public LoginTrackingFilter(JdbcTemplate jdbcTemplate,
                                EmailService emailService,
                                @org.springframework.beans.factory.annotation.Value("${kelta.external-base-url:}") String uiBaseUrl) {
        this(jdbcTemplate, emailService, uiBaseUrl, new ConcurrentHashMap<>());
    }

    /** Constructor for testing — allows injecting the throttle cache. */
    LoginTrackingFilter(JdbcTemplate jdbcTemplate, Map<String, Long> lastTrackedAt) {
        this(jdbcTemplate, null, null, lastTrackedAt);
    }

    LoginTrackingFilter(JdbcTemplate jdbcTemplate, EmailService emailService,
                         String uiBaseUrl, Map<String, Long> lastTrackedAt) {
        this.jdbcTemplate = jdbcTemplate;
        this.emailService = emailService;
        this.uiBaseUrl = uiBaseUrl;
        this.lastTrackedAt = lastTrackedAt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {
        try {
            trackLoginIfNeeded(request);
        } catch (Throwable t) {
            // Catch Throwable (not just Exception) to handle NoClassDefFoundError,
            // ExceptionInInitializerError, and other Errors that would otherwise
            // crash normal requests due to OpenSearch/audit failures.
            log.warn("Login tracking failed (non-fatal): {}", t.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    private void trackLoginIfNeeded(HttpServletRequest request) {
        String email = request.getHeader("X-User-Id");
        String tenantId = request.getHeader("X-Tenant-ID");

        if (email == null || email.isBlank() || tenantId == null || tenantId.isBlank()) {
            return;
        }

        // X-User-Id contains an email from the JWT sub claim.
        // If it already looks like a UUID, skip tracking (should not happen in normal flow).
        if (isUuid(email)) {
            return;
        }

        // Throttle: only track once per interval per user per tenant
        String cacheKey = tenantId + ":" + email;
        long now = Instant.now().getEpochSecond();
        Long lastTime = lastTrackedAt.get(cacheKey);
        if (lastTime != null && (now - lastTime) < TRACKING_INTERVAL_SECONDS) {
            return;
        }

        // Resolve user UUID from email, auto-provisioning if needed
        String groups = request.getHeader("X-Forwarded-Groups");
        String userId = lookupUserId(email, tenantId);
        if (userId == null) {
            String username = request.getHeader("X-Forwarded-User");
            userId = provisionUser(email, tenantId, username, groups);
            if (userId == null) {
                return;
            }
        } else {
            // Existing user — sync profile from OIDC groups mapping
            syncProfileFromGroups(userId, tenantId, groups);
        }

        String sourceIp = extractClientIp(request);
        String userAgent = truncateUserAgent(request.getHeader("User-Agent"));
        GeoStamp geo = GeoHeaders.parse(request).orElse(null);
        Instant loginTime = Instant.now();
        Timestamp ts = Timestamp.from(loginTime);

        // 1. Update platform_user login metadata
        updateUserLoginInfo(userId, ts);

        // 2. Insert login_history row
        insertLoginHistory(userId, tenantId, ts, sourceIp, userAgent, geo);

        // 3. Insert security_audit_log LOGIN event
        insertSecurityAuditLogin(userId, email, tenantId, sourceIp, userAgent);

        lastTrackedAt.put(cacheKey, now);
        log.debug("Tracked login for user {} ({}) in tenant {}", userId, email, tenantId);

        // Periodic cleanup of stale cache entries
        if (lastTrackedAt.size() > MAX_CACHE_SIZE) {
            evictStaleEntries(now);
        }
    }

    String lookupUserId(String email, String tenantId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM platform_user WHERE tenant_id = ? AND email = ? LIMIT 1",
                    String.class, tenantId, email);
        } catch (Exception e) {
            log.debug("Could not resolve user '{}' in tenant '{}': {}", email, tenantId, e.getMessage());
            return null;
        }
    }

    /**
     * Auto-provisions a new {@code platform_user} record for a user authenticating
     * for the first time. Uses {@code INSERT ... ON CONFLICT DO NOTHING} for thread
     * safety when multiple concurrent requests arrive for the same new user.
     *
     * <p>The profile is resolved from the user's OIDC groups using the
     * {@code groups_profile_mapping} on the tenant's OIDC provider. Falls back
     * to "Standard User" if no mapping matches.
     *
     * @return the new user's UUID, or {@code null} if provisioning failed
     */
    String provisionUser(String email, String tenantId, String username, String groups) {
        String id = UUID.randomUUID().toString();
        try {
            String profileId = resolveProfileForGroups(tenantId, groups);
            String profileName = profileId != null ? "mapped" : null;
            if (profileId == null) {
                profileId = lookupDefaultProfileId(tenantId);
                profileName = profileId != null ? "Standard User" : "none";
            }
            jdbcTemplate.update(
                    "INSERT INTO platform_user (id, tenant_id, email, username, profile_id, status, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW()) " +
                            "ON CONFLICT (tenant_id, email) DO NOTHING",
                    id, tenantId, email, username, profileId);

            // Re-query to get the actual ID (handles race condition where another thread inserted first)
            String resolvedId = lookupUserId(email, tenantId);
            if (resolvedId != null) {
                log.info("Auto-provisioned platform_user for '{}' in tenant '{}' with profile '{}'",
                        email, tenantId, profileName);
                return resolvedId;
            }
        } catch (Exception e) {
            log.warn("Failed to auto-provision user '{}' in tenant '{}': {}", email, tenantId, e.getMessage());
        }
        return null;
    }

    /**
     * Syncs an existing user's profile based on their OIDC groups.
     * If the groups_profile_mapping resolves to a different profile than
     * currently assigned, updates the user's profile. This ensures profiles
     * stay in sync when OIDC group membership changes.
     */
    void syncProfileFromGroups(String userId, String tenantId, String groups) {
        String resolvedProfileId = resolveProfileForGroups(tenantId, groups);
        if (resolvedProfileId == null) {
            return;
        }
        try {
            String currentProfileId = jdbcTemplate.queryForObject(
                    "SELECT profile_id FROM platform_user WHERE id = ?",
                    String.class, userId);
            if (!resolvedProfileId.equals(currentProfileId)) {
                jdbcTemplate.update(
                        "UPDATE platform_user SET profile_id = ?, updated_at = NOW() WHERE id = ?",
                        resolvedProfileId, userId);
                log.info("Synced profile for user '{}' in tenant '{}' from OIDC groups", userId, tenantId);
            }
        } catch (Exception e) {
            log.debug("Could not sync profile for user '{}': {}", userId, e.getMessage());
        }
    }

    /**
     * Resolves a profile ID from the user's OIDC groups using the tenant's
     * {@code groups_profile_mapping} configuration.
     *
     * @param tenantId the tenant UUID
     * @param groups   comma-separated OIDC group names from the gateway
     * @return profile ID if a mapping matched, or {@code null} to fall back to default
     */
    String resolveProfileForGroups(String tenantId, String groups) {
        if (groups == null || groups.isBlank()) {
            return null;
        }
        try {
            String mappingJson = jdbcTemplate.queryForObject(
                    "SELECT groups_profile_mapping FROM oidc_provider " +
                            "WHERE tenant_id = ? AND active = true AND groups_profile_mapping IS NOT NULL LIMIT 1",
                    String.class, tenantId);
            if (mappingJson == null) {
                return null;
            }

            Map<String, String> mapping = OBJECT_MAPPER.readValue(
                    mappingJson, new TypeReference<Map<String, String>>() {});

            // Check all groups and prefer "System Administrator" over other matches.
            // This ensures users in multiple groups (e.g. emf-admins + emf-developers)
            // get the highest-privilege profile regardless of group ordering.
            String bestProfileId = null;
            String bestProfileName = null;
            for (String group : groups.split(",")) {
                String profileName = mapping.get(group.trim());
                if (profileName != null) {
                    String profileId = lookupProfileIdByName(tenantId, profileName);
                    if (profileId != null) {
                        log.debug("Mapped OIDC group '{}' to profile '{}' for tenant '{}'",
                                group.trim(), profileName, tenantId);
                        if ("System Administrator".equals(profileName)) {
                            return profileId; // Highest privilege — return immediately
                        }
                        if (bestProfileId == null) {
                            bestProfileId = profileId;
                            bestProfileName = profileName;
                        }
                    }
                }
            }
            return bestProfileId;
        } catch (Exception e) {
            log.debug("Could not resolve profile from groups for tenant '{}': {}", tenantId, e.getMessage());
        }
        return null;
    }

    /**
     * Looks up a profile by name for the given tenant.
     */
    private String lookupProfileIdByName(String tenantId, String profileName) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM profile WHERE tenant_id = ? AND name = ? LIMIT 1",
                    String.class, tenantId, profileName);
        } catch (Exception e) {
            log.debug("No profile '{}' found for tenant '{}': {}", profileName, tenantId, e.getMessage());
            return null;
        }
    }

    /**
     * Looks up the "Standard User" profile for the given tenant.
     * Falls back to {@code null} if the profile does not exist
     * (e.g. the tenant was created before profile seeding).
     */
    private String lookupDefaultProfileId(String tenantId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM profile WHERE tenant_id = ? AND name = 'Standard User' LIMIT 1",
                    String.class, tenantId);
        } catch (Exception e) {
            log.debug("No 'Standard User' profile found for tenant '{}': {}", tenantId, e.getMessage());
            return null;
        }
    }

    private void updateUserLoginInfo(String userId, Timestamp ts) {
        boolean firstLogin = false;
        try {
            Object last = jdbcTemplate.queryForObject(
                    "SELECT welcomed_at FROM platform_user WHERE id = ?",
                    Object.class, userId);
            firstLogin = (last == null);
        } catch (Exception ignored) {
            // Older schemas may not have welcomed_at — skip the welcome path.
        }
        jdbcTemplate.update(
                "UPDATE platform_user SET last_login_at = ?, login_count = COALESCE(login_count, 0) + 1, updated_at = ? WHERE id = ?",
                ts, ts, userId);

        if (firstLogin && emailService != null) {
            try {
                var rows = jdbcTemplate.queryForList(
                        "SELECT pu.email, pu.first_name, pu.tenant_id, t.name AS tenant_name "
                                + "FROM platform_user pu JOIN tenant t ON t.id = pu.tenant_id "
                                + "WHERE pu.id = ?", userId);
                if (!rows.isEmpty()) {
                    var row = rows.get(0);
                    emailService.sendByKey(
                            (String) row.get("tenant_id"),
                            (String) row.get("email"),
                            "user.welcome",
                            Map.of(
                                    "tenantName", row.getOrDefault("tenant_name", "Kelta"),
                                    "firstName",  row.getOrDefault("first_name", ""),
                                    "actionUrl",  uiBaseUrl == null ? "" : uiBaseUrl),
                            "WELCOME", userId);
                    jdbcTemplate.update(
                            "UPDATE platform_user SET welcomed_at = ? WHERE id = ?", ts, userId);
                }
            } catch (Exception e) {
                log.warn("Failed to send welcome email for {}: {}", userId, e.getMessage());
            }
        }
    }

    private void insertLoginHistory(String userId, String tenantId, Timestamp ts,
                                     String sourceIp, String userAgent, GeoStamp geo) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO login_history (id, user_id, tenant_id, login_time, source_ip, login_type, status, user_agent, " +
                        "geo_country, geo_region, geo_city, geo_lat, geo_lon, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, userId, tenantId, ts, sourceIp, "OAUTH", "SUCCESS", userAgent,
                geo != null ? geo.country() : null,
                geo != null ? geo.region() : null,
                geo != null ? geo.city() : null,
                geo != null ? geo.latitude() : null,
                geo != null ? geo.longitude() : null,
                ts, ts);
    }

    private void insertSecurityAuditLogin(String userId, String email, String tenantId,
                                           String sourceIp, String userAgent) {
        String id = UUID.randomUUID().toString();
        String details = "{\"provider\":\"OIDC\",\"loginType\":\"OAUTH\"}";
        jdbcTemplate.update(
                "INSERT INTO security_audit_log (id, tenant_id, event_type, event_category, actor_user_id, actor_email, " +
                        "target_type, target_id, target_name, details, ip_address, user_agent, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                id, tenantId, "LOGIN_SUCCESS", "AUTH", userId, email,
                "USER", userId, email, details, sourceIp, userAgent,
                Timestamp.from(Instant.now()));
    }

    /**
     * The client address to record. Prefers {@value #CLIENT_IP_HEADER}, which the gateway
     * resolves through its trusted-proxy rules and overwrites on every request (any
     * client-supplied copy is stripped). The {@code X-Forwarded-For} fallback only applies
     * to requests that did not come through the gateway; its left-most hop is
     * client-controlled.
     */
    static String extractClientIp(HttpServletRequest request) {
        String gatewayResolved = request.getHeader(CLIENT_IP_HEADER);
        if (gatewayResolved != null && !gatewayResolved.isBlank()) {
            return gatewayResolved.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    static String truncateUserAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        if (userAgent.length() > MAX_USER_AGENT_LENGTH) {
            return userAgent.substring(0, MAX_USER_AGENT_LENGTH);
        }
        return userAgent;
    }

    private void evictStaleEntries(long nowEpochSecond) {
        long threshold = nowEpochSecond - (TRACKING_INTERVAL_SECONDS * 2);
        lastTrackedAt.entrySet().removeIf(entry -> entry.getValue() < threshold);
    }

    private static boolean isUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        return value.charAt(8) == '-' && value.charAt(13) == '-'
                && value.charAt(18) == '-' && value.charAt(23) == '-';
    }
}
