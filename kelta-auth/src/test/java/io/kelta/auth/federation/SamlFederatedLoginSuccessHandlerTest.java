package io.kelta.auth.federation;

import io.kelta.auth.TestFixtures;
import io.kelta.auth.model.SamlLogoutInfo;
import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.service.WorkerClient.SamlProviderInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("SamlFederatedLoginSuccessHandler — logout-info capture")
@ExtendWith(MockitoExtension.class)
class SamlFederatedLoginSuccessHandlerTest {

    @Mock private FederatedUserMapper userMapper;
    @Mock private WorkerClient workerClient;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("stashes SamlLogoutInfo (NameID + SessionIndexes + registrationId) in the session")
    void capturesLogoutInfo() throws Exception {
        SamlProviderInfo provider = new SamlProviderInfo(
                "saml-1", "Acme IdP", "acme",
                "https://idp.acme.example/entity", "https://idp.acme.example/sso",
                "https://idp.acme.example/slo", "cert",
                "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress", "email", null, true);
        when(workerClient.findActiveSamlProviders("tenant-1")).thenReturn(List.of(provider));
        when(userMapper.mapSamlUser(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("tenant-1"),
                org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(TestFixtures.userDetails()));

        DefaultSaml2AuthenticatedPrincipal principal = new DefaultSaml2AuthenticatedPrincipal(
                "user@acme.example", Map.of(), List.of("_session-index-1"));
        principal.setRelyingPartyRegistrationId("tenant-1:saml-1");
        Saml2Authentication authentication = new Saml2Authentication(principal, "<resp/>", List.of());

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SamlFederatedLoginSuccessHandler(userMapper, workerClient)
                .onAuthenticationSuccess(request, response, authentication);

        Object stashed = request.getSession().getAttribute(SamlLogoutInfo.SESSION_ATTRIBUTE);
        assertThat(stashed).isInstanceOf(SamlLogoutInfo.class);
        SamlLogoutInfo info = (SamlLogoutInfo) stashed;
        assertThat(info.registrationId()).isEqualTo("tenant-1:saml-1");
        assertThat(info.nameId()).isEqualTo("user@acme.example");
        assertThat(info.sessionIndexes()).containsExactly("_session-index-1");
    }

    @Test
    @DisplayName("the platform authentication keeps the SAML login's factor for the ID token's auth_time (PLT-767)")
    void keepsSamlFactor() throws Exception {
        SamlProviderInfo provider = new SamlProviderInfo(
                "saml-1", "Acme IdP", "acme",
                "https://idp.acme.example/entity", "https://idp.acme.example/sso",
                "https://idp.acme.example/slo", "cert",
                "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress", "email", null, true);
        when(workerClient.findActiveSamlProviders("tenant-1")).thenReturn(List.of(provider));
        when(userMapper.mapSamlUser(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("tenant-1"),
                org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(TestFixtures.userDetails()));
        DefaultSaml2AuthenticatedPrincipal principal = new DefaultSaml2AuthenticatedPrincipal(
                "user@acme.example", Map.of(), List.of("_session-index-1"));
        principal.setRelyingPartyRegistrationId("tenant-1:saml-1");
        Instant assertedAt = Instant.now().minus(1, ChronoUnit.MINUTES);
        FactorGrantedAuthority samlFactor = FactorGrantedAuthority
                .withAuthority(FactorGrantedAuthority.SAML_RESPONSE_AUTHORITY).issuedAt(assertedAt).build();
        Saml2Authentication authentication = new Saml2Authentication(principal, "<resp/>", List.of(samlFactor));

        new SamlFederatedLoginSuccessHandler(userMapper, workerClient)
                .onAuthenticationSuccess(new MockHttpServletRequest(), new MockHttpServletResponse(), authentication);

        Authentication platform = SecurityContextHolder.getContext().getAuthentication();
        assertThat(List.<GrantedAuthority>copyOf(platform.getAuthorities()))
                .filteredOn(FactorGrantedAuthority.class::isInstance)
                .containsExactly(samlFactor);
    }
}
