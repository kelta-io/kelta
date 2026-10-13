package io.kelta.auth.service;

import io.kelta.auth.model.KeltaUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationEventListener Tests")
class AuthenticationEventListenerTest {

    @Mock private PasswordPolicyService policyService;
    private static final String TENANT_A = "aaaaaaaa-0000-0000-0000-000000000001";

    @Mock private JdbcTemplate jdbcTemplate;
    private AuthenticationEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new AuthenticationEventListener(policyService, jdbcTemplate);
    }

    @Nested
    @DisplayName("onSuccess")
    class OnSuccess {
        @Test
        void shouldResetFailedAttemptsAndCheckExpiration() {
            KeltaUserDetails userDetails = new KeltaUserDetails(
                    "user-1", "user@test.com", "tenant-1", "profile-1",
                    "Standard User", "Test User", "hash", true, false, false);
            Authentication auth = new UsernamePasswordAuthenticationToken(userDetails, null);
            AuthenticationSuccessEvent event = new AuthenticationSuccessEvent(auth);

            listener.onSuccess(event);

            verify(policyService).resetFailedAttempts("user-1");
            verify(policyService).checkPasswordExpiration("user-1", "tenant-1");
        }

        @Test
        void shouldDoNothingForNonKeltaPrincipal() {
            Authentication auth = new UsernamePasswordAuthenticationToken("plain-user", "password");
            AuthenticationSuccessEvent event = new AuthenticationSuccessEvent(auth);

            listener.onSuccess(event);

            verifyNoInteractions(policyService);
        }
    }

    @Nested
    @DisplayName("onFailure")
    class OnFailure {

        private static final String LOOKUP = "FROM platform_user";

        @AfterEach
        void clearRequest() {
            RequestContextHolder.resetRequestAttributes();
        }

        @Test
        void shouldIncrementFailedAttemptsForUserOfTheSignInTenant() {
            signInTenant(TENANT_A);
            when(jdbcTemplate.queryForList(contains(LOOKUP), eq(String.class),
                    eq("user@test.com"), eq("user@test.com"), eq(TENANT_A)))
                    .thenReturn(List.of("user-a"));

            listener.onFailure(wrongPassword("user@test.com"));

            verify(policyService).incrementFailedAttempts("user-a", TENANT_A);
        }

        @Test
        void shouldScopeTheLookupToTheSignInTenant() {
            signInTenant(TENANT_A);
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
                    .thenReturn(List.of("user-a"));

            listener.onFailure(wrongPassword("user@test.com"));

            verify(jdbcTemplate).queryForList(contains("pu.tenant_id = ?"), eq(String.class),
                    eq("user@test.com"), eq("user@test.com"), eq(TENANT_A));
        }

        @Test
        void shouldResolveASlugInTheSessionToItsTenant() {
            signInTenant("tenant-a");
            when(jdbcTemplate.queryForList(contains("FROM tenant WHERE slug"), eq(String.class), eq("tenant-a")))
                    .thenReturn(List.of(TENANT_A));
            when(jdbcTemplate.queryForList(contains(LOOKUP), eq(String.class),
                    eq("user@test.com"), eq("user@test.com"), eq(TENANT_A)))
                    .thenReturn(List.of("user-a"));

            listener.onFailure(wrongPassword("user@test.com"));

            verify(policyService).incrementFailedAttempts("user-a", TENANT_A);
        }

        @Test
        void shouldNotCountWhenNoTenantIsKnown() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getSession(false)).thenReturn(null);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            listener.onFailure(wrongPassword("user@test.com"));

            verifyNoInteractions(policyService, jdbcTemplate);
        }

        @Test
        void shouldNotCountWhenThereIsNoRequest() {
            listener.onFailure(wrongPassword("user@test.com"));

            verifyNoInteractions(policyService, jdbcTemplate);
        }

        @Test
        void shouldNotCountWhenNoUserWasLoaded() {
            // What DaoAuthenticationProvider publishes when loadUserByUsername refused — unknown
            // user, inactive account, or no tenant context.
            Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "password");
            AuthenticationFailureBadCredentialsEvent event = new AuthenticationFailureBadCredentialsEvent(auth,
                    new BadCredentialsException("Bad credentials",
                            new UsernameNotFoundException("Tenant context is required to authenticate.")));

            listener.onFailure(event);

            verifyNoInteractions(policyService, jdbcTemplate);
        }

        @Test
        void shouldDoNothingForUnknownUser() {
            signInTenant(TENANT_A);
            when(jdbcTemplate.queryForList(contains(LOOKUP), eq(String.class), any(Object[].class)))
                    .thenReturn(List.of());

            listener.onFailure(wrongPassword("unknown@test.com"));

            verifyNoInteractions(policyService);
        }

        @Test
        void shouldNotGuessWhenTheNameMatchesSeveralUsers() {
            signInTenant(TENANT_A);
            when(jdbcTemplate.queryForList(contains(LOOKUP), eq(String.class), any(Object[].class)))
                    .thenReturn(List.of("user-a", "user-a2"));

            listener.onFailure(wrongPassword("shared"));

            verifyNoInteractions(policyService);
        }

        private AuthenticationFailureBadCredentialsEvent wrongPassword(String username) {
            Authentication auth = new UsernamePasswordAuthenticationToken(username, "bad-password");
            return new AuthenticationFailureBadCredentialsEvent(auth, new BadCredentialsException("Bad credentials"));
        }

        private void signInTenant(String sessionTenant) {
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpSession session = mock(HttpSession.class);
            when(request.getSession(false)).thenReturn(session);
            when(session.getAttribute("tenantId")).thenReturn(sessionTenant);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        }
    }
}
