package io.kelta.auth.controller;

import io.kelta.auth.TestFixtures;
import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.session.UserSessionRevoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ForcePasswordChangeController Tests")
class ForcePasswordChangeControllerTest {

    private static final String USER_ID = TestFixtures.USER_ID;
    private static final String TENANT_ID = TestFixtures.TENANT_ID;
    private static final String EMAIL = TestFixtures.EMAIL;

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private UserSessionRevoker sessionRevoker;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private ForcePasswordChangeController controller;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        controller = new ForcePasswordChangeController(jdbcTemplate, passwordEncoder, sessionRevoker);
        session = new MockHttpSession();
    }

    /** The session state LoginFailureHandler leaves for the signed-in user. */
    private void pendingChange() {
        session.setAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL, EMAIL);
        session.setAttribute(ForcePasswordChangeController.SESSION_ATTR_USER_ID, USER_ID);
        session.setAttribute(ForcePasswordChangeController.SESSION_ATTR_TENANT_ID, TENANT_ID);
    }

    private void account(String currentPassword, boolean flagged) {
        when(jdbcTemplate.queryForList(contains("FROM platform_user"), eq(USER_ID), eq(TENANT_ID)))
                .thenReturn(List.of(Map.of("password_hash", passwordEncoder.encode(currentPassword),
                        "force_change_on_login", flagged)));
    }

    @Nested
    @DisplayName("begin")
    class Begin {
        @Test
        @DisplayName("records the signed-in user's tenant and id and gives the session a new id")
        void recordsTheUser() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            String before = request.getSession(true).getId();
            KeltaUserDetails user = TestFixtures.forcePasswordChangeUser();

            ForcePasswordChangeController.begin(request, user);

            var current = request.getSession(false);
            assertThat(current.getId()).isNotEqualTo(before);
            assertThat(current.getAttribute(ForcePasswordChangeController.SESSION_ATTR_USER_ID)).isEqualTo(user.getId());
            assertThat(current.getAttribute(ForcePasswordChangeController.SESSION_ATTR_TENANT_ID))
                    .isEqualTo(user.getTenantId());
            assertThat(current.getAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL)).isEqualTo(user.getEmail());
        }
    }

    @Nested
    @DisplayName("GET /change-password")
    class ShowForm {
        @Test
        void shouldRedirectToLoginWithoutAPendingChange() {
            assertThat(controller.showChangePasswordForm(session, new ConcurrentModel())).isEqualTo("redirect:/login");
        }

        @Test
        @DisplayName("an email alone is not a pending change")
        void shouldRedirectToLoginWithOnlyAnEmail() {
            session.setAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL, EMAIL);
            assertThat(controller.showChangePasswordForm(session, new ConcurrentModel())).isEqualTo("redirect:/login");
        }

        @Test
        void shouldShowFormForThePendingUser() {
            pendingChange();
            Model model = new ConcurrentModel();
            assertThat(controller.showChangePasswordForm(session, model)).isEqualTo("change-password");
            assertThat(model.getAttribute("email")).isEqualTo(EMAIL);
        }
    }

    @Nested
    @DisplayName("POST /change-password")
    class ChangePassword {
        @Test
        void shouldRedirectToLoginWithoutAPendingChange() {
            String view = controller.changePassword("old", "newpassword", "newpassword", session, new ConcurrentModel());
            assertThat(view).isEqualTo("redirect:/login");
            verifyNoInteractions(jdbcTemplate, sessionRevoker);
        }

        @Test
        void shouldRejectShortPassword() {
            pendingChange();
            Model model = new ConcurrentModel();
            assertThat(controller.changePassword("old", "short", "short", session, model)).isEqualTo("change-password");
            assertThat(model.getAttribute("error")).isEqualTo("New password must be at least 8 characters.");
        }

        @Test
        void shouldRejectMismatchedPasswords() {
            pendingChange();
            Model model = new ConcurrentModel();
            assertThat(controller.changePassword("old", "newpassword", "different", session, model))
                    .isEqualTo("change-password");
            assertThat(model.getAttribute("error")).isEqualTo("New passwords do not match.");
        }

        @Test
        void shouldRejectWhenAccountNotFound() {
            pendingChange();
            when(jdbcTemplate.queryForList(anyString(), eq(USER_ID), eq(TENANT_ID))).thenReturn(List.of());
            Model model = new ConcurrentModel();
            assertThat(controller.changePassword("old", "newpassword", "newpassword", session, model))
                    .isEqualTo("change-password");
            assertThat(model.getAttribute("error")).isEqualTo("Account not found.");
        }

        @Test
        void shouldRejectIncorrectCurrentPassword() {
            pendingChange();
            account("correct-password", true);
            Model model = new ConcurrentModel();
            assertThat(controller.changePassword("wrong-password", "newpassword", "newpassword", session, model))
                    .isEqualTo("change-password");
            assertThat(model.getAttribute("error")).isEqualTo("Current password is incorrect.");
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
            verifyNoInteractions(sessionRevoker);
        }

        @Test
        @DisplayName("an account no longer flagged for a change is not changed here")
        void shouldNotChangeAnUnflaggedAccount() {
            pendingChange();
            account("correct-password", false);
            assertThat(controller.changePassword("correct-password", "newpassword", "newpassword", session,
                    new ConcurrentModel())).isEqualTo("redirect:/login");
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
            assertThat(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_USER_ID)).isNull();
            verifyNoInteractions(sessionRevoker);
        }

        @Test
        @DisplayName("updates exactly the pending user's credential and signs that user out elsewhere")
        void shouldUpdateThePendingUserAndRedirect() {
            pendingChange();
            account("correct-password", true);
            when(jdbcTemplate.update(contains("UPDATE user_credential"), anyString(), any(Timestamp.class),
                    any(Timestamp.class), eq(USER_ID), eq(TENANT_ID))).thenReturn(1);

            String view = controller.changePassword("correct-password", "newpassword", "newpassword", session,
                    new ConcurrentModel());

            assertThat(view).isEqualTo("redirect:/login?passwordChanged");
            verify(jdbcTemplate).update(argThat((String sql) -> sql.contains("WHERE user_id = ?")
                            && sql.contains("pu.tenant_id = ?") && sql.contains("force_change_on_login = true")),
                    anyString(), any(Timestamp.class), any(Timestamp.class), eq(USER_ID), eq(TENANT_ID));
            assertThat(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_USER_ID)).isNull();
            assertThat(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_TENANT_ID)).isNull();
            assertThat(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL)).isNull();
            verify(sessionRevoker).signOutElsewhere(USER_ID, session.getId());
        }

        @Test
        @DisplayName("no row updated (flag cleared meanwhile): nobody is signed out")
        void shouldNotSignOutWhenNothingWasUpdated() {
            pendingChange();
            account("correct-password", true);

            assertThat(controller.changePassword("correct-password", "newpassword", "newpassword", session,
                    new ConcurrentModel())).isEqualTo("redirect:/login");
            verifyNoInteractions(sessionRevoker);
        }
    }
}
