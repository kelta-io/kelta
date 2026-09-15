package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.SecurityAuditLogger;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminPersonalAccessTokenController Tests")
class AdminPersonalAccessTokenControllerTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private UserIdResolver userIdResolver;
    @Mock private CerbosPermissionResolver permissionResolver;
    @Mock private BootstrapRepository bootstrapRepository;
    @Mock private HttpServletRequest request;

    private AdminPersonalAccessTokenController controller;

    @BeforeEach
    void setUp() {
        PersonalAccessTokenController personalAccessTokenController =
                new PersonalAccessTokenController(jdbcTemplate, redisTemplate, userIdResolver);
        controller = new AdminPersonalAccessTokenController(
                permissionResolver, bootstrapRepository, personalAccessTokenController);
        TenantContext.set("tenant-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void grantManageUsers() {
        when(permissionResolver.getProfileId(request)).thenReturn("admin-profile");
        when(bootstrapRepository.findProfileSystemPermissions("admin-profile")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_USERS", "granted", true)));
    }

    @Nested
    @DisplayName("POST /{id}/tokens")
    class CreateToken {

        @Test
        @DisplayName("403 when the caller's profile lacks MANAGE_USERS")
        void shouldRejectWithoutManageUsers() {
            when(permissionResolver.getProfileId(request)).thenReturn("caller-profile");
            when(bootstrapRepository.findProfileSystemPermissions("caller-profile")).thenReturn(List.of(
                    Map.of("permission_name", "MANAGE_USERS", "granted", false)));

            assertThatThrownBy(() -> controller.createToken(request, "target-user", Map.of("name", "CI bot")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("403 when no gateway identity headers are present")
        void shouldRejectWithoutIdentity() {
            when(permissionResolver.getProfileId(request)).thenReturn(null);

            assertThatThrownBy(() -> controller.createToken(request, "target-user", Map.of("name", "CI bot")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("admin mints a token for the target user, distinct from the caller")
        void shouldMintTokenForTargetUser() {
            grantManageUsers();
            when(permissionResolver.getEmail(request)).thenReturn("admin@test.com");
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("target-user"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("email", "target@test.com")));
            when(jdbcTemplate.queryForList(contains("COUNT"), eq("target-user"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("cnt", 0L)));
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            var response = controller.createToken(request, "target-user",
                    Map.of("name", "Integration token", "expiresInDays", 90));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = (Map<String, Object>) response.getBody();
            String rawToken = (String) responseBody.get("token");
            assertThat(rawToken).startsWith("klt_");

            // Inserted for the TARGET user, not the caller/admin.
            verify(jdbcTemplate).update(contains("INSERT INTO user_api_token"),
                    eq("target-user"), eq("tenant-1"), eq("Integration token"), any(), any(), any(), any());

            // The minted token actually authenticates as the target user: hash it the same way
            // the gateway does (PersonalAccessTokenController.sha256) and feed it through the
            // validate endpoint the gateway calls as its Redis-cache-miss fallback.
            String tokenHash = PersonalAccessTokenController.sha256(rawToken);
            when(jdbcTemplate.queryForList(contains("FROM user_api_token"), eq(tokenHash)))
                    .thenReturn(List.of(Map.of(
                            "id", "tok-1",
                            "user_id", "target-user",
                            "tenant_id", "tenant-1",
                            "scopes", "[\"api\"]",
                            "expires_at", java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)),
                            "email", "target@test.com",
                            "status", "ACTIVE")));

            PersonalAccessTokenController selfServiceController =
                    new PersonalAccessTokenController(jdbcTemplate, redisTemplate, userIdResolver);
            var validateResponse = selfServiceController.validateToken(tokenHash);

            assertThat(validateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> validated = (Map<String, Object>) validateResponse.getBody();
            assertThat(validated.get("userId")).isEqualTo("target-user");
            assertThat(validated.get("userId")).isNotEqualTo("admin@test.com");
        }

        @Test
        @DisplayName("audit trail records the admin actor and the target user under PAT_ADMIN_CREATED")
        void shouldAuditWithActorAndTarget() {
            grantManageUsers();
            when(permissionResolver.getEmail(request)).thenReturn("admin@test.com");
            when(jdbcTemplate.queryForList(contains("platform_user"), eq("target-user"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("email", "target@test.com")));
            when(jdbcTemplate.queryForList(contains("COUNT"), eq("target-user"), eq("tenant-1")))
                    .thenReturn(List.of(Map.of("cnt", 0L)));
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            try (MockedStatic<SecurityAuditLogger> audit = mockStatic(SecurityAuditLogger.class)) {
                var response = controller.createToken(request, "target-user", Map.of("name", "Audited token"));

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                // Actor is the admin caller (not the target, not a hardcoded "admin" literal),
                // target is the minted-for user, tenant is the current tenant context — the SLF4J
                // logger stamps the timestamp itself via MDC (same mechanism as every other
                // SecurityAuditLogger call in this codebase).
                audit.verify(() -> SecurityAuditLogger.log(
                        SecurityAuditLogger.EventType.PAT_ADMIN_CREATED,
                        "admin@test.com", "target-user", "tenant-1", "success", "name=Audited token"));
            }
        }
    }
}
