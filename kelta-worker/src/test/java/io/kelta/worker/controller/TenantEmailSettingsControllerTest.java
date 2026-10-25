package io.kelta.worker.controller;

import io.kelta.crypto.EncryptionService;
import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.worker.repository.EmailRepository;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who may read and repoint the tenant's SMTP / From settings. Whoever controls them receives every
 * magic link and password-reset mail the tenant sends, so the endpoint takes {@code MANAGE_TENANTS}
 * — the gate the admin UI puts on its {@code email-settings} route.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantEmailSettingsController")
@WithTenant("tenant-1")
class TenantEmailSettingsControllerTest {

    private final MockHttpServletRequest adminRequest = AdminPermissionGuardFixture.request();

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private EmailRepository emailRepository;
    @Mock private EmailService emailService;
    @Mock private EncryptionService encryptionService;

    private TenantEmailSettingsController controller(AdminPermissionGuard guard) {
        return new TenantEmailSettingsController(jdbcTemplate, emailRepository, emailService,
                encryptionService, new ObjectMapper(), guard);
    }

    private TenantEmailSettingsController controller;

    @BeforeEach
    void setUp() {
        controller = controller(AdminPermissionGuardFixture.granting("MANAGE_TENANTS"));
    }

    private static TenantEmailSettingsController.EmailSettingsUpdate attackerSmtp() {
        return new TenantEmailSettingsController.EmailSettingsUpdate(
                "smtp.attacker.example", 25, "u", "p", true, "noreply@attacker.example", "Support",
                null, null);
    }

    @Test
    @DisplayName("A profile without MANAGE_TENANTS (API_ACCESS only, like Portal User) gets 403 on GET, PUT and POST /test")
    void deniedWithoutManageTenants() {
        TenantEmailSettingsController denied = controller(AdminPermissionGuardFixture.apiAccessOnly());

        AdminPermissionGuardFixture.assertForbidden(() -> denied.get(adminRequest), "MANAGE_TENANTS");
        AdminPermissionGuardFixture.assertForbidden(() -> denied.update(adminRequest, attackerSmtp()), "MANAGE_TENANTS");
        AdminPermissionGuardFixture.assertForbidden(() -> denied.testSend(adminRequest,
                new TenantEmailSettingsController.TestSendRequest("me@attacker.example")), "MANAGE_TENANTS");
        verifyNoInteractions(jdbcTemplate, emailRepository, emailService, encryptionService);
    }

    @Test
    @DisplayName("A profile holding other admin permissions but not MANAGE_TENANTS still gets 403 on PUT")
    void deniedWithUnrelatedPermissions() {
        TenantEmailSettingsController denied = controller(
                AdminPermissionGuardFixture.granting("API_ACCESS", "MANAGE_USERS", "CUSTOMIZE_APPLICATION"));

        AdminPermissionGuardFixture.assertForbidden(() -> denied.update(adminRequest, attackerSmtp()), "MANAGE_TENANTS");
        verifyNoInteractions(jdbcTemplate, encryptionService);
    }

    @Test
    @DisplayName("A request with no profile header gets 403")
    void deniedWithoutIdentity() {
        AdminPermissionGuardFixture.assertForbiddenNoIdentity(
                () -> controller.update(new MockHttpServletRequest(), attackerSmtp()));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("A profile holding MANAGE_TENANTS can save SMTP settings")
    void putAllowedWithManageTenants() {
        when(encryptionService.encrypt(anyString())).thenReturn("ciphertext");

        var response = controller.update(adminRequest, attackerSmtp());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(jdbcTemplate).update(contains("INSERT INTO credential"),
                anyString(), eq("tenant-1"), eq("tenant-smtp"), eq("ciphertext"), anyString());
    }

    @Test
    @DisplayName("A profile holding MANAGE_TENANTS can queue a test email")
    void testSendAllowedWithManageTenants() {
        when(emailService.queueEmail(eq("tenant-1"), eq("qa@example.com"), anyString(), anyString(),
                eq("EMAIL_TEST"), isNull())).thenReturn("log-1");

        var response = controller.testSend(adminRequest,
                new TenantEmailSettingsController.TestSendRequest("qa@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
