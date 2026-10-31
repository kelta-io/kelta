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
 * The platform's HTTP client for external modules ({@code docs/module-protocol/openapi.yaml}).
 * Part 9c needs two calls: the manifest fetch that plans an install, and {@code lifecycle}.
 * Part 9d adds actions, hooks, services and the proxies on the same request path.
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
 */
public class ModuleHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ModuleHttpClient.class);

    /** Where a module publishes its manifest, relative to its base URL. */
    public static final String MANIFEST_PATH = "/.well-known/kelta-module.json";
    static final String LIFECYCLE_PATH = "/kelta/v1/lifecycle";
    public static final String ACCESS_TOKEN_HEADER = "Kelta-Access-Token";

    /** A manifest embeds a metadata package; 1 MiB is far above any real one. */
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEADLINE = Duration.ofSeconds(15);

    /** A module request that did not get a 2xx; {@code status} is 0 when nothing was answered. */
    public static class ModuleCallException extends RuntimeException {
        private final int status;

        public ModuleCallException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
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
    private final ModuleAssertionSigner signer;
    private final ObjectMapper objectMapper;

    public ModuleHttpClient(ModuleHostGuard guard, ModuleAssertionSigner signer, ObjectMapper objectMapper) {
        this.guard = Objects.requireNonNull(guard);
        this.http = new OutboundHttpClient(guard,
                new OutboundHttpClient.Limits(CONNECT_TIMEOUT, DEADLINE, MAX_RESPONSE_BYTES, 0));
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
        Map<String, String> headers = signedHeaders(module, tenantSlug, "POST", url, body, accessToken);
        headers.put("Content-Type", "application/json");
        OutboundHttpClient.Response response = send("POST", url, headers, body);
        if (!response.isSuccess()) {
            throw new ModuleCallException(response.statusCode(), "lifecycle '" + event.event()
                    + "' answered " + response.statusCode() + snippet(response));
        }
        log.debug("Module '{}' accepted lifecycle '{}' for tenant {}", module.moduleId(), event.event(),
                module.tenantId());
    }

    private Map<String, String> signedHeaders(TenantModuleData module, String tenantSlug, String method,
                                              String url, byte[] body, String accessToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + signer.sign(new ModuleAssertionSigner.Assertion(
                module.moduleId(), module.id(), module.tenantId(), tenantSlug, null, method, url, body)));
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
    private static String snippet(OutboundHttpClient.Response response) {
        String body = response.bodyAsString();
        if (body == null || body.isBlank()) {
            return "";
        }
        String flat = body.strip().replaceAll("\\s+", " ");
        return ": " + (flat.length() > 200 ? flat.substring(0, 200) + "…" : flat);
    }
}
