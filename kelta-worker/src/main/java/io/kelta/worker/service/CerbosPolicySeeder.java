package io.kelta.worker.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Seeds Cerbos policies on application startup — once per <em>image</em>, not once per pod.
 *
 * <p>The Cerbos store is durable (Postgres) and runtime permission changes are synced by
 * hooks, so a startup seed is only needed when the policy generator may have changed (a new
 * build) or the store is empty. After a successful seed the pod writes a done-marker keyed on
 * the build fingerprint ({@code BuildProperties#getTime()}); later replicas of the same build
 * see the marker and skip, provided the base policy is still present in Cerbos (so a wiped or
 * restored store is re-seeded regardless). {@code kelta.worker.cerbos.seed.force=true} bypasses
 * the marker. Without build info (no {@code META-INF/build-info.properties}) the marker is
 * disabled and every pod seeds, as before.
 *
 * <p>Uses a Redis-based distributed lock so only one worker instance
 * seeds policies at a time, preventing simultaneous policy compilation
 * storms against Cerbos.
 *
 * <p>The lock holder publishes a heartbeat every {@link #HEARTBEAT_INTERVAL}
 * to extend the {@link #LOCK_TTL}, so a slow seed will not expire
 * mid-run while a crashed seeder's lock still falls off within the
 * original TTL window.
 */
@Component
public class CerbosPolicySeeder {

    private static final Logger log = LoggerFactory.getLogger(CerbosPolicySeeder.class);
    private static final String LOCK_KEY = "cerbos:policy-seed-lock";
    static final String DONE_KEY_PREFIX = "cerbos:policy-seed:done:";
    static final Duration LOCK_TTL = Duration.ofMinutes(5);
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    /** Long enough to outlive any rollout; a new build always has a new fingerprint anyway. */
    static final Duration DONE_TTL = Duration.ofDays(30);

    private final CerbosPolicySyncService syncService;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final Counter contendedCounter;
    private final Counter expiredCounter;
    private final Counter skippedCounter;
    private final String buildFingerprint;
    private final boolean forceSeed;

    public CerbosPolicySeeder(CerbosPolicySyncService syncService,
                               StringRedisTemplate redisTemplate,
                               MeterRegistry meterRegistry,
                               ObjectProvider<BuildProperties> buildProperties,
                               @Value("${kelta.worker.cerbos.seed.force:false}") boolean forceSeed) {
        this.syncService = syncService;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.contendedCounter = meterRegistry.counter("cerbos.policy.seed.lock.contended");
        this.expiredCounter = meterRegistry.counter("cerbos.policy.seed.lock.expired");
        this.skippedCounter = meterRegistry.counter("cerbos.policy.seed.skipped", "reason", "already-seeded");
        this.buildFingerprint = fingerprintOf(buildProperties.getIfAvailable());
        this.forceSeed = forceSeed;
    }

    private static String fingerprintOf(BuildProperties buildProperties) {
        if (buildProperties == null || buildProperties.getTime() == null) {
            return null;
        }
        return Long.toString(buildProperties.getTime().toEpochMilli());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedPolicies() {
        if (alreadySeededByThisBuild()) {
            skippedCounter.increment();
            log.info("Cerbos policies already seeded by build {} and base policy present — skipping startup seed",
                    buildFingerprint);
            return;
        }

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, "locked", LOCK_TTL);

        if (!Boolean.TRUE.equals(acquired)) {
            contendedCounter.increment();
            String currentValue = safeGetLockValue();
            Long ttlSeconds = safeGetLockTtl();
            log.warn(
                    "Cerbos seed lock already held — skipping. lock_value={} ttl_seconds={}",
                    currentValue, ttlSeconds);
            return;
        }

        ScheduledExecutorService heartbeat = startHeartbeat();
        try {
            log.info("Seeding Cerbos policies for all tenants on startup (lock acquired)");
            // Seed base (ancestor) policies first — Cerbos requires these
            // before scoped per-tenant policies can compile
            syncService.seedBasePolicies();
            syncService.syncAllTenants();
            log.info("Cerbos policy seeding complete");
            markSeededByThisBuild();
        } catch (Exception e) {
            log.error("Failed to seed Cerbos policies on startup: {}", e.getMessage(), e);
            meterRegistry.counter("cerbos.policy.seed.failures", "tenant", "unknown").increment();
        } finally {
            stopHeartbeat(heartbeat);
            try {
                redisTemplate.delete(LOCK_KEY);
            } catch (Exception e) {
                log.warn("Failed to release Cerbos seed lock: {}", e.getMessage());
            }
        }
    }

    /**
     * True only when this build already seeded (marker present), the store still holds the
     * base policy, and the operator has not forced a seed. Any Redis error counts as
     * "not seeded" so a broken marker read can never suppress a needed seed.
     */
    private boolean alreadySeededByThisBuild() {
        if (forceSeed) {
            log.info("kelta.worker.cerbos.seed.force=true — seeding regardless of the done-marker");
            return false;
        }
        if (buildFingerprint == null) {
            log.info("No build info available — Cerbos seed done-marker disabled, seeding on every pod");
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(DONE_KEY_PREFIX + buildFingerprint))) {
                return false;
            }
        } catch (Exception e) {
            log.warn("Failed to read Cerbos seed done-marker: {}", e.getMessage());
            return false;
        }
        if (!syncService.basePoliciesPresent()) {
            log.warn("Cerbos seed done-marker present for build {} but the base policy is missing — re-seeding",
                    buildFingerprint);
            return false;
        }
        return true;
    }

    private void markSeededByThisBuild() {
        if (buildFingerprint == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(DONE_KEY_PREFIX + buildFingerprint,
                    Instant.now().toString(), DONE_TTL);
        } catch (Exception e) {
            log.warn("Failed to write Cerbos seed done-marker: {}", e.getMessage());
        }
    }

    private ScheduledExecutorService startHeartbeat() {
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cerbos-seed-lock-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long intervalSeconds = HEARTBEAT_INTERVAL.toSeconds();
        heartbeat.scheduleAtFixedRate(this::renewLock, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        return heartbeat;
    }

    private void stopHeartbeat(ScheduledExecutorService heartbeat) {
        heartbeat.shutdownNow();
        try {
            heartbeat.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void renewLock() {
        try {
            Boolean renewed = redisTemplate.expire(LOCK_KEY, LOCK_TTL);
            if (!Boolean.TRUE.equals(renewed)) {
                expiredCounter.increment();
                log.warn("Cerbos seed lock expired mid-seed — key missing during heartbeat renew");
            }
        } catch (Exception e) {
            log.warn("Failed to renew Cerbos seed lock heartbeat: {}", e.getMessage());
        }
    }

    private String safeGetLockValue() {
        try {
            return redisTemplate.opsForValue().get(LOCK_KEY);
        } catch (Exception e) {
            return "<error: " + e.getMessage() + ">";
        }
    }

    private Long safeGetLockTtl() {
        try {
            return redisTemplate.getExpire(LOCK_KEY);
        } catch (Exception e) {
            return null;
        }
    }
}
