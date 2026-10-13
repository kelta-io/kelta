package io.kelta.auth.service;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.session.UserSessionRevoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetService")
class PasswordResetServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PasswordPolicyService policyService;
    @Mock private UserSessionRevoker sessionRevoker;
    @Mock private WorkerClient workerClient;
    @Mock private AuthDomainResolver domainResolver;

    private final AuthProperties properties = new AuthProperties();

    @BeforeEach
    void setUp() {
        properties.setUiBaseUrl("https://app.kelta.io");
    }

    private PasswordResetService service() {
        return new PasswordResetService(jdbcTemplate, passwordEncoder, policyService, sessionRevoker, workerClient,
                domainResolver, properties, Runnable::run);
    }

    private static MockHttpServletRequest requestTo(String scheme, String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/password/reset-request");
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort(port);
        return request;
    }

    private static Map<String, Object> account(String userId, String tenantId, String slug) {
        return Map.of("id", userId, "tenant_id", tenantId, "first_name", "A", "tenant_name", "Tenant " + slug,
                "tenant_slug", slug);
    }

    private void accounts(String email, Map<String, Object>... rows) {
        when(jdbcTemplate.queryForList(contains("FROM platform_user"), eq(email))).thenReturn(List.of(rows));
        lenient().when(jdbcTemplate.update(contains("reset_token = ?"), anyString(), any(Timestamp.class),
                any(Timestamp.class), anyString())).thenReturn(1);
    }

    /** The link mailed to {@code userId}. */
    @SuppressWarnings("unchecked")
    private String mailedLink(String userId) {
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(workerClient).sendTemplateEmail(anyString(), anyString(), eq("user.password_reset"), vars.capture(),
                eq("PASSWORD_RESET"), eq(userId));
        return (String) vars.getValue().get("actionUrl");
    }

    @Nested
    @DisplayName("requestReset")
    class RequestReset {

        @Test
        @DisplayName("an unknown address: no token, no email")
        void unknownAddress() {
            accounts("nobody@test.com");

            service().requestReset("nobody@test.com", requestTo("https", "auth.kelta.io", 443));

            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
            verifyNoInteractions(workerClient);
        }

        @Test
        @DisplayName("the same address in two tenants: one token per user, each stored on that user only")
        void oneTokenPerAccount() {
            accounts("shared@test.com", account("user-a", "tenant-a", "a"), account("user-b", "tenant-b", "b"));

            service().requestReset("shared@test.com", requestTo("https", "auth.kelta.io", 443));

            ArgumentCaptor<Object> tokens = ArgumentCaptor.forClass(Object.class);
            verify(jdbcTemplate).update(contains("WHERE user_id = ?"), tokens.capture(), any(Timestamp.class),
                    any(Timestamp.class), eq("user-a"));
            verify(jdbcTemplate).update(contains("WHERE user_id = ?"), tokens.capture(), any(Timestamp.class),
                    any(Timestamp.class), eq("user-b"));
            assertThat(tokens.getAllValues()).doesNotHaveDuplicates();
            assertThat(mailedLink("user-a")).isEqualTo("https://auth.kelta.io/reset-password?token=" + tokens.getAllValues().get(0));
            assertThat(mailedLink("user-b")).isEqualTo("https://auth.kelta.io/reset-password?token=" + tokens.getAllValues().get(1));
        }

        @Test
        @DisplayName("the link opens on the request's own host when it is a platform host, port included")
        void platformHost() {
            accounts("a@test.com", account("user-a", "tenant-a", "a"));

            service().requestReset("a@test.com", requestTo("http", "auth.localhost", 8081));

            assertThat(mailedLink("user-a")).startsWith("http://auth.localhost:8081/reset-password?token=");
        }

        @Test
        @DisplayName("a custom domain of the account's tenant is a link host")
        void ownCustomDomain() {
            accounts("a@test.com", account("user-a", "tenant-a", "acme"));
            when(domainResolver.resolveTenantSlug("login.acme.example")).thenReturn(Optional.of("acme"));

            service().requestReset("a@test.com", requestTo("https", "login.acme.example", 443));

            assertThat(mailedLink("user-a")).startsWith("https://login.acme.example/reset-password?token=");
        }

        @Test
        @DisplayName("an unknown host (forged Host header) never gets a link; nothing is issued without an issuer")
        void forgedHost() {
            accounts("a@test.com", account("user-a", "tenant-a", "acme"));
            when(domainResolver.resolveTenantSlug("attacker.example")).thenReturn(Optional.empty());

            service().requestReset("a@test.com", requestTo("https", "attacker.example", 443));

            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
            verifyNoInteractions(workerClient);
        }

        @Test
        @DisplayName("another tenant's custom domain is not a link host for this account")
        void otherTenantsDomain() {
            accounts("a@test.com", account("user-a", "tenant-a", "acme"));
            when(domainResolver.resolveTenantSlug("login.other.example")).thenReturn(Optional.of("other"));

            service().requestReset("a@test.com", requestTo("https", "login.other.example", 443));

            verifyNoInteractions(workerClient);
        }

        @Test
        @DisplayName("an untrusted host falls back to the configured issuer")
        void issuerFallback() {
            properties.setIssuerUri("https://auth.example.org/");
            accounts("a@test.com", account("user-a", "tenant-a", "acme"));
            when(domainResolver.resolveTenantSlug("attacker.example")).thenReturn(Optional.empty());

            service().requestReset("a@test.com", requestTo("https", "attacker.example", 443));

            assertThat(mailedLink("user-a")).startsWith("https://auth.example.org/reset-password?token=");
        }

        @Test
        @DisplayName("the custom-domain lookup runs whether or not the address has an account")
        void lookupDoesNotDependOnTheAddress() {
            accounts("nobody@test.com");
            when(domainResolver.resolveTenantSlug("login.acme.example")).thenReturn(Optional.of("acme"));

            service().requestReset("nobody@test.com", requestTo("https", "login.acme.example", 443));

            verify(domainResolver).resolveTenantSlug("login.acme.example");
        }

        @Test
        @DisplayName("the email is sent through the mail executor, not on the request thread")
        void mailGoesThroughTheExecutor() {
            accounts("a@test.com", account("user-a", "tenant-a", "a"));
            List<Runnable> queued = new java.util.ArrayList<>();
            PasswordResetService service = new PasswordResetService(jdbcTemplate, passwordEncoder, policyService,
                    sessionRevoker, workerClient, domainResolver, properties, queued::add);

            service.requestReset("a@test.com", requestTo("https", "auth.kelta.io", 443));

            verifyNoInteractions(workerClient);
            assertThat(queued).hasSize(1);
            queued.getFirst().run();
            verify(workerClient).sendTemplateEmail(eq("tenant-a"), eq("a@test.com"), eq("user.password_reset"),
                    anyMap(), eq("PASSWORD_RESET"), eq("user-a"));
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset {

        private Map<String, Object> tokenRow(Instant expiresAt) {
            Map<String, Object> row = new HashMap<>();
            row.put("user_id", "user-1");
            row.put("tenant_id", "tenant-1");
            row.put("tenant_slug", "acme");
            row.put("email", "user@test.com");
            row.put("first_name", "Test");
            row.put("last_name", "User");
            row.put("reset_token_expires_at", expiresAt == null ? null : Timestamp.from(expiresAt));
            return row;
        }

        private void token(String token, Map<String, Object>... rows) {
            when(jdbcTemplate.queryForList(contains("WHERE uc.reset_token = ?"), eq(token))).thenReturn(List.of(rows));
        }

        private void policyAccepts() {
            when(policyService.validatePassword(anyString(), anyString(), anyString(), anyString())).thenReturn(List.of());
            when(policyService.checkHistory(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("describe: a live token of an account with no password yet is a first password")
        void describesAFirstPassword() {
            Map<String, Object> invited = tokenRow(Instant.now().plusSeconds(600));
            invited.put("password_hash", "");
            Map<String, Object> existing = tokenRow(Instant.now().plusSeconds(600));
            existing.put("password_hash", "$2a$hash");
            token("invite", invited);
            token("reset", existing);
            token("gone");

            assertThat(service().describe("invite")).contains(new PasswordResetService.TokenInfo(true));
            assertThat(service().describe("reset")).contains(new PasswordResetService.TokenInfo(false));
            assertThat(service().describe("gone")).isEmpty();
        }

        @Test
        @DisplayName("an unknown token is invalid")
        void unknownToken() {
            token("bad-token");

            assertThat(service().reset("bad-token", "a-new-pass-phrase", null))
                    .isInstanceOf(PasswordResetService.ResetResult.InvalidToken.class);
        }

        @Test
        @DisplayName("an expired token is invalid")
        void expiredToken() {
            token("expired", tokenRow(Instant.now().minusSeconds(60)));

            assertThat(service().reset("expired", "a-new-pass-phrase", null))
                    .isInstanceOf(PasswordResetService.ResetResult.InvalidToken.class);
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("a token row with no expiry is invalid (no NPE)")
        void nullExpiry() {
            token("null-expiry", tokenRow(null));

            assertThat(service().reset("null-expiry", "a-new-pass-phrase", null))
                    .isInstanceOf(PasswordResetService.ResetResult.InvalidToken.class);
        }

        @Test
        @DisplayName("a token matching more than one credential resets nobody")
        void ambiguousToken() {
            token("dup", tokenRow(Instant.now().plusSeconds(600)), tokenRow(Instant.now().plusSeconds(600)));

            assertThat(service().reset("dup", "a-new-pass-phrase", null))
                    .isInstanceOf(PasswordResetService.ResetResult.InvalidToken.class);
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
            verifyNoInteractions(sessionRevoker);
        }

        @Test
        @DisplayName("a password the tenant's policy refuses is not set and the token stays usable")
        void policyViolation() {
            token("live", tokenRow(Instant.now().plusSeconds(600)));
            when(policyService.validatePassword("password", "user@test.com", "Test User", "tenant-1"))
                    .thenReturn(List.of("Password is too common"));

            var result = service().reset("live", "password", null);

            assertThat(result).isEqualTo(new PasswordResetService.ResetResult.Rejected("Password is too common"));
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
            verifyNoInteractions(sessionRevoker);
        }

        @Test
        @DisplayName("a recently used password is not set")
        void historyViolation() {
            token("live", tokenRow(Instant.now().plusSeconds(600)));
            when(policyService.validatePassword(anyString(), anyString(), anyString(), anyString())).thenReturn(List.of());
            when(policyService.checkHistory("user-1", "an-old-pass-phrase", "tenant-1"))
                    .thenReturn(Optional.of("Password was recently used"));

            assertThat(service().reset("live", "an-old-pass-phrase", null))
                    .isEqualTo(new PasswordResetService.ResetResult.Rejected("Password was recently used"));
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("a live token sets that one user's password once, records history and signs them out elsewhere")
        void resets() {
            token("live", tokenRow(Instant.now().plusSeconds(600)));
            policyAccepts();
            when(passwordEncoder.encode("a-new-pass-phrase")).thenReturn("$2a$newhash");
            when(jdbcTemplate.update(contains("WHERE user_id = ? AND reset_token = ?"), eq("$2a$newhash"),
                    any(Timestamp.class), any(Timestamp.class), eq("user-1"), eq("live"))).thenReturn(1);

            var result = service().reset("live", "a-new-pass-phrase", "current-session");

            assertThat(result).isEqualTo(new PasswordResetService.ResetResult.Done("acme"));
            verify(policyService).saveToHistory("user-1", "$2a$newhash", "tenant-1");
            verify(sessionRevoker).signOutElsewhere("user-1", "current-session");
        }

        @Test
        @DisplayName("a token used meanwhile (no row updated) is invalid; no history, nobody signed out")
        void consumedMeanwhile() {
            token("used", tokenRow(Instant.now().plusSeconds(600)));
            policyAccepts();
            when(passwordEncoder.encode("a-new-pass-phrase")).thenReturn("$2a$newhash");

            assertThat(service().reset("used", "a-new-pass-phrase", null))
                    .isInstanceOf(PasswordResetService.ResetResult.InvalidToken.class);
            verify(policyService, never()).saveToHistory(any(), any(), any());
            verifyNoInteractions(sessionRevoker);
        }
    }
}
