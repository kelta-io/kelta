package io.kelta.auth.session;

import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.IndexResolver;
import org.springframework.session.MapSession;
import org.springframework.session.Session;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A test stand-in for the Redis session store with the same contract: sessions are kept as
 * serialized values (through the production {@link SessionAttributeSerializer}, so a value that
 * does not survive serialization fails here too) and can be listed per user through the
 * production index resolver. Lets flow tests run the real session filter without Docker; the
 * Redis store itself is covered by {@code SharedHttpSessionIntegrationTest}.
 */
public final class InMemoryIndexedSessionRepository implements FindByIndexNameSessionRepository<MapSession> {

    private record Stored(Instant creationTime, Instant lastAccessedTime, Duration maxInactiveInterval,
                          Map<String, byte[]> attributes) {
    }

    private final Map<String, Stored> sessions = new ConcurrentHashMap<>();
    private final IndexResolver<Session> indexResolver;
    private final RedisSerializer<Object> serializer;
    private final Duration defaultMaxInactiveInterval;

    public InMemoryIndexedSessionRepository(IndexResolver<Session> indexResolver, RedisSerializer<Object> serializer,
                                            Duration defaultMaxInactiveInterval) {
        this.indexResolver = indexResolver;
        this.serializer = serializer;
        this.defaultMaxInactiveInterval = defaultMaxInactiveInterval;
    }

    @Override
    public MapSession createSession() {
        MapSession session = new MapSession();
        session.setMaxInactiveInterval(defaultMaxInactiveInterval);
        return session;
    }

    @Override
    public void save(MapSession session) {
        if (!session.getId().equals(session.getOriginalId())) {
            sessions.remove(session.getOriginalId());
        }
        Map<String, byte[]> attributes = new LinkedHashMap<>();
        for (String name : session.getAttributeNames()) {
            attributes.put(name, serializer.serialize(session.getAttribute(name)));
        }
        sessions.put(session.getId(), new Stored(session.getCreationTime(), session.getLastAccessedTime(),
                session.getMaxInactiveInterval(), attributes));
    }

    @Override
    public MapSession findById(String id) {
        Stored stored = sessions.get(id);
        if (stored == null) {
            return null;
        }
        MapSession session = new MapSession(id);
        session.setCreationTime(stored.creationTime());
        session.setLastAccessedTime(stored.lastAccessedTime());
        session.setMaxInactiveInterval(stored.maxInactiveInterval());
        stored.attributes().forEach((name, bytes) -> {
            Object value = serializer.deserialize(bytes);
            if (value != null) {
                session.setAttribute(name, value);
            }
        });
        if (session.isExpired()) {
            sessions.remove(id);
            return null;
        }
        return session;
    }

    @Override
    public void deleteById(String id) {
        sessions.remove(id);
    }

    @Override
    public Map<String, MapSession> findByIndexNameAndIndexValue(String indexName, String indexValue) {
        Map<String, MapSession> found = new HashMap<>();
        for (String id : sessions.keySet()) {
            MapSession session = findById(id);
            if (session != null && indexValue.equals(indexResolver.resolveIndexesFor(session).get(indexName))) {
                found.put(id, session);
            }
        }
        return found;
    }

    /** The stored idle timeout of session {@code id}, or {@code null} when there is none. */
    public Duration storedMaxInactiveInterval(String id) {
        Stored stored = sessions.get(id);
        return stored == null ? null : stored.maxInactiveInterval();
    }
}
