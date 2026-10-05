package io.kelta.auth.controller;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.config.AuthorizationServerConfig;
import io.kelta.auth.model.KeltaUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Direct login as a freshly provisioned tenant's seeded admin ({@code <slug>-admin@kelta.local},
 * created without {@code adminEmail}) must fail. The worker's {@code TenantProvisioningHook}
 * seeds that admin with an empty password hash and {@code force_change_on_login = true}; this
 * runs it through the real password encoder ({@link AuthorizationServerConfig#passwordEncoder()},
 * which still accepts prefix-less bcrypt hashes) and the real {@link DaoAuthenticationProvider}.
 */
@DisplayName("Direct login as a freshly provisioned tenant admin")
class ProvisionedAdminLoginTest {

    private static final String SLUG = "fresh-tenant";
    private static final String ADMIN_EMAIL = SLUG + "-admin@kelta.local";
    /** What TenantProvisioningHook seeded before PLT-337: bare BCrypt of "password". */
    private static final String OLD_DEFAULT_HASH = "$2a$10$zAQaSHX1XSR1bwUL3pz9EOzecplsxInVizZc9HwLf7xPluSiE1EP6";

    private final PasswordEncoder passwordEncoder = new AuthorizationServerConfig().passwordEncoder();
    private final JwtEncoder jwtEncoder = mock(JwtEncoder.class);
    private HttpSession session;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        session = mock(HttpSession.class);
        httpRequest = mock(HttpServletRequest.class);
        lenient().when(httpRequest.getRequestURI()).thenReturn("/auth/direct-login");
        lenient().when(httpRequest.getScheme()).thenReturn("https");
        lenient().when(httpRequest.getServerName()).thenReturn("auth.kelta.io");
        lenient().when(httpRequest.getServerPort()).thenReturn(443);
    }

    private DirectLoginController controllerFor(String storedHash) {
        UserDetailsService users = username -> {
            if (!ADMIN_EMAIL.equals(username) && !(SLUG + "-admin").equals(username)) {
                throw new UsernameNotFoundException(username);
            }
            return new KeltaUserDetails("admin-id", ADMIN_EMAIL, "tenant-id", "profile-id",
                    "System Administrator", "System Administrator", storedHash, true, false, true);
        };
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        AuthProperties props = new AuthProperties();
        props.getDirectLogin().setEnabled(true);
        props.setIssuerUri("https://auth.kelta.io");
        return new DirectLoginController(new ProviderManager(provider), jwtEncoder, props);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> login(DirectLoginController controller, String password, int expectedStatus) {
        ResponseEntity<?> response = controller.login(
                new DirectLoginController.DirectLoginRequest(ADMIN_EMAIL, password, SLUG), session, httpRequest);
        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        return (Map<String, Object>) response.getBody();
    }

    @Test
    @DisplayName("fails with invalid_credentials for the old well-known password and any other guess")
    void seededCredentialRejectsEveryPassword() {
        DirectLoginController controller = controllerFor("");

        for (String guess : new String[] {"password", "", " ", "admin", SLUG}) {
            assertThat(login(controller, guess, 401)).containsEntry("error", "invalid_credentials");
        }
        verifyNoInteractions(jwtEncoder);
    }

    @Test
    @DisplayName("the encoder rejects the empty hash without throwing")
    void encoderRejectsEmptyHashQuietly() {
        assertThatCode(() -> passwordEncoder.matches("password", "")).doesNotThrowAnyException();
        assertThat(passwordEncoder.matches("password", "")).isFalse();
        assertThat(passwordEncoder.matches("", "")).isFalse();
    }

    @Test
    @DisplayName("contrast: the old default hash matched \"password\" — only force-change stood in the way")
    void oldDefaultHashMatched() {
        assertThat(passwordEncoder.matches("password", OLD_DEFAULT_HASH)).isTrue();
        assertThat(login(controllerFor(OLD_DEFAULT_HASH), "password", 403))
                .containsEntry("error", "credentials_expired");
    }
}
