package io.kelta.auth.service;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
import io.kelta.crypto.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.and;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TotpService Tests")
class TotpServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private EncryptionService encryptionService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private TotpService totpService;

    @BeforeEach
    void setUp() {
        totpService = new TotpService(jdbcTemplate, passwordEncoder, encryptionService,
                mock(io.kelta.auth.service.WorkerClient.class));
    }

    @Nested
    @DisplayName("Secret Generation")
    class SecretGeneration {
        @Test
        void shouldGenerateNonNullBase32Secret() {
            String secret = totpService.generateSecret();
            assertThat(secret).isNotNull().isNotBlank();
            // Base32 characters only
            assertThat(secret).matches("[A-Z2-7]+");
        }

        @Test
        void shouldGenerateUniqueSecrets() {
            String s1 = totpService.generateSecret();
            String s2 = totpService.generateSecret();
            assertThat(s1).isNotEqualTo(s2);
        }
    }

    @Nested
    @DisplayName("QR Code URI")
    class QrCodeUri {
        @Test
        void shouldContainOtpauthPrefix() {
            String uri = totpService.getQrCodeUri("user@example.com", "JBSWY3DPEHPK3PXP");
            assertThat(uri).startsWith("otpauth://totp/Kelta:");
        }

        @Test
        void shouldContainEmailAndSecret() {
            String uri = totpService.getQrCodeUri("user@example.com", "JBSWY3DPEHPK3PXP");
            assertThat(uri).contains("user@example.com");
            assertThat(uri).contains("JBSWY3DPEHPK3PXP");
            assertThat(uri).contains("issuer=Kelta");
        }
    }

    @Nested
    @DisplayName("Code Verification")
    class CodeVerification {
        @Test
        void shouldRejectNullCode() {
            assertThat(totpService.verifyCode("JBSWY3DPEHPK3PXP", null)).isFalse();
        }

        @Test
        void shouldRejectWrongLengthCode() {
            assertThat(totpService.verifyCode("JBSWY3DPEHPK3PXP", "12345")).isFalse();
            assertThat(totpService.verifyCode("JBSWY3DPEHPK3PXP", "1234567")).isFalse();
        }

        @Test
        void shouldAcceptValidCode() throws Exception {
            String secret = totpService.generateSecret();
            // Generate a valid code for current time
            var generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1);
            long currentBucket = Math.floorDiv(new SystemTimeProvider().getTime(), 30);
            String validCode = generator.generate(secret, currentBucket);

            assertThat(totpService.verifyCode(secret, validCode)).isTrue();
        }

        @Test
        void shouldRejectInvalidCode() {
            assertThat(totpService.verifyCode("JBSWY3DPEHPK3PXP", "000000")).isFalse();
        }
    }

    @Nested
    @DisplayName("Enrollment")
    class Enrollment {
        @Test
        void shouldRejectInvalidCodeDuringEnrollment() {
            assertThatThrownBy(() -> totpService.enrollUser("user-1", "JBSWY3DPEHPK3PXP", "000000"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid TOTP code");
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldStoreEncryptedSecretAndEnableMfa() throws Exception {
            String secret = totpService.generateSecret();
            var generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1);
            long currentBucket = Math.floorDiv(new SystemTimeProvider().getTime(), 30);
            String validCode = generator.generate(secret, currentBucket);

            when(encryptionService.encrypt(secret)).thenReturn("enc:v1:encrypted");
            when(jdbcTemplate.queryForList(eq("SELECT id FROM user_totp_secret WHERE user_id = ?"), eq("user-1")))
                    .thenReturn(List.of());

            List<String> codes = totpService.enrollUser("user-1", secret, validCode);

            assertThat(codes).hasSize(8);
            // Each code should be in xxxx-xxxx format
            for (String code : codes) {
                assertThat(code).matches("[a-z0-9]{4}-[a-z0-9]{4}");
            }

            verify(encryptionService).encrypt(secret);
            verify(jdbcTemplate).update(contains("INSERT INTO user_totp_secret"), any(), eq("user-1"),
                    eq("enc:v1:encrypted"), anyLong());
            verify(jdbcTemplate).update(contains("UPDATE platform_user SET mfa_enabled = true"), eq("user-1"));
        }
    }

    @Nested
    @DisplayName("Recovery Codes")
    class RecoveryCodes {
        @Test
        void shouldGenerate8CodesInCorrectFormat() {
            List<String> codes = totpService.generateRecoveryCodes("user-1");
            assertThat(codes).hasSize(8);
            for (String code : codes) {
                assertThat(code).matches("[a-z0-9]{4}-[a-z0-9]{4}");
            }
        }

        @Test
        void shouldVerifyValidRecoveryCode() {
            List<String> codes = totpService.generateRecoveryCodes("user-1");
            String firstCode = codes.get(0);

            // Mock the DB query to return the stored hashes
            List<Map<String, Object>> storedCodes = codes.stream()
                    .map(c -> Map.<String, Object>of(
                            "id", java.util.UUID.randomUUID().toString(),
                            "code_hash", passwordEncoder.encode(c)))
                    .toList();
            when(jdbcTemplate.queryForList(contains("user_recovery_code"), eq("user-1")))
                    .thenReturn(storedCodes);

            assertThat(totpService.verifyRecoveryCode("user-1", firstCode)).isTrue();
        }

        @Test
        void shouldRejectInvalidRecoveryCode() {
            when(jdbcTemplate.queryForList(contains("user_recovery_code"), eq("user-1")))
                    .thenReturn(List.of());

            assertThat(totpService.verifyRecoveryCode("user-1", "abcd-efgh")).isFalse();
        }

        @Test
        void shouldRejectBlankRecoveryCode() {
            assertThat(totpService.verifyRecoveryCode("user-1", "")).isFalse();
            assertThat(totpService.verifyRecoveryCode("user-1", null)).isFalse();
        }
    }

    @Nested
    @DisplayName("MFA Rate Limiting")
    class RateLimiting {
        @Test
        void shouldNotBeLockédByDefault() {
            when(jdbcTemplate.queryForList(contains("mfa_locked_until"), eq("user-1")))
                    .thenReturn(List.of(Map.of("mfa_locked_until", java.sql.Timestamp.from(
                            java.time.Instant.now().minusSeconds(60)))));

            assertThat(totpService.isMfaLocked("user-1")).isFalse();
        }

        @Test
        void shouldBeLockédWhenLockedUntilInFuture() {
            when(jdbcTemplate.queryForList(contains("mfa_locked_until"), eq("user-1")))
                    .thenReturn(List.of(Map.of("mfa_locked_until", java.sql.Timestamp.from(
                            java.time.Instant.now().plusSeconds(300)))));

            assertThat(totpService.isMfaLocked("user-1")).isTrue();
        }

        @Test
        void shouldNotBeLockédWhenNoRecord() {
            when(jdbcTemplate.queryForList(contains("mfa_locked_until"), eq("user-1")))
                    .thenReturn(List.of());

            assertThat(totpService.isMfaLocked("user-1")).isFalse();
        }

        @Test
        void shouldStartAFreshCountOnceTheMfaLockHasExpired() {
            when(jdbcTemplate.queryForList(contains("SELECT mfa_failed_attempts"), eq("user-1")))
                    .thenReturn(List.of(Map.of("mfa_failed_attempts", 1)));

            totpService.incrementMfaFailedAttempts("user-1");

            verify(jdbcTemplate).update(
                    and(contains("mfa_failed_attempts = CASE WHEN mfa_locked_until <= NOW() THEN 1"),
                            contains("mfa_locked_until = CASE WHEN mfa_locked_until <= NOW() THEN NULL")),
                    eq("user-1"));
            verify(jdbcTemplate, never()).update(contains("SET mfa_locked_until = ?"), any(), any());
        }
    }

    @Nested
    @DisplayName("Disable / Reset")
    class DisableReset {
        @Test
        void shouldDeleteAllMfaDataOnReset() {
            totpService.resetMfa("user-1");

            verify(jdbcTemplate).update(contains("DELETE FROM user_recovery_code"), eq("user-1"));
            verify(jdbcTemplate).update(contains("DELETE FROM user_totp_secret"), eq("user-1"));
            verify(jdbcTemplate).update(contains("UPDATE platform_user SET mfa_enabled = false"), eq("user-1"));
        }
    }

    @Nested
    @DisplayName("Enrollment Status")
    class EnrollmentStatus {
        @Test
        void shouldReturnFalseWhenNotEnrolled() {
            when(jdbcTemplate.queryForList(contains("user_totp_secret"), (Object[]) any()))
                    .thenReturn(List.of());

            assertThat(totpService.isEnrolled("user-1")).isFalse();
        }

        @Test
        void shouldReturnTrueWhenEnrolled() {
            when(jdbcTemplate.queryForList(contains("user_totp_secret"), (Object[]) any()))
                    .thenReturn(List.of(Map.of("id", "secret-1")));

            assertThat(totpService.isEnrolled("user-1")).isTrue();
        }
    }
}
