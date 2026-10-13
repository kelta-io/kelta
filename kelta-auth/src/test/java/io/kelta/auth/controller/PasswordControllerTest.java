package io.kelta.auth.controller;

import io.kelta.auth.TestFixtures;
import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.PasswordPolicyService;
import io.kelta.auth.service.PasswordResetService;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
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
    private PasswordResetService resetService;

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
        controller = new PasswordController(jdbcTemplate, passwordEncoder, policyService, sessionRevoker, resetService);
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
        @DisplayName("hands the address to the reset service and always answers the same")
        void alwaysReturnsSuccess() {
            var result = controller.requestPasswordReset(
                    new PasswordController.ResetRequestBody("someone@test.com"), httpRequest);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).containsEntry("status", "ok");
            verify(resetService).requestReset("someone@test.com", httpRequest);
        }
    }

    @Nested
    @DisplayName("resetPassword")
    class ResetPassword {

        @BeforeEach
        void session() {
            lenient().when(httpRequest.getSession(false)).thenReturn(httpSession);
            lenient().when(httpSession.getId()).thenReturn("current-session");
        }

        private ResponseEntity<Map<String, String>> reset(PasswordResetService.ResetResult result) {
            when(resetService.reset("the-token", "a-new-pass-phrase", "current-session")).thenReturn(result);
            return controller.resetPassword(
                    new PasswordController.ResetPasswordRequest("the-token", "a-new-pass-phrase"), httpRequest);
        }

        @Test
        @DisplayName("200 when the password was reset")
        void done() {
            assertThat(reset(new PasswordResetService.ResetResult.Done("tenant-a")).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("400 for a token that is not live")
        void invalidToken() {
            var result = reset(new PasswordResetService.ResetResult.InvalidToken());

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody().get("error")).contains("expired");
        }

        @Test
        @DisplayName("400 with the policy's reasons for a password the tenant does not accept")
        void rejected() {
            var result = reset(new PasswordResetService.ResetResult.Rejected("Password is too common"));

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody()).containsEntry("error", "Password is too common");
        }
    }
}
