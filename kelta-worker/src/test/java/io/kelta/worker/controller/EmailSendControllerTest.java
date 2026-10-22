package io.kelta.worker.controller;

import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.EmailRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("EmailSendController")
class EmailSendControllerTest {

    private EmailService emailService;
    private EmailRepository emailRepository;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private HttpServletRequest request;
    private EmailSendController controller;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        emailRepository = mock(EmailRepository.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        request = mock(HttpServletRequest.class);
        controller = new EmailSendController(
                emailService, emailRepository, permissionResolver, bootstrapRepository);

        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(permissionResolver.getEmail(request)).thenReturn("admin@test.com");
    }

    private void grantPermission() {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1"))
                .thenReturn(List.of(Map.of("permission_name", "MANAGE_EMAIL_TEMPLATES", "granted", true)));
    }

    @Test
    @DisplayName("Should queue email via templateId when permitted and under rate limit")
    void shouldSendByTemplateId() {
        grantPermission();
        when(emailRepository.countRecentByTenant(eq("t1"), any(Instant.class))).thenReturn(3);
        when(emailService.sendById(eq("t1"), eq("dest@test.com"), eq("tpl-1"), anyMap(), eq("QUICK_ACTION"), isNull()))
                .thenReturn(Optional.of("log-1"));

        var req = new EmailSendController.SendRequest(
                "tpl-1", null, "dest@test.com", Map.of("firstName", "Ada"));

        var response = controller.send("t1", req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("emailLogId", "log-1");
        assertThat(response.getBody()).containsEntry("status", "QUEUED");

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendById(eq("t1"), eq("dest@test.com"), eq("tpl-1"),
                captor.capture(), eq("QUICK_ACTION"), isNull());
        assertThat(captor.getValue()).containsEntry("firstName", "Ada");
        verify(emailService, never()).sendByKey(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should queue email via templateKey")
    void shouldSendByTemplateKey() {
        grantPermission();
        when(emailRepository.countRecentByTenant(eq("t1"), any(Instant.class))).thenReturn(0);
        when(emailService.sendByKey(eq("t1"), eq("dest@test.com"), eq("welcome"), anyMap(), eq("QUICK_ACTION"), isNull()))
                .thenReturn(Optional.of("log-2"));

        var req = new EmailSendController.SendRequest(
                null, "welcome", "dest@test.com", null);

        var response = controller.send("t1", req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("emailLogId", "log-2");
    }

    @Test
    @DisplayName("Should 403 when the profile lacks MANAGE_EMAIL_TEMPLATES")
    void shouldRejectWithoutPermission() {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1"))
                .thenReturn(List.of(Map.of("permission_name", "API_ACCESS", "granted", true)));

        var req = new EmailSendController.SendRequest("tpl-1", null, "dest@test.com", null);

        assertThatThrownBy(() -> controller.send("t1", req, request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("MANAGE_EMAIL_TEMPLATES");
        verify(emailService, never()).sendById(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should 403 when there is no identity on the request")
    void shouldRejectWithoutIdentity() {
        when(permissionResolver.getProfileId(request)).thenReturn(null);

        var req = new EmailSendController.SendRequest("tpl-1", null, "dest@test.com", null);

        assertThatThrownBy(() -> controller.send("t1", req, request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    @DisplayName("Should 400 when both templateId and templateKey are supplied")
    void shouldRejectAmbiguousTemplate() {
        grantPermission();

        var req = new EmailSendController.SendRequest("tpl-1", "welcome", "dest@test.com", null);

        var response = controller.send("t1", req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(emailService, never()).sendById(any(), any(), any(), any(), any(), any());
        verify(emailService, never()).sendByKey(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should 400 when neither templateId nor templateKey is supplied")
    void shouldRejectMissingTemplate() {
        grantPermission();

        var req = new EmailSendController.SendRequest(null, null, "dest@test.com", null);

        var response = controller.send("t1", req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("Should 404 when the template cannot be resolved")
    void shouldReturn404WhenTemplateMissing() {
        grantPermission();
        when(emailRepository.countRecentByTenant(eq("t1"), any(Instant.class))).thenReturn(0);
        when(emailService.sendById(any(), any(), eq("missing"), anyMap(), any(), any()))
                .thenReturn(Optional.empty());

        var req = new EmailSendController.SendRequest("missing", null, "dest@test.com", null);

        var response = controller.send("t1", req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsEntry("error", "Template not found: missing");
    }

    @Test
    @DisplayName("Should 429 when the tenant is over the rate limit")
    void shouldRateLimit() {
        grantPermission();
        when(emailRepository.countRecentByTenant(eq("t1"), any(Instant.class)))
                .thenReturn(EmailSendController.MAX_SENDS_PER_WINDOW);

        var req = new EmailSendController.SendRequest("tpl-1", null, "dest@test.com", null);

        assertThatThrownBy(() -> controller.send("t1", req, request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("rate limit");
        verify(emailService, never()).sendById(any(), any(), any(), any(), any(), any());
    }
}
