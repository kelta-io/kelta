package io.kelta.worker.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ObservabilitySettingsController Tests")
class ObservabilitySettingsControllerTest {

    private final MockHttpServletRequest adminRequest = AdminPermissionGuardFixture.request();

    private JdbcTemplate jdbcTemplate;
    private ObservabilitySettingsController controller;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new ObservabilitySettingsController(jdbcTemplate,
                AdminPermissionGuardFixture.granting("CUSTOMIZE_APPLICATION"));
    }

    @Nested
    @DisplayName("getSettings")
    class GetSettings {

        @Test
        void shouldReturnSettingsForValidTenant() {
            List<Map<String, Object>> rows = List.of(
                    Map.of("setting_key", "retention_days", "setting_value", "30"));
            when(jdbcTemplate.queryForList(anyString(), eq("tenant-1"))).thenReturn(rows);

            var response = controller.getSettings(adminRequest, "tenant-1");
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        void shouldReturnBadRequestForNullTenantId() {
            var response = controller.getSettings(adminRequest, null);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }

        @Test
        void shouldReturnBadRequestForBlankTenantId() {
            var response = controller.getSettings(adminRequest, "  ");
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }

        @Test
        void shouldReturn500OnException() {
            when(jdbcTemplate.queryForList(anyString(), eq("tenant-1")))
                    .thenThrow(new RuntimeException("DB error"));

            var response = controller.getSettings(adminRequest, "tenant-1");
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("updateSettings")
    class UpdateSettings {

        @Test
        void shouldReturnBadRequestForNullTenantId() {
            var response = controller.updateSettings(adminRequest, null, Map.of());
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }

        @Test
        void shouldReturnBadRequestForEmptySettings() {
            var response = controller.updateSettings(adminRequest, "tenant-1", Map.of());
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }

        @Test
        void shouldReturnBadRequestForNullSettingsList() {
            var body = new HashMap<String, Object>();
            body.put("settings", null);
            var response = controller.updateSettings(adminRequest, "tenant-1", body);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }

        @Test
        void shouldUpdateExistingSetting() {
            when(jdbcTemplate.update(contains("UPDATE"), anyString(), eq("tenant-1"), eq("retention_days")))
                    .thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("setting_key", "retention_days", "setting_value", "60")));

            var settings = List.of(Map.of("settingKey", "retention_days", "settingValue", "60"));
            var body = Map.<String, Object>of("settings", settings);
            var response = controller.updateSettings(adminRequest, "tenant-1", body);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(jdbcTemplate, never()).update(contains("INSERT"), any(), any(), any(), any());
        }

        @Test
        void shouldInsertNewSetting() {
            when(jdbcTemplate.update(contains("UPDATE"), anyString(), eq("tenant-1"), eq("new_key")))
                    .thenReturn(0);
            when(jdbcTemplate.update(contains("INSERT"), anyString(), eq("tenant-1"), eq("new_key"), eq("value")))
                    .thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("setting_key", "new_key", "setting_value", "value")));

            var settings = List.of(Map.of("settingKey", "new_key", "settingValue", "value"));
            var body = Map.<String, Object>of("settings", settings);
            var response = controller.updateSettings(adminRequest, "tenant-1", body);

            assertEquals(HttpStatus.OK, response.getStatusCode());
        }
    }

    @Test
    @DisplayName("A profile without CUSTOMIZE_APPLICATION (API_ACCESS only, like Portal User) gets 403 on every handler")
    void deniedWithoutCustomizeApplication() {
        controller = new ObservabilitySettingsController(jdbcTemplate, AdminPermissionGuardFixture.apiAccessOnly());

        AdminPermissionGuardFixture.assertForbidden(() -> controller.getSettings(adminRequest, "tenant-1"),
                "CUSTOMIZE_APPLICATION");
        AdminPermissionGuardFixture.assertForbidden(() -> controller.updateSettings(adminRequest, "tenant-1",
                Map.of("settings", List.of(Map.of("settingKey", "k", "settingValue", "v")))), "CUSTOMIZE_APPLICATION");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("A profile holding CUSTOMIZE_APPLICATION can read settings")
    void allowedWithCustomizeApplication() {
        when(jdbcTemplate.queryForList(anyString(), eq("tenant-1"))).thenReturn(List.of());

        assertEquals(HttpStatus.OK, controller.getSettings(adminRequest, "tenant-1").getStatusCode());
    }
}
