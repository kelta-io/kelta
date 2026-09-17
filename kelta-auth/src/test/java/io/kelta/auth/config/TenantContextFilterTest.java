package io.kelta.auth.config;

import io.kelta.auth.service.AuthDomainResolver;
import io.kelta.runtime.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantContextFilter Tests")
class TenantContextFilterTest {

    private TenantContextFilter filter;

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;
    @Mock private HttpSession session;
    @Mock private AuthDomainResolver domainResolver;
    @Mock private JdbcTemplate jdbcTemplate;

    private static final String TENANT_UUID = "11111111-2222-3333-4444-555555555555";

    @BeforeEach
    void setUp() {
        filter = new TenantContextFilter(domainResolver, jdbcTemplate);
        lenient().when(domainResolver.resolveTenantSlug(anyString())).thenReturn(Optional.empty());
    }

    /** Captures the tenant bound for the duration of the chain, or null if none was. */
    private String tenantSeenByChain() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(TenantContext.get());
            return null;
        }).when(filterChain).doFilter(request, response);
        filter.doFilterInternal(request, response, filterChain);
        return seen.get();
    }

    @Test
    @DisplayName("should set tenant slug from redirect_uri on /oauth2/authorize")
    void shouldSetTenantSlugFromRedirectUri() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getServletPath()).thenReturn("/oauth2/authorize");
        when(request.getParameter("redirect_uri"))
                .thenReturn("https://app.kelta.io/default/auth/callback");
        when(request.getSession()).thenReturn(session);

        filter.doFilterInternal(request, response, filterChain);

        verify(session).setAttribute("tenantId", "default");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("should not set tenant when no redirect_uri parameter")
    void shouldNotSetTenantWhenNoRedirectUri() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getServletPath()).thenReturn("/oauth2/authorize");
        when(request.getParameter("redirect_uri")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(session, never()).setAttribute(anyString(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("should not set tenant when path does not match /{slug}/auth/callback")
    void shouldNotSetTenantWhenPathDoesNotMatch() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getServletPath()).thenReturn("/oauth2/authorize");
        when(request.getParameter("redirect_uri"))
                .thenReturn("https://app.kelta.io/auth/callback");

        filter.doFilterInternal(request, response, filterChain);

        verify(session, never()).setAttribute(anyString(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("should not set tenant on non-authorize requests")
    void shouldNotSetTenantOnNonAuthorizeRequest() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        lenient().when(request.getServletPath()).thenReturn("/login");

        filter.doFilterInternal(request, response, filterChain);

        verify(session, never()).setAttribute(anyString(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("should always call filter chain")
    void shouldAlwaysCallFilterChain() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getServletPath()).thenReturn("/oauth2/authorize");
        when(request.getParameter("redirect_uri")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("should handle invalid redirect_uri without throwing")
    void shouldHandleInvalidRedirectUri() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getServletPath()).thenReturn("/oauth2/authorize");
        when(request.getParameter("redirect_uri")).thenReturn("not a valid uri %%");

        filter.doFilterInternal(request, response, filterChain);

        verify(session, never()).setAttribute(anyString(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("should clear Spring Security context when tenant slug changes")
    void shouldClearSecurityContextWhenTenantChanges() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getServletPath()).thenReturn("/oauth2/authorize");
        when(request.getParameter("redirect_uri"))
                .thenReturn("https://app.kelta.io/default/auth/callback");
        when(request.getSession()).thenReturn(session);
        when(session.getAttribute("tenantId")).thenReturn("threadline-clothing");

        filter.doFilterInternal(request, response, filterChain);

        verify(session).removeAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        verify(session).setAttribute("tenantId", "default");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("runs before springSecurityFilterChain via @Order annotation")
    void shouldBeOrderedBeforeSpringSecurity() {
        Order order = TenantContextFilter.class.getAnnotation(Order.class);
        assertNotNull(order, "TenantContextFilter must have @Order to run before Spring Security");
        assertTrue(order.value() < SecurityFilterProperties.DEFAULT_FILTER_ORDER,
                "TenantContextFilter @Order must be less than SecurityFilterProperties.DEFAULT_FILTER_ORDER "
                        + "(" + SecurityFilterProperties.DEFAULT_FILTER_ORDER + ") so the session tenant is "
                        + "set before Spring Security redirects to /login. Was: " + order.value());
    }

    @Test
    @DisplayName("should not clear Spring Security context when tenant slug is unchanged")
    void shouldNotClearSecurityContextWhenTenantSame() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getServletPath()).thenReturn("/oauth2/authorize");
        when(request.getParameter("redirect_uri"))
                .thenReturn("https://app.kelta.io/default/auth/callback");
        when(request.getSession()).thenReturn(session);
        when(session.getAttribute("tenantId")).thenReturn("default");

        filter.doFilterInternal(request, response, filterChain);

        verify(session, never()).removeAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        verify(session).setAttribute("tenantId", "default");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("should resolve tenant from Host header when redirect_uri has no slug prefix")
    void shouldResolveTenantFromHostHeader() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getServletPath()).thenReturn("/oauth2/authorize");
        // Slug-less redirect_uri on a custom domain
        when(request.getParameter("redirect_uri"))
                .thenReturn("https://acme.com/auth/callback");
        when(request.getServerName()).thenReturn("acme.com");
        when(request.getSession()).thenReturn(session);
        when(domainResolver.resolveTenantSlug("acme.com")).thenReturn(Optional.of("acme"));

        filter.doFilterInternal(request, response, filterChain);

        verify(session).setAttribute("tenantId", "acme");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("path-slug wins over Host header when both are present")
    void pathSlugPrecedesHost() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getServletPath()).thenReturn("/oauth2/authorize");
        when(request.getParameter("redirect_uri"))
                .thenReturn("https://app.kelta.io/threadline/auth/callback");
        when(request.getSession()).thenReturn(session);

        filter.doFilterInternal(request, response, filterChain);

        verify(session).setAttribute("tenantId", "threadline");
        verify(domainResolver, never()).resolveTenantSlug(anyString());
    }

    // ── binding the resolved tenant to the database connection ───────────────

    @Test
    @DisplayName("binds the session's tenant slug, resolved to its UUID, for the whole request")
    void bindsTenantResolvedFromSessionSlug() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("tenantId")).thenReturn("threadline-clothing");
        when(jdbcTemplate.queryForList("SELECT id FROM tenant WHERE slug = ?", String.class,
                "threadline-clothing")).thenReturn(List.of(TENANT_UUID));

        assertEquals(TENANT_UUID, tenantSeenByChain());
    }

    @Test
    @DisplayName("binds a session tenant that is already a UUID without touching the database")
    void bindsSessionTenantUuidDirectly() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("tenantId")).thenReturn(TENANT_UUID);

        assertEquals(TENANT_UUID, tenantSeenByChain());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("binds from the ?tenant= parameter before the session carries it")
    void bindsFromTenantQueryParameter() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getServletPath()).thenReturn("/login");
        when(request.getSession(false)).thenReturn(null);
        when(request.getParameter("tenant")).thenReturn("threadline-clothing");
        when(jdbcTemplate.queryForList("SELECT id FROM tenant WHERE slug = ?", String.class,
                "threadline-clothing")).thenReturn(List.of(TENANT_UUID));

        assertEquals(TENANT_UUID, tenantSeenByChain());
    }

    @Test
    @DisplayName("resolves each slug once — the lookup is cached across requests")
    void cachesSlugResolution() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("tenantId")).thenReturn("threadline-clothing");
        when(jdbcTemplate.queryForList("SELECT id FROM tenant WHERE slug = ?", String.class,
                "threadline-clothing")).thenReturn(List.of(TENANT_UUID));

        tenantSeenByChain();
        tenantSeenByChain();

        verify(jdbcTemplate, times(1)).queryForList(anyString(), eq(String.class), anyString());
    }

    @Test
    @DisplayName("a request with no tenant keeps the platform session — nothing is bound")
    void bindsNothingWithoutATenant() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getSession(false)).thenReturn(null);
        when(request.getParameter("tenant")).thenReturn(null);

        assertNull(tenantSeenByChain());
    }

    @Test
    @DisplayName("an unknown slug binds nothing rather than binding a value no policy matches")
    void unknownSlugBindsNothing() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("tenantId")).thenReturn("does-not-exist");
        when(jdbcTemplate.queryForList("SELECT id FROM tenant WHERE slug = ?", String.class,
                "does-not-exist")).thenReturn(List.of());

        assertNull(tenantSeenByChain());
    }

    @Test
    @DisplayName("a database error during resolution leaves the request on the platform session")
    void databaseErrorDoesNotFailTheRequest() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("tenantId")).thenReturn("threadline-clothing");
        when(jdbcTemplate.queryForList("SELECT id FROM tenant WHERE slug = ?", String.class,
                "threadline-clothing")).thenThrow(new IllegalStateException("pool exhausted"));

        assertNull(tenantSeenByChain());
        verify(filterChain).doFilter(request, response);
    }
}
