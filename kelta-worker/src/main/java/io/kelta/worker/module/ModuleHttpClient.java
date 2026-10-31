package io.kelta.worker.module;

import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.outbound.ModuleHostGuard;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The platform's HTTP client for external modules ({@code docs/module-protocol/openapi.yaml}):
 * the manifest fetch that plans an install, {@code lifecycle}, and (part 9d) {@link #invoke} for
 * every other protocol call — actions, hooks, service ports, health, and the route and webhook
 * proxies. Breaker, token and metrics sit one layer up, in {@link ExternalModuleInvoker}.
 *
 * <p>Every target goes through the {@link ModuleHostGuard} — never the general
 * {@code OutboundUrlGuard}, whose private-CIDR allow-list also opens flow callouts — and is
 * re-checked on every request, because DNS can change after install. <b>No redirect is
 * followed</b> ({@code maxRedirects = 0}): a 3xx is a failure, so a module cannot bounce a signed
 * request, or the manifest fetch, to a host the guard never saw.
 *
 * <p>Every protocol request carries {@code Authorization: Bearer <assertion>}
 * ({@link ModuleAssertionSigner}) signed over the exact URL and body bytes sent, the install's
 * {@code Kelta-Access-Token}, and {@code traceparent} when a span is current. The manifest fetch
 * carries neither credential: it is public by contract and runs before an install exists.
 *
 * <p>{@link #invoke} runs on a second client with a 2 s connect timeout and the call's own
 * deadline (the manifest's {@code timeoutMs}), so a slow module holds a flow step, a write or a
 * proxied request for exactly as long as the module declared and no longer.
 */
public class ModuleHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ModuleHttpClient.class);

    /** Where a module publishes its manifest, relative to its base URL. */
    public static final String MANIFEST_PATH = "/.well-known/kelta-module.json";
    static final String LIFECYCLE_PATH = "/kelta/v1/lifecycle";
    static final String ACTIONS_PATH = "/kelta/v1/actions/";
    static final String HOOKS_PATH = "/kelta/v1/hooks";
    static final String ENTITLEMENTS_PATH = "/kelta/v1/services/entitlements/resolve";
    static final String ROUTES_PATH = "/kelta/v1/routes/";
    static final String WEBHOOKS_PATH = "/kelta/v1/webhooks";
    static final String HEALTH_PATH = "/kelta/v1/health";
    public static final String ACCESS_TOKEN_HEADER = "Kelta-Access-Token";

    /** A manifest embeds a metadata package; 1 MiB is far above any real one. */
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEADLINE = Duration.ofSeconds(15);
    /** Connect timeout of every invocation (spec §3.6, failure handling). */
    static final Duration INVOKE_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** The longest any invocation may take: the action {@code timeoutMs} maximum. */
    static final Duration INVOKE_MAX_DEADLINE = Duration.ofMillis(120_000);

    /**
     * A module request that did not get a 2xx; {@code status} is 0 when nothing was answered, and
     * {@code timedOut} says the module did not answer within the call's deadline.
     */
    public static class ModuleCallException extends RuntimeException {
        private final int status;
        private final boolean timedOut;

        public ModuleCallException(int status, String message) {
            this(status, message, false);
        }

        public ModuleCallException(int status, String message, boolean timedOut) {
            super(message);
            this.status = status;
            this.timedOut = timedOut;
        }

        public int status() {
            return status;
        }

        public boolean timedOut() {
            return timedOut;
        }
    }

    /**
     * One protocol call.
     *
     * @param method      HTTP method
     * @param path        path under the base URL, starting {@code /kelta/v1/}
     * @param rawQuery    the raw query string to append, or {@code null}
     * @param body        exact body bytes, or {@code null} for none
     * @param contentType the body's {@code Content-Type}, or {@code null}
     * @param headers     further headers (an {@code Idempotency-Key}, a webhook's allowlisted
     *                    headers); never the assertion, token or trace context, which are added
     * @param timeout     the deadline for the whole exchange, at most 120 s
     * @param actor       the user whose action caused the call ({@code act}), or {@code null}
     */
    public record Call(String method, String path, String rawQuery, byte[] body, String contentType,
                       Map<String, String> headers, Duration timeout, ModuleAssertionSigner.Actor actor) {
        public Call {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(timeout, "timeout");
            headers = headers == null ? Map.of() : headers;
            if (timeout.compareTo(INVOKE_MAX_DEADLINE) > 0) {
                timeout = INVOKE_MAX_DEADLINE;
            }
        }

        /** A JSON {@code POST} to {@code path}. */
        public static Call postJson(String path, byte[] body, Duration timeout, ModuleAssertionSigner.Actor actor) {
            return new Call("POST", path, null, body, "application/json", Map.of(), timeout, actor);
        }
    }

    /** The {@code lifecycle} request body (protocol {@code LifecycleEvent}). */
    public record LifecycleEvent(String event, String tenantId, String moduleVersion,
                                 String fromVersion, Boolean purgeData, Map<String, Object> settings) {

        public static LifecycleEvent of(String event, TenantModuleData module) {
            return new LifecycleEvent(event, module.tenantId(), module.version(), null, null, null);
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("event", event);
            json.put("tenantId", tenantId);
            json.put("moduleVersion", moduleVersion);
            if (fromVersion != null) {
                json.put("fromVersion", fromVersion);
            }
            if (purgeData != null) {
                json.put("purgeData", purgeData);
            }
            if (settings != null) {
                json.put("settings", settings);
            }
            return json;
        }
    }

    private final ModuleHostGuard guard;
    private final OutboundHttpClient http;
    private final OutboundHttpClient invocationHttp;
    private final ModuleAssertionSigner signer;
    private final ObjectMapper objectMapper;

    public ModuleHttpClient(ModuleHostGuard guard, ModuleAssertionSigner signer, ObjectMapper objectMapper) {
        this.guard = Objects.requireNonNull(guard);
        this.http = new OutboundHttpClient(guard,
                new OutboundHttpClient.Limits(CONNECT_TIMEOUT, DEADLINE, MAX_RESPONSE_BYTES, 0));
        this.invocationHttp = new OutboundHttpClient(guard,
                new OutboundHttpClient.Limits(INVOKE_CONNECT_TIMEOUT, INVOKE_MAX_DEADLINE, MAX_RESPONSE_BYTES, 0));
        this.signer = Objects.requireNonNull(signer);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * Normalises an admin-typed base URL: trimmed, no trailing slash, no query or fragment, and
     * accepted by the module-host guard.
     *
     * @throws IllegalArgumentException when it is not an acceptable module base URL
     */
    public String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl is not a valid URL");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not carry a query or fragment");
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            guard.check(normalized);
        } catch (OutboundUrlGuard.RejectedUrlException e) {
            throw new IllegalArgumentException("baseUrl refused: " + e.getMessage());
        }
        return normalized;
    }

    /**
     * Fetches the module's manifest JSON.
     *
     * @throws ModuleCallException when it is not served with a 200 or is too large
     */
    public String fetchManifest(String baseUrl) {
        String url = baseUrl + MANIFEST_PATH;
        OutboundHttpClient.Response response = send("GET", url, Map.of("Accept", "application/json"), null);
        if (response.statusCode() != 200) {
            throw new ModuleCallException(response.statusCode(), "GET " + MANIFEST_PATH + " answered "
                    + response.statusCode() + (isRedirect(response.statusCode()) ? " (redirects are not followed)" : ""));
        }
        if (response.truncated()) {
            throw new ModuleCallException(response.statusCode(),
                    "the manifest is larger than " + MAX_RESPONSE_BYTES + " bytes");
        }
        return response.bodyAsString();
    }

    /**
     * Sends a {@code lifecycle} notification.
     *
     * @throws ModuleCallException on a non-2xx answer or when the module could not be reached
     */
    public void lifecycle(TenantModuleData module, String tenantSlug, LifecycleEvent event, String accessToken) {
        byte[] body = objectMapper.writeValueAsBytes(event.toJson());
        String url = module.baseUrl() + LIFECYCLE_PATH;
        Map<String, String> headers = signedHeaders(module, tenantSlug, null, "POST", url, body, accessToken);
        headers.put("Content-Type", "application/json");
        OutboundHttpClient.Response response = send("POST", url, headers, body);
        if (!response.isSuccess()) {
            throw new ModuleCallException(response.statusCode(), "lifecycle '" + event.event()
                    + "' answered " + response.statusCode() + snippet(response));
        }
        log.debug("Module '{}' accepted lifecycle '{}' for tenant {}", module.moduleId(), event.event(),
                module.tenantId());
    }

    /**
     * Sends one protocol call, signed over the exact URL and body bytes, with the install's
     * access token and the current trace context. Any status comes back as the response —
     * whether a non-2xx is a failure depends on the call ({@link ExternalModuleInvoker}).
     * Redirects are not followed; a 3xx is returned like any other status.
     *
     * @throws ModuleCallException when nothing was answered: refused by the host guard, could not
     *         connect, or timed out ({@link ModuleCallException#timedOut()}); also when the answer
     *         was larger than 1 MiB
     */
    public OutboundHttpClient.Response invoke(TenantModuleData module, String tenantSlug, Call call,
                                              String accessToken) {
        String url = module.baseUrl() + call.path() + (call.rawQuery() == null || call.rawQuery().isEmpty()
                ? "" : "?" + call.rawQuery());
        byte[] body = call.body();
        Map<String, String> headers = new LinkedHashMap<>(call.headers());
        headers.putAll(signedHeaders(module, tenantSlug, call.actor(), call.method(), url, body, accessToken));
        if (call.contentType() != null) {
            headers.put("Content-Type", call.contentType());
        }
        headers.putIfAbsent("Accept", "application/json");
        OutboundHttpClient.Response response;
        try {
            response = invocationHttp.send(new OutboundHttpClient.Request(call.method(), url, headers, body,
                    call.timeout()));
        } catch (OutboundUrlGuard.RejectedUrlException e) {
            throw new ModuleCallException(0, "module URL refused: " + e.getMessage());
        } catch (OutboundHttpClient.OutboundRequestException e) {
            throw new ModuleCallException(0, call.method() + " " + call.path() + " failed: " + e.getMessage(),
                    e.kind() == OutboundHttpClient.OutboundRequestException.Kind.TIMEOUT);
        }
        if (response.truncated()) {
            throw new ModuleCallException(response.statusCode(), call.method() + " " + call.path()
                    + " answered more than " + MAX_RESPONSE_BYTES + " bytes");
        }
        return response;
    }

    private Map<String, String> signedHeaders(TenantModuleData module, String tenantSlug,
                                              ModuleAssertionSigner.Actor actor, String method,
                                              String url, byte[] body, String accessToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + signer.sign(new ModuleAssertionSigner.Assertion(
                module.moduleId(), module.id(), module.tenantId(), tenantSlug, actor, method, url, body)));
        if (accessToken != null) {
            headers.put(ACCESS_TOKEN_HEADER, accessToken);
        }
        String traceparent = traceparent();
        if (traceparent != null) {
            headers.put("traceparent", traceparent);
        }
        return headers;
    }

    private OutboundHttpClient.Response send(String method, String url, Map<String, String> headers, byte[] body) {
        try {
            return http.send(new OutboundHttpClient.Request(method, url, headers, body));
        } catch (OutboundUrlGuard.RejectedUrlException e) {
            throw new ModuleCallException(0, "module URL refused: " + e.getMessage());
        } catch (OutboundHttpClient.OutboundRequestException e) {
            throw new ModuleCallException(0, method + " " + URI.create(url).getPath() + " failed: "
                    + e.getMessage());
        }
    }

    /** W3C trace context of the current span, so the module's spans join the platform trace. */
    static String traceparent() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-"
                + context.getTraceFlags().asHex();
    }

    private static boolean isRedirect(int status) {
        return status >= 300 && status < 400;
    }

    /** The start of an error body, flattened, for {@code last_error}. */
    static String snippet(OutboundHttpClient.Response response) {
        String body = response.bodyAsString();
        if (body == null || body.isBlank()) {
            return "";
        }
        String flat = body.strip().replaceAll("\\s+", " ");
        return ": " + (flat.length() > 200 ? flat.substring(0, 200) + "…" : flat);
    }
}
