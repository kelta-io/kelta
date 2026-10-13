package io.kelta.testharness.scenarios;

import io.kelta.testharness.KeltaStack;
import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.AuthFixture;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An administrator's password reset in kelta-worker signs the user out of kelta-auth, across the
 * real services: the user signs in to kelta-auth with the form (a signed-in session in Redis,
 * indexed by tenant + user id), the platform admin calls
 * {@code POST /{slug}/api/admin/users/{id}/reset-password} through the gateway, and the worker's
 * call to kelta-auth's internal endpoint ({@code KELTA_AUTH_INTERNAL_URL}, shared internal token)
 * ends that session.
 */
@DisplayName("Admin sign-out scenario — a password reset ends the user's kelta-auth sessions")
class AdminSignOutScenarioTest extends ScenarioBase {

    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*>");
    private static final Pattern VALUE_ATTR = Pattern.compile("value=\"([^\"]*)\"");
    private static final String SESSION_PREFIX = "kelta:auth:session:";

    @Test
    @DisplayName("admin reset-password → the user's signed-in kelta-auth session is deleted")
    void adminResetEndsTheSession() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);
        String email = "signout-" + UUID.randomUUID().toString().substring(0, 8) + "@harness.test";
        String userId;
        try (Connection db = openDbConnection()) {
            userId = seedUser(db, tenantId, email);
        }
        try {
            FormSession browser = new FormSession();
            String base = KeltaStack.authBaseUrl();
            HttpResponse<String> loginPage = browser.send(HttpRequest.newBuilder(
                    URI.create(base + "/login?tenant=" + TenantFixture.DEFAULT_SLUG)).GET().build());
            HttpResponse<String> signedIn = browser.send(browser.form(base + "/login", Map.of(
                    "username", email,
                    "password", AuthFixture.PROVISIONED_ADMIN_PASSWORD,
                    "_csrf", csrfToken(loginPage))));
            assertThat(signedIn.headers().firstValue("Location").orElse(""))
                    .as("signed in (status %s)", signedIn.statusCode())
                    .doesNotContain("error").doesNotContain("change-password");

            String sessionId = new String(java.util.Base64.getDecoder().decode(
                    browser.cookies.get("kelta_auth_session")), StandardCharsets.UTF_8);
            assertThat(redis("EXISTS", SESSION_PREFIX + "sessions:" + sessionId)).isEqualTo("1");
            String index = SESSION_PREFIX + "index:"
                    + "org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME:"
                    + tenantId + ":" + userId;
            assertThat(redis("SMEMBERS", index))
                    .as("the signed-in session is indexed under tenant + user id (sign-in went to %s; keys %s)",
                            signedIn.headers().firstValue("Location").orElse(""),
                            redis("--scan", "--pattern", SESSION_PREFIX + "index:*"))
                    .contains(sessionId);
            assertThat(signedInPage(browser, base)).as("signed in before the reset").isTrue();

            ResponseEntity<Map> reset = gatewayClientWithToken(adminToken).post()
                    .uri("/" + TenantFixture.DEFAULT_SLUG + "/api/admin/users/" + userId + "/reset-password")
                    .retrieve().toEntity(Map.class);
            assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);

            // The worker calls kelta-auth after its update commits, before answering.
            assertThat(redis("SMEMBERS", index)).as("indexed sessions after the admin reset")
                    .doesNotContain(sessionId);
            assertThat(signedInPage(browser, base)).as("signed in after the reset").isFalse();
        } finally {
            try (Connection db = openDbConnection()) {
                delete(db, "DELETE FROM login_history WHERE user_id = ?", userId);
                delete(db, "DELETE FROM user_credential WHERE user_id = ?", userId);
                delete(db, "DELETE FROM platform_user WHERE id = ?", userId);
            }
        }
    }

    /**
     * Whether the browser's session is signed in: a page that needs a signed-in user answers
     * without sending the browser to {@code /login}.
     */
    private static boolean signedInPage(FormSession browser, String base) {
        HttpResponse<String> page = browser.send(HttpRequest.newBuilder(
                URI.create(base + "/signed-in-probe")).header("Accept", "text/html").GET().build());
        boolean toLogin = page.statusCode() == 302
                && page.headers().firstValue("Location").orElse("").contains("/login");
        return !toLogin && page.statusCode() != 401;
    }

    private static String seedUser(Connection db, String tenantId, String email) throws Exception {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO platform_user (id, tenant_id, email, username, first_name, last_name, status,
                                           created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Harness', 'User', 'ACTIVE', NOW(), NOW())
                """)) {
            ps.setString(1, id);
            ps.setString(2, tenantId);
            ps.setString(3, email);
            ps.setString(4, email);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO user_credential (id, user_id, password_hash, force_change_on_login, created_at)
                VALUES (?, ?, ?, FALSE, NOW())
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, id);
            ps.setString(3, AuthFixture.PROVISIONED_ADMIN_PASSWORD_HASH);
            ps.executeUpdate();
        }
        return id;
    }

    private static void delete(Connection db, String sql, String id) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private static String redis(String... command) throws Exception {
        String[] full = new String[command.length + 1];
        full[0] = "redis-cli";
        System.arraycopy(command, 0, full, 1, command.length);
        return KeltaStack.REDIS.execInContainer(full).getStdout().trim();
    }

    private static String csrfToken(HttpResponse<String> page) {
        Matcher input = CSRF_INPUT.matcher(page.body());
        if (input.find()) {
            Matcher value = VALUE_ATTR.matcher(input.group());
            if (value.find()) {
                return value.group(1);
            }
        }
        throw new IllegalStateException("No _csrf field on " + page.uri() + " (status " + page.statusCode() + ")");
    }

    /** A cookie-carrying, non-redirecting client for one browser session (cookies by hand). */
    private static final class FormSession {
        private final HttpClient http = HttpClient.newHttpClient();
        private final Map<String, String> cookies = new LinkedHashMap<>();

        HttpRequest form(String url, Map<String, String> fields) {
            StringBuilder body = new StringBuilder();
            fields.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&")
                    .append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
            return HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
        }

        HttpResponse<String> send(HttpRequest request) {
            HttpRequest.Builder withCookies = HttpRequest.newBuilder(request, (name, value) -> true);
            if (!cookies.isEmpty()) {
                StringBuilder header = new StringBuilder();
                cookies.forEach((k, v) -> header.append(header.isEmpty() ? "" : "; ").append(k).append('=').append(v));
                withCookies.header("Cookie", header.toString());
            }
            try {
                HttpResponse<String> response = http.send(withCookies.build(), HttpResponse.BodyHandlers.ofString());
                for (String setCookie : response.headers().allValues("Set-Cookie")) {
                    String pair = setCookie.split(";", 2)[0];
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                    }
                }
                return response;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
