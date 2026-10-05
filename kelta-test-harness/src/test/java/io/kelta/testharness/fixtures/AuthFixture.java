package io.kelta.testharness.fixtures;

import io.kelta.testharness.KeltaStack;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mints JWTs against the in-harness kelta-auth instance via the direct-login endpoint.
 *
 * <p>The {@code default} tenant's admin is the Flyway baseline's platform admin
 * ({@code admin@kelta.local}). kelta-auth replaces its seeded credential on first boot
 * ({@code BaselineAdminPasswordInitializer}): the harness starts kelta-auth with
 * {@code KELTA_BOOTSTRAP_ADMIN_PASSWORD} = {@link #BOOTSTRAP_ADMIN_PASSWORD}, which carries a forced
 * change, and the first {@link #loginAsAdmin()} completes that change once through the real
 * sign-in form, to {@link #PLATFORM_ADMIN_PASSWORD} ({@link #ensurePlatformAdminPassword()}).
 *
 * <p>Every other tenant is provisioned at runtime by the worker's {@code TenantProvisioningHook},
 * which seeds its admin as {@code <slug>-admin@kelta.local} (username {@code <slug>-admin}) with
 * <em>no usable password</em> — a real tenant claims it by invite. A fixture that wants to log in
 * as such an admin first calls {@link #setProvisionedAdminCredential(String)}, which writes the
 * harness-only {@link #PROVISIONED_ADMIN_PASSWORD} straight into the database.
 * {@link #loginAsAdmin(String)} picks the right identity and password per slug.
 */
public final class AuthFixture {

    /**
     * Harness-only {@code KELTA_BOOTSTRAP_ADMIN_PASSWORD}: the initial platform-admin password
     * kelta-auth applies on first boot, with a forced change. Never a platform default.
     */
    public static final String BOOTSTRAP_ADMIN_PASSWORD = "harness-bootstrap-admin-secret";

    /** Harness-only platform-admin password the fixture chooses when completing the forced change. */
    public static final String PLATFORM_ADMIN_PASSWORD = "harness-platform-admin-secret";

    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*>");
    private static final Pattern VALUE_ATTR = Pattern.compile("value=\"([^\"]*)\"");

    private static volatile boolean platformAdminReady;

    /** Harness-only secret for runtime-provisioned tenant admins. Never a platform default. */
    public static final String PROVISIONED_ADMIN_PASSWORD = "harness-tenant-admin-secret";

    /** {@code {bcrypt}} hash of {@link #PROVISIONED_ADMIN_PASSWORD}, in kelta-auth's stored form. */
    public static final String PROVISIONED_ADMIN_PASSWORD_HASH =
            "{bcrypt}$2a$10$zp1bsun6IbTXVK3L.CN1MOBD0.n0QzRXd13.ZakYsoQc30GLcZw6K";

    private final RestClient client;

    public AuthFixture() {
        this.client = RestClient.builder()
                .baseUrl(KeltaStack.authBaseUrl())
                .build();
    }

    /**
     * Logs in as the {@code admin@kelta.local} user in the {@code default} tenant.
     * kelta-auth refuses to authenticate without a tenant context because the
     * platform_user table is RLS-scoped, so a tenant slug is always required.
     */
    public String loginAsAdmin() {
        return loginAsAdmin(TenantFixture.DEFAULT_SLUG);
    }

    /**
     * Logs in as the admin user of the specified tenant and returns the raw access
     * token string. The admin identity depends on how the tenant was created:
     * <ul>
     *   <li>{@code default} — {@code admin@kelta.local} (Flyway baseline seed)</li>
     *   <li>any other slug — {@code <slug>-admin@kelta.local}, seeded by the worker's
     *       {@code TenantProvisioningHook} when the tenant was created via the admin API
     *       (e.g. the {@code threadline-clothing} fixture tenant)</li>
     * </ul>
     * The platform admin's password is {@link #PLATFORM_ADMIN_PASSWORD}; a provisioned admin's is
     * {@link #PROVISIONED_ADMIN_PASSWORD}, once {@link #setProvisionedAdminCredential(String)}
     * has set it.
     *
     * @param tenantSlug the slug of the tenant to scope the login to (e.g.
     *                   {@code "default"} or {@code "threadline-clothing"})
     * @return a valid RS256 JWT access token whose {@code tenant_id} claim
     *         matches the tenant identified by {@code tenantSlug}
     */
    @SuppressWarnings("unchecked")
    public String loginAsAdmin(String tenantSlug) {
        if (TenantFixture.DEFAULT_SLUG.equals(tenantSlug)) {
            ensurePlatformAdminPassword();
        }
        String username = adminUsernameForSlug(tenantSlug);
        Map<String, Object> response = client.post()
                .uri("/auth/direct-login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "username", username,
                        "password", passwordForSlug(tenantSlug),
                        "tenantSlug", tenantSlug))
                .retrieve()
                .body(Map.class);

        if (response == null || !response.containsKey("access_token")) {
            throw new RuntimeException("Direct login did not return access_token. Response: " + response);
        }
        return (String) response.get("access_token");
    }

    /**
     * Gives a runtime-provisioned tenant's seeded admin the harness-only credential
     * ({@link #PROVISIONED_ADMIN_PASSWORD}) and clears {@code force_change_on_login}, so
     * {@link #loginAsAdmin(String)} can authenticate as it. Provisioning leaves the admin with
     * no usable password, and the harness has no mailbox to accept an invite from. Idempotent.
     * Runs as the bootstrap superuser, which bypasses RLS.
     *
     * @return the number of credential rows updated (1 once the tenant is provisioned)
     */
    public static int setProvisionedAdminCredential(String tenantSlug) {
        String sql = "UPDATE user_credential SET password_hash = ?, force_change_on_login = false "
                + "WHERE user_id IN (SELECT pu.id FROM platform_user pu JOIN tenant t ON t.id = pu.tenant_id "
                + "WHERE t.slug = ? AND pu.username = ?)";
        try (Connection conn = DriverManager.getConnection(
                        KeltaStack.dbJdbcUrl(), KeltaStack.dbUsername(), KeltaStack.dbPassword());
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, PROVISIONED_ADMIN_PASSWORD_HASH);
            ps.setString(2, tenantSlug);
            ps.setString(3, tenantSlug + "-admin");
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set the harness admin credential for " + tenantSlug, e);
        }
    }

    private static String passwordForSlug(String tenantSlug) {
        return TenantFixture.DEFAULT_SLUG.equals(tenantSlug) ? PLATFORM_ADMIN_PASSWORD : PROVISIONED_ADMIN_PASSWORD;
    }

    /**
     * Completes the platform admin's forced first-sign-in change once per harness run, the way a
     * person does after a fresh install: sign in with {@link #BOOTSTRAP_ADMIN_PASSWORD}, get sent
     * to {@code /change-password}, choose {@link #PLATFORM_ADMIN_PASSWORD}. No-op once that works.
     */
    public static synchronized void ensurePlatformAdminPassword() {
        if (platformAdminReady) {
            return;
        }
        if (directLoginStatus(PLATFORM_ADMIN_PASSWORD) != 200) {
            completeForcedPasswordChange(BOOTSTRAP_ADMIN_PASSWORD, PLATFORM_ADMIN_PASSWORD);
        }
        platformAdminReady = true;
    }

    private static int directLoginStatus(String password) {
        String body = "{\"username\":\"" + adminUsernameForSlug(TenantFixture.DEFAULT_SLUG)
                + "\",\"password\":\"" + password
                + "\",\"tenantSlug\":\"" + TenantFixture.DEFAULT_SLUG + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(KeltaStack.authBaseUrl() + "/auth/direct-login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return new FormSession().send(request).statusCode();
    }

    /**
     * Drives kelta-auth's form sign-in and {@code /change-password} pages for the platform admin.
     * Cookies are carried by hand: the session cookie may be {@code Secure}, which
     * {@link java.net.CookieManager} withholds over the harness's plain http.
     */
    private static void completeForcedPasswordChange(String currentPassword, String newPassword) {
        String base = KeltaStack.authBaseUrl();
        FormSession session = new FormSession();

        HttpResponse<String> loginPage = session.send(HttpRequest.newBuilder(
                URI.create(base + "/login?tenant=" + TenantFixture.DEFAULT_SLUG)).GET().build());
        HttpResponse<String> signIn = session.send(session.form(base + "/login", Map.of(
                "username", adminUsernameForSlug(TenantFixture.DEFAULT_SLUG),
                "password", currentPassword,
                "_csrf", csrfToken(loginPage))));
        String location = signIn.headers().firstValue("Location").orElse("");
        if (!location.contains("/change-password")) {
            throw new IllegalStateException("Platform admin sign-in did not ask for a password change "
                    + "(status " + signIn.statusCode() + ", Location '" + location + "'). Is kelta-auth "
                    + "running with KELTA_BOOTSTRAP_ADMIN_PASSWORD = AuthFixture.BOOTSTRAP_ADMIN_PASSWORD?");
        }

        HttpResponse<String> changePage = session.send(HttpRequest.newBuilder(
                URI.create(base + "/change-password")).GET().build());
        HttpResponse<String> changed = session.send(session.form(base + "/change-password", Map.of(
                "currentPassword", currentPassword,
                "newPassword", newPassword,
                "confirmPassword", newPassword,
                "_csrf", csrfToken(changePage))));
        String done = changed.headers().firstValue("Location").orElse("");
        if (!done.contains("passwordChanged")) {
            throw new IllegalStateException("Platform admin password change was not accepted (status "
                    + changed.statusCode() + ", Location '" + done + "')");
        }
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

    /** A cookie-carrying, non-redirecting client for one browser-like session. */
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

    /**
     * The admin's login name, which is also the value the gateway stamps into {@code X-User-Id}.
     * Exposed because grants take a principal in exactly this form.
     */
    public static String adminUsername(String tenantSlug) {
        return adminUsernameForSlug(tenantSlug);
    }

    private static String adminUsernameForSlug(String tenantSlug) {
        return TenantFixture.DEFAULT_SLUG.equals(tenantSlug)
                ? "admin@kelta.local"
                : tenantSlug + "-admin@kelta.local";
    }

    /**
     * Extracts the {@code tenant_id} claim from the access token without signature
     * verification (the harness trusts the token was issued by the in-process auth service).
     */
    public String extractTenantId(String accessToken) {
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("Not a JWT: " + accessToken);
        byte[] payloadBytes = Base64.getUrlDecoder().decode(padBase64(parts[1]));
        String payload = new String(payloadBytes);
        // Naive extraction — sufficient for harness use; avoids Jackson dependency in fixtures
        return extractClaim(payload, "tenant_id");
    }

    private static String extractClaim(String json, String claim) {
        String key = "\"" + claim + "\":\"";
        int start = json.indexOf(key);
        if (start == -1) throw new IllegalArgumentException("Claim '" + claim + "' not found in: " + json);
        start += key.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private static String padBase64(String base64url) {
        int rem = base64url.length() % 4;
        // rem==2 → "==", rem==3 → "=", rem==0 → "" (already a multiple of 4).
        return rem == 0 ? base64url : base64url + "====".substring(rem);
    }
}
