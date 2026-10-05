package io.kelta.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.List;

/**
 * Replaces the platform admin's well-known baseline password on first boot.
 *
 * <p>The Flyway baseline seeds the platform tenant's {@code admin@kelta.local} — an account that
 * can manage every tenant — with BCrypt("password") and no forced change, so every fresh install
 * would otherwise start with a published platform-admin credential. While the stored hash is still
 * exactly {@link #BASELINE_HASH} (a string compare, never a BCrypt match), this runner swaps it for
 * {@code KELTA_BOOTSTRAP_ADMIN_PASSWORD} or a generated password and sets
 * {@code force_change_on_login}, so the admin chooses their own at first sign-in. A generated
 * password is printed once, in a WARN banner; a configured one is never logged.
 *
 * <p>Race-safe across replicas: the swap is one conditional {@code UPDATE … WHERE password_hash =
 * <baseline>}, and only the replica whose update changed the row logs anything. Every later boot
 * finds a different hash and does nothing, as does an install whose admin already changed it.
 *
 * <p>Runs with no tenant bound, i.e. on the platform session, which is what lets it read the
 * platform tenant's credential.
 */
@Component
@Order(20)
public class BaselineAdminPasswordInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BaselineAdminPasswordInitializer.class);

    static final String PLATFORM_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    static final String ADMIN_EMAIL = "admin@kelta.local";
    /** BCrypt("password") as seeded by {@code V1__baseline.sql}. */
    static final String BASELINE_HASH = "$2a$10$zAQaSHX1XSR1bwUL3pz9EOzecplsxInVizZc9HwLf7xPluSiE1EP6";
    static final String ENV_VAR = "KELTA_BOOTSTRAP_ADMIN_PASSWORD";
    static final int GENERATED_LENGTH = 24;

    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public BaselineAdminPasswordInitializer(JdbcTemplate jdbcTemplate,
                                            PasswordEncoder passwordEncoder,
                                            AuthProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    private record AdminCredential(String userId, String username, String passwordHash) {}

    @Override
    public void run(ApplicationArguments args) {
        List<AdminCredential> found = jdbcTemplate.query(
                """
                SELECT pu.id, pu.username, uc.password_hash
                FROM platform_user pu
                JOIN user_credential uc ON uc.user_id = pu.id
                WHERE pu.tenant_id = ? AND pu.email = ?
                """,
                (rs, rowNum) -> new AdminCredential(
                        rs.getString("id"), rs.getString("username"), rs.getString("password_hash")),
                PLATFORM_TENANT_ID, ADMIN_EMAIL);
        if (found.isEmpty() || !BASELINE_HASH.equals(found.get(0).passwordHash())) {
            return;
        }
        AdminCredential admin = found.get(0);

        String configured = properties.getBootstrapAdminPassword();
        boolean fromEnv = configured != null && !configured.isBlank();
        String password = fromEnv ? configured : generatePassword();

        int updated = jdbcTemplate.update(
                "UPDATE user_credential SET password_hash = ?, force_change_on_login = true, "
                        + "password_changed_at = now(), updated_at = now() "
                        + "WHERE user_id = ? AND password_hash = ?",
                passwordEncoder.encode(password), admin.userId(), BASELINE_HASH);
        if (updated == 0) {
            // Another replica replaced it between our read and our update; it owns the banner.
            return;
        }

        revokeAuthorizations(admin);

        if (fromEnv) {
            log.warn("Platform admin {} still had the Flyway baseline's well-known password. "
                    + "Replaced it with the value of {}; sign in with it and choose a new password "
                    + "when asked.", ADMIN_EMAIL, ENV_VAR);
        } else {
            log.warn("""

                    ================================================================================
                      Kelta platform admin initial password
                    ================================================================================
                      The platform admin {} still had the Flyway baseline's well-known
                      password, so it was replaced with a generated one:

                        Account:   {}  (tenant: default)
                        Password:  {}

                      Sign in with it; you will be asked to change it on first sign-in.
                      This password is shown only once. To choose it instead, set
                      {} before the first boot.
                    ================================================================================""",
                    ADMIN_EMAIL, ADMIN_EMAIL, password, ENV_VAR);
        }
    }

    /**
     * Drops the account's stored OAuth2 authorizations (refresh tokens and authorization codes),
     * so nothing obtained with the well-known password outlives the swap. principal_name is the
     * login name as typed — email or username — and carries no tenant, so this can also sign out
     * a same-named user in another tenant once; that user simply signs in again.
     */
    private void revokeAuthorizations(AdminCredential admin) {
        int revoked = jdbcTemplate.update(
                "DELETE FROM oauth2_authorization WHERE principal_name IN (?, ?)",
                ADMIN_EMAIL, admin.username() != null ? admin.username() : ADMIN_EMAIL);
        if (revoked > 0) {
            log.info("Revoked {} stored OAuth2 authorization(s) for {}", revoked, ADMIN_EMAIL);
        }
    }

    static String generatePassword() {
        StringBuilder sb = new StringBuilder(GENERATED_LENGTH);
        for (int i = 0; i < GENERATED_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
