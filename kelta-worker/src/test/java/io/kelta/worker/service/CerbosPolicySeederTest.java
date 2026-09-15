package io.kelta.worker.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CerbosPolicySeeder")
class CerbosPolicySeederTest {

    private static final String LOCK_KEY = "cerbos:policy-seed-lock";

    private CerbosPolicySyncService syncService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private MeterRegistry meterRegistry;
    private CerbosPolicySeeder seeder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        syncService = mock(CerbosPolicySyncService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        meterRegistry = new SimpleMeterRegistry();
        // No build info -> done-marker disabled -> legacy seed-on-every-pod behaviour
        seeder = newSeeder(null, false);
    }

    private CerbosPolicySeeder newSeeder(BuildProperties buildProperties, boolean force) {
        ObjectProvider<BuildProperties> provider = new ObjectProvider<>() {
            @Override public BuildProperties getIfAvailable() { return buildProperties; }
            @Override public BuildProperties getObject(Object... args) { return buildProperties; }
            @Override public BuildProperties getObject() { return buildProperties; }
            @Override public BuildProperties getIfUnique() { return buildProperties; }
        };
        return new CerbosPolicySeeder(syncService, redisTemplate, meterRegistry, provider, force);
    }

    private static BuildProperties buildAt(String isoTime) {
        Properties props = new Properties();
        props.setProperty("time", isoTime);
        return new BuildProperties(props);
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("Acquires lock, seeds, then releases lock")
        void acquiresAndSeeds() {
            when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class)))
                    .thenReturn(true);

            seeder.seedPolicies();

            verify(syncService).seedBasePolicies();
            verify(syncService).syncAllTenants();
            verify(redisTemplate).delete(LOCK_KEY);
            assertThat(meterRegistry.counter("cerbos.policy.seed.lock.contended").count())
                    .isZero();
        }

        @Test
        @DisplayName("Releases lock even when seeding throws")
        void releasesLockOnFailure() {
            when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class)))
                    .thenReturn(true);
            org.mockito.Mockito.doThrow(new RuntimeException("Cerbos down"))
                    .when(syncService).syncAllTenants();

            seeder.seedPolicies();

            verify(redisTemplate).delete(LOCK_KEY);
            assertThat(meterRegistry.counter("cerbos.policy.seed.failures", "tenant", "unknown").count())
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Lock contention")
    class LockContention {

        @Test
        @DisplayName("Skips seeding and records contended counter when lock is held")
        void skipsWhenLockHeld() {
            when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class)))
                    .thenReturn(false);
            when(valueOps.get(LOCK_KEY)).thenReturn("locked");
            when(redisTemplate.getExpire(LOCK_KEY)).thenReturn(120L);

            seeder.seedPolicies();

            verify(syncService, never()).seedBasePolicies();
            verify(syncService, never()).syncAllTenants();
            verify(redisTemplate, never()).delete(LOCK_KEY);
            assertThat(meterRegistry.counter("cerbos.policy.seed.lock.contended").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Tolerates Redis errors when fetching diagnostic lock value/ttl")
        void tolerantDiagnosticReads() {
            when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class)))
                    .thenReturn(false);
            when(valueOps.get(LOCK_KEY)).thenThrow(new RuntimeException("Redis blip"));
            when(redisTemplate.getExpire(LOCK_KEY)).thenThrow(new RuntimeException("Redis blip"));

            seeder.seedPolicies();

            assertThat(meterRegistry.counter("cerbos.policy.seed.lock.contended").count())
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Heartbeat renewal")
    class HeartbeatRenewal {

        @Test
        @DisplayName("renewLock extends lock TTL")
        void renewsLockTtl() {
            when(redisTemplate.expire(LOCK_KEY, CerbosPolicySeeder.LOCK_TTL)).thenReturn(true);

            seeder.renewLock();

            verify(redisTemplate).expire(LOCK_KEY, CerbosPolicySeeder.LOCK_TTL);
            assertThat(meterRegistry.counter("cerbos.policy.seed.lock.expired").count())
                    .isZero();
        }

        @Test
        @DisplayName("renewLock increments expired counter when key already gone")
        void detectsExpiredLockMidSeed() {
            when(redisTemplate.expire(LOCK_KEY, CerbosPolicySeeder.LOCK_TTL)).thenReturn(false);

            seeder.renewLock();

            assertThat(meterRegistry.counter("cerbos.policy.seed.lock.expired").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("renewLock swallows Redis errors without throwing")
        void swallowsRedisErrors() {
            when(redisTemplate.expire(LOCK_KEY, CerbosPolicySeeder.LOCK_TTL))
                    .thenThrow(new RuntimeException("Redis down"));

            seeder.renewLock();

            assertThat(meterRegistry.counter("cerbos.policy.seed.lock.expired").count())
                    .isZero();
        }
    }

    @Nested
    @DisplayName("Contended path then recovery (integration scenario)")
    class ContendedPathRecovery {

        @Test
        @DisplayName("Second worker acquires after first worker's lock TTL expires")
        void recoversAfterCrashedSeeder() {
            // First setIfAbsent: worker A's prior crashed run still owns lock -> false
            // Second setIfAbsent (after TTL elapsed): lock gone -> true
            when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class)))
                    .thenReturn(false)
                    .thenReturn(true);
            when(valueOps.get(LOCK_KEY)).thenReturn("locked");
            when(redisTemplate.getExpire(LOCK_KEY)).thenReturn(45L);

            // First attempt: lock contended, skip seeding
            seeder.seedPolicies();
            verify(syncService, never()).syncAllTenants();
            assertThat(meterRegistry.counter("cerbos.policy.seed.lock.contended").count())
                    .isEqualTo(1.0);

            // Second attempt (simulating restart after TTL): seeding proceeds
            seeder.seedPolicies();
            verify(syncService).seedBasePolicies();
            verify(syncService).syncAllTenants();
            verify(redisTemplate, atLeastOnce()).delete(LOCK_KEY);
        }
    }

    @Nested
    @DisplayName("Once-per-image done-marker")
    class DoneMarker {

        private static final String BUILD_TIME = "2026-09-15T20:00:00Z";
        private final String doneKey = "cerbos:policy-seed:done:"
                + java.time.Instant.parse(BUILD_TIME).toEpochMilli();

        @Test
        @DisplayName("First pod of a build seeds and writes the done-marker")
        void firstPodSeedsAndMarks() {
            seeder = newSeeder(buildAt(BUILD_TIME), false);
            when(redisTemplate.hasKey(doneKey)).thenReturn(false);
            when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);

            seeder.seedPolicies();

            verify(syncService).seedBasePolicies();
            verify(syncService).syncAllTenants();
            verify(valueOps).set(eq(doneKey), anyString(), eq(CerbosPolicySeeder.DONE_TTL));
            verify(syncService, never()).basePoliciesPresent();
        }

        @Test
        @DisplayName("Later replica of the same build skips when marker present and base policy exists")
        void laterReplicaSkips() {
            seeder = newSeeder(buildAt(BUILD_TIME), false);
            when(redisTemplate.hasKey(doneKey)).thenReturn(true);
            when(syncService.basePoliciesPresent()).thenReturn(true);

            seeder.seedPolicies();

            verify(syncService, never()).seedBasePolicies();
            verify(syncService, never()).syncAllTenants();
            verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
            assertThat(meterRegistry.counter("cerbos.policy.seed.skipped", "reason", "already-seeded").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Marker present but Cerbos store lost the base policy -> re-seeds")
        void reseedsWhenStoreEmpty() {
            seeder = newSeeder(buildAt(BUILD_TIME), false);
            when(redisTemplate.hasKey(doneKey)).thenReturn(true);
            when(syncService.basePoliciesPresent()).thenReturn(false);
            when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);

            seeder.seedPolicies();

            verify(syncService).seedBasePolicies();
            verify(syncService).syncAllTenants();
        }

        @Test
        @DisplayName("A different build fingerprint does not honour the old marker")
        void newBuildSeeds() {
            seeder = newSeeder(buildAt("2026-09-16T08:00:00Z"), false);
            when(redisTemplate.hasKey(doneKey)).thenReturn(true); // old build's key
            when(redisTemplate.hasKey(anyString())).thenAnswer(inv -> doneKey.equals(inv.getArgument(0)));
            when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);

            seeder.seedPolicies();

            verify(syncService).syncAllTenants();
        }

        @Test
        @DisplayName("seed.force=true seeds without consulting the marker")
        void forceSeeds() {
            seeder = newSeeder(buildAt(BUILD_TIME), true);
            when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);

            seeder.seedPolicies();

            verify(redisTemplate, never()).hasKey(anyString());
            verify(syncService).syncAllTenants();
        }

        @Test
        @DisplayName("Marker is not written when seeding throws")
        void noMarkerOnFailure() {
            seeder = newSeeder(buildAt(BUILD_TIME), false);
            when(redisTemplate.hasKey(doneKey)).thenReturn(false);
            when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
            org.mockito.Mockito.doThrow(new RuntimeException("Cerbos down"))
                    .when(syncService).syncAllTenants();

            seeder.seedPolicies();

            verify(valueOps, never()).set(eq(doneKey), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("Redis error reading the marker falls through to a normal seed")
        void redisErrorSeeds() {
            seeder = newSeeder(buildAt(BUILD_TIME), false);
            when(redisTemplate.hasKey(doneKey)).thenThrow(new RuntimeException("redis down"));
            when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);

            seeder.seedPolicies();

            verify(syncService).syncAllTenants();
        }

        @Test
        @DisplayName("Without build info every pod seeds (marker disabled)")
        void noBuildInfoSeeds() {
            seeder = newSeeder(null, false);
            when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);

            seeder.seedPolicies();

            verify(redisTemplate, never()).hasKey(anyString());
            verify(syncService).syncAllTenants();
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }
    }
}
