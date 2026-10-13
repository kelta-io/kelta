package io.kelta.auth.service;

import io.kelta.auth.config.AuthProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes {@code oauth2_authorization} rows that can no longer be used. Spring Authorization
 * Server writes a row per authorization and never deletes one — not when its tokens expire,
 * not when a refresh token is rotated away — so without this the table only grows.
 *
 * <p>A row is deleted when it holds at least one token and every token it holds (authorization
 * code, access token, OIDC ID token, refresh token, device code, user code) expired before
 * {@code now - grace}. A token whose expiry is missing keeps the row. Expired tokens are
 * rejected by Spring Authorization Server anyway, so a deleted row changes nothing a client can
 * do; the grace period only keeps recent rows around for troubleshooting. A row holding no token
 * at all (an authorization waiting on the consent page) has no time to judge it by and is kept.
 *
 * <p>Settings: {@code kelta.auth.authorization-retention.*} ({@link AuthProperties.AuthorizationRetention}).
 *
 * <p><b>Several replicas.</b> Every pod runs the sweep; there is no cluster lock. Each statement
 * deletes at most {@code batch-size} rows chosen with {@code FOR UPDATE SKIP LOCKED}, so two
 * pods sweeping at once take different rows instead of waiting on each other, and no statement
 * holds locks for long. A sweep stops after a short batch (nothing left, or another pod took
 * the rest) or after {@code max-batches} statements; a larger backlog drains over several runs.
 *
 * <p>The table has no row-level security (it is platform-wide, keyed by client and principal),
 * and kelta-auth's database role already deletes from it when a token is revoked.
 */
@Service
public class AuthorizationRetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationRetentionSweep.class);

    /**
     * A token group is spent when it is absent or expired before the cutoff (cutoff bound seven
     * times). The leading {@code GREATEST(...) < ?} — the latest expiry of the row — is implied
     * by the per-token conditions for any row Spring Authorization Server writes, and matches the
     * expression index {@code idx_oauth2_authorization_latest_expiry} (worker migration V210),
     * so a batch reads the expired rows through the index instead of scanning the table. Keep
     * the expression identical to the index's.
     */
    static final String DELETE_BATCH = """
            DELETE FROM oauth2_authorization WHERE id IN (
                SELECT id FROM oauth2_authorization
                WHERE GREATEST(authorization_code_expires_at, access_token_expires_at,
                               oidc_id_token_expires_at, refresh_token_expires_at,
                               device_code_expires_at, user_code_expires_at) < ?
                  AND (authorization_code_value IS NOT NULL OR access_token_value IS NOT NULL
                       OR oidc_id_token_value IS NOT NULL OR refresh_token_value IS NOT NULL
                       OR device_code_value IS NOT NULL OR user_code_value IS NOT NULL)
                  AND (authorization_code_value IS NULL OR authorization_code_expires_at < ?)
                  AND (access_token_value IS NULL OR access_token_expires_at < ?)
                  AND (oidc_id_token_value IS NULL OR oidc_id_token_expires_at < ?)
                  AND (refresh_token_value IS NULL OR refresh_token_expires_at < ?)
                  AND (device_code_value IS NULL OR device_code_expires_at < ?)
                  AND (user_code_value IS NULL OR user_code_expires_at < ?)
                LIMIT ?
                FOR UPDATE SKIP LOCKED)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AuthProperties.AuthorizationRetention settings;
    private final Clock clock;
    private final Counter deletedCounter;

    @Autowired
    public AuthorizationRetentionSweep(JdbcTemplate jdbcTemplate, AuthProperties properties,
                                       MeterRegistry meterRegistry) {
        this(jdbcTemplate, properties.getAuthorizationRetention(), meterRegistry, Clock.systemUTC());
    }

    AuthorizationRetentionSweep(JdbcTemplate jdbcTemplate, AuthProperties.AuthorizationRetention settings,
                                MeterRegistry meterRegistry, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.settings = settings;
        this.clock = clock;
        this.deletedCounter = Counter.builder("kelta_auth_authorizations_deleted")
                .description("oauth2_authorization rows deleted by the retention sweep")
                .register(meterRegistry);
    }

    @Scheduled(initialDelayString = "PT5M",
            fixedDelayString = "${kelta.auth.authorization-retention.interval:1h}")
    public void sweep() {
        if (!settings.isEnabled()) {
            return;
        }
        try {
            int deleted = deleteExpired();
            if (deleted > 0) {
                log.info("Authorization retention: deleted {} expired oauth2_authorization row(s)", deleted);
            }
        } catch (RuntimeException e) {
            log.error("Authorization retention sweep failed: {}", e.getMessage(), e);
        }
    }

    /** One sweep: batches until a short one or the per-sweep budget; returns rows deleted. */
    int deleteExpired() {
        Timestamp cutoff = Timestamp.from(cutoff());
        int batchSize = Math.max(1, settings.getBatchSize());
        int maxBatches = Math.max(1, settings.getMaxBatches());
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = jdbcTemplate.update(DELETE_BATCH,
                    cutoff, cutoff, cutoff, cutoff, cutoff, cutoff, cutoff, batchSize);
            total += deleted;
            deletedCounter.increment(deleted);
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }

    Instant cutoff() {
        Duration grace = settings.getGrace() == null || settings.getGrace().isNegative()
                ? Duration.ZERO : settings.getGrace();
        return clock.instant().minus(grace);
    }
}
