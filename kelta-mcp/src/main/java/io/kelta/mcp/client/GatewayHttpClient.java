package io.kelta.mcp.client;

import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.auth.RequestSlugHolder;
import io.kelta.mcp.config.McpProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;

/**
 * Synchronous HTTP client that forwards MCP tool calls through the
 * Kelta gateway. The gateway validates the PAT, applies Cerbos
 * authorization — kelta-mcp does neither.
 *
 * <p>Tenant routing: the gateway routes by URL prefix
 * ({@code /{tenantSlug}/api/...}). The slug for the current tool call
 * comes from {@link RequestSlugHolder}, which {@code PatPropagatingToolDecorator}
 * populated from the MCP transport context. The slug originally
 * arrived in the inbound MCP URL ({@code /{tenantSlug}/mcp/(user|admin)})
 * — that's the per-session binding mechanism, so a single
 * deployment can serve any number of tenants concurrently.
 *
 * <p>The PAT is pulled from {@link RequestPatHolder} (same propagation
 * mechanism); never stored in config, never logged.
 */
@Component
public class GatewayHttpClient {

    private final RestClient client;

    public GatewayHttpClient(RestClient.Builder builder, McpProperties properties) {
        // Pin HTTP/1.1 — the in-cluster gateway hop doesn't benefit from HTTP/2,
        // and the JDK HttpClient's default h2c upgrade negotiation occasionally
        // trips intermediaries (and WireMock in tests).
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.client = builder
                .baseUrl(properties.gatewayUrl())
                .requestFactory(new JdkClientHttpRequestFactory(http))
                .build();
    }

    public Response get(String pathAndQuery) {
        return exchange("GET", pathAndQuery, null);
    }

    public Response post(String pathAndQuery, Object body) {
        return exchange("POST", pathAndQuery, body);
    }

    public Response patch(String pathAndQuery, Object body) {
        return exchange("PATCH", pathAndQuery, body);
    }

    public Response put(String pathAndQuery, Object body) {
        return exchange("PUT", pathAndQuery, body);
    }

    public Response delete(String pathAndQuery) {
        return exchange("DELETE", pathAndQuery, null);
    }

    /**
     * Visible for testing — assemble the gateway-side URI for a tool path.
     * Paths already prefixed with {@code /{slug}/} pass through unchanged
     * so a tool can opt out of automatic prefixing if it ever needs to.
     */
    String buildPath(String pathAndQuery) {
        String slug = RequestSlugHolder.get();
        if (slug == null || slug.isEmpty()) return pathAndQuery;
        String prefix = "/" + slug;
        if (pathAndQuery.startsWith(prefix + "/") || pathAndQuery.equals(prefix)) {
            return pathAndQuery;
        }
        return prefix + pathAndQuery;
    }

    private Response exchange(String method, String pathAndQuery, Object body) {
        String pat = RequestPatHolder.get();
        if (pat == null) {
            throw new IllegalStateException("No PAT in request context — auth filter must run before tool dispatch");
        }
        URI uri = URI.create(buildPath(pathAndQuery));
        RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(method))
                .uri(uri)
                .header("Authorization", "Bearer " + pat)
                .accept(MediaType.APPLICATION_JSON);

        RestClient.RequestHeadersSpec<?> sendSpec = (body == null)
                ? spec
                : spec.contentType(MediaType.APPLICATION_JSON).body(body);

        return sendSpec.exchange(
                (req, res) -> new Response(res.getStatusCode(), res.bodyTo(String.class)),
                true);
    }

    /**
     * Raw HTTP response from the gateway. Body is the response payload as
     * a string (JSON for normal API responses, anything for errors).
     */
    public record Response(HttpStatusCode status, String body) {
        public boolean isSuccess() {
            return status != null && status.is2xxSuccessful();
        }

        public Map<String, Object> jsonAsMap(tools.jackson.databind.ObjectMapper om) {
            if (body == null || body.isBlank()) return Map.of();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = om.readValue(body, Map.class);
                return m;
            } catch (RuntimeException e) {
                return Map.of("raw", body);
            }
        }
    }
}
