package io.kelta.auth.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ConfigurableObjectInputStream;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

/**
 * Writes and reads HTTP session values in the Redis session store with Java serialization.
 *
 * <p>Java serialization because every value the framework keeps in a session — the security
 * context, saved request, CSRF token, OAuth2 and SAML authentication requests, authentication
 * exceptions — is {@code Serializable} by contract, while the Jackson modules cover only part of
 * them. Every type stored must be registered for serialization in the native image
 * ({@code io.kelta.auth.aot.AuthRuntimeHints}); {@code SessionAttributeHintsTest} checks it.
 *
 * <p>A value that cannot be stored or read never fails the request:
 * <ul>
 *   <li>a value that cannot be written is left out of the session (logged), so the request
 *       behaves as if it had not been set;</li>
 *   <li>a stored value that cannot be read back — written by a release whose classes have
 *       since changed, or not a permitted type — reads as absent (logged). For the security
 *       context that means the user signs in again.</li>
 * </ul>
 *
 * <p>Only JDK, Spring and platform classes are read ({@link #ALLOWED_PACKAGE_PREFIXES}); any other
 * class in a stored value rejects the whole value before it is instantiated.
 */
public final class SessionAttributeSerializer implements RedisSerializer<Object> {

    private static final Logger log = LoggerFactory.getLogger(SessionAttributeSerializer.class);

    private static final byte[] EMPTY = new byte[0];

    /** Package prefixes of the classes a stored session value may contain. */
    static final List<String> ALLOWED_PACKAGE_PREFIXES = List.of("java.", "org.springframework.", "io.kelta.");

    /** Bounds that no legitimate session value comes near. */
    private static final long MAX_DEPTH = 64;
    private static final long MAX_ARRAY_LENGTH = 100_000;

    static final ObjectInputFilter ALLOWED_TYPES = SessionAttributeSerializer::check;

    private final ClassLoader classLoader;

    public SessionAttributeSerializer(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public byte[] serialize(Object value) {
        if (value == null) {
            return EMPTY;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Exception | Error e) {
            log.warn("HTTP session value of type {} was not stored: {}", value.getClass().getName(), e.toString());
            return EMPTY;
        }
        return bytes.toByteArray();
    }

    @Override
    public Object deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (ObjectInputStream in = new ConfigurableObjectInputStream(new ByteArrayInputStream(bytes), classLoader)) {
            in.setObjectInputFilter(ALLOWED_TYPES);
            return in.readObject();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Exception | Error e) {
            log.warn("Stored HTTP session value could not be read and is treated as absent: {}", e.toString());
            return null;
        }
    }

    static boolean isAllowed(Class<?> type) {
        Class<?> element = type;
        while (element.isArray()) {
            element = element.getComponentType();
        }
        if (element.isPrimitive()) {
            return true;
        }
        String name = element.getName();
        for (String prefix : ALLOWED_PACKAGE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectInputFilter.Status check(ObjectInputFilter.FilterInfo info) {
        if (info.depth() > MAX_DEPTH || info.arrayLength() > MAX_ARRAY_LENGTH) {
            return ObjectInputFilter.Status.REJECTED;
        }
        Class<?> type = info.serialClass();
        if (type == null) {
            return ObjectInputFilter.Status.UNDECIDED;
        }
        return isAllowed(type) ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
    }
}
