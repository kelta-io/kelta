package io.kelta.worker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;

/**
 * Tells the gateway to stop accepting a user's access tokens once the user is deactivated or
 * deleted. Access tokens are self-contained JWTs the gateway validates by signature, so signing
 * the user out of kelta-auth ({@link AuthSessionRevocationClient}) only stops new ones being
 * issued; this writes {@code user:revoked:<tenantId>:<userId>} = the revocation time in epoch
 * seconds, and the gateway's {@code RevokedUserFilter} answers 401 to every JWT of that user
 * issued at or before it.
 *
 * <p>The key expires after {@code kelta.security.user-revocation-ttl} (default 2 h), which must
 * stay above the longest access-token TTL kelta-auth issues (1 h): past it every token the
 * marker covers has expired anyway. Written after the surrounding transaction commits
 * (immediately when there is none); best effort — a Redis failure is logged at ERROR, never
 * thrown to the user write.
 */
@Component
public class UserAccessRevocationMarker {

    private static final Logger log = LoggerFactory.getLogger(UserAccessRevocationMarker.class);

    public static final String KEY_PREFIX = "user:revoked:";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public UserAccessRevocationMarker(StringRedisTemplate redis,
                                      @Value("${kelta.security.user-revocation-ttl:PT2H}") Duration ttl) {
        this(redis, ttl, Clock.systemUTC());
    }

    UserAccessRevocationMarker(StringRedisTemplate redis, Duration ttl, Clock clock) {
        this.redis = redis;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Marks tenant {@code tenantId}'s user {@code userId} revoked once the current transaction commits. */
    public void markAfterCommit(String tenantId, String userId) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mark(tenantId, userId);
                }
            });
        } else {
            mark(tenantId, userId);
        }
    }

    /** Writes the marker now; {@code true} when Redis accepted it. Never throws. */
    boolean mark(String tenantId, String userId) {
        try {
            redis.opsForValue().set(key(tenantId, userId),
                    Long.toString(clock.instant().getEpochSecond()), ttl);
            return true;
        } catch (RuntimeException e) {
            log.error("Could not write the access revocation marker of user {} (tenant {}): {}",
                    userId, tenantId, e.getMessage());
            return false;
        }
    }

    static String key(String tenantId, String userId) {
        return KEY_PREFIX + tenantId + ":" + userId;
    }
}
