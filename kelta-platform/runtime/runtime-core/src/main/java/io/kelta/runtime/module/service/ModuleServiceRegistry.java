package io.kelta.runtime.module.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Services published <b>by a module, for the platform to call</b>: a platform-defined port (today
 * {@link EntitlementProvider}) answered by a module, resolved by platform code per call.
 *
 * <p>Action handlers and before-save hooks only let a module react to something the platform
 * dispatches to it. They cannot answer a question the platform needs answered <i>inline</i>. An
 * external module declares the ports it answers in its manifest's {@code services[]}, and
 * {@code ExternalModuleRegistry} registers a remote implementation here
 * ({@code RemoteEntitlementProvider}, which calls the module over HTTP).
 *
 * <p>Registrations are <b>tenant-scoped</b>: a module installed by one tenant never answers for
 * another. A tenant with no module-provided implementation resolves empty, and the caller is
 * expected to fall back to its compiled-in behaviour — so adding this changes nothing until a
 * module actually publishes something.
 *
 * <h2>Resolve per call — never cache the instance</h2>
 * A module is disabled, upgraded or uninstalled at run time, and its registration is replaced or
 * removed with it. A reference held across that keeps calling the old registration, so callers
 * must {@link #find(String, Class)} each time rather than injecting the result once. The lookup is
 * a map read.
 *
 * <h2>Why the port type is validated</h2>
 * The port must be an interface the platform itself defines and loads, so the key a caller
 * resolves with is the same {@code Class} object the registration used. It is checked at
 * registration, where the error can name the actual cause.
 *
 * @since 1.0.0
 */
public class ModuleServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModuleServiceRegistry.class);

    /** tenantId -> (port interface -> implementation published by a module). */
    private final Map<String, Map<Class<?>, Object>> tenantServices = new ConcurrentHashMap<>();

    /**
     * Publishes {@code service} as this tenant's implementation of {@code port}.
     *
     * @throws IllegalArgumentException if the port is not a platform-loaded interface, the service
     *         does not implement it, or this tenant already has an implementation of that port
     */
    public void register(String tenantId, Class<?> port, Object service) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required to publish a module service");
        }
        if (port == null || service == null) {
            throw new IllegalArgumentException("port and service are both required");
        }
        requirePlatformPort(port);
        if (!port.isInstance(service)) {
            throw new IllegalArgumentException(
                    "Module service " + service.getClass().getName()
                            + " does not implement the port it was published under: " + port.getName());
        }

        Map<Class<?>, Object> services = tenantServices.computeIfAbsent(
                tenantId, k -> new ConcurrentHashMap<>());
        Object existing = services.putIfAbsent(port, service);
        if (existing != null && existing != service) {
            // Two modules claiming the same port for one tenant is ambiguous, and silently letting
            // the last registration win would make behaviour depend on registration order. Refuse
            // instead; the caller logs it and leaves the first registration in place.
            throw new IllegalArgumentException(
                    "Tenant " + tenantId + " already has an implementation of " + port.getName()
                            + " (" + existing.getClass().getName() + "); "
                            + service.getClass().getName() + " cannot also provide it");
        }
        log.info("Registered module service for tenant {}: {} -> {}",
                tenantId, port.getName(), service.getClass().getName());
    }

    /**
     * Removes the exact instances a registration recorded, by identity — the values recorded at
     * registration are what must be handed back, mirroring
     * {@code BeforeSaveHookRegistry.removeTenantHooks}.
     */
    public void remove(String tenantId, Map<Class<?>, Object> servicesToRemove) {
        Map<Class<?>, Object> services = tenantServices.get(tenantId);
        if (services == null || servicesToRemove == null) {
            return;
        }
        servicesToRemove.forEach((port, service) -> {
            // Identity, not equals: Map.remove(k, v) compares by equals, so a service implemented
            // as a record would be evicted by any value-equal instance -- including one this load
            // never registered. The duplicate check in register() is likewise identity-based.
            Object current = services.get(port);
            if (current == service && services.remove(port, current)) {
                log.info("Removed module service for tenant {}: {}", tenantId, port.getName());
            }
        });
        if (services.isEmpty()) {
            tenantServices.remove(tenantId);
        }
    }

    /**
     * Resolves this tenant's module-provided implementation of {@code port}, or empty when no
     * installed module publishes one — in which case the caller keeps its compiled-in behaviour.
     */
    public <T> Optional<T> find(String tenantId, Class<T> port) {
        if (tenantId == null || port == null) {
            return Optional.empty();
        }
        Map<Class<?>, Object> services = tenantServices.get(tenantId);
        if (services == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(port.cast(services.get(port)));
    }

    /** True when this tenant has a module-provided implementation of the port. */
    public boolean has(String tenantId, Class<?> port) {
        Map<Class<?>, Object> services = tenantServices.get(tenantId);
        return services != null && services.containsKey(port);
    }

    /** Number of ports this tenant currently has module implementations for. */
    public int serviceCount(String tenantId) {
        Map<Class<?>, Object> services = tenantServices.get(tenantId);
        return services == null ? 0 : services.size();
    }

    /**
     * Rejects a port the platform does not itself define, or a duplicate loaded by another
     * classloader that shadows a platform class of the same name. Either way the platform could
     * never consume it.
     */
    private void requirePlatformPort(Class<?> port) {
        if (!port.isInterface()) {
            throw new IllegalArgumentException(
                    "Module services must be published under an interface, got: " + port.getName());
        }
        Class<?> platformView;
        try {
            platformView = Class.forName(
                    port.getName(), false, ModuleServiceRegistry.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Port " + port.getName() + " is not a platform type -- a module can only "
                            + "publish an implementation of an interface the platform defines", e);
        }
        if (platformView != port) {
            throw new IllegalArgumentException(
                    "Port " + port.getName() + " was loaded by another ClassLoader and shadows "
                            + "the platform type of the same name");
        }
    }
}
