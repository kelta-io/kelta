package io.kelta.testharness.fixtures;

import io.kelta.testharness.KeltaStack;
import org.springframework.web.client.RestClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;

/**
 * Mints JWTs against the in-harness kelta-auth instance via the direct-login endpoint.
 *
 * <p>The {@code default} tenant is seeded by the Flyway baseline with
 * {@code admin@kelta.local / password}. Every other tenant is provisioned at runtime
 * by the worker's {@code TenantProvisioningHook}, which seeds its admin as
 * {@code <slug>-admin@kelta.local} (username {@code <slug>-admin}) with <em>no usable
 * password</em> — a real tenant claims it by invite. A fixture that wants to log in as such an
 * admin first calls {@link #setProvisionedAdminCredential(String)}, which writes the
 * harness-only {@link #PROVISIONED_ADMIN_PASSWORD} straight into the database.
 * {@link #loginAsAdmin(String)} picks the right identity and password per slug.
 */
public final class AuthFixture {

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
     * The baseline admin's password is {@code password}; a provisioned admin's is
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
        return TenantFixture.DEFAULT_SLUG.equals(tenantSlug) ? "password" : PROVISIONED_ADMIN_PASSWORD;
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
