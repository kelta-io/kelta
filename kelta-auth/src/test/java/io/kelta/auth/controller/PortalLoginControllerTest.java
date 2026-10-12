package io.kelta.auth.controller;

import io.kelta.auth.TestFixtures;
import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.config.TenantContextFilter;
import io.kelta.auth.service.AuthDomainResolver;
import io.kelta.auth.service.PortalLoginService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PortalLoginController")
class PortalLoginControllerTest {

    private static final String SLUG = "acme";
    private static final String TOKEN = "magic-token";

    private PortalLoginService portalLoginService;
    private AuthDomainResolver domainResolver;
    private PortalLoginController controller;

    @BeforeEach
    void setUp() {
        portalLoginService = mock(PortalLoginService.class);
        domainResolver = mock(AuthDomainResolver.class);
        when(domainResolver.resolveTenantSlug(anyString())).thenReturn(Optional.empty());
        AuthProperties authProperties = new AuthProperties();
        authProperties.setUiBaseUrl("https://app.example.com/");
        controller = new PortalLoginController(portalLoginService, domainResolver, authProperties);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest verifyRequest(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/portal/login/verify");
        request.setSession(session);
        return request;
    }

    @Test
    @DisplayName("an unknown or used token renders the error page and authenticates nothing")
    void invalidTokenRendersError() {
        when(portalLoginService.verify(TOKEN)).thenReturn(Optional.empty());
        MockHttpSession session = new MockHttpSession();

        String view = controller.verify(TOKEN, verifyRequest(session), new MockHttpServletResponse());

        assertThat(view).isEqualTo("portal-login-error");
        assertThat(session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
    }

    @Test
    @DisplayName("a valid token saves the session, stores the tenant SLUG and redirects to the app")
    void validTokenStoresSlugAndRedirects() {
        when(portalLoginService.verify(TOKEN)).thenReturn(Optional.of(
                new PortalLoginService.PortalVerification(TestFixtures.userDetails(), SLUG)));
        MockHttpSession session = new MockHttpSession();

        String view = controller.verify(TOKEN, verifyRequest(session), new MockHttpServletResponse());

        assertThat(view).isEqualTo("redirect:https://app.example.com/" + SLUG + "/app");
        assertThat(session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNotNull();
        // The slug, as LoginController writes — never the tenant UUID.
        assertThat(session.getAttribute("tenantId")).isEqualTo(SLUG);
    }

    @Test
    @DisplayName("the session survives the SPA's /oauth2/authorize for the same tenant")
    void sessionSurvivesTheFollowingAuthorize() throws Exception {
        when(portalLoginService.verify(TOKEN)).thenReturn(Optional.of(
                new PortalLoginService.PortalVerification(TestFixtures.userDetails(), SLUG)));
        MockHttpSession session = new MockHttpSession();
        controller.verify(TOKEN, verifyRequest(session), new MockHttpServletResponse());
        SecurityContextHolder.clearContext();

        // The app the link redirects to starts the OIDC flow on the same browser session.
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(SLUG)))
                .thenReturn(List.of(TestFixtures.TENANT_ID));
        TenantContextFilter filter = new TenantContextFilter(domainResolver, jdbc);
        MockHttpServletRequest authorize = new MockHttpServletRequest("GET", "/oauth2/authorize");
        authorize.setServletPath("/oauth2/authorize");
        authorize.setParameter("redirect_uri", "https://app.example.com/" + SLUG + "/auth/callback");
        authorize.setSession(session);

        filter.doFilter(authorize, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .as("a portal sign-in must not be cleared as a tenant switch")
                .isNotNull();
    }
}
