package io.kelta.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("ExpiredLoginFormHandler")
class ExpiredLoginFormHandlerTest {

    private static final String AUTHORIZE =
            "/oauth2/authorize?response_type=code&client_id=kelta-platform&state=abc";

    private final AccessDeniedHandler delegate = mock(AccessDeniedHandler.class);
    private final ExpiredLoginFormHandler handler = new ExpiredLoginFormHandler(delegate);

    @Test
    @DisplayName("a stale login POST resumes the authorization request it carried")
    void resumesAuthorizeRequest() throws Exception {
        MockHttpServletRequest request = loginPost(AUTHORIZE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new MissingCsrfTokenException("t"));

        assertThat(response.getRedirectedUrl()).isEqualTo(AUTHORIZE);
        assertThat(request.getSession().getAttribute(ExpiredLoginFormHandler.SESSION_EXPIRED_ATTR))
                .isEqualTo(Boolean.TRUE);
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("without an authorization request, a stale login POST returns to the login page")
    void fallsBackToLoginPage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(loginPost(null), response, new MissingCsrfTokenException("t"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
    }

    @Test
    @DisplayName("refuses to follow anything but a same-origin /oauth2/authorize URL")
    void rejectsOpenRedirects() throws Exception {
        for (String target : new String[] {
                "https://evil.example/oauth2/authorize?x=1",
                "//evil.example/oauth2/authorize?x=1",
                "/oauth2/authorizeX?x=1",
                "/logout?x=1",
                "/oauth2/authorize?x=1\r\nSet-Cookie: a=b",
                "/oauth2/authorize?x=1\\@evil.example"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            handler.handle(loginPost(target), response, new MissingCsrfTokenException("t"));
            assertThat(response.getRedirectedUrl()).as(target).isEqualTo("/login");
        }
    }

    @Test
    @DisplayName("any other access-denied case keeps the default handling")
    void delegatesEverythingElse() throws Exception {
        MockHttpServletRequest otherPost = new MockHttpServletRequest("POST", "/mfa-challenge");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MissingCsrfTokenException csrf = new MissingCsrfTokenException("t");
        handler.handle(otherPost, response, csrf);
        verify(delegate).handle(otherPost, response, csrf);

        MockHttpServletRequest login = loginPost(AUTHORIZE);
        AccessDeniedException denied = new AccessDeniedException("nope");
        handler.handle(login, response, denied);
        verify(delegate).handle(login, response, denied);
    }

    @Test
    @DisplayName("pendingAuthorizeUrl exposes only a saved GET /oauth2/authorize request")
    void pendingAuthorizeUrl() {
        assertThat(ExpiredLoginFormHandler.pendingAuthorizeUrl(saved("GET", "/oauth2/authorize",
                "response_type=code&state=abc")))
                .isEqualTo("/oauth2/authorize?response_type=code&state=abc");
        assertThat(ExpiredLoginFormHandler.pendingAuthorizeUrl(saved("GET", "/admin", "x=1"))).isNull();
        assertThat(ExpiredLoginFormHandler.pendingAuthorizeUrl(saved("POST", "/oauth2/authorize", "x=1")))
                .isNull();
        assertThat(ExpiredLoginFormHandler.pendingAuthorizeUrl(null)).isNull();
    }

    private static MockHttpServletRequest loginPost(String authorizeUrl) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        if (authorizeUrl != null) {
            request.setParameter(ExpiredLoginFormHandler.AUTHORIZE_URL_PARAM, authorizeUrl);
        }
        return request;
    }

    private static SavedRequest saved(String method, String uri, String query) {
        MockHttpServletRequest original = new MockHttpServletRequest(method, uri);
        original.setQueryString(query);
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.saveRequest(original, new MockHttpServletResponse());
        return cache.getRequest(original, null);
    }
}
