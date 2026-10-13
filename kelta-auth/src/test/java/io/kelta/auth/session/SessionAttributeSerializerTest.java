package io.kelta.auth.session;

import com.nimbusds.jose.JWSAlgorithm;
import io.kelta.auth.model.KeltaUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SessionAttributeSerializer — session values in Redis")
class SessionAttributeSerializerTest {

    private final SessionAttributeSerializer serializer = new SessionAttributeSerializer(getClass().getClassLoader());

    @Test
    @DisplayName("a signed-in principal round-trips, still erased and equal to the original")
    void principalRoundTrips() {
        KeltaUserDetails user = SessionAttributeSamples.user(null);

        Object read = serializer.deserialize(serializer.serialize(user));

        assertThat(read).isInstanceOf(KeltaUserDetails.class).isEqualTo(user);
        assertThat(((KeltaUserDetails) read).getPassword()).isNull();
    }

    @Test
    @DisplayName("null is stored as nothing and nothing reads as null")
    void nullAndEmpty() {
        assertThat(serializer.serialize(null)).isEmpty();
        assertThat(serializer.deserialize(null)).isNull();
        assertThat(serializer.deserialize(new byte[0])).isNull();
    }

    @Test
    @DisplayName("a value that cannot be serialized is left out instead of failing the request")
    void unserializableValueIsDropped() {
        List<Object> value = new ArrayList<>(List.of(new Object()));

        assertThat(serializer.serialize(value)).isEmpty();
    }

    @Test
    @DisplayName("bytes that are not a serialized value read as absent")
    void garbageReadsAsAbsent() {
        assertThat(serializer.deserialize("not a serialized value".getBytes(StandardCharsets.UTF_8))).isNull();
    }

    @Test
    @DisplayName("a value written by a release whose class has since changed reads as absent")
    void incompatibleClassVersionReadsAsAbsent() {
        byte[] bytes = serializer.serialize(SessionAttributeSamples.user(null));
        byte[] name = KeltaUserDetails.class.getName().getBytes(StandardCharsets.UTF_8);
        int at = indexOf(bytes, name);
        assertThat(at).isPositive();
        // The class descriptor's serialVersionUID (8 bytes) follows the class name.
        bytes[at + name.length + 7] ^= 0x01;

        assertThat(serializer.deserialize(bytes)).isNull();
    }

    @Test
    @DisplayName("a stored value holding a class outside the JDK, Spring and the platform is rejected unread")
    void foreignClassIsRejected() {
        // Serializable, but from a third-party library.
        byte[] bytes = serializer.serialize(JWSAlgorithm.RS256);
        assertThat(bytes).isNotEmpty();

        assertThat(serializer.deserialize(bytes)).isNull();
        assertThat(SessionAttributeSerializer.isAllowed(JWSAlgorithm.class)).isFalse();
    }

    @Test
    @DisplayName("JDK, Spring and platform classes, their arrays and primitives are allowed")
    void allowedTypes() {
        assertThat(SessionAttributeSerializer.isAllowed(String[].class)).isTrue();
        assertThat(SessionAttributeSerializer.isAllowed(long.class)).isTrue();
        assertThat(SessionAttributeSerializer.isAllowed(KeltaUserDetails.class)).isTrue();
        assertThat(SessionAttributeSerializer.isAllowed(
                org.springframework.security.core.context.SecurityContextImpl.class)).isTrue();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
