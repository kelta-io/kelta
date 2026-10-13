package io.kelta.auth.service;

import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import io.kelta.crypto.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service for TOTP (RFC 6238) multi-factor authentication.
 *
 * <p>Handles secret generation, code verification with replay prevention,
 * recovery code management, and MFA rate limiting.
 *
 * <p>TOTP secrets are encrypted at rest via {@link EncryptionService}.
 * Recovery codes are BCrypt-hashed (shown once, never recoverable).
 *
 * @since 1.0.0
 */
@Service
public class TotpService {

    private static final Logger log = LoggerFactory.getLogger(TotpService.class);

    private static final int RECOVERY_CODE_COUNT = 8;
    private static final int RECOVERY_CODE_LENGTH = 8; // 4+4 with dash
    private static final String RECOVERY_CODE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MFA_LOCKOUT_THRESHOLD = 5;
    private static final int MFA_LOCKOUT_DURATION_MINUTES = 5;
    private static final String ISSUER = "Kelta";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final EncryptionService encryptionService;
    private final SecretGenerator secretGenerator;
    private final CodeVerifier codeVerifier;
    private final SecureRandom secureRandom;

    private final WorkerClient workerClient;

    public TotpService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
                       @org.springframework.beans.factory.annotation.Autowired(required = false) EncryptionService encryptionService,
                       WorkerClient workerClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.encryptionService = encryptionService;
        this.workerClient = workerClient;
        this.secretGenerator = new DefaultSecretGenerator();
        this.codeVerifier = new DefaultCodeVerifier(
                new DefaultCodeGenerator(HashingAlgorithm.SHA1),
                new SystemTimeProvider()
        );
        // Allow ±1 window (30 seconds each) for clock drift
        ((DefaultCodeVerifier) this.codeVerifier).setAllowedTimePeriodDiscrepancy(1);
        this.secureRandom = new SecureRandom();
    }

    // -----------------------------------------------------------------------
    // Secret Generation
    // -----------------------------------------------------------------------

    public String generateSecret() {
        return secretGenerator.generate();
    }

    public String getQrCodeUri(String email, String secret) {
        return String.format("otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=6&period=30",
                ISSUER, email, secret, ISSUER);
    }

    // -----------------------------------------------------------------------
    // Code Verification
    // -----------------------------------------------------------------------

    public boolean verifyCode(String secret, String code) {
        if (code == null || code.length() != 6) return false;
        return codeVerifier.isValidCode(secret, code);
    }

    // -----------------------------------------------------------------------
    // Enrollment
    // -----------------------------------------------------------------------

    public List<String> enrollUser(String userId, String secret, String code) {
        // Verify the code first
        if (!verifyCode(secret, code)) {
            throw new IllegalArgumentException("Invalid TOTP code");
        }

        // Encrypt and store the secret
        String encryptedSecret = encryptionService.encrypt(secret);
        long nowEpoch = Instant.now().getEpochSecond();

        // Upsert: if a pending (unverified) secret exists, replace it
        var existing = jdbcTemplate.queryForList(
                "SELECT id FROM user_totp_secret WHERE user_id = ?", userId);

        if (existing.isEmpty()) {
            jdbcTemplate.update(
                    "INSERT INTO user_totp_secret (id, user_id, secret, verified, last_used_at, created_at, updated_at) " +
                            "VALUES (?, ?, ?, true, ?, NOW(), NOW())",
                    UUID.randomUUID().toString(), userId, encryptedSecret, nowEpoch
            );
        } else {
            jdbcTemplate.update(
                    "UPDATE user_totp_secret SET secret = ?, verified = true, last_used_at = ?, " +
                            "mfa_failed_attempts = 0, mfa_locked_until = NULL, updated_at = NOW() WHERE user_id = ?",
                    encryptedSecret, nowEpoch, userId
            );
        }

        // Enable MFA on the user
        jdbcTemplate.update(
                "UPDATE platform_user SET mfa_enabled = true, updated_at = NOW() WHERE id = ?", userId);

        // Generate recovery codes
        return generateRecoveryCodes(userId);
    }

    // -----------------------------------------------------------------------
    // Recovery Codes
    // -----------------------------------------------------------------------

    public List<String> generateRecoveryCodes(String userId) {
        // Delete existing codes
        jdbcTemplate.update("DELETE FROM user_recovery_code WHERE user_id = ?", userId);

        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = generateSingleRecoveryCode();
            String hash = passwordEncoder.encode(code);
            jdbcTemplate.update(
                    "INSERT INTO user_recovery_code (id, user_id, code_hash, used, created_at) VALUES (?, ?, ?, false, NOW())",
                    UUID.randomUUID().toString(), userId, hash
            );
            codes.add(code);
        }

        log.info("Generated {} recovery codes for user {}", RECOVERY_CODE_COUNT, userId);
        return codes;
    }

    private String generateSingleRecoveryCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
            if (i == 4) sb.append('-');
            sb.append(RECOVERY_CODE_CHARS.charAt(secureRandom.nextInt(RECOVERY_CODE_CHARS.length())));
        }
        return sb.toString();
    }

    public boolean verifyRecoveryCode(String userId, String code) {
        if (code == null || code.isBlank()) return false;

        String normalizedCode = code.trim().toLowerCase();
        var codes = jdbcTemplate.queryForList(
                "SELECT id, code_hash FROM user_recovery_code WHERE user_id = ? AND used = false",
                userId
        );

        // Check all codes (timing-safe: don't short-circuit)
        String matchedId = null;
        for (var row : codes) {
            if (passwordEncoder.matches(normalizedCode, (String) row.get("code_hash"))) {
                matchedId = (String) row.get("id");
            }
        }

        if (matchedId != null) {
            jdbcTemplate.update("UPDATE user_recovery_code SET used = true WHERE id = ?", matchedId);
            resetMfaFailedAttempts(userId);
            return true;
        }
        return false;
    }

    public int getRemainingRecoveryCodeCount(String userId) {
        var result = jdbcTemplate.queryForList(
                "SELECT COUNT(*) AS cnt FROM user_recovery_code WHERE user_id = ? AND used = false", userId);
        return result.isEmpty() ? 0 : ((Number) result.get(0).get("cnt")).intValue();
    }

    // -----------------------------------------------------------------------
    // Disable / Reset
    // -----------------------------------------------------------------------

    public void disableMfa(String userId, String code) {
        String secret = loadSecret(userId);
        if (secret == null || !verifyCode(secret, code)) {
            throw new IllegalArgumentException("Invalid TOTP code");
        }
        deleteMfaData(userId);
        sendMfaDisabledEmail(userId);
    }

    public void resetMfa(String userId) {
        deleteMfaData(userId);
        sendMfaDisabledEmail(userId);
        log.info("MFA reset by admin for user {}", userId);
    }

    private void sendMfaDisabledEmail(String userId) {
        try {
            var rows = jdbcTemplate.queryForList(
                    "SELECT pu.email, pu.first_name, pu.tenant_id, t.name AS tenant_name "
                            + "FROM platform_user pu JOIN tenant t ON t.id = pu.tenant_id "
                            + "WHERE pu.id = ?",
                    userId);
            if (rows.isEmpty()) return;
            var row = rows.get(0);
            workerClient.sendTemplateEmail(
                    (String) row.get("tenant_id"),
                    (String) row.get("email"),
                    "user.mfa_disabled",
                    java.util.Map.of(
                            "tenantName", row.getOrDefault("tenant_name", "Kelta"),
                            "firstName",  row.getOrDefault("first_name", ""),
                            "changedAt",  java.time.Instant.now().toString()),
                    "MFA_DISABLED", userId);
        } catch (Exception e) {
            log.warn("Failed to send MFA-disabled notification for {}: {}", userId, e.getMessage());
        }
    }

    private void deleteMfaData(String userId) {
        jdbcTemplate.update("DELETE FROM user_recovery_code WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_totp_secret WHERE user_id = ?", userId);
        jdbcTemplate.update("UPDATE platform_user SET mfa_enabled = false, updated_at = NOW() WHERE id = ?", userId);
    }

    // -----------------------------------------------------------------------
    // Secret Loading
    // -----------------------------------------------------------------------

    public String loadSecret(String userId) {
        var results = jdbcTemplate.queryForList(
                "SELECT secret FROM user_totp_secret WHERE user_id = ? AND verified = true", userId);
        if (results.isEmpty()) return null;
        return encryptionService.decrypt((String) results.get(0).get("secret"));
    }

    public boolean isEnrolled(String userId) {
        var results = jdbcTemplate.queryForList(
                "SELECT id FROM user_totp_secret WHERE user_id = ? AND verified = true", userId);
        return !results.isEmpty();
    }

    // -----------------------------------------------------------------------
    // Replay Prevention
    // -----------------------------------------------------------------------

    /**
     * Check if the TOTP code was already used in this time window.
     * Updates last_used_at on success to prevent replay.
     */
    public boolean verifyCodeWithReplayPrevention(String userId, String code) {
        String secret = loadSecret(userId);
        if (secret == null) return false;
        if (!verifyCode(secret, code)) return false;

        long currentEpoch = Instant.now().getEpochSecond();
        long currentWindow = currentEpoch / 30; // 30-second TOTP period

        var results = jdbcTemplate.queryForList(
                "SELECT last_used_at FROM user_totp_secret WHERE user_id = ?", userId);
        if (!results.isEmpty()) {
            Object lastUsed = results.get(0).get("last_used_at");
            if (lastUsed != null) {
                long lastUsedWindow = ((Number) lastUsed).longValue() / 30;
                if (lastUsedWindow == currentWindow) {
                    log.warn("TOTP code replay attempt for user {}", userId);
                    return false;
                }
            }
        }

        // Mark as used
        jdbcTemplate.update(
                "UPDATE user_totp_secret SET last_used_at = ?, updated_at = NOW() WHERE user_id = ?",
                currentEpoch, userId);
        return true;
    }

    // -----------------------------------------------------------------------
    // Rate Limiting
    // -----------------------------------------------------------------------

    public boolean isMfaLocked(String userId) {
        var results = jdbcTemplate.queryForList(
                "SELECT mfa_locked_until FROM user_totp_secret WHERE user_id = ?", userId);
        if (results.isEmpty()) return false;
        Object lockedUntil = results.get(0).get("mfa_locked_until");
        if (lockedUntil == null) return false;
        return ((Timestamp) lockedUntil).toInstant().isAfter(Instant.now());
    }

    public void incrementMfaFailedAttempts(String userId) {
        // As for password lockout: once an MFA lock has run out the count starts over.
        jdbcTemplate.update(
                "UPDATE user_totp_secret SET "
                        + "mfa_failed_attempts = CASE WHEN mfa_locked_until <= NOW() THEN 1 ELSE mfa_failed_attempts + 1 END, "
                        + "mfa_locked_until = CASE WHEN mfa_locked_until <= NOW() THEN NULL ELSE mfa_locked_until END, "
                        + "updated_at = NOW() WHERE user_id = ?",
                userId);

        var results = jdbcTemplate.queryForList(
                "SELECT mfa_failed_attempts FROM user_totp_secret WHERE user_id = ?", userId);
        if (!results.isEmpty()) {
            int attempts = ((Number) results.get(0).get("mfa_failed_attempts")).intValue();
            if (attempts >= MFA_LOCKOUT_THRESHOLD) {
                Instant lockedUntil = Instant.now().plus(MFA_LOCKOUT_DURATION_MINUTES, ChronoUnit.MINUTES);
                jdbcTemplate.update(
                        "UPDATE user_totp_secret SET mfa_locked_until = ?, updated_at = NOW() WHERE user_id = ?",
                        Timestamp.from(lockedUntil), userId);
                log.warn("MFA locked for user {}: {} failed attempts, locked until {}",
                        userId, attempts, lockedUntil);
            }
        }
    }

    public void resetMfaFailedAttempts(String userId) {
        jdbcTemplate.update(
                "UPDATE user_totp_secret SET mfa_failed_attempts = 0, mfa_locked_until = NULL, updated_at = NOW() WHERE user_id = ?",
                userId);
    }
}
