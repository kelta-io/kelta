package io.kelta.auth.controller;

import io.kelta.auth.TestFixtures;
import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.PasswordPolicyService;
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
import java.util.Optional;

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
    @Mock private PasswordPolicyService policyService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private ForcePasswordChangeController controller;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        controller = new ForcePasswordChangeController(jdbcTemplate, passwordEncoder, sessionRevoker, policyService);
        lenient().when(policyService.validatePassword(anyString(), any(), any(), anyString())).thenReturn(List.of());
        lenient().when(policyService.checkHistory(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
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
                        "force_change_on_login", flagged, "email", EMAIL, "first_name", "Test", "last_name", "User")));
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
        @DisplayName("a password the tenant's policy refuses is not set; the reasons are shown")
        void shouldRejectAPolicyViolation() {
            pendingChange();
            account("correct-password", true);
            when(policyService.validatePassword("password123", EMAIL, "Test User", TENANT_ID))
                    .thenReturn(List.of("Password is too common", "Password must contain at least one uppercase letter"));
            Model model = new ConcurrentModel();

            assertThat(controller.changePassword("correct-password", "password123", "password123", session, model))
                    .isEqualTo("change-password");
            assertThat(model.getAttribute("error"))
                    .isEqualTo("Password is too common; Password must contain at least one uppercase letter");
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
            verify(policyService, never()).saveToHistory(any(), any(), any());
            verifyNoInteractions(sessionRevoker);
            assertThat(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_USER_ID))
                    .as("still pending").isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("a recently used password is not set")
        void shouldRejectARecentlyUsedPassword() {
            pendingChange();
            account("correct-password", true);
            when(policyService.checkHistory(USER_ID, "an-old-passphrase", TENANT_ID))
                    .thenReturn(Optional.of("Password was recently used"));
            Model model = new ConcurrentModel();

            assertThat(controller.changePassword("correct-password", "an-old-passphrase", "an-old-passphrase",
                    session, model)).isEqualTo("change-password");
            assertThat(model.getAttribute("error")).isEqualTo("Password was recently used");
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
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
            verify(policyService).validatePassword("newpassword", EMAIL, "Test User", TENANT_ID);
            verify(policyService).saveToHistory(eq(USER_ID), argThat(hash -> passwordEncoder.matches("newpassword", hash)),
                    eq(TENANT_ID));
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
