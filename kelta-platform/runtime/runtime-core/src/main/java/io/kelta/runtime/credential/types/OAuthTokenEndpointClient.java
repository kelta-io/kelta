package io.kelta.runtime.credential.types;

import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Posts a form to an OAuth 2.0 token endpoint. The token URL is credential metadata a tenant
 * administrator entered, so the request goes through {@link OutboundHttpClient}: a host that
 * resolves to a non-public address is refused unless the service's {@link OutboundUrlGuard}
 * allow-lists it, redirects are not followed (a token endpoint answers directly), and the call is
 * bounded by {@link #CONNECT_TIMEOUT} / {@link #REQUEST_DEADLINE} and a
 * {@value #MAX_RESPONSE_BYTES}-byte response.
 */
final class OAuthTokenEndpointClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_DEADLINE = Duration.ofSeconds(15);
    static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final OutboundHttpClient httpClient;

    OAuthTokenEndpointClient(OutboundHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Uses the service's {@link OutboundUrlGuard} bean; a service without one gets a guard that
     * allows no private range.
     */
    static OAuthTokenEndpointClient from(ObjectProvider<OutboundUrlGuard> guard) {
        OutboundUrlGuard urlGuard = guard == null ? null : guard.getIfAvailable();
        return new OAuthTokenEndpointClient(new OutboundHttpClient(
                urlGuard != null ? urlGuard : new OutboundUrlGuard(""),
                new OutboundHttpClient.Limits(CONNECT_TIMEOUT, REQUEST_DEADLINE, MAX_RESPONSE_BYTES, 0)));
    }

    /**
     * @return the response; the body is complete
     * @throws IllegalStateException when the URL is refused, the request fails or the response
     *         is too large — the message is safe to show an administrator
     */
    OutboundHttpClient.Response postForm(String tokenUrl, String form) {
        OutboundHttpClient.Response response;
        try {
            response = httpClient.send(new OutboundHttpClient.Request("POST", tokenUrl, Map.of(
                    "Content-Type", "application/x-www-form-urlencoded",
                    "Accept", "application/json"), form.getBytes(StandardCharsets.UTF_8)));
        } catch (OutboundUrlGuard.RejectedUrlException e) {
            throw new IllegalStateException("token URL refused: " + e.getMessage());
        } catch (OutboundHttpClient.OutboundRequestException e) {
            throw new IllegalStateException("token request failed: " + e.getMessage());
        }
        if (response.truncated()) {
            throw new IllegalStateException(
                    "token response is larger than " + MAX_RESPONSE_BYTES + " bytes");
        }
        return response;
    }
}
