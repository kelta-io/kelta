package io.kelta.auth.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service for password policy validation, history tracking, and lockout enforcement.
 *
 * <p>Supports per-tenant configurable policies with NIST SP 800-63B aligned defaults.
 * Uses a bundled common password dictionary (10k entries) for dictionary checks.
 *
 * @since 1.0.0
 */
@Service
public class PasswordPolicyService {

    private static final Logger log = LoggerFactory.getLogger(PasswordPolicyService.class);

    private static final int MIN_SEGMENT_LENGTH = 3;
    private static final int MIN_DICTIONARY_SUBSTRING_LENGTH = 4;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final WorkerClient workerClient;
    private final io.kelta.auth.config.AuthProperties authProperties;
    private Set<String> commonPasswords = Set.of();

    public PasswordPolicyService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
                                  WorkerClient workerClient,
                                  io.kelta.auth.config.AuthProperties authProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.workerClient = workerClient;
        this.authProperties = authProperties;
    }

    @PostConstruct
    void loadDictionary() {
        try {
            var resource = new ClassPathResource("common-passwords.txt");
            try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
                Set<String> passwords = new HashSet<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim().toLowerCase();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        passwords.add(trimmed);
                    }
                }
                commonPasswords = Set.copyOf(passwords);
                log.info("Loaded {} common passwords for dictionary check", commonPasswords.size());
            }
        } catch (Exception e) {
            log.warn("Failed to load common-passwords.txt — dictionary check will be disabled: {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Policy Loading
    // -----------------------------------------------------------------------

    public record PasswordPolicy(
            int minLength, int maxLength,
            boolean requireUppercase, boolean requireLowercase,
            boolean requireDigit, boolean requireSpecial,
            int historyCount, boolean dictionaryCheck, boolean personalDataCheck,
            int lockoutThreshold, int lockoutDurationMinutes,
            Integer maxAgeDays
    ) {
        static final PasswordPolicy DEFAULTS = new PasswordPolicy(
                8, 128, false, false, false, false,
                3, true, true, 5, 30, null
        );
    }

    public PasswordPolicy loadPolicy(String tenantId) {
        if (tenantId == null) return PasswordPolicy.DEFAULTS;

        var results = jdbcTemplate.queryForList(
                "SELECT min_length, max_length, require_uppercase, require_lowercase, " +
                        "require_digit, require_special, history_count, dictionary_check, " +
                        "personal_data_check, lockout_threshold, lockout_duration_minutes, max_age_days " +
                        "FROM password_policy WHERE tenant_id = ?",
                tenantId
        );

        if (results.isEmpty()) return PasswordPolicy.DEFAULTS;

        var row = results.get(0);
        return new PasswordPolicy(
                (int) row.get("min_length"),
                (int) row.get("max_length"),
                (boolean) row.get("require_uppercase"),
                (boolean) row.get("require_lowercase"),
                (boolean) row.get("require_digit"),
                (boolean) row.get("require_special"),
                (int) row.get("history_count"),
                (boolean) row.get("dictionary_check"),
                (boolean) row.get("personal_data_check"),
                (int) row.get("lockout_threshold"),
                (int) row.get("lockout_duration_minutes"),
                (Integer) row.get("max_age_days")
        );
    }

    // -----------------------------------------------------------------------
    // MFA Policy
    // -----------------------------------------------------------------------

    public boolean isMfaRequired(String tenantId) {
        if (tenantId == null) return false;
        var results = jdbcTemplate.queryForList(
                "SELECT mfa_required FROM password_policy WHERE tenant_id = ?", tenantId);
        if (results.isEmpty()) return false;
        return Boolean.TRUE.equals(results.get(0).get("mfa_required"));
    }

    // -----------------------------------------------------------------------
    // Password Validation
    // -----------------------------------------------------------------------

    public List<String> validatePassword(String password, String email, String displayName, String tenantId) {
        PasswordPolicy policy = loadPolicy(tenantId);
        List<String> violations = new ArrayList<>();

        // Length checks
        if (password.length() < policy.minLength()) {
            violations.add("Password must be at least " + policy.minLength() + " characters");
        }
        if (password.length() > policy.maxLength()) {
            violations.add("Password must be at most " + policy.maxLength() + " characters");
        }

        // Complexity checks
        if (policy.requireUppercase() && !password.chars().anyMatch(Character::isUpperCase)) {
            violations.add("Password must contain at least one uppercase letter");
        }
        if (policy.requireLowercase() && !password.chars().anyMatch(Character::isLowerCase)) {
            violations.add("Password must contain at least one lowercase letter");
        }
        if (policy.requireDigit() && !password.chars().anyMatch(Character::isDigit)) {
            violations.add("Password must contain at least one digit");
        }
        if (policy.requireSpecial() && password.chars().allMatch(c -> Character.isLetterOrDigit(c))) {
            violations.add("Password must contain at least one special character");
        }

        // Dictionary check (exact + substring)
        if (policy.dictionaryCheck() && !commonPasswords.isEmpty()) {
            String lower = password.toLowerCase();
            if (commonPasswords.contains(lower)) {
                violations.add("Password is too common");
            } else {
                // Substring match — check if any dictionary word >= 4 chars is contained in password
                for (String word : commonPasswords) {
                    if (word.length() >= MIN_DICTIONARY_SUBSTRING_LENGTH && lower.contains(word)) {
                        violations.add("Password contains a common word");
                        break;
                    }
                }
            }
        }

        // Personal data check
        if (policy.personalDataCheck()) {
            String lower = password.toLowerCase();
            List<String> segments = extractPersonalSegments(email, displayName);
            for (String segment : segments) {
                if (lower.contains(segment)) {
                    violations.add("Password must not contain your name or email");
                    break;
                }
            }
        }

        return violations;
    }

    private List<String> extractPersonalSegments(String email, String displayName) {
        List<String> segments = new ArrayList<>();

        if (email != null && !email.isBlank()) {
            String localPart = email.contains("@") ? email.substring(0, email.indexOf("@")) : email;
            for (String part : localPart.split("[.+_-]")) {
                if (part.length() >= MIN_SEGMENT_LENGTH) {
                    segments.add(part.toLowerCase());
                }
            }
        }

        if (displayName != null && !displayName.isBlank()) {
            for (String part : displayName.split("\\s+")) {
                if (part.length() >= MIN_SEGMENT_LENGTH) {
                    segments.add(part.toLowerCase());
                }
            }
        }

        return segments;
    }

    // -----------------------------------------------------------------------
    // Password History
    // -----------------------------------------------------------------------

    /**
     * Checks if the new password matches any of the last N passwords.
     * Timing-safe: always compares against ALL entries (no short-circuit).
     */
    public Optional<String> checkHistory(String userId, String newPassword, String tenantId) {
        PasswordPolicy policy = loadPolicy(tenantId);
        if (policy.historyCount() <= 0) return Optional.empty();

        var history = jdbcTemplate.queryForList(
                "SELECT password_hash FROM password_history WHERE user_id = ? ORDER BY created_at DESC LIMIT ?",
                userId, policy.historyCount()
        );

        // Timing-safe: compare ALL entries, don't short-circuit on first match
        boolean matched = false;
        for (var row : history) {
            String hash = (String) row.get("password_hash");
            if (passwordEncoder.matches(newPassword, hash)) {
                matched = true;
            }
            // Do NOT break — continue comparing all entries for timing safety
        }

        return matched ? Optional.of("Password was recently used") : Optional.empty();
    }

    public void saveToHistory(String userId, String passwordHash, String tenantId) {
        jdbcTemplate.update(
                "INSERT INTO password_history (id, user_id, password_hash, created_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), userId, passwordHash, Timestamp.from(Instant.now())
        );

        // Prune oldest entries beyond historyCount
        PasswordPolicy policy = loadPolicy(tenantId);
        jdbcTemplate.update(
                "DELETE FROM password_history WHERE user_id = ? AND id NOT IN " +
                        "(SELECT id FROM password_history WHERE user_id = ? ORDER BY created_at DESC LIMIT ?)",
                userId, userId, policy.historyCount()
        );
    }

    // -----------------------------------------------------------------------
    // Account Lockout
    // -----------------------------------------------------------------------

    public void incrementFailedAttempts(String userId, String tenantId) {
        // A lockout that has run out starts a fresh count: the attempts that caused it are
        // spent, so the next miss must not re-lock the account on their strength.
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "UPDATE user_credential SET "
                        + "failed_attempts = CASE WHEN locked_until <= ? THEN 1 ELSE failed_attempts + 1 END, "
                        + "locked_until = CASE WHEN locked_until <= ? THEN NULL ELSE locked_until END, "
                        + "updated_at = ? WHERE user_id = ?",
                now, now, now, userId
        );

        // Check if threshold reached
        PasswordPolicy policy = loadPolicy(tenantId);
        var result = jdbcTemplate.queryForList(
                "SELECT failed_attempts FROM user_credential WHERE user_id = ?", userId
        );
        if (!result.isEmpty()) {
            int attempts = (int) result.get(0).get("failed_attempts");
            if (attempts >= policy.lockoutThreshold()) {
                Instant lockedUntil = Instant.now().plus(policy.lockoutDurationMinutes(), ChronoUnit.MINUTES);
                jdbcTemplate.update(
                        "UPDATE user_credential SET locked_until = ?, updated_at = ? WHERE user_id = ?",
                        Timestamp.from(lockedUntil), Timestamp.from(Instant.now()), userId
                );
                log.warn("Account locked for user {}: {} failed attempts, locked until {}",
                        userId, attempts, lockedUntil);
                sendAccountLockedEmail(userId, lockedUntil);
            }
        }
    }

    private void sendAccountLockedEmail(String userId, Instant lockedUntil) {
        try {
            var rows = jdbcTemplate.queryForList(
                    "SELECT pu.email, pu.first_name, pu.tenant_id, t.name AS tenant_name "
                            + "FROM platform_user pu JOIN tenant t ON t.id = pu.tenant_id "
                            + "WHERE pu.id = ?",
                    userId
            );
            if (rows.isEmpty()) return;
            var row = rows.get(0);
            String resetUrl = authProperties.getUiBaseUrl() + "/forgot-password";
            workerClient.sendTemplateEmail(
                    (String) row.get("tenant_id"),
                    (String) row.get("email"),
                    "user.account_locked",
                    java.util.Map.of(
                            "tenantName", row.getOrDefault("tenant_name", "Kelta"),
                            "firstName",  row.getOrDefault("first_name", ""),
                            "unlockAt",   lockedUntil.toString(),
                            "actionUrl",  resetUrl),
                    "ACCOUNT_LOCKED", userId);
        } catch (Exception e) {
            log.warn("Failed to send account-locked notification for {}: {}", userId, e.getMessage());
        }
    }

    public void resetFailedAttempts(String userId) {
        jdbcTemplate.update(
                "UPDATE user_credential SET failed_attempts = 0, locked_until = NULL, updated_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now()), userId
        );
    }

    public void unlockAccount(String userId) {
        resetFailedAttempts(userId);
        log.info("Account unlocked for user {}", userId);
    }

    // -----------------------------------------------------------------------
    // Password Expiration
    // -----------------------------------------------------------------------

    public void checkPasswordExpiration(String userId, String tenantId) {
        PasswordPolicy policy = loadPolicy(tenantId);
        if (policy.maxAgeDays() == null) return;

        var result = jdbcTemplate.queryForList(
                "SELECT password_changed_at FROM user_credential WHERE user_id = ?", userId
        );
        if (result.isEmpty()) return;

        Object changedAt = result.get(0).get("password_changed_at");
        if (changedAt == null) return;

        Instant lastChanged = ((Timestamp) changedAt).toInstant();
        Instant expiresAt = lastChanged.plus(policy.maxAgeDays(), ChronoUnit.DAYS);

        if (Instant.now().isAfter(expiresAt)) {
            jdbcTemplate.update(
                    "UPDATE user_credential SET force_change_on_login = true, updated_at = ? WHERE user_id = ?",
                    Timestamp.from(Instant.now()), userId
            );
            log.info("Password expired for user {} — forcing change on next login", userId);
        }
    }
}
