package io.kelta.testharness.scenarios;

import io.kelta.testharness.KeltaStack;
import io.kelta.testharness.ScenarioBase;
import io.kelta.testharness.fixtures.AuthFixture;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * PLT-343: a fresh install no longer ships the platform admin with the Flyway baseline's
 * well-known password. kelta-auth's {@code BaselineAdminPasswordInitializer} replaced it on
 * first boot with {@code KELTA_BOOTSTRAP_ADMIN_PASSWORD} (the harness sets
 * {@link AuthFixture#BOOTSTRAP_ADMIN_PASSWORD}) and a forced change, which
 * {@link AuthFixture#ensurePlatformAdminPassword()} completes through the real sign-in form.
 *
 * <p>Every boot variant (generated banner, a second boot, an install that already changed the
 * password, two replicas racing) is covered against a migrated database by kelta-auth's
 * {@code BaselineAdminPasswordIntegrationTest}; the shared harness stack boots only once.
 */
@DisplayName("Baseline platform admin password scenario")
class BaselineAdminPasswordScenarioTest extends ScenarioBase {

    private static final String ADMIN = "admin@kelta.local";
    private static final String BASELINE_HASH = "$2a$10$zAQaSHX1XSR1bwUL3pz9EOzecplsxInVizZc9HwLf7xPluSiE1EP6";

    @Test
    @DisplayName("the well-known baseline password no longer signs the platform admin in")
    void baselinePasswordIsRejected() throws Exception {
        HttpClientErrorException rejected = directLoginError("password");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rejected.getResponseBodyAsString()).contains("invalid_credentials");

        try (Connection db = openDbConnection()) {
            assertThat(adminHash(db)).isNotEqualTo(BASELINE_HASH);
        }
    }

    @Test
    @DisplayName("kelta-auth logged that it applied KELTA_BOOTSTRAP_ADMIN_PASSWORD, without the value or a banner")
    void bootLogNamesTheAccountButNotTheValue() {
        String logs = KeltaStack.AUTH.getLogs();

        assertThat(logs).contains("Platform admin " + ADMIN + " still had the Flyway baseline")
                .contains("KELTA_BOOTSTRAP_ADMIN_PASSWORD")
                .doesNotContain(AuthFixture.BOOTSTRAP_ADMIN_PASSWORD)
                .doesNotContain("Kelta platform admin initial password");
    }

    @Test
    @DisplayName("after the forced change the chosen password works and the bootstrap one does not")
    void forcedChangeCompletes() throws Exception {
        AuthFixture.ensurePlatformAdminPassword();

        assertThat(auth.loginAsAdmin()).isNotBlank();
        assertThat(directLoginError(AuthFixture.BOOTSTRAP_ADMIN_PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        try (Connection db = openDbConnection();
                PreparedStatement ps = db.prepareStatement(
                        "SELECT uc.force_change_on_login FROM user_credential uc "
                                + "JOIN platform_user pu ON pu.id = uc.user_id "
                                + "JOIN tenant t ON t.id = pu.tenant_id WHERE t.slug = ? AND pu.email = ?")) {
            ps.setString(1, TenantFixture.DEFAULT_SLUG);
            ps.setString(2, ADMIN);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isFalse();
            }
        }
    }

    private String adminHash(Connection db) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT uc.password_hash FROM user_credential uc JOIN platform_user pu ON pu.id = uc.user_id "
                        + "JOIN tenant t ON t.id = pu.tenant_id WHERE t.slug = ? AND pu.email = ?")) {
            ps.setString(1, TenantFixture.DEFAULT_SLUG);
            ps.setString(2, ADMIN);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private HttpClientErrorException directLoginError(String password) {
        HttpClientErrorException e = catchThrowableOfType(HttpClientErrorException.class, () ->
                authClient().post()
                        .uri("/auth/direct-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("username", ADMIN, "password", password,
                                "tenantSlug", TenantFixture.DEFAULT_SLUG))
                        .retrieve()
                        .toBodilessEntity());
        assertThat(e).as("direct-login with " + password.length() + "-char password must be rejected").isNotNull();
        return e;
    }
}
