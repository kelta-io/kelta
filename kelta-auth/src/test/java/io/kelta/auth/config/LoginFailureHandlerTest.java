package io.kelta.auth.config;

import io.kelta.auth.TestFixtures;
import io.kelta.auth.controller.ForcePasswordChangeController;
import io.kelta.auth.service.KeltaUserDetailsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoginFailureHandler")
class LoginFailureHandlerTest {

    private final LoginFailureHandler handler = new LoginFailureHandler();

    @Test
    @DisplayName("a required password change continues at /change-password for the user this sign-in loaded")
    void forcedChangeForTheLoadedUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setParameter("username", TestFixtures.EMAIL);
        var user = TestFixtures.forcePasswordChangeUser();
        request.setAttribute(KeltaUserDetailsService.LOADED_USER_ATTRIBUTE, user);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new CredentialsExpiredException("change required"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/change-password");
        var session = request.getSession(false);
        assertThat(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_USER_ID)).isEqualTo(user.getId());
        assertThat(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_TENANT_ID)).isEqualTo(user.getTenantId());
        assertThat(request.getAttribute(KeltaUserDetailsService.LOADED_USER_ATTRIBUTE)).isNull();
    }

    @Test
    @DisplayName("without a loaded user the sign-in returns to the form")
    void noLoadedUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new CredentialsExpiredException("change required"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    @DisplayName("a wrong password returns to the form even when a user was loaded")
    void otherFailures() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setAttribute(KeltaUserDetailsService.LOADED_USER_ATTRIBUTE, TestFixtures.forcePasswordChangeUser());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("bad"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(request.getSession(false)).isNull();
    }
}
