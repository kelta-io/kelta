package io.kelta.auth.controller;

import io.kelta.auth.config.ExpiredLoginFormHandler;
import io.kelta.auth.federation.DynamicClientRegistrationRepository;
import io.kelta.auth.federation.DynamicRelyingPartyRegistrationRepository;
import io.kelta.auth.service.AuthDomainResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LoginController Tests")
class LoginControllerTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpSession session;
    @Mock private AuthDomainResolver domainResolver;

    @BeforeEach
    void setUp() {
        lenient().when(request.getSession()).thenReturn(session);
        lenient().when(request.getParameter("pending_activation")).thenReturn(null);
        lenient().when(request.getParameter("federation")).thenReturn(null);
        lenient().when(domainResolver.resolveTenantSlug(anyString())).thenReturn(Optional.empty());
    }

    /** An ObjectProvider yielding the given SAML repo (or none when null). */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<RelyingPartyRegistrationRepository> samlProvider(
            RelyingPartyRegistrationRepository repo) {
        ObjectProvider<RelyingPartyRegistrationRepository> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(repo);
        return provider;
    }

    @Nested
    @DisplayName("With DynamicClientRegistrationRepository")
    class WithDynamicRepo {
        @Mock private DynamicClientRegistrationRepository dynamicRepo;
        private LoginController controller;

        @BeforeEach
        void setUp() {
            controller = new LoginController(dynamicRepo, domainResolver, samlProvider(null));
        }

        @Test
        void shouldLoadSsoProvidersWhenTenantInSession() {
            when(session.getAttribute("tenantId")).thenReturn("tenant-1");
            ClientRegistration reg = buildRegistration("tenant-1:provider-1", "Google SSO");
            when(dynamicRepo.findByTenantId("tenant-1")).thenReturn(List.of(reg));

            Model model = new ConcurrentModel();
            String view = controller.login(model, request);

            assertThat(view).isEqualTo("login");
            @SuppressWarnings("unchecked")
            var providers = (List<LoginController.SsoProviderInfo>) model.getAttribute("ssoProviders");
            assertThat(providers).hasSize(1);
            assertThat(providers.get(0).name()).isEqualTo("Google SSO");
        }

        @Test
        void shouldResolveTenantFromQueryParam() {
            when(session.getAttribute("tenantId")).thenReturn(null);
            when(request.getParameter("tenant")).thenReturn("tenant-2");
            when(dynamicRepo.findByTenantId("tenant-2")).thenReturn(List.of());

            Model model = new ConcurrentModel();
            controller.login(model, request);

            verify(session).setAttribute("tenantId", "tenant-2");
            verify(dynamicRepo).findByTenantId("tenant-2");
        }

        @Test
        void shouldReturnLoginViewWithoutProvidersWhenNoTenant() {
            when(session.getAttribute("tenantId")).thenReturn(null);
            when(request.getParameter("tenant")).thenReturn(null);

            Model model = new ConcurrentModel();
            String view = controller.login(model, request);

            assertThat(view).isEqualTo("login");
            assertThat(model.containsAttribute("ssoProviders")).isFalse();
        }

        @Test
        void shouldRedirectToFederationWhenIdpHintMatchesRegistration() {
            doReturn("tenant-1:provider-1").when(request).getParameter("idp_hint");
            ClientRegistration reg = buildRegistration("tenant-1:provider-1", "Google SSO");
            doReturn(reg).when(dynamicRepo).findByRegistrationId("tenant-1:provider-1");

            Model model = new ConcurrentModel();
            String view = controller.login(model, request);

            assertThat(view).isEqualTo("redirect:/oauth2/authorization/tenant-1%3Aprovider-1");
            verify(dynamicRepo).findByRegistrationId("tenant-1:provider-1");
            verify(dynamicRepo, never()).findByTenantId(any());
        }

        @Test
        void shouldFallBackToLoginPageWhenIdpHintIsUnknown() {
            doReturn("tenant-1:unknown").when(request).getParameter("idp_hint");
            doReturn(null).when(dynamicRepo).findByRegistrationId("tenant-1:unknown");

            Model model = new ConcurrentModel();
            String view = controller.login(model, request);

            assertThat(view).isEqualTo("login");
        }

        @Test
        void shouldRenderSsoButtonsForHintTenantWhenAutoFederationFails() {
            // Hint short-circuit can't match a registration (e.g. provider missing
            // client_secret), so the user lands on /login. They must still see the
            // tenant's SSO buttons so they can pick a provider manually.
            doReturn("tenant-7:does-not-exist").when(request).getParameter("idp_hint");
            doReturn(null).when(dynamicRepo).findByRegistrationId("tenant-7:does-not-exist");
            when(session.getAttribute("tenantId")).thenReturn(null);
            ClientRegistration reg = buildRegistration("tenant-7:authentik", "Authentik");
            when(dynamicRepo.findByTenantId("tenant-7")).thenReturn(List.of(reg));

            Model model = new ConcurrentModel();
            String view = controller.login(model, request);

            assertThat(view).isEqualTo("login");
            verify(session).setAttribute("tenantId", "tenant-7");
            @SuppressWarnings("unchecked")
            var providers = (List<LoginController.SsoProviderInfo>) model.getAttribute("ssoProviders");
            assertThat(providers).hasSize(1);
            assertThat(providers.get(0).name()).isEqualTo("Authentik");
        }
    }

    @Nested
    @DisplayName("With SAML federation")
    class WithSamlRepo {
        @Mock private ClientRegistrationRepository oidcRepo; // plain → OIDC branch skipped
        @Mock private DynamicRelyingPartyRegistrationRepository samlRepo;
        private LoginController controller;

        @BeforeEach
        void setUp() {
            controller = new LoginController(oidcRepo, domainResolver, samlProvider(samlRepo));
        }

        @Test
        void shouldLoadSamlProvidersWhenTenantInSession() {
            when(session.getAttribute("tenantId")).thenReturn("tenant-9");
            when(samlRepo.findButtonsByTenantId("tenant-9")).thenReturn(List.of(
                    new DynamicRelyingPartyRegistrationRepository.SamlButton("tenant-9:saml-1", "Acme IdP")));

            Model model = new ConcurrentModel();
            String view = controller.login(model, request);

            assertThat(view).isEqualTo("login");
            @SuppressWarnings("unchecked")
            var providers = (List<LoginController.SsoProviderInfo>) model.getAttribute("samlProviders");
            assertThat(providers).hasSize(1);
            assertThat(providers.get(0).registrationId()).isEqualTo("tenant-9:saml-1");
            assertThat(providers.get(0).name()).isEqualTo("Acme IdP");
        }

        @Test
        void shouldNotAddSamlProvidersWhenNoTenant() {
            when(session.getAttribute("tenantId")).thenReturn(null);
            when(request.getParameter("tenant")).thenReturn(null);

            Model model = new ConcurrentModel();
            controller.login(model, request);

            assertThat(model.containsAttribute("samlProviders")).isFalse();
        }
    }

    @Nested
    @DisplayName("With plain ClientRegistrationRepository")
    class WithPlainRepo {
        @Mock private ClientRegistrationRepository plainRepo;
        private LoginController controller;

        @BeforeEach
        void setUp() {
            controller = new LoginController(plainRepo, domainResolver, samlProvider(null));
        }

        @Test
        void shouldNotLoadProvidersWhenRepoIsNotDynamic() {
            when(session.getAttribute("tenantId")).thenReturn("tenant-1");

            Model model = new ConcurrentModel();
            String view = controller.login(model, request);

            assertThat(view).isEqualTo("login");
            assertThat(model.containsAttribute("ssoProviders")).isFalse();
        }
    }

    @Nested
    @DisplayName("Resuming an expired login form")
    class ExpiredForm {
        @Mock private ClientRegistrationRepository plainRepo;

        @Test
        @DisplayName("exposes the pending authorize request and the expired notice once")
        void exposesPendingAuthorizeAndExpiredNotice() {
            LoginController controller = new LoginController(plainRepo, domainResolver, samlProvider(null));
            MockHttpServletRequest authorize = new MockHttpServletRequest("GET", "/oauth2/authorize");
            authorize.setQueryString("response_type=code&state=abc");
            new HttpSessionRequestCache().saveRequest(authorize, new MockHttpServletResponse());

            MockHttpServletRequest login = new MockHttpServletRequest("GET", "/login");
            login.setSession(authorize.getSession());
            login.getSession().setAttribute(ExpiredLoginFormHandler.SESSION_EXPIRED_ATTR, Boolean.TRUE);

            Model model = new ConcurrentModel();
            controller.login(model, login);
            assertThat(model.getAttribute("authorizeUrl"))
                    .isEqualTo("/oauth2/authorize?response_type=code&state=abc");
            assertThat(model.getAttribute("loginExpired")).isEqualTo(true);

            Model again = new ConcurrentModel();
            controller.login(again, login);
            assertThat(again.containsAttribute("loginExpired")).isFalse();
        }
    }

    private ClientRegistration buildRegistration(String registrationId, String clientName) {
        return ClientRegistration.withRegistrationId(registrationId)
                .clientId("client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://example.com/auth")
                .tokenUri("https://example.com/token")
                .clientName(clientName)
                .build();
    }
}
