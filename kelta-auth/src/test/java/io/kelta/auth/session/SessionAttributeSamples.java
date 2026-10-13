package io.kelta.auth.session;

import io.kelta.auth.config.AuthorizationServerConfig;
import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.model.SamlLogoutInfo;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2PostAuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2RedirectAuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.logout.Saml2LogoutRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

import jakarta.servlet.http.Cookie;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One value of every kind kelta-auth and the framework keep in an HTTP session, built the way
 * the running service builds it (real authentication provider, request cache, CSRF repository,
 * authorization-request resolver, flash map manager) wherever that is practical.
 */
public final class SessionAttributeSamples {

    private static final String TENANT_ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String REGISTRATION_ID = TENANT_ID + ":provider-1";

    private SessionAttributeSamples() {
    }

    /** Session attribute (or session value) name → a representative value. */
    public static Map<String, Object> all() throws Exception {
        Map<String, Object> samples = new LinkedHashMap<>();
        samples.put("security context after password login", new SecurityContextImpl(passwordLogin()));
        samples.put("security context after federated login", new SecurityContextImpl(federatedPlatformToken()));
        samples.put("security context holding an OIDC login", new SecurityContextImpl(oidcLogin()));
        samples.put("security context holding a SAML login", new SecurityContextImpl(samlLogin()));
        samples.put("saved authorization request", savedRequest());
        samples.put("CSRF token", new HttpSessionCsrfTokenRepository().generateToken(browserRequest()));
        samples.put("OAuth2 authorization request", oauth2AuthorizationRequest());
        samples.put("SAML redirect authentication request", Saml2RedirectAuthenticationRequest
                .withRelyingPartyRegistration(samlRegistration())
                .samlRequest("encoded-request").relayState("relay").sigAlg("alg").signature("sig").id("ARQ1")
                .build());
        samples.put("SAML POST authentication request", Saml2PostAuthenticationRequest
                .withRelyingPartyRegistration(samlRegistration())
                .samlRequest("encoded-request").relayState("relay").id("ARQ2")
                .build());
        samples.put("SAML logout request", Saml2LogoutRequest.withRelyingPartyRegistration(samlRegistration())
                .samlRequest("encoded-logout").relayState("relay").id("LRQ1")
                .binding(Saml2MessageBinding.REDIRECT).location("https://idp.example.test/slo")
                .parameters(params -> params.put("SigAlg", "alg"))
                .build());
        samples.put("SAML logout info", new SamlLogoutInfo(REGISTRATION_ID, "name-id", List.of("session-index")));
        samples.put("OAuth2 login failure", new OAuth2AuthenticationException(
                new OAuth2Error("invalid_token_response", "token endpoint failed", null)));
        samples.put("SAML login failure", new Saml2AuthenticationException(
                new Saml2Error(Saml2ErrorCodes.INVALID_IN_RESPONSE_TO, "no saved request")));
        samples.put("bad credentials", new BadCredentialsException("Bad credentials"));
        samples.put("expired credentials", new CredentialsExpiredException("expired"));
        samples.put("flash attributes", flashMaps());
        samples.put("string attribute", "tenant-a");
        samples.put("boolean attribute", Boolean.TRUE);
        samples.put("session creation time", Instant.now().toEpochMilli());
        samples.put("session idle timeout", 1800);
        // The indexed store publishes a new session's first values as one map.
        samples.put("session-created event", new HashMap<>(samples));
        return samples;
    }

    /** A principal the way {@code KeltaUserDetailsService} returns it. */
    static KeltaUserDetails user(PasswordEncoder encoder) {
        return new KeltaUserDetails("11111111-0000-0000-0000-000000000001", "user@example.test", TENANT_ID,
                "profile-1", "Standard", "Test User", encoder == null ? null : encoder.encode("password"),
                true, false, false);
    }

    private static Authentication passwordLogin() {
        PasswordEncoder encoder = new AuthorizationServerConfig().passwordEncoder();
        KeltaUserDetails user = user(encoder);
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(username -> user(encoder) );
        provider.setPasswordEncoder(encoder);
        UsernamePasswordAuthenticationToken request =
                UsernamePasswordAuthenticationToken.unauthenticated(user.getUsername(), "password");
        request.setDetails(new WebAuthenticationDetails(browserRequest()));
        return new ProviderManager(provider).authenticate(request);
    }

    private static Authentication federatedPlatformToken() {
        KeltaUserDetails user = user(null);
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    private static Authentication oidcLogin() throws Exception {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(IdTokenClaimNames.ISS, URI.create("https://idp.example.test").toURL());
        claims.put(IdTokenClaimNames.SUB, "subject-1");
        claims.put(IdTokenClaimNames.AUD, List.of("client-1"));
        claims.put(IdTokenClaimNames.IAT, now);
        claims.put(IdTokenClaimNames.EXP, now.plusSeconds(300));
        claims.put("email", "user@example.test");
        OidcIdToken idToken = new OidcIdToken("id-token", now, now.plusSeconds(300), claims);
        OidcUserInfo userInfo = new OidcUserInfo(Map.of("sub", "subject-1", "email", "user@example.test"));
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new OidcUserAuthority(idToken, userInfo));
        authorities.add(new SimpleGrantedAuthority("SCOPE_openid"));
        authorities.add(FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY));
        DefaultOidcUser oidcUser = new DefaultOidcUser(authorities, idToken, userInfo);
        return new OAuth2AuthenticationToken(oidcUser, authorities, REGISTRATION_ID);
    }

    private static Authentication samlLogin() {
        Map<String, List<Object>> attributes = new LinkedHashMap<>();
        attributes.put("email", List.of("user@example.test"));
        DefaultSaml2AuthenticatedPrincipal principal =
                new DefaultSaml2AuthenticatedPrincipal("name-id", attributes, List.of("session-index"));
        principal.setRelyingPartyRegistrationId(REGISTRATION_ID);
        return new Saml2Authentication(principal, "<Response/>", List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.SAML_RESPONSE_AUTHORITY)));
    }

    private static DefaultSavedRequest savedRequest() {
        MockHttpServletRequest request = browserRequest();
        request.setRequestURI("/oauth2/authorize");
        request.setQueryString("response_type=code&client_id=kelta-platform");
        request.addParameter("response_type", "code");
        request.addParameter("client_id", "kelta-platform");
        request.addParameter("scope", "openid profile email");
        return new DefaultSavedRequest(request, "continue");
    }

    private static MockHttpServletRequest browserRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setScheme("https");
        request.setServerName("auth.example.test");
        request.setServerPort(443);
        request.setSession(new MockHttpSession());
        request.addHeader("Accept", "text/html");
        request.addHeader("User-Agent", "test-browser");
        request.addPreferredLocale(Locale.ENGLISH);
        request.setCookies(new Cookie("kelta_auth_session", "abc"));
        return request;
    }

    private static OAuth2AuthorizationRequest oauth2AuthorizationRequest() {
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId("client-1").clientSecret("secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "email", "profile")
                .authorizationUri("https://idp.example.test/authorize")
                .tokenUri("https://idp.example.test/token")
                .jwkSetUri("https://idp.example.test/jwks")
                .userInfoUri("https://idp.example.test/userinfo")
                .userNameAttributeName("sub")
                .build();
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                new InMemoryClientRegistrationRepository(registration), "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        MockHttpServletRequest request = browserRequest();
        request.setRequestURI("/oauth2/authorization/" + REGISTRATION_ID);
        request.setServletPath("/oauth2/authorization/" + REGISTRATION_ID);
        return resolver.resolve(request);
    }

    private static RelyingPartyRegistration samlRegistration() {
        return RelyingPartyRegistration.withRegistrationId(REGISTRATION_ID)
                .entityId("https://auth.example.test/saml2/metadata")
                .assertionConsumerServiceLocation("https://auth.example.test/login/saml2/sso/" + REGISTRATION_ID)
                .assertingPartyMetadata(party -> party
                        .entityId("https://idp.example.test")
                        .singleSignOnServiceLocation("https://idp.example.test/sso")
                        .singleLogoutServiceLocation("https://idp.example.test/slo"))
                .build();
    }

    private static Object flashMaps() {
        MockHttpServletRequest request = browserRequest();
        FlashMap flashMap = new FlashMap();
        flashMap.put("error", "Invalid verification code. Please try again.");
        flashMap.setTargetRequestPath("/mfa-challenge");
        flashMap.addTargetRequestParam("retry", "1");
        new SessionFlashMapManager().saveOutputFlashMap(flashMap, request, new MockHttpServletResponse());
        return request.getSession().getAttribute(SessionFlashMapManager.class.getName() + ".FLASH_MAPS");
    }
}
