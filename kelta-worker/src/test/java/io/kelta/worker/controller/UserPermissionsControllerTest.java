package io.kelta.worker.controller;

import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.cache.WorkerCacheManager;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.util.WithTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("UserPermissionsController Tests")
@WithTenant("t1")
class UserPermissionsControllerTest {

    private static final String USER_UUID = "22222222-2222-2222-2222-222222222222";

    private CerbosPermissionResolver permissionResolver;
    private JdbcTemplate jdbcTemplate;
    private WorkerCacheManager cacheManager;
    private UserIdResolver userIdResolver;
    private UserPermissionsController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        permissionResolver = mock(CerbosPermissionResolver.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        cacheManager = mock(WorkerCacheManager.class);
        userIdResolver = mock(UserIdResolver.class);
        when(userIdResolver.resolve(anyString(), any()))
                .thenAnswer(inv -> "user@example.com".equals(inv.getArgument(0))
                        ? USER_UUID : inv.getArgument(0));
        controller = new UserPermissionsController(permissionResolver, jdbcTemplate, cacheManager,
                userIdResolver);
        request = mock(HttpServletRequest.class);
    }

    @Nested
    @DisplayName("getIdentity")
    class GetIdentity {

        @Test
        void shouldReturnResolvedIdentity() {
            when(permissionResolver.getEmail(request)).thenReturn("user@example.com");
            when(permissionResolver.getProfileId(request)).thenReturn("profile-1");

            var response = controller.getIdentity("user@example.com", request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(USER_UUID, response.getBody().get("userId"));
            assertEquals("user@example.com", response.getBody().get("email"));
            assertEquals("profile-1", response.getBody().get("profileId"));
        }

        @Test
        void shouldRejectMissingHeader() {
            var ex = assertThrows(ResponseStatusException.class,
                    () -> controller.getIdentity(null, request));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }

        @Test
        void shouldRejectUnresolvableIdentifier() {
            var ex = assertThrows(ResponseStatusException.class,
                    () -> controller.getIdentity("ghost@example.com", request));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("getPermissions")
    class GetPermissions {

        @Test
        void shouldReturnEmptyPermissionsWhenNoProfileId() {
            when(permissionResolver.getProfileId(request)).thenReturn(null);

            var response = controller.getPermissions(request);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(Map.of(), response.getBody().get("systemPermissions"));
            assertEquals(Map.of(), response.getBody().get("objectPermissions"));
            assertEquals(Map.of(), response.getBody().get("fieldPermissions"));
        }

        @Test
        void shouldReturnEmptyPermissionsWhenBlankProfileId() {
            when(permissionResolver.getProfileId(request)).thenReturn("  ");

            var response = controller.getPermissions(request);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(Map.of(), response.getBody().get("systemPermissions"));
        }

        @Test
        void shouldReturnCachedPermissions() {
            when(permissionResolver.getProfileId(request)).thenReturn("prof-1");
            Map<String, Object> cached = Map.of(
                    "systemPermissions", Map.of("viewSetup", true),
                    "objectPermissions", Map.of(),
                    "fieldPermissions", Map.of());
            when(cacheManager.getPermissions("prof-1")).thenReturn(Optional.of(cached));

            var response = controller.getPermissions(request);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(cached, response.getBody());
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void shouldQueryDatabaseOnCacheMiss() {
            when(permissionResolver.getProfileId(request)).thenReturn("prof-1");
            when(cacheManager.getPermissions("prof-1")).thenReturn(Optional.empty());

            var response = controller.getPermissions(request);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().containsKey("systemPermissions"));
            assertTrue(response.getBody().containsKey("objectPermissions"));
            assertTrue(response.getBody().containsKey("fieldPermissions"));
            verify(cacheManager).putPermissions(eq("prof-1"), any());
        }
    }
}
