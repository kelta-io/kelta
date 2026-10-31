package io.kelta.worker.module;

import io.kelta.runtime.module.TenantModuleData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code tenant_module.installed_by} must hold whatever the gateway stamps as the actor.
 *
 * <p>The column was {@code varchar(36)} — sized for a UUID — but {@code ModuleController} writes
 * the raw {@code X-User-ID} header, which in this deployment is an email. Any actor identifier
 * over 36 characters therefore failed the install outright with
 * {@code value too long for type character varying(36)}.
 *
 * <p>It stayed latent because the production admin ({@code spotopened-admin@kelta.local}, 28 chars)
 * fits. It surfaced installing a module as a sandbox admin, whose generated email derives from the
 * sandbox slug and runs to 51 characters. Widened to 255 in V187, matching every other actor column
 * in the module tables.
 *
 * <p>Deliberately a real-database test: a mocked {@code JdbcTemplate} cannot enforce a column
 * width, which is exactly why the unit tests and smoke E2E all passed while the install 400'd.
 */
@DisplayName("JdbcModuleStore — installed_by column width")
class JdbcModuleStoreInstalledByWidthTest {

    /** The identifier that actually broke it: a sandbox admin's generated email. */
    private static final String LONG_ACTOR =
            "spotopened--billing-stripe-verify-admin@kelta.local";

    private JdbcTemplate jdbcTemplate;
    private JdbcModuleStore store;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build());
        // The production INSERT casts the manifest with Postgres's ?::jsonb, which H2 does not
        // know. Declaring JSONB as a domain lets the REAL statement run unmodified — the point of
        // this test is the column width, so the SQL under test must not be rewritten for it.
        jdbcTemplate.execute("CREATE DOMAIN IF NOT EXISTS JSONB AS VARCHAR(100000)");
        // Mirrors the shipped schema after V221 (installed_by widened in V187). If installed_by is
        // ever narrowed back toward a UUID width, this test fails rather than the next long-named
        // actor discovering it.
        jdbcTemplate.execute("""
                CREATE TABLE tenant_module (
                    id VARCHAR(36) PRIMARY KEY,
                    tenant_id VARCHAR(36) NOT NULL,
                    module_id VARCHAR(100) NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    version VARCHAR(64) NOT NULL,
                    description VARCHAR(1000),
                    source_url VARCHAR(2000) NOT NULL,
                    jar_checksum VARCHAR(64),
                    jar_size_bytes BIGINT,
                    module_class VARCHAR(500),
                    manifest VARCHAR(100000),
                    status VARCHAR(20) NOT NULL,
                    installed_by VARCHAR(255) NOT NULL,
                    s3_key VARCHAR(2000),
                    base_url VARCHAR(2000),
                    protocol_version INTEGER,
                    connected_app_id VARCHAR(36),
                    service_user_id VARCHAR(36),
                    installed_at TIMESTAMP,
                    updated_at TIMESTAMP
                )""");
        store = new JdbcModuleStore(jdbcTemplate);
    }

    @Test
    @DisplayName("Accepts an actor identifier longer than 36 characters")
    void acceptsLongActorIdentifier() {
        assertThat(LONG_ACTOR.length())
                .as("the regression only reproduces above the old 36-char width")
                .isGreaterThan(36);

        TenantModuleData data = new TenantModuleData(
                UUID.randomUUID().toString(), "c734b580-1204-4e00-97a4-3500a4f5c25e",
                "kelta-billing", "Kelta Billing", "1.0.0", "Billing",
                "local://upload", "checksum", 1L,
                "io.kelta.modules.billing.BillingModule", "{}",
                TenantModuleData.STATUS_INSTALLED, LONG_ACTOR,
                null, null, "s3/key.jar", List.of());

        assertThatCode(() -> store.createModule(data)).doesNotThrowAnyException();

        String stored = jdbcTemplate.queryForObject(
                "SELECT installed_by FROM tenant_module WHERE module_id = ?",
                String.class, "kelta-billing");
        assertThat(stored)
                .as("the actor must be stored intact, not silently truncated")
                .isEqualTo(LONG_ACTOR);
    }
}
