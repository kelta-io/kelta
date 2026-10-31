package io.kelta.worker.module;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.service.EntitlementProvider;
import io.kelta.runtime.module.service.MemberEntitlements;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.module.ExternalModuleRegistry.Endpoint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An external module's {@code entitlements} service port
 * ({@code specs/module-platform/9-external-modules.md} §3.6): registered in
 * {@code ModuleServiceRegistry} for its tenant while the install is callable, and answered by
 * {@code POST {baseUrl}/kelta/v1/services/entitlements/resolve}.
 *
 * <p><b>Cached, per the manifest.</b> A JAR provider is never cached by the platform
 * ({@code ModuleEntitlementService}); an external one is called over the network on the alert and
 * watch paths, so the module opts in to a cache with {@code services[].cacheTtlSeconds}
 * (0–300, default 60; 0 = none). Only answers are cached, per member, on this pod — never a
 * failure — and the cache is dropped when the install is re-registered (an upgrade). A module that
 * needs a change to take effect sooner declares a shorter TTL.
 *
 * <p><b>Failure.</b> Every failure throws; {@code ModuleEntitlementService} turns it into
 * {@link MemberEntitlements#EMPTY} (the restrictive answer) with a loud log.
 */
final class RemoteEntitlementProvider implements EntitlementProvider {

    static final Duration TIMEOUT = Duration.ofSeconds(2);
    static final int MAX_CACHED_MEMBERS = 10_000;

    private final String tenantId;
    private final String moduleId;
    private final ExternalModuleRegistry registry;
    private final ExternalModuleInvoker invoker;
    private final ObjectMapper objectMapper;
    private volatile CachedAnswers cache;

    private record CachedAnswers(int ttlSeconds, Cache<String, MemberEntitlements> answers) {}

    RemoteEntitlementProvider(String tenantId, String moduleId, ExternalModuleRegistry registry,
                              ExternalModuleInvoker invoker, ObjectMapper objectMapper) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.moduleId = Objects.requireNonNull(moduleId);
        this.registry = Objects.requireNonNull(registry);
        this.invoker = Objects.requireNonNull(invoker);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public MemberEntitlements resolve(String tenantId, String userId) {
        if (!this.tenantId.equals(tenantId)) {
            // Registered per tenant, so this cannot happen; refuse rather than answer for another.
            return MemberEntitlements.EMPTY;
        }
        Endpoint endpoint = registry.endpoint(tenantId, moduleId).orElseThrow(() ->
                ModuleUnavailableException.of(moduleId, "Module '" + moduleId + "' is not installed"));
        int ttl = endpoint.manifest().services().stream()
                .filter(s -> ExternalModuleRegistry.ENTITLEMENTS_PORT.equals(s.port()))
                .mapToInt(ExternalModuleManifest.Service::cacheTtlSeconds).findFirst().orElse(0);
        Cache<String, MemberEntitlements> answers = answers(ttl);
        if (answers != null) {
            MemberEntitlements cached = answers.getIfPresent(userId);
            if (cached != null) {
                return cached;
            }
        }
        byte[] body = objectMapper.writeValueAsBytes(Map.of("userId", userId));
        MemberEntitlements resolved = invoker.call(endpoint.row(), ExternalModuleInvoker.Kind.SERVICE,
                ModuleHttpClient.Call.postJson(ModuleHttpClient.ENTITLEMENTS_PATH, body, TIMEOUT,
                        ExternalModuleRegistry.currentActor(null)), this::read);
        if (answers != null) {
            answers.put(userId, resolved);
        }
        return resolved;
    }

    /** Drops every cached answer (the install was re-registered). */
    void invalidate() {
        cache = null;
    }

    private Cache<String, MemberEntitlements> answers(int ttlSeconds) {
        if (ttlSeconds <= 0) {
            return null;
        }
        CachedAnswers current = cache;
        if (current == null || current.ttlSeconds() != ttlSeconds) {
            current = new CachedAnswers(ttlSeconds, Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                    .maximumSize(MAX_CACHED_MEMBERS)
                    .build());
            cache = current;
        }
        return current.answers();
    }

    private MemberEntitlements read(OutboundHttpClient.Response response) {
        JsonNode json = ExternalModuleRegistry.readObject(objectMapper, response);
        JsonNode values = json.get("values");
        if (values == null || !values.isObject()) {
            throw new ExternalModuleInvoker.InvalidResponseException("MemberEntitlements.values must be an object");
        }
        // MemberEntitlements copies with Map.copyOf, which refuses nulls: a null value is an absent key.
        Map<String, Object> present = new LinkedHashMap<>();
        objectMapper.convertValue(values, ExternalModuleRegistry.MAP_TYPE).forEach((key, value) -> {
            if (value != null) {
                present.put(key, value);
            }
        });
        return new MemberEntitlements(text(json.get("planCode")), text(json.get("subscriptionStatus")), present);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new ExternalModuleInvoker.InvalidResponseException("planCode and subscriptionStatus must be strings");
        }
        return node.stringValue();
    }
}
