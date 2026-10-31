package io.kelta.runtime.module.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModuleServiceRegistry")
class ModuleServiceRegistryTest {

    /** Stands in for a platform-defined port such as an entitlement provider. */
    public interface Greeter {
        String greet();
    }

    private record FixedGreeter(String value) implements Greeter {
        @Override
        public String greet() {
            return value;
        }
    }

    private final ModuleServiceRegistry registry = new ModuleServiceRegistry();

    @Test
    @DisplayName("resolves the implementation a module published for that tenant")
    void resolvesPublishedService() {
        registry.register("t1", Greeter.class, new FixedGreeter("hello"));

        assertThat(registry.find("t1", Greeter.class)).map(Greeter::greet).contains("hello");
        assertThat(registry.has("t1", Greeter.class)).isTrue();
        assertThat(registry.serviceCount("t1")).isEqualTo(1);
    }

    @Test
    @DisplayName("a tenant with no module implementation resolves empty, so callers keep their own behaviour")
    void unknownTenantResolvesEmpty() {
        registry.register("t1", Greeter.class, new FixedGreeter("hello"));

        // The whole safety property of this registry: adding it changes nothing until a module
        // actually publishes something for that specific tenant.
        assertThat(registry.find("t2", Greeter.class)).isEmpty();
        assertThat(registry.find("t1", Runnable.class)).isEmpty();
        assertThat(registry.has("t2", Greeter.class)).isFalse();
    }

    @Test
    @DisplayName("registrations are tenant-scoped -- one tenant's module never answers for another")
    void tenantsAreIsolated() {
        registry.register("t1", Greeter.class, new FixedGreeter("one"));
        registry.register("t2", Greeter.class, new FixedGreeter("two"));

        assertThat(registry.find("t1", Greeter.class)).map(Greeter::greet).contains("one");
        assertThat(registry.find("t2", Greeter.class)).map(Greeter::greet).contains("two");
    }

    @Test
    @DisplayName("removal is by identity, and leaves other tenants alone")
    void removesByIdentity() {
        Greeter published = new FixedGreeter("one");
        registry.register("t1", Greeter.class, published);
        registry.register("t2", Greeter.class, new FixedGreeter("two"));

        // A different instance of the same port must not evict the registered one: identity is what
        // a removal can rely on.
        registry.remove("t1", Map.of(Greeter.class, new FixedGreeter("one")));
        assertThat(registry.find("t1", Greeter.class)).isPresent();

        registry.remove("t1", Map.of(Greeter.class, published));
        assertThat(registry.find("t1", Greeter.class)).isEmpty();
        assertThat(registry.find("t2", Greeter.class)).isPresent();
    }

    @Test
    @DisplayName("two modules cannot claim the same port for one tenant")
    void rejectsDuplicatePortForTenant() {
        registry.register("t1", Greeter.class, new FixedGreeter("first"));

        // Last-write-wins would make behaviour depend on module load order.
        assertThatThrownBy(() -> registry.register("t1", Greeter.class, new FixedGreeter("second")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already has an implementation");

        assertThat(registry.find("t1", Greeter.class)).map(Greeter::greet).contains("first");
    }

    @Test
    @DisplayName("re-registering the exact same instance is a no-op, not a conflict")
    void sameInstanceIsIdempotent() {
        Greeter published = new FixedGreeter("hello");
        registry.register("t1", Greeter.class, published);

        registry.register("t1", Greeter.class, published);

        assertThat(registry.serviceCount("t1")).isEqualTo(1);
    }

    @Test
    @DisplayName("a port the platform does not define is rejected at registration, not at first call")
    void rejectsPortLoadedByAnotherClassLoader() throws Exception {
        // The real failure mode: a module bundles its own copy of the port interface. A child-first
        // ClassLoader loads the duplicate happily, and without this check the platform would only
        // find out as a ClassCastException inside whichever bean later resolved the service.
        ClassLoader shadowing = new ShadowingClassLoader(getClass().getClassLoader());
        Class<?> shadowedPort = shadowing.loadClass(Greeter.class.getName());

        assertThat(shadowedPort).isNotSameAs(Greeter.class);
        assertThatThrownBy(() ->
                registry.register("t1", shadowedPort, java.lang.reflect.Proxy.newProxyInstance(
                        shadowing, new Class<?>[]{shadowedPort}, (p, m, a) -> "shadow")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shadows the platform type");
    }

    @Test
    @DisplayName("a service that does not implement its declared port is rejected")
    void rejectsMismatchedImplementation() {
        assertThatThrownBy(() -> registry.register("t1", Greeter.class, "not a greeter"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not implement the port");
    }

    @Test
    @DisplayName("a concrete class is rejected as a port -- ports are interfaces")
    void rejectsNonInterfacePort() {
        assertThatThrownBy(() -> registry.register("t1", FixedGreeter.class, new FixedGreeter("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be published under an interface");
    }

    @Test
    @DisplayName("a blank tenant is rejected -- an unscoped service would answer for everyone")
    void rejectsMissingTenant() {
        assertThatThrownBy(() -> registry.register("  ", Greeter.class, new FixedGreeter("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId required");
    }

    @Test
    @DisplayName("removing what was never registered is harmless")
    void removeIsTolerant() {
        registry.remove("nobody", Map.of(Greeter.class, new FixedGreeter("x")));
        registry.remove("nobody", null);

        assertThat(registry.serviceCount("nobody")).isZero();
    }

    @Test
    @DisplayName("multiple distinct ports coexist for one tenant")
    void multiplePortsPerTenant() {
        Map<Class<?>, Object> published = new LinkedHashMap<>();
        published.put(Greeter.class, new FixedGreeter("hi"));
        published.put(Runnable.class, (Runnable) () -> { });
        published.forEach((port, svc) -> registry.register("t1", port, svc));

        assertThat(registry.serviceCount("t1")).isEqualTo(2);

        registry.remove("t1", published);
        assertThat(registry.serviceCount("t1")).isZero();
    }

    /** Loads the test's own port class itself rather than delegating, mimicking a module JAR that bundles a copy of the platform API. */
    private static final class ShadowingClassLoader extends ClassLoader {
        private ShadowingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(Greeter.class.getName())) {
                Class<?> already = findLoadedClass(name);
                if (already != null) {
                    return already;
                }
                try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (Exception e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
