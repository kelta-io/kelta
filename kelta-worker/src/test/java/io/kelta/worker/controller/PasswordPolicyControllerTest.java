package io.kelta.worker.controller;

import io.kelta.worker.repository.PasswordPolicyRepository;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("PasswordPolicyController")
@WithTenant("t1")
class PasswordPolicyControllerTest {

    private PasswordPolicyRepository repository;
    private JdbcTemplate jdbcTemplate;
    private PasswordPolicyController controller;

    @BeforeEach
    void setUp() {
        repository = mock(PasswordPolicyRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new PasswordPolicyController(repository, jdbcTemplate);
    }

    @Nested
    @DisplayName("getPolicy")
    class GetPolicy {

        @Test
        @DisplayName("Should return defaults when no policy set")
        void shouldReturnDefaults() {
            when(repository.findByTenantId("t1")).thenReturn(Optional.empty());

            var response = controller.getPolicy();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("Should return tenant policy when set")
        void shouldReturnTenantPolicy() {
            when(repository.findByTenantId("t1")).thenReturn(Optional.of(Map.ofEntries(
                    Map.entry("min_length", 12),
                    Map.entry("max_length", 128),
                    Map.entry("require_uppercase", true),
                    Map.entry("require_lowercase", true),
                    Map.entry("require_digit", true),
                    Map.entry("require_special", false),
                    Map.entry("history_count", 5),
                    Map.entry("dictionary_check", true),
                    Map.entry("personal_data_check", true),
                    Map.entry("lockout_threshold", 3),
                    Map.entry("lockout_duration_minutes", 60),
                    Map.entry("max_age_days", 90)
            )));

            var response = controller.getPolicy();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("updatePolicy")
    class UpdatePolicy {

        @Test
        @DisplayName("Should reject minLength below 8")
        void shouldRejectMinLengthBelow8() {
            var response = controller.updatePolicy(Map.of("minLength", 4));
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("Should accept valid policy update")
        void shouldAcceptValidUpdate() {
            when(repository.findByTenantId("t1")).thenReturn(Optional.empty());

            var policyMap = new java.util.HashMap<String, Object>();
            policyMap.put("minLength", 12); policyMap.put("maxLength", 128);
            policyMap.put("requireUppercase", true); policyMap.put("requireLowercase", true);
            policyMap.put("requireDigit", true); policyMap.put("requireSpecial", false);
            policyMap.put("historyCount", 5); policyMap.put("dictionaryCheck", true);
            policyMap.put("personalDataCheck", true); policyMap.put("lockoutThreshold", 5);
            policyMap.put("lockoutDurationMinutes", 30); policyMap.put("maxAgeDays", 90);
            var response = controller.updatePolicy(policyMap);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(repository).upsert(eq("t1"), any());
        }
    }

    @Nested
    @DisplayName("unlockAccount")
    class UnlockAccount {

        @Test
        @DisplayName("Should unlock user in same tenant")
        void shouldUnlockUser() {
            when(jdbcTemplate.queryForList(startsWith("SELECT id FROM platform_user"), any(Object[].class)))
                    .thenReturn(List.of(Map.of("id", "user-1")));

            var response = controller.unlockAccount("user-1");
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("Should return 404 for user in other tenant")
        void shouldReturn404ForOtherTenant() {
            when(jdbcTemplate.queryForList(startsWith("SELECT id FROM platform_user"), any(Object[].class)))
                    .thenReturn(List.of());

            var response = controller.unlockAccount("user-other");
            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }
}
