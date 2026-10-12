package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.service.OutboundUrlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs OIDC Discovery when an OIDC provider is saved: fetches the issuer's
 * {@code /.well-known/openid-configuration} and fills the endpoint fields an administrator did
 * not set, recording the outcome in {@code discoveryStatus}.
 *
 * <p><b>When it runs.</b> On create, and on update when the issuer changes or the last discovery
 * did not succeed (so saving again retries). An update that keeps a successfully discovered
 * issuer does not refetch.
 *
 * <p><b>A failed discovery never blocks the save.</b> The status records why instead:
 * <ul>
 *   <li>{@code discovered} — the document was fetched, its {@code issuer} matches and the
 *       endpoints were filled;</li>
 *   <li>{@code error} — the fetch failed (host does not resolve, unreachable, timeout, non-200,
 *       oversized, not JSON) or the document names a different issuer;</li>
 *   <li>{@code blocked} — {@link OutboundUrlGuard} refused the URL (non-public address, bad
 *       scheme, credentials in the URL), so nothing was fetched.</li>
 * </ul>
 * Not blocking is deliberate: kelta-auth resolves the endpoints itself at login time (overrides
 * first, then its own discovery), so a stored result is a convenience; the worker's network view
 * can differ from kelta-auth's; and an administrator can always enter the endpoints by hand.
 *
 * <p><b>Overrides.</b> An endpoint value is an administrator override, and is kept, when it is
 * non-blank — except that on an issuer change a value still equal to what the previous
 * successful discovery stored is treated as discovered: it belonged to the old issuer, so it is
 * replaced, or cleared when the new issuer cannot be discovered.
 *
 * <p><b>Outbound safety.</b> The issuer is tenant-supplied, so the fetch goes through
 * {@link OutboundUrlGuard}, never follows redirects, has connect and overall deadlines, and reads
 * at most {@link #MAX_DOCUMENT_BYTES}. The document is read as a JSON tree (no reflective
 * binding, so nothing to register for the native image). Discovered values are stored only when
 * they are absolute http(s) URLs that fit their column.
 */
@Component
public class OidcDiscoveryHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(OidcDiscoveryHook.class);

    static final String COLLECTION = "oidc-providers";
    static final String ISSUER_FIELD = "issuer";
    static final String STATUS_FIELD = "discoveryStatus";
    static final String STATUS_DISCOVERED = "discovered";
    static final String STATUS_ERROR = "error";
    static final String STATUS_BLOCKED = "blocked";

    static final String WELL_KNOWN_PATH = "/.well-known/openid-configuration";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_DOCUMENT_BYTES = 256 * 1024;
    /** Column width of every endpoint field on {@code oidc-providers}. */
    static final int MAX_ENDPOINT_LENGTH = 500;

    /** Endpoint field on the collection → its key in the discovery document. */
    static final Map<String, String> ENDPOINTS = endpoints();

    private static Map<String, String> endpoints() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("jwksUri", "jwks_uri");
        m.put("authorizationUri", "authorization_endpoint");
        m.put("tokenUri", "token_endpoint");
        m.put("userinfoUri", "userinfo_endpoint");
        m.put("endSessionUri", "end_session_endpoint");
        return Map.copyOf(m);
    }

    private final ObjectMapper objectMapper;
    private final OutboundUrlGuard urlGuard;
    private final HttpClient httpClient;

    public OidcDiscoveryHook(ObjectMapper objectMapper, OutboundUrlGuard urlGuard) {
        this.objectMapper = objectMapper;
        this.urlGuard = urlGuard;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String getCollectionName() {
        return COLLECTION;
    }

    /** After {@link OidcProviderSecretHook} (-100). */
    @Override
    public int getOrder() {
        return -50;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        record.remove(STATUS_FIELD); // the platform's to set, not the caller's
        String issuer = text(record.get(ISSUER_FIELD));
        if (issuer == null) {
            return BeforeSaveResult.ok();
        }
        Map<String, Boolean> overridden = new HashMap<>();
        for (String field : ENDPOINTS.keySet()) {
            overridden.put(field, text(record.get(field)) != null);
        }
        return discover(issuer, overridden, Map.of());
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        record.remove(STATUS_FIELD);
        Map<String, Object> before = previous != null ? previous : Map.of();
        String previousIssuer = text(before.get(ISSUER_FIELD));
        String issuer = record.containsKey(ISSUER_FIELD) ? text(record.get(ISSUER_FIELD)) : previousIssuer;
        if (issuer == null) {
            return BeforeSaveResult.ok();
        }
        boolean issuerChanged = !issuer.equals(previousIssuer);
        boolean previouslyDiscovered = STATUS_DISCOVERED.equals(before.get(STATUS_FIELD));
        if (!issuerChanged && previouslyDiscovered) {
            return BeforeSaveResult.ok();
        }

        Map<String, Boolean> overridden = new HashMap<>();
        Map<String, Object> clearIfUndiscovered = new HashMap<>();
        for (String field : ENDPOINTS.keySet()) {
            String stored = text(before.get(field));
            String effective = record.containsKey(field) ? text(record.get(field)) : stored;
            boolean staleDiscovered = issuerChanged && previouslyDiscovered
                    && effective != null && effective.equals(stored);
            overridden.put(field, effective != null && !staleDiscovered);
            if (staleDiscovered) {
                clearIfUndiscovered.put(field, null);
            }
        }
        return discover(issuer, overridden, clearIfUndiscovered);
    }

    private BeforeSaveResult discover(String issuer, Map<String, Boolean> overridden,
                                      Map<String, Object> clearIfUndiscovered) {
        Map<String, Object> updates = new HashMap<>(clearIfUndiscovered);

        URI url;
        try {
            url = urlGuard.check(discoveryUrl(issuer));
        } catch (OutboundUrlGuard.RejectedUrlException e) {
            log.warn("OIDC discovery not attempted for issuer {}: {}", issuer, e.getMessage());
            // A host that does not resolve is a failed discovery, not a refused one.
            updates.put(STATUS_FIELD, e.reason() == OutboundUrlGuard.Reason.UNRESOLVABLE
                    ? STATUS_ERROR : STATUS_BLOCKED);
            return BeforeSaveResult.withFieldUpdates(updates);
        }

        JsonNode document;
        try {
            document = fetch(url);
        } catch (DiscoveryFailure e) {
            log.warn("OIDC discovery failed for issuer {}: {}", issuer, e.getMessage());
            updates.put(STATUS_FIELD, STATUS_ERROR);
            return BeforeSaveResult.withFieldUpdates(updates);
        }

        String documentIssuer = text(document.get("issuer"));
        if (documentIssuer == null || !withoutTrailingSlash(documentIssuer).equals(withoutTrailingSlash(issuer))) {
            log.warn("OIDC discovery for issuer {} returned a document for a different issuer", issuer);
            updates.put(STATUS_FIELD, STATUS_ERROR);
            return BeforeSaveResult.withFieldUpdates(updates);
        }

        for (Map.Entry<String, String> endpoint : ENDPOINTS.entrySet()) {
            if (Boolean.TRUE.equals(overridden.get(endpoint.getKey()))) {
                continue;
            }
            String discovered = endpointValue(document.get(endpoint.getValue()));
            if (discovered != null) {
                updates.put(endpoint.getKey(), discovered);
            }
        }
        updates.put(STATUS_FIELD, STATUS_DISCOVERED);
        log.info("OIDC discovery succeeded for issuer {}", issuer);
        return BeforeSaveResult.withFieldUpdates(updates);
    }

    private JsonNode fetch(URI url) throws DiscoveryFailure {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> pending = httpClient.sendAsync(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_DOCUMENT_BYTES));
        HttpResponse<byte[]> response;
        try {
            // One deadline for headers and body together, so a slow body cannot hold the save.
            response = pending.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new DiscoveryFailure("timed out after " + REQUEST_TIMEOUT.toSeconds() + "s");
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new DiscoveryFailure("interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new DiscoveryFailure(cause.getClass().getSimpleName()
                    + (cause.getMessage() != null ? ": " + cause.getMessage() : ""));
        }
        if (response.statusCode() != 200) {
            throw new DiscoveryFailure("HTTP " + response.statusCode());
        }
        JsonNode document;
        try {
            document = objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new DiscoveryFailure("the response is not JSON");
        }
        if (document == null || !document.isObject()) {
            throw new DiscoveryFailure("the response is not a JSON object");
        }
        return document;
    }

    static String discoveryUrl(String issuer) {
        return withoutTrailingSlash(issuer) + WELL_KNOWN_PATH;
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** A discovered endpoint worth storing: an absolute http(s) URL that fits its column. */
    private static String endpointValue(JsonNode node) {
        String value = text(node);
        if (value == null || value.length() > MAX_ENDPOINT_LENGTH) {
            return null;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return value;
    }

    private static String text(Object value) {
        if (value instanceof JsonNode node) {
            return node.isString() ? text(node.asString()) : null;
        }
        if (value == null) {
            return null;
        }
        String s = Objects.toString(value).trim();
        return s.isEmpty() ? null : s;
    }

    /** Why a fetch produced no usable document; the message is safe to log. */
    private static final class DiscoveryFailure extends Exception {
        DiscoveryFailure(String message) {
            super(message, null, false, false);
        }
    }
}
