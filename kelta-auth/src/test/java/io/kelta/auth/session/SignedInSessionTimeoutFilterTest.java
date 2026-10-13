package io.kelta.auth.session;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SignedInSessionTimeoutFilter — short idle timeout until signed in")
class SignedInSessionTimeoutFilterTest {

    private static final int SHORT = (int) Duration.ofMinutes(30).toSeconds();
    private static final int LONG = (int) Duration.ofHours(8).toSeconds();

    private final SignedInSessionTimeoutFilter filter =
            new SignedInSessionTimeoutFilter(Duration.ofHours(8), Duration.ofMinutes(30));

    @Test
    @DisplayName("a request that signs the session in gives it the long timeout")
    void signingInExtends() throws Exception {
        MockHttpServletRequest request = requestWithSession(SHORT);

        filter.doFilter(request, new MockHttpServletResponse(), signIn());

        assertThat(request.getSession().getMaxInactiveInterval()).isEqualTo(LONG);
    }

    @Test
    @DisplayName("a session without a signed-in user keeps (or returns to) the short timeout")
    void notSignedInIsShort() throws Exception {
        MockHttpServletRequest request = requestWithSession(LONG);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(request.getSession().getMaxInactiveInterval()).isEqualTo(SHORT);
    }

    @Test
    @DisplayName("an anonymous authentication is not a signed-in user")
    void anonymousIsShort() throws Exception {
        MockHttpServletRequest request = requestWithSession(SHORT);
        request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new AnonymousAuthenticationToken("key", "anonymous",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))));

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(request.getSession().getMaxInactiveInterval()).isEqualTo(SHORT);
    }

    @Test
    @DisplayName("a request without a session, or one that ends it, is left alone")
    void noSessionOrEndedSession() throws Exception {
        MockHttpServletRequest noSession = new MockHttpServletRequest();
        filter.doFilter(noSession, new MockHttpServletResponse(), (req, res) -> { });
        assertThat(noSession.getSession(false)).isNull();

        MockHttpServletRequest signingOut = requestWithSession(LONG);
        filter.doFilter(signingOut, new MockHttpServletResponse(),
                (req, res) -> ((MockHttpServletRequest) req).getSession().invalidate());
        assertThat(signingOut.getSession(false)).isNull();
    }

    private static MockHttpServletRequest requestWithSession(int maxInactiveSeconds) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setMaxInactiveInterval(maxInactiveSeconds);
        request.setSession(session);
        return request;
    }

    private static FilterChain signIn() {
        return (req, res) -> ((MockHttpServletRequest) req).getSession().setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                        "user", null, AuthorityUtils.createAuthorityList("ROLE_USER"))));
    }
}
