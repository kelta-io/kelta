package io.kelta.worker.controller;

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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Email template overrides take {@code MANAGE_EMAIL_TEMPLATES}, the gate on the admin UI's templates page. */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailTemplatesController")
@WithTenant("tenant-1")
class EmailTemplatesControllerTest {

    private static final String PERMISSION = "MANAGE_EMAIL_TEMPLATES";

    private final MockHttpServletRequest adminRequest = AdminPermissionGuardFixture.request();

    @Mock private JdbcTemplate jdbcTemplate;

    private EmailTemplatesController controller;

    @BeforeEach
    void setUp() {
        controller = new EmailTemplatesController(jdbcTemplate, AdminPermissionGuardFixture.granting(PERMISSION));
    }

    @Test
    @DisplayName("A profile without MANAGE_EMAIL_TEMPLATES (API_ACCESS only, like Portal User) gets 403 on list, create and delete")
    void deniedWithoutManageEmailTemplates() {
        EmailTemplatesController denied = new EmailTemplatesController(jdbcTemplate,
                AdminPermissionGuardFixture.apiAccessOnly());

        AdminPermissionGuardFixture.assertForbidden(() -> denied.list(adminRequest), PERMISSION);
        AdminPermissionGuardFixture.assertForbidden(() -> denied.override(adminRequest, "magic-link", Map.of()), PERMISSION);
        AdminPermissionGuardFixture.assertForbidden(() -> denied.revert(adminRequest, "magic-link"), PERMISSION);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("A profile holding MANAGE_EMAIL_TEMPLATES can create an override")
    void overrideAllowedWithManageEmailTemplates() {
        when(jdbcTemplate.queryForList(contains("FROM email_template WHERE tenant_id = ?"),
                eq("tenant-1"), eq("magic-link"))).thenReturn(List.of());
        when(jdbcTemplate.update(contains("INSERT INTO email_template"),
                anyString(), eq("tenant-1"), eq("system"), eq("magic-link"))).thenReturn(1);

        var response = controller.override(adminRequest, "magic-link", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("status", "created"));
    }

    @Test
    @DisplayName("A profile holding MANAGE_EMAIL_TEMPLATES can revert an override")
    void revertAllowedWithManageEmailTemplates() {
        var response = controller.revert(adminRequest, "magic-link");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(jdbcTemplate).update(contains("DELETE FROM email_template"), eq("tenant-1"), eq("magic-link"));
    }

    @Test
    @DisplayName("A profile holding MANAGE_EMAIL_TEMPLATES can list templates")
    void listAllowedWithManageEmailTemplates() {
        when(jdbcTemplate.queryForList(contains("FROM email_template"), eq("tenant-1"))).thenReturn(List.of());

        assertThat(controller.list(adminRequest).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
