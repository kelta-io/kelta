package io.kelta.auth.session;

import io.kelta.auth.aot.AuthRuntimeHints;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the classes Java serialization writes for a value, so tests can check that each one
 * is registered for serialization in the native image.
 */
public final class SerializedTypes {

    private SerializedTypes() {
    }

    /** Every class (with its serializable superclasses) Java serialization writes for {@code value}. */
    public static Set<Class<?>> of(Object value) {
        Set<Class<?>> types = new LinkedHashSet<>();
        try (ObjectOutputStream out = new ObjectOutputStream(OutputStream.nullOutputStream()) {
            @Override
            protected void annotateClass(Class<?> type) {
                types.add(type);
            }

            @Override
            protected void annotateProxyClass(Class<?> type) {
                types.add(type);
            }
        }) {
            out.writeObject(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Not serializable: " + value.getClass().getName(), e);
        }
        return types;
    }

    /**
     * The runtime hints the native image is built with for session values: the platform's
     * registrar plus every registrar the classpath declares in {@code META-INF/spring/aot.factories}
     * (Spring Session's and Spring Security's among them), as Spring AOT applies them.
     */
    public static RuntimeHints nativeHints() {
        RuntimeHints hints = new RuntimeHints();
        ClassLoader classLoader = SerializedTypes.class.getClassLoader();
        new AuthRuntimeHints().registerHints(hints, classLoader);
        SpringFactoriesLoader.forResourceLocation("META-INF/spring/aot.factories")
                .load(RuntimeHintsRegistrar.class)
                .forEach(registrar -> registrar.registerHints(hints, classLoader));
        return hints;
    }

    /** The names of {@code types} that {@code hints} does not register for Java serialization. */
    public static Set<String> missingSerializationHints(RuntimeHints hints, Collection<Class<?>> types) {
        Set<String> registered = new TreeSet<>();
        hints.serialization().javaSerializationHints()
                .forEach(hint -> registered.add(hint.getType().getName()));
        Set<String> missing = new TreeSet<>();
        for (Class<?> type : types) {
            String name = TypeReference.of(type).getName();
            if (!registered.contains(name)) {
                missing.add(name);
            }
        }
        return missing;
    }

    /** A serializer that records the classes of every value written through {@code delegate}. */
    public static final class Recording implements RedisSerializer<Object> {

        private final RedisSerializer<Object> delegate;
        private final Set<Class<?>> types = ConcurrentHashMap.newKeySet();

        public Recording(RedisSerializer<Object> delegate) {
            this.delegate = delegate;
        }

        @Override
        public byte[] serialize(Object value) {
            if (value != null) {
                try {
                    types.addAll(of(value));
                } catch (UncheckedIOException notSerializable) {
                    // Recorded so the hint check names it; the production serializer drops it.
                    types.add(value.getClass());
                }
            }
            return delegate.serialize(value);
        }

        @Override
        public Object deserialize(byte[] bytes) {
            return delegate.deserialize(bytes);
        }

        public Set<Class<?>> types() {
            return Set.copyOf(types);
        }
    }
}
