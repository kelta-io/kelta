package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.ModuleManifestParser;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.worker.service.TenantSlugResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.Optional;

/**
 * Routes inbound calls to a tenant's loaded module: authenticated HTTP routes, unauthenticated
 * webhooks, and reads of the module's UI bundle. Each call binds the tenant (id and slug) before
 * entering module code.
 *
 * <p>Holds no per-load state; it resolves handlers from the {@link ActionHandlerRegistry} and
 * routes from the {@link ModuleRouteRegistry} that {@link ModuleLoader} populates.
 */
final class ModuleDispatcher {

    /**
     * Logs under {@link RuntimeModuleManager}'s name rather than this class's: this code was split
     * out of that class, and log queries and alerts select on the logger name.
     */
    private static final Logger log = LoggerFactory.getLogger(RuntimeModuleManager.class);

    private final ModuleStore moduleStore;
    private final ActionHandlerRegistry actionHandlerRegistry;
    private final ObjectMapper objectMapper;
    private final ModuleManifestParser manifestParser;
    private final ModuleJarService jarService;
    private final ModuleSignatureVerifier signatureVerifier;
    /** Resolves tenantId -> slug for webhook dispatch; may be null in tests. */
    private final TenantSlugResolver tenantSlugResolver;

    /** Routes each loaded module serves. Null in tests that do not exercise HTTP dispatch. */
    private ModuleRouteRegistry moduleRouteRegistry;

    ModuleDispatcher(ModuleStore moduleStore, ActionHandlerRegistry actionHandlerRegistry,
                     ObjectMapper objectMapper, ModuleManifestParser manifestParser,
                     ModuleJarService jarService, ModuleSignatureVerifier signatureVerifier,
                     TenantSlugResolver tenantSlugResolver) {
        this.moduleStore = moduleStore;
        this.actionHandlerRegistry = actionHandlerRegistry;
        this.objectMapper = objectMapper;
        this.manifestParser = manifestParser;
        this.jarService = jarService;
        this.signatureVerifier = signatureVerifier;
        this.tenantSlugResolver = tenantSlugResolver;
    }

    /**
     * Dispatches a raw inbound webhook to the {@code webhookHandlerKey} the module's manifest
     * names.
     *
     * <p><b>The platform authenticates nothing here.</b> The route is unauthenticated by design —
     * a payment processor or other external system cannot present a platform JWT — so the
     * handler owns its own trust anchor, typically an HMAC over the raw body verified against a
     * credential the module resolves itself. The {@code tenantId} in the path is untrusted input:
     * it selects which tenant's module (and therefore which secret) to dispatch to, nothing more.
     *
     * <p>Returns empty when the tenant has no such ACTIVE module, when that module declares no
     * webhook handler, or when its handler is not registered. All three collapse to one outcome
     * so an unauthenticated caller cannot tell them apart and enumerate a tenant's modules.
     *
     * @param tenantId  the tenant named in the path (untrusted)
     * @param moduleId  the module named in the path (untrusted)
     * @param rawBody   the unparsed request body — handlers that verify a body signature MUST use
     *                  this exact string, since re-serializing changes the bytes the HMAC covers
     * @param headers   inbound request headers (signature headers live here)
     * @return the handler's result, or empty if nothing was dispatched
     */
    /**
     * Dispatches an authenticated HTTP request to the module handler its manifest routes it to.
     *
     * <p>The counterpart of {@link #dispatchWebhook} for the authenticated prefix. Two differences
     * matter: the caller is a known platform user, so {@code userId} is bound and a handler can
     * scope to the actor; and the route table comes from {@link ModuleRouteRegistry}, which is
     * populated on load, so a disabled module stops answering at once.
     *
     * <p>Tenant context is bound with id <b>and slug</b>, as everywhere else the platform calls into
     * a module: a module cannot resolve a slug, and a null one silently reads the public schema
     * rather than failing.
     *
     * @return the handler's result, or empty when the module is not ACTIVE or serves no such route
     */
    public Optional<ActionResult> dispatchRoute(String tenantId, String moduleId, String userId,
                                                String method, String path,
                                                Map<String, Object> query, String rawBody) {
        if (moduleRouteRegistry == null) {
            return Optional.empty();
        }
        Optional<TenantModuleData> found = moduleStore.findByTenantAndModuleId(tenantId, moduleId);
        if (found.isEmpty() || !TenantModuleData.STATUS_ACTIVE.equals(found.get().status())) {
            return Optional.empty();
        }

        Optional<String> handlerKey = moduleRouteRegistry.resolve(tenantId, moduleId, method, path);
        if (handlerKey.isEmpty()) {
            // Log what IS registered. Knowing the route did not resolve is not the same as knowing
            // what was registered, and the difference is what turns a 404 into a diagnosis.
            log.warn("Module '{}' in tenant {} serves no route for '{} {}'. Registered: {}",
                moduleId, tenantId, method, path,
                moduleRouteRegistry.registeredKeys(tenantId, moduleId));
            return Optional.empty();
        }

        Optional<ActionHandler> handler = actionHandlerRegistry.getHandler(tenantId, handlerKey.get());
        if (handler.isEmpty()) {
            log.warn("Module '{}' of tenant {} routes {} {} to handler '{}', which is not registered",
                moduleId, tenantId, method, path, handlerKey.get());
            return Optional.empty();
        }

        Map<String, Object> input = new java.util.LinkedHashMap<>();
        if (query != null) {
            input.putAll(query);
        }
        // The JSON body has to become inputs too, not just ride along as rawBody. Without this a
        // POST handler reading its own documented fields finds nothing and answers "... is
        // required" against a request that supplied everything -- indistinguishable from real
        // validation, which is exactly how it reached production. Body wins over a same-named query
        // parameter: for a POST it is the primary source.
        input.putAll(parseBodyAsInputs(rawBody));
        // The flow engine passes a state envelope, and handlers read through ActionInputs, which
        // unwraps "input" when present. Matching that here means one handler serves both paths.
        Map<String, Object> resolved = Map.of(
            "input", input,
            "method", method,
            "path", path,
            "rawBody", rawBody == null ? "" : rawBody,
            "moduleId", moduleId);

        ActionContext context = ActionContext.builder()
            .tenantId(tenantId)
            .userId(userId)
            .resolvedData(resolved)
            .build();

        String tenantSlug = tenantSlugResolver == null ? null
            : tenantSlugResolver.resolveSlug(tenantId).orElse(null);
        return Optional.of(TenantContext.callWithTenant(tenantId, tenantSlug,
            () -> handler.get().execute(context)));
    }

    /**
     * The request body as handler inputs, or empty when there is nothing usable to read.
     *
     * <p>Only a JSON object contributes inputs. A body that is an array, a scalar, or malformed
     * yields nothing rather than throwing: the handler then reports its own missing-field error,
     * which is a better message than a parse failure from the dispatcher. {@code rawBody} is still
     * passed through untouched, because a webhook handler must verify a signature over the exact
     * bytes and cannot use a re-serialised map.
     */
    private Map<String, Object> parseBodyAsInputs(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            node.propertyStream().forEach(e -> values.put(e.getKey(), toPlainValue(e.getValue())));
            return values;
        } catch (RuntimeException e) {
            log.debug("Module route body was not readable as JSON, passing rawBody only: {}",
                e.getMessage());
            return Map.of();
        }
    }

    /** Unwraps a JSON scalar to the Java value a handler expects; anything else stays a node. */
    private static Object toPlainValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.stringValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.numberValue();
        }
        return value;
    }

    /** Injected via setter so the existing constructor overloads stay as they are. */
    public void setModuleRouteRegistry(ModuleRouteRegistry moduleRouteRegistry) {
        this.moduleRouteRegistry = moduleRouteRegistry;
    }

    public Optional<ActionResult> dispatchWebhook(String tenantId, String moduleId,
                                                  String rawBody, Map<String, String> headers) {
        Optional<TenantModuleData> found = moduleStore.findByTenantAndModuleId(tenantId, moduleId);
        if (found.isEmpty() || !TenantModuleData.STATUS_ACTIVE.equals(found.get().status())) {
            return Optional.empty();
        }
        if (found.get().isExternal()) {
            // An external module's webhook is proxied to its service from part 9d on; it has no
            // in-process handler to dispatch to.
            return Optional.empty();
        }

        String handlerKey;
        try {
            handlerKey = manifestParser.parse(found.get().manifest()).webhookHandlerKey();
        } catch (RuntimeException e) {
            log.warn("Module '{}' of tenant {} has an unreadable manifest — cannot dispatch webhook",
                moduleId, tenantId);
            return Optional.empty();
        }
        if (handlerKey == null || handlerKey.isBlank()) {
            return Optional.empty();
        }

        Optional<ActionHandler> handler = actionHandlerRegistry.getHandler(tenantId, handlerKey);
        if (handler.isEmpty()) {
            log.warn("Module '{}' of tenant {} declares webhook handler '{}' which is not "
                + "registered — the module may have fallen back to stubs", moduleId, tenantId, handlerKey);
            return Optional.empty();
        }

        ActionContext context = ActionContext.builder()
            .tenantId(tenantId)
            .resolvedData(Map.of(
                "rawBody", rawBody == null ? "" : rawBody,
                "headers", headers == null ? Map.of() : headers,
                "moduleId", moduleId))
            .build();

        // Bind the tenant for the handler's data access (Critical Rule 3). The webhook route is
        // unauthenticated, so nothing upstream binds one — and a module CANNOT bind it itself:
        // io.kelta.runtime.context is outside SandboxedModuleClassLoader's parent allowlist. An
        // unbound connection leaves app.current_tenant_id empty, which matches the admin_bypass
        // RLS policy, so a module's reads and writes would silently run unscoped across tenants.
        //
        // The SLUG matters as much as the id, and binding only the id is not enough:
        // PhysicalTableStorageAdapter#getTableRef resolves a tenant collection's schema from
        // TenantContext.getSlug(), and with no slug it falls back to the PUBLIC schema. Every
        // query then hits an unqualified table that does not exist there — the module's own
        // collections live in the tenant schema. Same reasoning as FlowWebhookController, which
        // resolves the slug for the identical reason.
        //
        // Binding before the handler verifies its signature is correct and necessary: resolving
        // the tenant's own signing secret is itself a tenant-scoped read. The path tenantId is
        // untrusted, but it only selects which tenant's secret must match — a forged request
        // fails the handler's own check and touches nothing.
        String tenantSlug = tenantSlugResolver == null
            ? null
            : tenantSlugResolver.resolveSlug(tenantId).orElse(null);
        if (tenantSlug == null) {
            log.warn("Could not resolve slug for tenant {} dispatching a webhook to module '{}' — "
                + "the module's tenant-schema queries will fail", tenantId, moduleId);
        }
        return Optional.of(TenantContext.callWithTenant(tenantId, tenantSlug,
            () -> handler.get().execute(context)));
    }

    /**
     * Reads a module's UI bundle from its verified JAR.
     *
     * <p>Streams the {@code uiBundlePath} resource out of the JAR the tenant already has
     * installed. That JAR passed signature verification at install and checksum + signature
     * re-verification on load, so this adds no new trust boundary — but it does serve
     * publisher-authored JavaScript same-origin to the admin UI, which is exactly what the
     * signature gate exists to make safe.
     *
     * <p>Empty when the tenant has no such ACTIVE module, the module declares no bundle, JAR
     * loading is unavailable, or the resource is absent from the JAR.
     */
    public Optional<byte[]> readUiBundle(String tenantId, String moduleId) {
        Optional<TenantModuleData> found = moduleStore.findByTenantAndModuleId(tenantId, moduleId);
        if (found.isEmpty() || !TenantModuleData.STATUS_ACTIVE.equals(found.get().status())) {
            return Optional.empty();
        }
        TenantModuleData module = found.get();
        if (module.s3Key() == null || jarService == null) {
            return Optional.empty();
        }

        String bundlePath;
        try {
            bundlePath = manifestParser.parse(module.manifest()).uiBundlePath();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (bundlePath == null || bundlePath.isBlank()) {
            return Optional.empty();
        }

        try {
            URL jarUrl = jarService.downloadJarToCache(module.s3Key());
            // Same gate the classloader path runs: never serve bytes out of a JAR whose
            // checksum or signature no longer verifies.
            ModuleLoader.verifyDownloadedJar(moduleStore, signatureVerifier, module, jarUrl);
            try (java.util.jar.JarFile jar =
                     new java.util.jar.JarFile(java.nio.file.Path.of(jarUrl.toURI()).toFile())) {
                java.util.jar.JarEntry entry = jar.getJarEntry(bundlePath);
                if (entry == null) {
                    log.warn("Module '{}' of tenant {} declares uiBundlePath '{}' which is not in "
                        + "its JAR", moduleId, tenantId, bundlePath);
                    return Optional.empty();
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    return Optional.of(in.readAllBytes());
                }
            }
        } catch (Exception e) {
            log.warn("Could not read UI bundle for module '{}' of tenant {}: {}",
                moduleId, tenantId, e.getMessage());
            return Optional.empty();
        }
    }
}
