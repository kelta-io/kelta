package io.kelta.auth.controller;

import io.kelta.auth.TestFixtures;
import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.PasswordPolicyService;
import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.session.UserSessionRevoker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordController")
class PasswordControllerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private WorkerClient workerClient;

    @Mock
    private PasswordPolicyService policyService;

    @Mock
    private Authentication authentication;

    @Mock
    private UserSessionRevoker sessionRevoker;

    @Mock
    private HttpServletRequest httpRequest;

    @Mock
    private HttpSession httpSession;

    private PasswordController controller;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.setUiBaseUrl("https://app.kelta.io");
        controller = new PasswordController(jdbcTemplate, passwordEncoder, workerClient, policyService, sessionRevoker, props);
    }

    @Nested
    @DisplayName("changePassword")
    class ChangePassword {

        private final KeltaUserDetails user = TestFixtures.userDetails();

        @BeforeEach
        void signedIn() {
            lenient().when(authentication.getPrincipal()).thenReturn(user);
            lenient().when(httpRequest.getSession(false)).thenReturn(httpSession);
            lenient().when(httpSession.getId()).thenReturn("current-session");
        }

        private void account(String hash) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", user.getId());
            row.put("tenant_id", user.getTenantId());
            row.put("email", user.getEmail());
            row.put("first_name", "John");
            row.put("last_name", "Doe");
            row.put("password_hash", hash);
            when(jdbcTemplate.queryForList(contains("WHERE pu.id = ? AND pu.tenant_id = ?"),
                    eq(user.getId()), eq(user.getTenantId()))).thenReturn(List.of(row));
        }

        @Test
        @DisplayName("returns 400 when current password is incorrect")
        void returns400WhenCurrentPasswordIncorrect() {
            account("$2a$hashed");
            when(passwordEncoder.matches("wrong", "$2a$hashed")).thenReturn(false);

            var request = new PasswordController.ChangePasswordRequest("wrong", "newpassword123");
            ResponseEntity<Map<String, String>> result = controller.changePassword(request, authentication, httpRequest);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody().get("error")).contains("incorrect");
            verifyNoInteractions(sessionRevoker);
        }

        @Test
        @DisplayName("returns 400 when new password violates policy")
        void returns400WhenPolicyViolation() {
            account("$2a$hashed");
            when(passwordEncoder.matches("current123", "$2a$hashed")).thenReturn(true);
            when(policyService.validatePassword(eq("weak"), eq(user.getEmail()), eq("John Doe"), eq(user.getTenantId())))
                    .thenReturn(List.of("Password too short"));

            var request = new PasswordController.ChangePasswordRequest("current123", "weak");
            ResponseEntity<Map<String, String>> result = controller.changePassword(request, authentication, httpRequest);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody().get("error")).contains("Password too short");
            verifyNoInteractions(sessionRevoker);
        }

        @Test
        @DisplayName("returns 400 when the user's account is missing")
        void returns400WhenAccountMissing() {
            when(jdbcTemplate.queryForList(contains("WHERE pu.id = ? AND pu.tenant_id = ?"),
                    eq(user.getId()), eq(user.getTenantId()))).thenReturn(List.of());

            var request = new PasswordController.ChangePasswordRequest("anything", "newpassword123");
            ResponseEntity<Map<String, String>> result = controller.changePassword(request, authentication, httpRequest);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            // Same generic message as a wrong password — don't leak account existence.
            assertThat(result.getBody().get("error")).contains("incorrect");
            // No subsequent DB work should have happened.
            verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a principal that is not a platform user is matched by login name only when one account has it")
        void ambiguousLoginNameIsRefused() {
            when(authentication.getPrincipal()).thenReturn("user@test.com");
            when(authentication.getName()).thenReturn("user@test.com");
            when(jdbcTemplate.queryForList(contains("WHERE pu.email = ?"), eq("user@test.com")))
                    .thenReturn(List.of(Map.of("id", "u1"), Map.of("id", "u2")));

            var request = new PasswordController.ChangePasswordRequest("anything", "newpassword123");
            ResponseEntity<Map<String, String>> result = controller.changePassword(request, authentication, httpRequest);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("changes the signed-in user's password and signs them out everywhere else")
        void changesPasswordSuccessfully() {
            account("$2a$hashed");
            when(passwordEncoder.matches("current123", "$2a$hashed")).thenReturn(true);
            when(policyService.validatePassword(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(List.of());
            when(policyService.checkHistory(user.getId(), "newpassword123", user.getTenantId()))
                    .thenReturn(Optional.empty());
            when(passwordEncoder.encode("newpassword123")).thenReturn("$2a$newhash");

            var request = new PasswordController.ChangePasswordRequest("current123", "newpassword123");
            ResponseEntity<Map<String, String>> result = controller.changePassword(request, authentication, httpRequest);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(jdbcTemplate).update(contains("WHERE user_id = ?"),
                    eq("$2a$newhash"), any(Timestamp.class), any(Timestamp.class), eq(user.getId()));
            verify(policyService).saveToHistory(user.getId(), "$2a$newhash", user.getTenantId());
            verify(sessionRevoker).signOutElsewhere(user.getId(), "current-session");
        }
    }

    @Nested
    @DisplayName("requestPasswordReset")
    class RequestPasswordReset {

        @Test
        @DisplayName("always returns success to prevent email enumeration")
        void alwaysReturnsSuccess() {
            when(jdbcTemplate.queryForList(contains("FROM platform_user"), eq("nonexistent@test.com")))
                    .thenReturn(List.of());

            var request = new PasswordController.ResetRequestBody("nonexistent@test.com");
            ResponseEntity<Map<String, String>> result = controller.requestPasswordReset(request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
            verifyNoInteractions(workerClient);
        }

        @Test
        @DisplayName("the same address in two tenants: one token per user, each stored on that user only")
        void oneTokenPerAccount() {
            when(jdbcTemplate.queryForList(contains("FROM platform_user"), eq("shared@test.com")))
                    .thenReturn(List.of(
                            Map.of("id", "user-a", "tenant_id", "tenant-a", "first_name", "A", "tenant_name", "Tenant A"),
                            Map.of("id", "user-b", "tenant_id", "tenant-b", "first_name", "B", "tenant_name", "Tenant B")));
            when(jdbcTemplate.update(contains("reset_token = ?"), anyString(), any(Timestamp.class),
                    any(Timestamp.class), anyString())).thenReturn(1);

            controller.requestPasswordReset(new PasswordController.ResetRequestBody("shared@test.com"));

            var tokens = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(jdbcTemplate).update(contains("WHERE user_id = ?"), tokens.capture(), any(Timestamp.class),
                    any(Timestamp.class), eq("user-a"));
            verify(jdbcTemplate).update(contains("WHERE user_id = ?"), tokens.capture(), any(Timestamp.class),
                    any(Timestamp.class), eq("user-b"));
            assertThat(tokens.getAllValues()).doesNotHaveDuplicates();
            verify(workerClient).sendTemplateEmail(eq("tenant-a"), eq("shared@test.com"), eq("user.password_reset"),
                    anyMap(), eq("PASSWORD_RESET"), eq("user-a"));
            verify(workerClient).sendTemplateEmail(eq("tenant-b"), eq("shared@test.com"), eq("user.password_reset"),
                    anyMap(), eq("PASSWORD_RESET"), eq("user-b"));
        }
    }

    @Nested
    @DisplayName("resetPassword")
    class ResetPassword {

        @Test
        @DisplayName("returns 400 for invalid token")
        void returns400ForInvalidToken() {
            when(jdbcTemplate.queryForList(contains("reset_token"), eq("bad-token")))
                    .thenReturn(List.of());

            var request = new PasswordController.ResetPasswordRequest("bad-token", "newpassword123");
            ResponseEntity<Map<String, String>> result = controller.resetPassword(request, httpRequest);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody().get("error")).contains("expired");
        }

        @Test
        @DisplayName("returns 400 for expired token")
        void returns400ForExpiredToken() {
            when(jdbcTemplate.queryForList(contains("reset_token"), eq("expired-token")))
                    .thenReturn(List.of(Map.of(
                            "user_id", "user-1",
                            "reset_token_expires_at", Timestamp.from(Instant.now().minusSeconds(3600))
                    )));

            var request = new PasswordController.ResetPasswordRequest("expired-token", "newpassword123");
            ResponseEntity<Map<String, String>> result = controller.resetPassword(request, httpRequest);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("returns 400 (no NPE) when stored expires_at is NULL")
        void returns400WhenExpiresAtNull() {
            // A reset_token row with null expires_at is a DB invariant violation;
            // we must treat it as invalid rather than NPE on the cast/.toInstant().
            Map<String, Object> row = new HashMap<>();
            row.put("user_id", "user-1");
            row.put("reset_token_expires_at", null);
            when(jdbcTemplate.queryForList(contains("reset_token"), eq("null-expires-token")))
                    .thenReturn(List.of(row));

            var request = new PasswordController.ResetPasswordRequest("null-expires-token", "newpassword123");
            ResponseEntity<Map<String, String>> result = controller.resetPassword(request, httpRequest);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody().get("error")).contains("expired");
            verify(jdbcTemplate, never()).update(contains("password_hash = ?"),
                    any(), any(), any(), any());
        }

        @Test
        @DisplayName("resets password with valid token")
        void resetsPasswordWithValidToken() {
            when(jdbcTemplate.queryForList(contains("reset_token"), eq("valid-token")))
                    .thenReturn(List.of(Map.of(
                            "user_id", "user-1",
                            "reset_token_expires_at", Timestamp.from(Instant.now().plusSeconds(3600))
                    )));
            when(passwordEncoder.encode("newpassword123")).thenReturn("$2a$newhash");
            when(jdbcTemplate.update(contains("password_hash = ?"), eq("$2a$newhash"), any(Timestamp.class),
                    any(Timestamp.class), eq("user-1"), eq("valid-token"))).thenReturn(1);

            var request = new PasswordController.ResetPasswordRequest("valid-token", "newpassword123");
            ResponseEntity<Map<String, String>> result = controller.resetPassword(request, httpRequest);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(jdbcTemplate).update(contains("WHERE user_id = ? AND reset_token = ?"),
                    eq("$2a$newhash"), any(Timestamp.class), any(Timestamp.class), eq("user-1"), eq("valid-token"));
            verify(sessionRevoker).signOutElsewhere("user-1", null);
        }

        @Test
        @DisplayName("a token matching more than one credential resets nobody")
        void ambiguousTokenIsRefused() {
            Timestamp future = Timestamp.from(Instant.now().plusSeconds(3600));
            when(jdbcTemplate.queryForList(contains("reset_token"), eq("dup-token")))
                    .thenReturn(List.of(Map.of("user_id", "user-1", "reset_token_expires_at", future),
                            Map.of("user_id", "user-2", "reset_token_expires_at", future)));

            var result = controller.resetPassword(
                    new PasswordController.ResetPasswordRequest("dup-token", "newpassword123"), httpRequest);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
            verifyNoInteractions(sessionRevoker);
        }

        @Test
        @DisplayName("a token used meanwhile (no row updated) is refused and signs nobody out")
        void consumedTokenIsRefused() {
            when(jdbcTemplate.queryForList(contains("reset_token"), eq("used-token")))
                    .thenReturn(List.of(Map.of("user_id", "user-1",
                            "reset_token_expires_at", Timestamp.from(Instant.now().plusSeconds(3600)))));
            when(passwordEncoder.encode("newpassword123")).thenReturn("$2a$newhash");

            var result = controller.resetPassword(
                    new PasswordController.ResetPasswordRequest("used-token", "newpassword123"), httpRequest);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(sessionRevoker);
        }
    }
}
