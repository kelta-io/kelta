package io.kelta.worker.controller;

import io.kelta.worker.service.email.DefaultEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("InternalEmailController")
class InternalEmailControllerTest {

    private DefaultEmailService emailService;
    private JdbcTemplate jdbcTemplate;
    private InternalEmailController controller;

    @BeforeEach
    void setUp() {
        emailService = mock(DefaultEmailService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new InternalEmailController(
                emailService, jdbcTemplate, "test-secret-token", "https://app.kelta.io");
    }

    @Test
    @DisplayName("Should accept request with valid internal token")
    void shouldAcceptWithValidToken() {
        when(emailService.queueEmail("t1", "user@test.com", "Subject", "<p>Body</p>", "TEST", null))
                .thenReturn("log-123");

        var request = new InternalEmailController.SendEmailRequest(
                "t1", "user@test.com", "Subject", "<p>Body</p>", "TEST", null);

        var response = controller.sendEmail("test-secret-token", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("emailLogId", "log-123");
        assertThat(response.getBody()).containsEntry("status", "QUEUED");
    }

    @Test
    @DisplayName("Should reject request with missing token")
    void shouldRejectMissingToken() {
        var request = new InternalEmailController.SendEmailRequest(
                "t1", "user@test.com", "Subject", "<p>Body</p>", "TEST", null);

        var response = controller.sendEmail(null, request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(emailService, never()).queueEmail(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should reject request with invalid token")
    void shouldRejectInvalidToken() {
        var request = new InternalEmailController.SendEmailRequest(
                "t1", "user@test.com", "Subject", "<p>Body</p>", "TEST", null);

        var response = controller.sendEmail("wrong-token", request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(emailService, never()).queueEmail(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should reject when internal token not configured")
    void shouldRejectWhenTokenNotConfigured() {
        InternalEmailController noTokenController = new InternalEmailController(
                emailService, jdbcTemplate, "", "https://app.kelta.io");

        var request = new InternalEmailController.SendEmailRequest(
                "t1", "user@test.com", "Subject", "<p>Body</p>", "TEST", null);

        var response = noTokenController.sendEmail("any-token", request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("Should send invite via user_invite template with substituted vars")
    void shouldSendInvite() {
        when(jdbcTemplate.queryForList("SELECT name, slug FROM tenant WHERE id = ?", "t1"))
                .thenReturn(List.of(Map.of("name", "Acme Inc", "slug", "acme")));
        when(emailService.sendByName(
                eq("t1"), eq("invitee@test.com"), eq("user_invite"),
                anyMap(), eq("USER_INVITE"), eq("tok-abc")))
                .thenReturn(Optional.of("log-99"));

        var request = new InternalEmailController.SendInviteRequest(
                "invitee@test.com", "t1", "tok-abc");

        var response = controller.sendInvite("test-secret-token", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("emailLogId", "log-99");
        assertThat(response.getBody()).containsEntry("status", "QUEUED");

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendByName(eq("t1"), eq("invitee@test.com"), eq("user_invite"),
                captor.capture(), eq("USER_INVITE"), eq("tok-abc"));
        assertThat(captor.getValue()).containsEntry("tenantName", "Acme Inc");
        assertThat(captor.getValue().get("inviteLink"))
                .as("the tenant's app — the user signs in with their identity provider")
                .isEqualTo("https://app.kelta.io/acme/app");
    }

    @Test
    @DisplayName("Should fall back to 'Kelta' when tenant name lookup is empty")
    void shouldFallBackToDefaultTenantName() {
        when(jdbcTemplate.queryForList("SELECT name, slug FROM tenant WHERE id = ?", "t-unknown"))
                .thenReturn(List.of());
        when(emailService.sendByName(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of("log-100"));

        var request = new InternalEmailController.SendInviteRequest(
                "x@test.com", "t-unknown", "tok-x");

        controller.sendInvite("test-secret-token", request);

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendByName(eq("t-unknown"), eq("x@test.com"), eq("user_invite"),
                captor.capture(), eq("USER_INVITE"), eq("tok-x"));
        assertThat(captor.getValue()).containsEntry("tenantName", "Kelta");
    }

    @Test
    @DisplayName("Should return 404 when user_invite template cannot be resolved")
    void shouldReturn404WhenTemplateMissing() {
        when(jdbcTemplate.queryForList("SELECT name, slug FROM tenant WHERE id = ?", "t1"))
                .thenReturn(List.of(Map.of("name", "Acme")));
        when(emailService.sendByName(any(), any(), eq("user_invite"), any(), any(), any()))
                .thenReturn(Optional.empty());

        var request = new InternalEmailController.SendInviteRequest(
                "y@test.com", "t1", "tok-y");

        var response = controller.sendInvite("test-secret-token", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsEntry("error", "Template not found: user_invite");
    }

    @Test
    @DisplayName("Should reject invite with invalid internal token")
    void shouldRejectInviteWithBadToken() {
        var request = new InternalEmailController.SendInviteRequest(
                "z@test.com", "t1", "tok-z");

        var response = controller.sendInvite("nope", request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(emailService, never()).sendByName(any(), any(), any(), any(), any(), any());
    }
}
