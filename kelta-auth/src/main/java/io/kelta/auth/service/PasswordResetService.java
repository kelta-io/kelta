package io.kelta.auth.service;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.session.UserSessionRevoker;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Email-link password reset for a signed-out user: the {@code /forgot-password} and
 * {@code /reset-password} pages and the {@code /auth/password/reset-request} and
 * {@code /auth/password/reset} JSON endpoints all go through here.
 *
 * <p><b>Request.</b> Every active account with the email address gets its own token — the same
 * address can belong to users in several tenants — stored on that user's credential only and mailed with that tenant's name. The caller always answers the same way, so
 * the response does not tell whether the address has an account.
 *
 * <p><b>The link's host.</b> The emailed link opens {@code /reset-password} on the host the
 * request came to, but only when that host is known to serve sign-in for the account: a platform
 * host ({@code localhost}, {@code *.localhost}, {@code kelta.io}, {@code *.kelta.io}), the host of
 * the configured issuer or UI base URL, or a custom domain of the account's own tenant. A request
 * with any other {@code Host} would otherwise mail the user a working reset link to a site of the
 * requester's choosing. With no trusted host the configured issuer is used; without one, no email
 * is sent for that account.
 *
 * <p><b>Reset.</b> The new password meets the account's tenant password policy and history
 * ({@link PasswordPolicyService}), as for a signed-in change; a refused password leaves the token
 * usable. A token resets exactly one user, once, within an hour; the user's other sessions and
 * refresh tokens end.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    static final Duration TOKEN_LIFETIME = Duration.ofHours(1);

    private static final String INVALID_TOKEN = "Invalid or expired reset token";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService policyService;
    private final UserSessionRevoker sessionRevoker;
    private final WorkerClient workerClient;
    private final AuthDomainResolver domainResolver;
    private final String issuerUri;
    private final List<String> configuredHosts;
    private final Executor mailExecutor;

    @Autowired
    public PasswordResetService(JdbcTemplate jdbcTemplate,
                                PasswordEncoder passwordEncoder,
                                PasswordPolicyService policyService,
                                UserSessionRevoker sessionRevoker,
                                WorkerClient workerClient,
                                AuthDomainResolver domainResolver,
                                AuthProperties authProperties) {
        this(jdbcTemplate, passwordEncoder, policyService, sessionRevoker, workerClient, domainResolver,
                authProperties, task -> Thread.ofVirtual().name("password-reset-mail").start(task));
    }

    /** {@code mailExecutor} sends the reset emails ({@code Runnable::run} in tests). */
    public PasswordResetService(JdbcTemplate jdbcTemplate,
                                PasswordEncoder passwordEncoder,
                                PasswordPolicyService policyService,
                                UserSessionRevoker sessionRevoker,
                                WorkerClient workerClient,
                                AuthDomainResolver domainResolver,
                                AuthProperties authProperties,
                                Executor mailExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.policyService = policyService;
        this.sessionRevoker = sessionRevoker;
        this.workerClient = workerClient;
        this.domainResolver = domainResolver;
        this.issuerUri = blankToNull(authProperties.getIssuerUri());
        this.configuredHosts = Stream.of(issuerUri, blankToNull(authProperties.getUiBaseUrl()))
                .map(PasswordResetService::hostOf)
                .filter(Objects::nonNull)
                .toList();
        this.mailExecutor = mailExecutor;
    }

    /** The outcome of a reset. */
    public sealed interface ResetResult {
        /** The password was set and the token is spent; the account's tenant. */
        record Done(String tenantSlug) implements ResetResult {}
        /** No live token: unknown, expired or already used. */
        record InvalidToken() implements ResetResult {
            public String message() {
                return INVALID_TOKEN;
            }
        }
        /** The password does not meet the tenant's policy or was used recently; the token stays usable. */
        record Rejected(String message) implements ResetResult {}
    }

    /**
     * Issues and mails a reset link to each active account with {@code email}. Returns nothing,
     * whatever was found: the caller's answer must not depend on it.
     */
    public void requestReset(String email, HttpServletRequest request) {
        if (email == null || email.isBlank()) {
            return;
        }
        List<Map<String, Object>> accounts = jdbcTemplate.queryForList(
                "SELECT pu.id, pu.tenant_id, pu.first_name, t.name AS tenant_name, t.slug AS tenant_slug "
                        + "FROM platform_user pu JOIN user_credential uc ON uc.user_id = pu.id "
                        + "JOIN tenant t ON t.id = pu.tenant_id "
                        + "WHERE pu.email = ? AND pu.status = 'ACTIVE' AND pu.user_type <> 'SERVICE'",
                email.trim());

        // Resolved whether or not an account matched, so a custom-domain lookup does not make a
        // known address answer slower than an unknown one.
        RequestHost host = RequestHost.of(request, this);
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(TOKEN_LIFETIME));
        for (Map<String, Object> account : accounts) {
            String userId = (String) account.get("id");
            Optional<String> base = linkBase(request, host, (String) account.get("tenant_slug"));
            if (base.isEmpty()) {
                log.warn("Password reset for user {} not sent: host '{}' is not a sign-in host for its tenant "
                        + "and no issuer is configured", userId, request.getServerName());
                continue;
            }
            // A new token replaces any earlier one for this user.
            String token = UUID.randomUUID().toString();
            int updated = jdbcTemplate.update(
                    "UPDATE user_credential SET reset_token = ?, reset_token_expires_at = ?, updated_at = ? "
                            + "WHERE user_id = ?",
                    token, expiresAt, now(), userId);
            if (updated == 1) {
                log.info("Password reset requested for user {} (tenant {})", userId, account.get("tenant_id"));
                // Off the request thread: the worker call would otherwise make a known address
                // answer measurably slower than an unknown one.
                String actionUrl = base.get() + "/reset-password?token=" + token;
                mailExecutor.execute(() -> sendResetEmail(email.trim(), account, actionUrl));
            }
        }
    }

    /**
     * A live token's account, as the reset page shows it: {@code firstPassword} when the account
     * has no password yet (an invite — the worker's {@code UserInviteService} writes the same
     * column).
     */
    public record TokenInfo(boolean firstPassword) {}

    /** {@code token}'s account when it is a live reset token (the reset page checks before showing its form). */
    public Optional<TokenInfo> describe(String token) {
        return findToken(token).map(row -> new TokenInfo(
                !(row.get("password_hash") instanceof String hash) || hash.isBlank()));
    }

    /**
     * Sets the password of the one user {@code token} belongs to, then clears the token.
     * {@code currentSessionId} is the session to keep signed in, if any.
     */
    public ResetResult reset(String token, String newPassword, String currentSessionId) {
        Optional<Map<String, Object>> found = findToken(token);
        if (found.isEmpty()) {
            return new ResetResult.InvalidToken();
        }
        Map<String, Object> row = found.get();
        String userId = (String) row.get("user_id");
        String tenantId = (String) row.get("tenant_id");

        String firstName = row.get("first_name") instanceof String value ? value : "";
        String lastName = row.get("last_name") instanceof String value ? value : "";
        List<String> violations = policyService.validatePassword(
                newPassword, (String) row.get("email"), (firstName + " " + lastName).trim(), tenantId);
        if (!violations.isEmpty()) {
            return new ResetResult.Rejected(String.join("; ", violations));
        }
        Optional<String> historyViolation = policyService.checkHistory(userId, newPassword, tenantId);
        if (historyViolation.isPresent()) {
            return new ResetResult.Rejected(historyViolation.get());
        }

        // On that one user, and only while the token is still theirs: a token is used once.
        String newHash = passwordEncoder.encode(newPassword);
        int updated = jdbcTemplate.update(
                "UPDATE user_credential SET password_hash = ?, password_changed_at = ?, "
                        + "reset_token = NULL, reset_token_expires_at = NULL, "
                        + "force_change_on_login = false, failed_attempts = 0, locked_until = NULL, "
                        + "updated_at = ? WHERE user_id = ? AND reset_token = ?",
                newHash, now(), now(), userId, token);
        if (updated != 1) {
            return new ResetResult.InvalidToken();
        }
        policyService.saveToHistory(userId, newHash, tenantId);

        log.info("Password reset completed for user {} (tenant {})", userId, tenantId);
        sessionRevoker.signOutElsewhere(userId, currentSessionId);
        sendPasswordChangedEmail(userId);
        return new ResetResult.Done((String) row.get("tenant_slug"));
    }

    /** The credential row of a live token: exactly one match, not expired. */
    private Optional<Map<String, Object>> findToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT uc.user_id, uc.reset_token_expires_at, uc.password_hash, pu.tenant_id, pu.email, pu.first_name, "
                        + "pu.last_name, "
                        + "t.slug AS tenant_slug "
                        + "FROM user_credential uc JOIN platform_user pu ON pu.id = uc.user_id "
                        + "JOIN tenant t ON t.id = pu.tenant_id "
                        + "WHERE uc.reset_token = ?",
                token);
        if (rows.size() != 1) {
            if (rows.size() > 1) {
                log.warn("Reset token matches {} credentials — treating as invalid", rows.size());
            }
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        // A token row without an expiry is an invariant violation; treat it as invalid.
        if (!(row.get("reset_token_expires_at") instanceof Timestamp expiresAt)
                || Instant.now().isAfter(expiresAt.toInstant())) {
            return Optional.empty();
        }
        return Optional.of(row);
    }

    /**
     * The request's host: a sign-in host for every tenant ({@code platform}), else the tenant whose
     * custom domain it is, if any.
     */
    record RequestHost(boolean platform, Optional<String> tenantSlug) {

        static RequestHost of(HttpServletRequest request, PasswordResetService service) {
            String host = AuthDomainResolver.sanitize(request.getServerName() == null ? "" : request.getServerName());
            if (host == null) {
                return new RequestHost(false, Optional.empty());
            }
            if (AuthDomainResolver.isReserved(host) || host.endsWith(".localhost")
                    || service.configuredHosts.contains(host)) {
                return new RequestHost(true, Optional.empty());
            }
            return new RequestHost(false, service.domainResolver.resolveTenantSlug(host));
        }

        boolean servesTenant(String slug) {
            return platform || (slug != null && tenantSlug.filter(slug::equals).isPresent());
        }
    }

    /**
     * Scheme, host and port the link opens on: this request's, when its host serves sign-in for
     * {@code tenantSlug}; else the configured issuer; else none.
     */
    static Optional<String> linkBase(HttpServletRequest request, RequestHost host, String tenantSlug,
                                     String issuerUri) {
        if (host.servesTenant(tenantSlug)) {
            return Optional.of(ServletUriComponentsBuilder.fromContextPath(request)
                    .replaceQuery(null).build().toUriString());
        }
        return Optional.ofNullable(issuerUri).map(uri -> uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri);
    }

    private Optional<String> linkBase(HttpServletRequest request, RequestHost host, String tenantSlug) {
        return linkBase(request, host, tenantSlug, issuerUri);
    }

    private void sendResetEmail(String email, Map<String, Object> account, String actionUrl) {
        try {
            String tenantName = account.get("tenant_name") instanceof String value ? value : "Kelta";
            String firstName = account.get("first_name") instanceof String value ? value : "";
            workerClient.sendTemplateEmail((String) account.get("tenant_id"), email, "user.password_reset",
                    Map.of(
                            "tenantName", tenantName,
                            "firstName", firstName,
                            "actionUrl", actionUrl,
                            "expiresIn", "1 hour"),
                    "PASSWORD_RESET", (String) account.get("id"));
            log.info("Password reset email queued for user {}", account.get("id"));
        } catch (Exception e) {
            log.error("Failed to send password reset email for user {}: {}", account.get("id"), e.getMessage());
        }
    }

    /** Tells the user their password changed. */
    public void sendPasswordChangedEmail(String userId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT pu.email, pu.first_name, pu.tenant_id, t.name AS tenant_name "
                            + "FROM platform_user pu JOIN tenant t ON t.id = pu.tenant_id "
                            + "WHERE pu.id = ?",
                    userId);
            if (rows.isEmpty()) {
                return;
            }
            Map<String, Object> row = rows.getFirst();
            workerClient.sendTemplateEmail(
                    (String) row.get("tenant_id"),
                    (String) row.get("email"),
                    "user.password_changed",
                    Map.of(
                            "tenantName", row.get("tenant_name") instanceof String value ? value : "Kelta",
                            "firstName", row.get("first_name") instanceof String value ? value : "",
                            "changedAt", Instant.now().toString(),
                            "ipAddress", "unknown"),
                    "PASSWORD_CHANGED", userId);
        } catch (Exception e) {
            log.warn("Failed to send password-changed notification: {}", e.getMessage());
        }
    }

    /**
     * The current time as a JDBC timestamp. The PostgreSQL driver cannot bind a
     * {@code java.time.Instant} ("Can't infer the SQL type").
     */
    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : AuthDomainResolver.sanitize(host);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
