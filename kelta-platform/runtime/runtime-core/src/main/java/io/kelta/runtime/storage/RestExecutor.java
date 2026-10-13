package io.kelta.runtime.storage;

import java.util.Map;

/**
 * Minimal HTTP seam used by {@link ExternalRestStorageAdapter} to talk to a remote
 * REST backend. Kept deliberately tiny and dependency-free so the adapter's
 * request-building, response-mapping, and error handling can be unit-tested with a
 * fake executor — and so runtime-core need not pick a concrete HTTP client. The worker's
 * implementation sends through {@link io.kelta.runtime.outbound.OutboundHttpClient}.
 *
 * @since 1.0.0
 */
public interface RestExecutor {

    /**
     * Perform an HTTP exchange. Implementations must NOT throw on non-2xx status —
     * they return the status in {@link RestResponse} so the adapter can map 404 → empty,
     * etc. They may throw only on transport failure (DNS, connect, timeout) or when the target
     * may not be fetched (the worker's implementation refuses non-public addresses).
     *
     * @param request the request to send
     * @return the response (status + raw body)
     */
    RestResponse exchange(RestRequest request);

    /** An outbound HTTP request. {@code body} is null for GET/DELETE. */
    record RestRequest(String method, String url, Map<String, String> headers, String body) {}

    /** An HTTP response: status code and raw (possibly empty) body. */
    record RestResponse(int status, String body) {}
}
