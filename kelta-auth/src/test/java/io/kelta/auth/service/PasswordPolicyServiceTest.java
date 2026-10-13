package io.kelta.auth.service;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.and;
import static org.mockito.Mockito.*;

@DisplayName("PasswordPolicyService")
class PasswordPolicyServiceTest {

    private JdbcTemplate jdbcTemplate;
    private PasswordEncoder passwordEncoder;
    private PasswordPolicyService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        passwordEncoder = new BCryptPasswordEncoder();
        service = new PasswordPolicyService(jdbcTemplate, passwordEncoder,
                mock(io.kelta.auth.service.WorkerClient.class),
                mock(io.kelta.auth.config.AuthProperties.class));
    }

    @Nested
    @DisplayName("validatePassword")
    class ValidatePassword {

        @BeforeEach
        void setupDefaultPolicy() {
            // No tenant policy → defaults apply
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        }

        @Test
        @DisplayName("Should reject password shorter than minLength")
        void shouldRejectTooShort() {
            List<String> violations = service.validatePassword("short", "user@test.com", "Test User", null);
            assertThat(violations).anyMatch(v -> v.contains("at least"));
        }

        @Test
        @DisplayName("Should accept valid password with default policy")
        void shouldAcceptValidDefault() {
            List<String> violations = service.validatePassword("validpassword123", "user@test.com", "Test User", null);
            // Default policy: no complexity requirements, but dictionary/personal data checks
            // "validpassword123" should pass (not in dictionary, doesn't contain personal data)
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Should return ALL violations, not just first")
        void shouldReturnAllViolations() {
            // Mock policy requiring everything
            var policyMap = new java.util.HashMap<String, Object>();
            policyMap.put("min_length", 12); policyMap.put("max_length", 128);
            policyMap.put("require_uppercase", true); policyMap.put("require_lowercase", true);
            policyMap.put("require_digit", true); policyMap.put("require_special", true);
            policyMap.put("history_count", 0); policyMap.put("dictionary_check", false);
            policyMap.put("personal_data_check", false); policyMap.put("lockout_threshold", 5);
            policyMap.put("lockout_duration_minutes", 30); policyMap.put("max_age_days", null);
            when(jdbcTemplate.queryForList(contains("min_length"), any(Object[].class)))
                    .thenReturn(List.of(policyMap));

            // "ab" fails: length, uppercase, digit, special
            List<String> violations = service.validatePassword("ab", "x@x.com", "", "tenant-1");
            assertThat(violations).hasSizeGreaterThan(1);
        }

        @Test
        @DisplayName("Should block password containing user email parts")
        void shouldBlockEmailParts() {
            List<String> violations = service.validatePassword("john_secure_pass!", "john.smith@company.com", "Other Name", null);
            assertThat(violations).anyMatch(v -> v.contains("name or email"));
        }

        @Test
        @DisplayName("Should block password containing user name parts")
        void shouldBlockNameParts() {
            List<String> violations = service.validatePassword("smithrules123!", "other@test.com", "John Smith", null);
            assertThat(violations).anyMatch(v -> v.contains("name or email"));
        }

        @Test
        @DisplayName("Should NOT block short email segments (< 3 chars)")
        void shouldNotBlockShortSegments() {
            List<String> violations = service.validatePassword("ab_secure_pass!", "ab@co.com", "Jo Li", null);
            // "ab", "co", "Jo", "Li" are all < 3 chars — should NOT trigger personal data check
            assertThat(violations.stream().filter(v -> v.contains("name or email")).count()).isZero();
        }
    }

    @Nested
    @DisplayName("checkHistory")
    class CheckHistory {

        @Test
        @DisplayName("Should reject password matching recent history")
        void shouldRejectRecentPassword() {
            String oldHash = passwordEncoder.encode("OldPassword123!");

            // Default policy → historyCount=3
            when(jdbcTemplate.queryForList(contains("min_length"), any(Object[].class)))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("password_hash"), any(Object[].class)))
                    .thenReturn(List.of(Map.of("password_hash", oldHash)));

            Optional<String> result = service.checkHistory("user-1", "OldPassword123!", null);
            assertThat(result).isPresent().hasValue("Password was recently used");
        }

        @Test
        @DisplayName("Should accept password not in history")
        void shouldAcceptNewPassword() {
            String oldHash = passwordEncoder.encode("CompletelyDifferent!");

            when(jdbcTemplate.queryForList(contains("min_length"), any(Object[].class)))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("password_hash"), any(Object[].class)))
                    .thenReturn(List.of(Map.of("password_hash", oldHash)));

            Optional<String> result = service.checkHistory("user-1", "BrandNewPassword456!", null);
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("loadPolicy")
    class LoadPolicy {

        @Test
        @DisplayName("Should return defaults when no tenant policy exists")
        void shouldReturnDefaults() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

            var policy = service.loadPolicy("tenant-1");
            assertThat(policy.minLength()).isEqualTo(8);
            assertThat(policy.lockoutThreshold()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should return defaults for null tenantId")
        void shouldReturnDefaultsForNull() {
            var policy = service.loadPolicy(null);
            assertThat(policy).isEqualTo(PasswordPolicyService.PasswordPolicy.DEFAULTS);
        }
    }

    @Nested
    @DisplayName("lockout")
    class Lockout {

        @Test
        @DisplayName("Should increment failed attempts")
        void shouldIncrementAttempts() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("failed_attempts"), any(Object[].class)))
                    .thenReturn(List.of(Map.of("failed_attempts", 3)));

            service.incrementFailedAttempts("user-1", null);

            verify(jdbcTemplate).update(contains("ELSE failed_attempts + 1"),
                    any(Timestamp.class), any(Timestamp.class), any(Timestamp.class), eq("user-1"));
        }

        @Test
        @DisplayName("Should start a fresh count once a lockout has expired")
        void shouldRestartCountAfterExpiredLock() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

            service.incrementFailedAttempts("user-1", null);

            verify(jdbcTemplate).update(
                    and(contains("failed_attempts = CASE WHEN locked_until <= ? THEN 1"),
                            contains("locked_until = CASE WHEN locked_until <= ? THEN NULL")),
                    any(Timestamp.class), any(Timestamp.class), any(Timestamp.class), eq("user-1"));
        }

        @Test
        @DisplayName("Should reset failed attempts")
        void shouldResetAttempts() {
            service.resetFailedAttempts("user-1");

            verify(jdbcTemplate).update(
                    contains("failed_attempts = 0"),
                    any(Timestamp.class), any()
            );
        }
    }
}
