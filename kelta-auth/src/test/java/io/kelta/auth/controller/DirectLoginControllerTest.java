package io.kelta.auth.controller;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.model.KeltaUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DirectLoginController")
class DirectLoginControllerTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtEncoder jwtEncoder;

    @Mock
    private HttpSession session;

    @Mock
    private HttpServletRequest httpRequest;

    @Mock
    private Authentication authentication;

    private DirectLoginController controller;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.getDirectLogin().setEnabled(true);
        props.setIssuerUri("https://auth.kelta.io");
        controller = new DirectLoginController(authenticationManager, jwtEncoder, props);

        lenient().when(httpRequest.getRequestURI()).thenReturn("/auth/direct-login");
        lenient().when(httpRequest.getScheme()).thenReturn("https");
        lenient().when(httpRequest.getServerName()).thenReturn("auth.kelta.io");
        lenient().when(httpRequest.getServerPort()).thenReturn(443);
    }

    @Test
    @DisplayName("returns credentials_expired (not invalid_credentials) with a force-change URL when the password matched but is expired")
    void forceChangeAccountGetsDistinctError() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new CredentialsExpiredException("User credentials have expired"));

        ResponseEntity<?> response = controller.login(
                new DirectLoginController.DirectLoginRequest("sbx-admin", "correct-password", "sbx"),
                session, httpRequest);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "credentials_expired");
        assertThat(body).containsKey("force_change_url");
        assertThat((String) body.get("force_change_url")).contains("/change-password");
    }

    @Test
    @DisplayName("returns invalid_credentials for a plain bad password")
    void badPasswordStillGetsInvalidCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        ResponseEntity<?> response = controller.login(
                new DirectLoginController.DirectLoginRequest("sbx-admin", "wrong-password", "sbx"),
                session, httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "invalid_credentials");
    }

    @Test
    @DisplayName("returns a token on successful authentication")
    void successfulLoginReturnsTokens() {
        KeltaUserDetails userDetails = new KeltaUserDetails(
                "user-1", "sbx-admin@kelta.local", "tenant-1", "profile-1", "Admin",
                "Admin", "{bcrypt}hash", true, false, false);
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(userDetails);
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claim("sub", "user-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(jwt);

        ResponseEntity<?> response = controller.login(
                new DirectLoginController.DirectLoginRequest("sbx-admin", "correct-password", "sbx"),
                session, httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("access_token", "token-value");
    }
}
