package io.kelta.worker.controller;

import io.kelta.worker.service.CerbosAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AuthorizationTestController Tests")
class AuthorizationTestControllerTest {

    private final MockHttpServletRequest adminRequest = AdminPermissionGuardFixture.request();

    private CerbosAuthorizationService authzService;
    private AuthorizationTestController controller;

    @BeforeEach
    void setUp() {
        authzService = mock(CerbosAuthorizationService.class);
        controller = new AuthorizationTestController(authzService,
                AdminPermissionGuardFixture.granting("MANAGE_USERS"));
    }

    @Nested
    @DisplayName("testAuthorization")
    class TestAuthorization {

        @Test
        @SuppressWarnings("unchecked")
        void shouldTestFieldAccess() {
            when(authzService.checkFieldAccess("user@test.com", "prof-1", "tenant-1", "col-1", "field-1", "read"))
                    .thenReturn(true);

            Map<String, Object> body = new HashMap<>();
            body.put("email", "user@test.com");
            body.put("profileId", "prof-1");
            body.put("tenantId", "tenant-1");
            body.put("resourceKind", "field");
            body.put("resourceId", "field-1");
            body.put("action", "read");
            body.put("resourceAttributes", Map.of("collectionId", "col-1"));

            var response = controller.testAuthorization(adminRequest, body);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(true, response.getBody().get("allowed"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldTestRecordAccess() {
            when(authzService.checkRecordAccess(eq("user@test.com"), eq("prof-1"), eq("tenant-1"),
                    eq("col-1"), eq("rec-1"), any(), eq("update")))
                    .thenReturn(false);

            Map<String, Object> body = new HashMap<>();
            body.put("email", "user@test.com");
            body.put("profileId", "prof-1");
            body.put("tenantId", "tenant-1");
            body.put("resourceKind", "record");
            body.put("resourceId", "rec-1");
            body.put("action", "update");
            body.put("resourceAttributes", Map.of("collectionId", "col-1"));

            var response = controller.testAuthorization(adminRequest, body);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(false, response.getBody().get("allowed"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldDefaultToRecordCheckForUnknownKind() {
            when(authzService.checkRecordAccess(eq("user@test.com"), eq("prof-1"), eq("tenant-1"),
                    eq("custom-kind"), eq("id-1"), any(), eq("delete")))
                    .thenReturn(true);

            Map<String, Object> body = new HashMap<>();
            body.put("email", "user@test.com");
            body.put("profileId", "prof-1");
            body.put("tenantId", "tenant-1");
            body.put("resourceKind", "custom-kind");
            body.put("resourceId", "id-1");
            body.put("action", "delete");

            var response = controller.testAuthorization(adminRequest, body);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(true, response.getBody().get("allowed"));
        }

        @Test
        void shouldReturnResponseFields() {
            when(authzService.checkRecordAccess(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(true);

            Map<String, Object> body = new HashMap<>();
            body.put("email", "user@test.com");
            body.put("resourceKind", "record");
            body.put("resourceId", "rec-1");
            body.put("action", "read");
            body.put("resourceAttributes", Map.of("collectionId", "c1"));

            var response = controller.testAuthorization(adminRequest, body);
            assertNotNull(response.getBody());
            assertEquals("user@test.com", response.getBody().get("email"));
            assertEquals("record", response.getBody().get("resourceKind"));
            assertEquals("rec-1", response.getBody().get("resourceId"));
            assertEquals("read", response.getBody().get("action"));
        }
    }

    @Test
    @DisplayName("A profile without MANAGE_USERS (API_ACCESS only, like Portal User) gets 403")
    void deniedWithoutManageUsers() {
        controller = new AuthorizationTestController(authzService, AdminPermissionGuardFixture.apiAccessOnly());

        AdminPermissionGuardFixture.assertForbidden(
                () -> controller.testAuthorization(adminRequest, new HashMap<>()), "MANAGE_USERS");
        verifyNoInteractions(authzService);
    }

    @Test
    @DisplayName("A profile holding MANAGE_USERS can run a policy test")
    void allowedWithManageUsers() {
        when(authzService.checkFieldAccess("user@test.com", "prof-1", "tenant-1", "col-1", "field-1", "read"))
                .thenReturn(true);
        Map<String, Object> body = new HashMap<>(Map.of("email", "user@test.com", "profileId", "prof-1",
                "tenantId", "tenant-1", "resourceKind", "field", "resourceId", "field-1", "action", "read",
                "resourceAttributes", Map.of("collectionId", "col-1")));

        assertEquals(HttpStatus.OK, controller.testAuthorization(adminRequest, body).getStatusCode());
    }
}
