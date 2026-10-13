package io.kelta.auth.controller;

import io.kelta.auth.session.UserSessionRevoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ForcePasswordChangeController Tests")
class ForcePasswordChangeControllerTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private HttpSession session;
    @Mock private UserSessionRevoker sessionRevoker;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private ForcePasswordChangeController controller;

    @BeforeEach
    void setUp() {
        controller = new ForcePasswordChangeController(jdbcTemplate, passwordEncoder, sessionRevoker);
    }

    @Nested
    @DisplayName("GET /change-password")
    class ShowForm {
        @Test
        void shouldRedirectToLoginWhenNoSessionEmail() {
            when(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL)).thenReturn(null);
            String view = controller.showChangePasswordForm(session, new ConcurrentModel());
            assertThat(view).isEqualTo("redirect:/login");
        }

        @Test
        void shouldShowFormWhenSessionEmailPresent() {
            when(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL)).thenReturn("user@test.com");
            Model model = new ConcurrentModel();
            String view = controller.showChangePasswordForm(session, model);
            assertThat(view).isEqualTo("change-password");
            assertThat(model.getAttribute("email")).isEqualTo("user@test.com");
        }
    }

    @Nested
    @DisplayName("POST /change-password")
    class ChangePassword {
        @Test
        void shouldRedirectToLoginWhenNoSessionEmail() {
            when(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL)).thenReturn(null);
            String view = controller.changePassword("old", "newpassword", "newpassword", session, new ConcurrentModel());
            assertThat(view).isEqualTo("redirect:/login");
        }

        @Test
        void shouldRejectShortPassword() {
            when(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL)).thenReturn("user@test.com");
            Model model = new ConcurrentModel();
            String view = controller.changePassword("old", "short", "short", session, model);
            assertThat(view).isEqualTo("change-password");
            assertThat(model.getAttribute("error")).isEqualTo("New password must be at least 8 characters.");
        }

        @Test
        void shouldRejectMismatchedPasswords() {
            when(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL)).thenReturn("user@test.com");
            Model model = new ConcurrentModel();
            String view = controller.changePassword("old", "newpassword", "different", session, model);
            assertThat(view).isEqualTo("change-password");
            assertThat(model.getAttribute("error")).isEqualTo("New passwords do not match.");
        }

        @Test
        void shouldRejectWhenAccountNotFound() {
            when(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL)).thenReturn("user@test.com");
            when(jdbcTemplate.queryForList(anyString(), eq("user@test.com"), eq("user@test.com"))).thenReturn(List.of());
            Model model = new ConcurrentModel();
            String view = controller.changePassword("old", "newpassword", "newpassword", session, model);
            assertThat(view).isEqualTo("change-password");
            assertThat(model.getAttribute("error")).isEqualTo("Account not found.");
        }

        @Test
        void shouldRejectIncorrectCurrentPassword() {
            when(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL)).thenReturn("user@test.com");
            String storedHash = passwordEncoder.encode("correct-password");
            when(jdbcTemplate.queryForList(anyString(), eq("user@test.com"), eq("user@test.com")))
                    .thenReturn(List.of(Map.of("password_hash", storedHash, "user_id", "uid-1")));
            Model model = new ConcurrentModel();
            String view = controller.changePassword("wrong-password", "newpassword", "newpassword", session, model);
            assertThat(view).isEqualTo("change-password");
            assertThat(model.getAttribute("error")).isEqualTo("Current password is incorrect.");
        }

        @Test
        void shouldUpdatePasswordAndRedirectOnSuccess() {
            when(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL)).thenReturn("user@test.com");
            String storedHash = passwordEncoder.encode("correct-password");
            when(jdbcTemplate.queryForList(anyString(), eq("user@test.com"), eq("user@test.com")))
                    .thenReturn(List.of(Map.of("password_hash", storedHash, "user_id", "uid-1")));
            when(jdbcTemplate.update(anyString(), anyString(), eq("user@test.com"), eq("user@test.com"))).thenReturn(1);
            when(jdbcTemplate.queryForList(contains("SELECT id FROM platform_user"), eq(String.class),
                    eq("user@test.com"), eq("user@test.com"))).thenReturn(List.of("uid-1"));
            when(session.getId()).thenReturn("session-1");

            Model model = new ConcurrentModel();
            String view = controller.changePassword("correct-password", "newpassword", "newpassword", session, model);

            assertThat(view).isEqualTo("redirect:/login?passwordChanged");
            verify(jdbcTemplate).update(anyString(), anyString(), eq("user@test.com"), eq("user@test.com"));
            verify(session).removeAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL);
            verify(sessionRevoker).signOutElsewhere("uid-1", "session-1");
        }

        @Test
        void shouldNotSignAnyoneOutWhenTheChangeIsRejected() {
            when(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL)).thenReturn("user@test.com");
            String storedHash = passwordEncoder.encode("correct-password");
            when(jdbcTemplate.queryForList(anyString(), eq("user@test.com"), eq("user@test.com")))
                    .thenReturn(List.of(Map.of("password_hash", storedHash, "user_id", "uid-1")));

            controller.changePassword("wrong-password", "newpassword", "newpassword", session, new ConcurrentModel());

            verifyNoInteractions(sessionRevoker);
        }
    }
}
