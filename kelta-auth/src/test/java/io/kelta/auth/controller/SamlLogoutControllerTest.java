package io.kelta.auth.controller;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.federation.DynamicRelyingPartyRegistrationRepository;
import io.kelta.auth.federation.SamlLogoutSupport;
import io.kelta.auth.federation.SamlSpCredentials;
import io.kelta.auth.model.SamlLogoutInfo;
import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.service.WorkerClient.SamlProviderInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.logout.Saml2LogoutRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.security.saml2.provider.service.web.authentication.logout.HttpSessionLogoutRequestRepository;
import org.springframework.security.saml2.provider.service.web.authentication.logout.Saml2LogoutRequestResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("SamlLogoutController — SP-initiated Single Logout")
@ExtendWith(MockitoExtension.class)
class SamlLogoutControllerTest {

    private static final String ISSUER = "https://auth.kelta.example";
    private static final String END_SESSION =
            ISSUER + "/connect/logout?id_token_hint=abc&post_logout_redirect_uri=https%3A%2F%2Fapp";
    private static final String REGISTRATION_ID = "tenant-1:saml-1";
    private static final String IDP_SLO = "https://idp.acme.example/slo";

    @Mock private Saml2LogoutRequestResolver logoutRequestResolver;
    @Mock private WorkerClient workerClient;

    private SamlLogoutController controller;
    private AuthProperties authProperties;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        authProperties.setIssuerUri(ISSUER);
        controller = new SamlLogoutController(logoutRequestResolver, authProperties);
    }

    private MockHttpServletRequest getRequest(String postLogout, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/logout/saml2/initiate");
        request.setScheme("https");
        request.setServerName("auth.kelta.example");
        request.setServerPort(443);
        if (postLogout != null) {
            request.setParameter("post_logout_redirect_uri", postLogout);
        }
        if (session != null) {
            request.setSession(session);
        }
        return request;
    }

    @Test
    @DisplayName("non-SAML session passes through to the (same-origin) post-logout target")
    void nonSamlSessionPassesThrough() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.initiate(getRequest(END_SESSION, new MockHttpSession()), response);

        assertThat(response.getRedirectedUrl()).isEqualTo(END_SESSION);
        verifyNoInteractions(logoutRequestResolver);
    }

    @Test
    @DisplayName("rejects a foreign post-logout target and falls back to the safe default")
    void rejectsForeignRedirect() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.initiate(getRequest("https://evil.example/steal", new MockHttpSession()), response);

        assertThat(response.getRedirectedUrl()).isEqualTo(SamlLogoutSupport.DEFAULT_LOGOUT_SUCCESS_URL);
        verifyNoInteractions(logoutRequestResolver);
    }

    @Test
    @DisplayName("SAML session redirects to the IdP SLO with a signed LogoutRequest and stashes the post-logout target")
    void samlSessionSendsLogoutRequest() throws IOException {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SamlLogoutInfo.SESSION_ATTRIBUTE,
                new SamlLogoutInfo(REGISTRATION_ID, "user@acme.example", List.of("_idx1")));
        MockHttpServletRequest request = getRequest(END_SESSION, session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        Saml2LogoutRequest logoutRequest = redirectLogoutRequest();
        when(logoutRequestResolver.resolve(any(), any(Authentication.class))).thenReturn(logoutRequest);

        controller.initiate(request, response);

        assertThat(response.getRedirectedUrl())
                .startsWith(IDP_SLO + "?")
                .contains("SAMLRequest=");
        // Login marker consumed; post-logout target stashed for the completion handler.
        assertThat(session.getAttribute(SamlLogoutInfo.SESSION_ATTRIBUTE)).isNull();
        assertThat(session.getAttribute(SamlLogoutSupport.POST_LOGOUT_URI_ATTRIBUTE)).isEqualTo(END_SESSION);
        // The LogoutRequest was saved for the returning LogoutResponse validation
        // (the repository keys it by RelayState, which the IdP echoes back).
        request.setParameter("RelayState", "relay-nonce");
        assertThat(new HttpSessionLogoutRequestRepository().loadLogoutRequest(request)).isNotNull();
    }

    @Test
    @DisplayName("SAML session with no resolvable SLO passes through to the post-logout target")
    void samlSessionNoSloPassesThrough() throws IOException {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SamlLogoutInfo.SESSION_ATTRIBUTE,
                new SamlLogoutInfo(REGISTRATION_ID, "user@acme.example", List.of()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(logoutRequestResolver.resolve(any(), any(Authentication.class))).thenReturn(null);

        controller.initiate(getRequest(END_SESSION, session), response);

        assertThat(response.getRedirectedUrl()).isEqualTo(END_SESSION);
    }

    /** A REDIRECT-binding LogoutRequest built from a real relying-party registration. */
    private Saml2LogoutRequest redirectLogoutRequest() {
        SamlSpCredentials creds = SamlSpCredentials.fromPem(resource("/saml/sp.crt"), resource("/saml/sp.key"));
        DynamicRelyingPartyRegistrationRepository repo =
                new DynamicRelyingPartyRegistrationRepository(workerClient, creds);
        when(workerClient.lookupActiveSamlProviders("tenant-1"))
                .thenReturn(Optional.of(List.of(samlProvider(resource("/saml/idp.crt")))));
        RelyingPartyRegistration reg = repo.findByRegistrationId(REGISTRATION_ID);
        return Saml2LogoutRequest.withRelyingPartyRegistration(reg)
                .samlRequest("PD94bWw+")
                .binding(Saml2MessageBinding.REDIRECT)
                .location(IDP_SLO)
                .relayState("relay-nonce")
                .parameters(p -> p.put("SAMLRequest", "PD94bWw+"))
                .build();
    }

    private static SamlProviderInfo samlProvider(String idpCertPem) {
        return new SamlProviderInfo(
                "saml-1", "Acme IdP", "acme",
                "https://idp.acme.example/entity",
                "https://idp.acme.example/sso",
                IDP_SLO,
                idpCertPem,
                "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress",
                "email", null, true);
    }

    private static String resource(String path) {
        try (InputStream in = SamlLogoutControllerTest.class.getResourceAsStream(path)) {
            assertThat(in).as("test fixture %s present", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
