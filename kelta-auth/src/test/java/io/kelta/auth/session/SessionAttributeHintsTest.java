package io.kelta.auth.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kelta-auth runs as a native image, where Java serialization works only for classes registered
 * at build time; an unregistered class in a session value makes the value unstorable, and the
 * JVM test suite would never notice. This test serializes one value of every kind the service
 * keeps in a session and checks that every class written is registered and readable.
 */
@DisplayName("HTTP session values — native serialization hints")
class SessionAttributeHintsTest {

    @Test
    @DisplayName("every class written for a session value is registered for serialization")
    void everySessionValueClassIsRegistered() throws Exception {
        RuntimeHints hints = SerializedTypes.nativeHints();
        Map<String, Set<String>> missing = new LinkedHashMap<>();
        for (Map.Entry<String, Object> sample : SessionAttributeSamples.all().entrySet()) {
            Set<String> unregistered = SerializedTypes.missingSerializationHints(
                    hints, SerializedTypes.of(sample.getValue()));
            if (!unregistered.isEmpty()) {
                missing.put(sample.getKey(), unregistered);
            }
        }
        assertThat(missing).as("classes without a serialization hint, by session value").isEmpty();
    }

    /**
     * A class with {@code writeReplace} is written as its serial proxy (all {@link SerializedTypes#of} sees),
     * but serialization still looks the class itself up, so it needs its own registration.
     */
    @Test
    @DisplayName("JDK classes written through a serial proxy are registered alongside the proxy")
    void serialProxySourcesAreRegistered() {
        List<Object> sources = List.of(
                Instant.now(), Duration.ofMinutes(1),
                List.of("a"), List.of("a", "b", "c"),
                Set.of("a"), Set.of("a", "b", "c"),
                Map.of("a", 1), Map.of("a", 1, "b", 2));
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Object source : sources) {
            types.add(source.getClass());
        }
        assertThat(SerializedTypes.missingSerializationHints(SerializedTypes.nativeHints(), types)).isEmpty();
    }

    @Test
    @DisplayName("every class written for a session value passes the read filter")
    void everySessionValueClassIsReadable() throws Exception {
        Set<String> rejected = new TreeSet<>();
        for (Object value : SessionAttributeSamples.all().values()) {
            for (Class<?> type : SerializedTypes.of(value)) {
                if (!SessionAttributeSerializer.isAllowed(type)) {
                    rejected.add(type.getName());
                }
            }
        }
        assertThat(rejected).isEmpty();
    }

    @Test
    @DisplayName("every session value survives a write and read through the session serializer")
    void everySessionValueRoundTrips() throws Exception {
        SessionAttributeSerializer serializer = new SessionAttributeSerializer(getClass().getClassLoader());
        for (Map.Entry<String, Object> sample : SessionAttributeSamples.all().entrySet()) {
            byte[] bytes = serializer.serialize(sample.getValue());
            assertThat(bytes).as(sample.getKey()).isNotEmpty();
            assertThat(serializer.deserialize(bytes)).as(sample.getKey()).isNotNull()
                    .hasSameClassAs(sample.getValue());
        }
    }
}
