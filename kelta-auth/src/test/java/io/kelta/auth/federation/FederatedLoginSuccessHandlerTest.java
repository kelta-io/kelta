package io.kelta.auth.federation;

import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.service.WorkerClient.OidcProviderInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.net.URL;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("FederatedLoginSuccessHandler")
@ExtendWith(MockitoExtension.class)
class FederatedLoginSuccessHandlerTest {

    @Mock private FederatedUserMapper userMapper;
    @Mock private WorkerClient workerClient;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;

    private FederatedLoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FederatedLoginSuccessHandler(userMapper, workerClient);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private OidcProviderInfo provider() {
        return new OidcProviderInfo(
                "prov-1", "Test Provider", "https://idp.example.com", null,
                null, "client-id", "enc-secret",
                null, null, null, null, null, null, null,
                null, null, null
        );
    }

    @Nested
    @DisplayName("onAuthenticationSuccess")
    class OnAuthenticationSuccess {

        @Test
        @DisplayName("delegates non-OAuth2 authentication")
        void delegatesNonOAuth2() throws Exception {
            Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass");

            handler.onAuthenticationSuccess(request, response, auth);

            verifyNoInteractions(userMapper);
            verifyNoInteractions(workerClient);
        }

        @Test
        @DisplayName("redirects to /login?federation for invalid registration ID format")
        void redirectsForInvalidFormat() throws Exception {
            OidcUser oidcUser = mock(OidcUser.class);
            OAuth2AuthenticationToken oauthToken = mock(OAuth2AuthenticationToken.class);
            when(oauthToken.getPrincipal()).thenReturn(oidcUser);
            when(oauthToken.getAuthorizedClientRegistrationId()).thenReturn("no-colon-format");

            handler.onAuthenticationSuccess(request, response, oauthToken);

            verify(response).sendRedirect("/login?federation");
            verifyNoInteractions(userMapper);
        }

        @Test
        @DisplayName("redirects to /login?federation when provider not found")
        void redirectsWhenProviderNotFound() throws Exception {
            OidcUser oidcUser = mock(OidcUser.class);
            when(oidcUser.getIssuer()).thenReturn(new URL("https://idp.example.com"));
            when(oidcUser.getSubject()).thenReturn("sub-1");

            OAuth2AuthenticationToken oauthToken = mock(OAuth2AuthenticationToken.class);
            when(oauthToken.getPrincipal()).thenReturn(oidcUser);
            when(oauthToken.getAuthorizedClientRegistrationId()).thenReturn("tenant1:prov-1");

            when(workerClient.findOidcProviderByIssuer("https://idp.example.com", "tenant1"))
                    .thenReturn(Optional.empty());

            handler.onAuthenticationSuccess(request, response, oauthToken);

            verify(response).sendRedirect("/login?federation");
        }

        @Test
        @DisplayName("redirects to /login?pending_activation when user mapping fails")
        void redirectsWhenMappingFails() throws Exception {
            OidcUser oidcUser = mock(OidcUser.class);
            when(oidcUser.getIssuer()).thenReturn(new URL("https://idp.example.com"));
            when(oidcUser.getSubject()).thenReturn("sub-1");

            OAuth2AuthenticationToken oauthToken = mock(OAuth2AuthenticationToken.class);
            when(oauthToken.getPrincipal()).thenReturn(oidcUser);
            when(oauthToken.getAuthorizedClientRegistrationId()).thenReturn("tenant1:prov-1");

            when(workerClient.findOidcProviderByIssuer("https://idp.example.com", "tenant1"))
                    .thenReturn(Optional.of(provider()));
            when(userMapper.mapUser(eq(oidcUser), eq("tenant1"), any())).thenReturn(Optional.empty());

            handler.onAuthenticationSuccess(request, response, oauthToken);

            verify(response).sendRedirect("/login?pending_activation");
        }

        @Test
        @DisplayName("sets SecurityContext with KeltaUserDetails on success")
        void setsSecurityContextOnSuccess() throws Exception {
            OidcUser oidcUser = mock(OidcUser.class);
            when(oidcUser.getIssuer()).thenReturn(new URL("https://idp.example.com"));
            when(oidcUser.getSubject()).thenReturn("sub-1");

            OAuth2AuthenticationToken oauthToken = mock(OAuth2AuthenticationToken.class);
            when(oauthToken.getPrincipal()).thenReturn(oidcUser);
            when(oauthToken.getAuthorizedClientRegistrationId()).thenReturn("tenant1:prov-1");

            KeltaUserDetails userDetails = new KeltaUserDetails(
                    "user-1", "user@test.com", "tenant1", "prof-1", "Admin",
                    "John Doe", "", true, false, false);

            when(workerClient.findOidcProviderByIssuer("https://idp.example.com", "tenant1"))
                    .thenReturn(Optional.of(provider()));
            when(userMapper.mapUser(eq(oidcUser), eq("tenant1"), any()))
                    .thenReturn(Optional.of(userDetails));

            handler.onAuthenticationSuccess(request, response, oauthToken);

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getPrincipal()).isInstanceOf(KeltaUserDetails.class);
            KeltaUserDetails principal = (KeltaUserDetails) auth.getPrincipal();
            assertThat(principal.getEmail()).isEqualTo("user@test.com");
            assertThat(principal.getTenantId()).isEqualTo("tenant1");
            // Spring's OIDC login provider adds no factor (spring-security#19140); the platform
            // authentication still needs one for the ID token's auth_time (PLT-767).
            assertThat(auth.getAuthorities())
                    .filteredOn(FactorGrantedAuthority.class::isInstance)
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);
        }

        @Test
        @DisplayName("delegates non-OidcUser principal")
        void delegatesNonOidcUserPrincipal() throws Exception {
            OAuth2AuthenticationToken oauthToken = mock(OAuth2AuthenticationToken.class);
            // Principal is not an OidcUser
            when(oauthToken.getPrincipal()).thenReturn(mock(org.springframework.security.oauth2.core.user.OAuth2User.class));

            handler.onAuthenticationSuccess(request, response, oauthToken);

            verifyNoInteractions(userMapper);
            verifyNoInteractions(workerClient);
        }
    }
}
