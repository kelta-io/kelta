package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.runtime.storage.UniqueConstraintViolationException;
import io.kelta.worker.service.SecurityAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Personal Access Token (PAT) management for authenticated users.
 *
 * <p>Allows users to create, list, and revoke their own API tokens
 * for programmatic access. Tokens use the {@code klt_} prefix for
 * easy identification.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/me/tokens")
public class PersonalAccessTokenController {

    private static final Logger log = LoggerFactory.getLogger(PersonalAccessTokenController.class);
    private static final String TOKEN_PREFIX = "klt_";
    private static final int TOKEN_LENGTH = 40;
    private static final int MAX_TOKENS_PER_USER = 10;
    private static final String PAT_KEY_PREFIX = "pat:";
    private static final String REVOCATION_KEY_PREFIX = "pat:revoked:";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final UserIdResolver userIdResolver;
    private final SecureRandom secureRandom = new SecureRandom();

    public PersonalAccessTokenController(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate,
                                         UserIdResolver userIdResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.userIdResolver = userIdResolver;
    }

    /**
     * Resolve the X-User-Id header (email) to a platform_user UUID.
     */
    private String resolveUserId(String userIdentifier, String tenantId) {
        return userIdResolver.resolve(userIdentifier, tenantId);
    }

    /**
     * Convert a JDBC row with snake_case column names to camelCase keys for the frontend SDK.
     */
    private Map<String, Object> toCamelCaseToken(Map<String, Object> row) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("name", row.get("name"));
        result.put("tokenPrefix", row.get("token_prefix"));
        result.put("scopes", row.get("scopes"));
        result.put("expiresAt", row.get("expires_at") != null ? row.get("expires_at").toString() : null);
        result.put("lastUsedAt", row.get("last_used_at") != null ? row.get("last_used_at").toString() : null);
        result.put("createdAt", row.get("created_at") != null ? row.get("created_at").toString() : null);
        return result;
    }

    /**
     * List current user's active (non-revoked) tokens. Never returns the token hash.
     */
    @GetMapping
    public ResponseEntity<?> listTokens(@RequestHeader(value = "X-User-Id", required = false) String userIdentifier) {
        String tenantId = TenantContext.get();
        if (tenantId == null || userIdentifier == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing tenant or user context"));
        }
        String userId = resolveUserId(userIdentifier, tenantId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, token_prefix, scopes, expires_at, last_used_at, created_at " +
                        "FROM user_api_token WHERE user_id = ? AND tenant_id = ? AND revoked = false " +
                        "ORDER BY created_at DESC",
                userId, tenantId);

        List<Map<String, Object>> tokens = rows.stream().map(this::toCamelCaseToken).toList();
        return ResponseEntity.ok(Map.of("data", tokens));
    }

    /**
     * Create a new personal access token. Returns the plaintext token exactly once.
     */
    @PostMapping
    public ResponseEntity<?> createToken(
            @RequestHeader(value = "X-User-Id", required = false) String userIdentifier,
            @RequestBody Map<String, Object> body) {
        String tenantId = TenantContext.get();
        if (tenantId == null || userIdentifier == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing tenant or user context"));
        }
        String userId = resolveUserId(userIdentifier, tenantId);
        return mintToken(userId, tenantId, body, userId, SecurityAuditLogger.EventType.PAT_CREATED);
    }

    /**
     * Mints a token for {@code userId}, auditing under {@code eventType} with {@code actorId} as
     * the acting party. Self-service minting passes {@code actorId == userId}; the admin-on-behalf-of
     * path ({@link AdminPersonalAccessTokenController}) passes the admin's identity and
     * {@code PAT_ADMIN_CREATED} so the two are distinguishable in the audit trail.
     */
    ResponseEntity<?> mintToken(String userId, String tenantId, Map<String, Object> body,
                                String actorId, SecurityAuditLogger.EventType eventType) {
        // Validate request body
        String name = (String) body.get("name");
        if (name == null || name.isBlank() || name.length() > 200) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required (max 200 chars)"));
        }

        Object expiresInDaysObj = body.get("expiresInDays");
        int expiresInDays = expiresInDaysObj instanceof Number n ? n.intValue() : 90;
        if (expiresInDays < 1 || expiresInDays > 365) {
            return ResponseEntity.badRequest().body(Map.of("error", "expiresInDays must be between 1 and 365"));
        }

        // Get user email for Redis cache
        var userResult = jdbcTemplate.queryForList(
                "SELECT email FROM platform_user WHERE id = ? AND tenant_id = ?", userId, tenantId);
        if (userResult.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
        }
        String userEmail = (String) userResult.get(0).get("email");

        // Check token count limit
        var countResult = jdbcTemplate.queryForList(
                "SELECT COUNT(*) AS cnt FROM user_api_token WHERE user_id = ? AND tenant_id = ? AND revoked = false",
                userId, tenantId);
        int currentCount = countResult.isEmpty() ? 0 : ((Number) countResult.get(0).get("cnt")).intValue();
        if (currentCount >= MAX_TOKENS_PER_USER) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Maximum " + MAX_TOKENS_PER_USER + " active tokens allowed per user"));
        }

        // Generate token
        String rawToken = TOKEN_PREFIX + generateRandomString(TOKEN_LENGTH);
        String tokenHash = sha256(rawToken);
        String tokenPrefixDisplay = rawToken.substring(0, Math.min(rawToken.length(), 8));
        Instant expiresAt = Instant.now().plus(expiresInDays, ChronoUnit.DAYS);

        @SuppressWarnings("unchecked")
        List<String> scopes = body.get("scopes") instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of("api");
        String scopesJson = "[" + String.join(",", scopes.stream().map(s -> "\"" + s + "\"").toList()) + "]";

        // Insert token — uq_user_api_token_name_per_user makes (user_id, name) unique
        try {
            jdbcTemplate.update(
                    "INSERT INTO user_api_token (user_id, tenant_id, name, token_prefix, token_hash, scopes, expires_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
                    userId, tenantId, name.trim(), tokenPrefixDisplay, tokenHash, scopesJson,
                    Timestamp.from(expiresAt));
        } catch (DuplicateKeyException e) {
            throw new UniqueConstraintViolationException("user_api_token", "name", name.trim());
        }

        // Cache token metadata in Redis for gateway validation
        try {
            String patJson = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "userId", userId,
                    "tenantId", tenantId,
                    "email", userEmail,
                    "scopes", scopesJson,
                    "expiresAt", expiresAt.toString()));
            redisTemplate.opsForValue().set(
                    PAT_KEY_PREFIX + tokenHash, patJson,
                    Duration.ofDays(expiresInDays + 1));
        } catch (Exception e) {
            log.warn("Failed to cache PAT in Redis: {}", e.getMessage());
        }

        SecurityAuditLogger.log(eventType, actorId, userId, tenantId, "success", "name=" + name.trim());
        log.info("PAT created for user {} in tenant {} (actor {}): {}", userId, tenantId, actorId, name.trim());

        return ResponseEntity.ok(Map.of(
                "token", rawToken,
                "name", name.trim(),
                "tokenPrefix", tokenPrefixDisplay,
                "scopes", scopes,
                "expiresAt", expiresAt.toString()));
    }

    /**
     * Revoke a personal access token.
     */
    @DeleteMapping("/{tokenId}")
    public ResponseEntity<?> revokeToken(
            @PathVariable String tokenId,
            @RequestHeader(value = "X-User-Id", required = false) String userIdentifier) {
        String tenantId = TenantContext.get();
        if (tenantId == null || userIdentifier == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing tenant or user context"));
        }
        String userId = resolveUserId(userIdentifier, tenantId);

        // Verify token belongs to current user
        var tokens = jdbcTemplate.queryForList(
                "SELECT id, token_hash FROM user_api_token WHERE id = ? AND user_id = ? AND revoked = false",
                tokenId, userId);
        if (tokens.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String tokenHash = (String) tokens.get(0).get("token_hash");

        // Revoke in database
        jdbcTemplate.update(
                "UPDATE user_api_token SET revoked = true, revoked_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), tokenId);

        // Remove from Redis cache and add to revocation set
        try {
            redisTemplate.delete(PAT_KEY_PREFIX + tokenHash);
            redisTemplate.opsForValue().set(
                    REVOCATION_KEY_PREFIX + tokenHash, "revoked", Duration.ofDays(366));
        } catch (Exception e) {
            log.warn("Failed to update PAT in Redis: {}", e.getMessage());
        }

        SecurityAuditLogger.log(SecurityAuditLogger.EventType.PAT_REVOKED,
                userId, tokenId, tenantId, "success", null);
        log.info("PAT revoked for user {} in tenant {}: tokenId={}", userId, tenantId, tokenId);

        return ResponseEntity.ok(Map.of("status", "revoked", "tokenId", tokenId));
    }

    /**
     * Validate a PAT by its hash — called by the gateway as a Redis fallback.
     * Returns token metadata if valid, 404 if not found or revoked.
     */
    @GetMapping("/validate/{tokenHash}")
    public ResponseEntity<?> validateToken(@PathVariable String tokenHash) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT t.id, t.user_id, t.tenant_id, t.scopes, t.expires_at, " +
                        "u.email, u.status " +
                        "FROM user_api_token t " +
                        "JOIN platform_user u ON u.id = t.user_id " +
                        "WHERE t.token_hash = ? AND t.revoked = false",
                tokenHash);

        if (results.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> row = results.get(0);
        Timestamp expiresAt = (Timestamp) row.get("expires_at");
        if (expiresAt != null && expiresAt.toInstant().isBefore(Instant.now())) {
            return ResponseEntity.notFound().build();
        }

        String status = (String) row.get("status");
        if (!"ACTIVE".equals(status)) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> data = Map.of(
                "userId", row.get("user_id"),
                "tenantId", row.get("tenant_id"),
                "email", row.get("email"),
                "scopes", row.get("scopes") != null ? row.get("scopes").toString() : "[\"api\"]",
                "expiresAt", expiresAt != null ? expiresAt.toInstant().toString() : "");

        // Re-cache in Redis for future requests
        try {
            String patJson = OBJECT_MAPPER.writeValueAsString(data);
            long daysUntilExpiry = expiresAt != null
                    ? Duration.between(Instant.now(), expiresAt.toInstant()).toDays() + 1
                    : 90;
            redisTemplate.opsForValue().set(
                    PAT_KEY_PREFIX + tokenHash, patJson,
                    Duration.ofDays(Math.max(1, daysUntilExpiry)));
        } catch (Exception e) {
            log.warn("Failed to re-cache PAT in Redis: {}", e.getMessage());
        }

        return ResponseEntity.ok(data);
    }

    private String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(secureRandom.nextInt(chars.length())));
        }
        return sb.toString();
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
