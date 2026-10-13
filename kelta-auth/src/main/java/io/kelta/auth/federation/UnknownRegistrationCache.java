package io.kelta.auth.federation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A short-lived, bounded record of registration ids the worker reported as unknown.
 *
 * <p>The dynamic registration repositories cache hits for five minutes, but a miss used
 * to cost a worker lookup every time. Remembering a miss briefly lets repeated requests
 * for the same unknown id skip the worker. Only a <em>successful</em> lookup that did not
 * contain the provider is recorded — a worker failure is never cached, so an outage does
 * not linger as a miss once the worker is back.
 *
 * <p><b>Freshness.</b> The TTL is short so a newly created provider becomes usable within
 * seconds without any event; per pod, like the positive cache. Rendering the login page
 * for a tenant (which lists its providers) forgets misses for every id it finds, and
 * {@code evictAll()} on the repositories clears this too.
 *
 * <p><b>Bounds.</b> At most {@link #DEFAULT_MAX_ENTRIES} ids, each at most
 * {@link #MAX_ID_LENGTH} characters. When full, expired entries are purged; if it is
 * still full the miss is simply not recorded (the lookup still happens, and the per-IP
 * rate limit in front bounds how often).
 */
final class UnknownRegistrationCache {

    static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    static final int DEFAULT_MAX_ENTRIES = 10_000;
    /** Real ids are {@code {tenantId}:{providerId}}; anything far longer is not worth keeping. */
    static final int MAX_ID_LENGTH = 256;

    private final Map<String, Instant> expiries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;

    UnknownRegistrationCache() {
        this(DEFAULT_TTL, DEFAULT_MAX_ENTRIES, Clock.systemUTC());
    }

    UnknownRegistrationCache(Duration ttl, int maxEntries, Clock clock) {
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /** True while a recorded miss for {@code registrationId} has not expired. */
    boolean isKnownUnknown(String registrationId) {
        Instant expiresAt = expiries.get(registrationId);
        if (expiresAt == null) {
            return false;
        }
        if (!expiresAt.isAfter(clock.instant())) {
            expiries.remove(registrationId, expiresAt);
            return false;
        }
        return true;
    }

    /** Records that a successful lookup did not contain {@code registrationId}. */
    void recordUnknown(String registrationId) {
        if (registrationId == null || registrationId.length() > MAX_ID_LENGTH) {
            return;
        }
        if (expiries.size() >= maxEntries) {
            purgeExpired();
            if (expiries.size() >= maxEntries) {
                return;
            }
        }
        expiries.put(registrationId, clock.instant().plus(ttl));
    }

    /** Drops any recorded miss for these ids — they were just found. */
    void forget(Collection<String> registrationIds) {
        for (String id : registrationIds) {
            expiries.remove(id);
        }
    }

    void clear() {
        expiries.clear();
    }

    int size() {
        return expiries.size();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        expiries.entrySet().removeIf(e -> !e.getValue().isAfter(now));
    }
}
