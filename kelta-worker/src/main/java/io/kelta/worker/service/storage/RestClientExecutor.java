package io.kelta.worker.service.storage;

import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.runtime.storage.RestExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link RestExecutor} used by {@code ExternalRestStorageAdapter} for external-rest connectors.
 *
 * <p>The connector's {@code baseUrl} is tenant configuration, so every request goes through
 * {@link OutboundHttpClient} with the worker's {@link OutboundUrlGuard}: a host that resolves to a
 * non-public address is refused unless allow-listed, {@code GET} redirects are followed up to
 * {@value #MAX_REDIRECTS} hops (each re-validated), and the call is bounded by
 * {@link #CONNECT_TIMEOUT} / {@link #REQUEST_DEADLINE} and a {@value #MAX_RESPONSE_BYTES}-byte
 * body. A larger response is a failure — the adapter needs the whole document.
 *
 * <p>Non-2xx responses are NOT raised as exceptions — the adapter inspects the status itself
 * (e.g. 404 → empty). Refusals, timeouts and transport failures throw, and the adapter reports
 * them as a transport failure.
 */
@Component
public class RestClientExecutor implements RestExecutor {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_DEADLINE = Duration.ofSeconds(30);
    static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    static final int MAX_REDIRECTS = 3;

    private final OutboundHttpClient httpClient;

    @Autowired
    public RestClientExecutor(OutboundUrlGuard urlGuard) {
        this(new OutboundHttpClient(urlGuard, new OutboundHttpClient.Limits(
                CONNECT_TIMEOUT, REQUEST_DEADLINE, MAX_RESPONSE_BYTES, MAX_REDIRECTS)));
    }

    /** With a pre-built client — tests use it to shorten the limits. */
    RestClientExecutor(OutboundHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public RestResponse exchange(RestRequest request) {
        OutboundHttpClient.Response response = httpClient.send(new OutboundHttpClient.Request(
                request.method(), request.url(), request.headers(),
                request.body() == null ? null : request.body().getBytes(StandardCharsets.UTF_8)));
        if (response.truncated()) {
            throw new OutboundHttpClient.OutboundRequestException(OutboundHttpClient.OutboundRequestException.Kind.FAILED,
                    "the response is larger than " + httpClient.limits().maxResponseBytes() + " bytes");
        }
        return new RestResponse(response.statusCode(), response.bodyAsString());
    }
}
