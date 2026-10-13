package io.kelta.worker.service;

import io.kelta.runtime.module.integration.spi.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("UserInviteService")
class UserInviteServiceTest {

    private JdbcTemplate jdbcTemplate;
    private EmailService emailService;
    private UserInviteService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        emailService = mock(EmailService.class);
        service = new UserInviteService(jdbcTemplate, emailService, "https://auth.example.com/");
    }

    @Test
    @DisplayName("Should generate token, store it, and queue invite email")
    void shouldGenerateAndSend() {
        when(jdbcTemplate.queryForList(anyString(), eq("u1"), eq("t1")))
                .thenReturn(List.of(Map.of(
                        "email", "ada@example.com",
                        "first_name", "Ada",
                        "tenant_name", "Acme")));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        when(emailService.sendByKey(anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Optional.of("log-1"));

        String token = service.inviteUser("t1", "u1");

        assertThat(token).isNotNull();
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendByKey(eq("t1"), eq("ada@example.com"), eq("user.invite"),
                vars.capture(), eq("USER_INVITE"), eq("u1"));
        assertThat(vars.getValue())
                .containsEntry("firstName", "Ada")
                .containsEntry("tenantName", "Acme")
                .containsEntry("email", "ada@example.com");
        assertThat(vars.getValue().get("actionUrl"))
                .as("kelta-auth's reset page on the configured issuer, the token only")
                .isEqualTo("https://auth.example.com/reset-password?token=" + token);
    }

    @Test
    @DisplayName("Should store the token but send nothing when no issuer is configured")
    void shouldNotSendWithoutAnIssuer() {
        UserInviteService unconfigured = new UserInviteService(jdbcTemplate, emailService, " ");
        when(jdbcTemplate.queryForList(anyString(), eq("u1"), eq("t1")))
                .thenReturn(List.of(Map.of("email", "ada@example.com", "first_name", "Ada", "tenant_name", "Acme")));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);

        assertThat(unconfigured.inviteUser("t1", "u1")).isNotNull();
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Should return null when user is missing")
    void shouldReturnNullWhenMissing() {
        when(jdbcTemplate.queryForList(anyString(), eq("u1"), eq("t1")))
                .thenReturn(List.of());

        assertThat(service.inviteUser("t1", "u1")).isNull();
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Should default auto-invite to true when tenant lookup fails")
    void shouldDefaultAutoInviteTrueOnFailure() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq("t1")))
                .thenThrow(new RuntimeException("boom"));
        assertThat(service.isAutoInviteEnabled("t1")).isTrue();
    }

    @Test
    @DisplayName("Should honour explicit auto-invite=false")
    void shouldHonourExplicitFalse() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq("t1")))
                .thenReturn(false);
        assertThat(service.isAutoInviteEnabled("t1")).isFalse();
    }
}
