package io.kelta.worker.module;

import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.service.ModuleServicePrincipalService;
import io.kelta.worker.service.ModuleServicePrincipalService.ClientCredentials;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code Kelta-Access-Token} every module request carries
 * ({@code specs/module-platform/9-external-modules.md} §3.3): a client_credentials access token
 * for the install's service principal, requested from kelta-auth's in-cluster address with the
 * kept client secret ({@link ModuleServicePrincipalService#clientCredentials}), and cached per
 * install until 60 s before it expires. kelta-auth issues these for 15 minutes.
 *
 * <p>The cache is per pod and keyed on the connected app, so a principal regenerated on upgrade
 * or deactivated on uninstall stops being served: {@link #evict} drops its entry, and a
 * deactivated app has no secret left to request a new one with.
 */
@Component
public class ModuleAccessTokenService {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    /** Refresh this long before {@code exp}, so a token never expires mid-request. */
    static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private record Cached(String token, Instant refreshAt) {}

    private final String tokenUrl;
    private final ModuleServicePrincipalService principals;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public ModuleAccessTokenService(@Value("${kelta.auth.internal-url:http://kelta-auth:8081}") String authUrl,
                                    ModuleServicePrincipalService principals,
                                    ObjectMapper objectMapper) {
        this(authUrl, principals, objectMapper, Clock.systemUTC());
    }

    ModuleAccessTokenService(String authUrl, ModuleServicePrincipalService principals,
                             ObjectMapper objectMapper, Clock clock) {
        this.tokenUrl = (authUrl == null ? "" : authUrl.replaceAll("/+$", "")) + "/oauth2/token";
        this.principals = principals;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * An access token for the install's service principal.
     *
     * @throws IllegalStateException when the install has no active principal or kelta-auth refuses
     */
    public String accessToken(TenantModuleData module) {
        String appId = module.connectedAppId();
        if (appId == null) {
            throw new IllegalStateException("Module '" + module.moduleId() + "' has no service principal");
        }
        Cached cached = cache.get(appId);
        if (cached != null && clock.instant().isBefore(cached.refreshAt())) {
            return cached.token();
        }
        ClientCredentials credentials = principals.clientCredentials(module.tenantId(), appId)
                .orElseThrow(() -> new IllegalStateException(
                        "Module '" + module.moduleId() + "' has no active service principal credentials"));
        Cached fresh = request(credentials);
        cache.put(appId, fresh);
        return fresh.token();
    }

    /** Forgets the cached token of a principal that changed or was deactivated. */
    public void evict(String connectedAppId) {
        if (connectedAppId != null) {
            cache.remove(connectedAppId);
        }
    }

    private Cached request(ClientCredentials credentials) {
        String basic = Base64.getEncoder().encodeToString((form(credentials.clientId()) + ":"
                + form(credentials.clientSecret())).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(RESPONSE_TIMEOUT)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted requesting a module access token");
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach kelta-auth for a module access token: "
                    + e.getMessage());
        }
        if (response.statusCode() != 200) {
            // The body is an OAuth error object (error, error_description); it never echoes the secret.
            throw new IllegalStateException("kelta-auth refused the module access token request ("
                    + response.statusCode() + ")");
        }
        JsonNode json = objectMapper.readTree(response.body());
        JsonNode token = json.get("access_token");
        if (token == null || !token.isString() || token.stringValue().isBlank()) {
            throw new IllegalStateException("kelta-auth answered without an access_token");
        }
        long expiresIn = json.has("expires_in") ? json.get("expires_in").asLong() : 300L;
        Instant refreshAt = clock.instant().plusSeconds(expiresIn).minus(EXPIRY_MARGIN);
        return new Cached(token.stringValue(), refreshAt);
    }

    /** RFC 6749 §2.3.1: client id and secret are form-encoded before Basic encoding. */
    private static String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
