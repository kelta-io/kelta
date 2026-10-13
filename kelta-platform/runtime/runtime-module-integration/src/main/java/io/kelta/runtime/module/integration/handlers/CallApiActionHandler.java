package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.module.integration.api.ApiOperation;
import io.kelta.runtime.module.integration.api.ApiSpec;
import io.kelta.runtime.module.integration.mapping.PayloadMapperException;
import io.kelta.runtime.module.integration.mapping.PayloadMapperService;
import io.kelta.runtime.module.integration.spi.ApiSpecStore;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.runtime.module.integration.spi.IdempotencyStore;
import io.kelta.runtime.module.integration.spi.IdempotencyStore.CachedResponse;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generic external-API call. Replaces {@code HTTP_CALLOUT} with a unified
 * handler that:
 * <ul>
 *   <li>Knows two modes — {@code operation} (look up an OpenAPI operation
 *       by id, derive URL/method from the spec) and {@code raw} (free-form
 *       URL+method, behaves like the legacy HTTP_CALLOUT).</li>
 *   <li>Resolves credentials by reference and applies the right auth
 *       transformation per credential type, so step config never carries a
 *       secret.</li>
 *   <li>Maps path/query/header/body inputs through {@link PayloadMapperService}
 *       so users can use {@code ${$.path}} placeholders or {@code =expr}
 *       JSONata expressions interchangeably.</li>
 *   <li>Supports an explicit idempotency key so retries against
 *       non-idempotent upstreams don't double-execute.</li>
 *   <li>Emits named error codes ({@code Api.HttpClientError},
 *       {@code Api.HttpServerError}, {@code Api.Timeout},
 *       {@code Credential.*}, {@code Mapper.Failure}) so flows can write
 *       targeted Catch policies.</li>
 * </ul>
 *
 * <p><b>Outbound safety.</b> In both modes the URL comes from tenant configuration (flow config
 * or an imported spec's base URL), so the request goes through {@link OutboundHttpClient} with the
 * host service's {@link OutboundUrlGuard}: a host that resolves to a non-public address is refused
 * unless allow-listed ({@code Api.Refused}), and nothing is sent. {@code GET}/{@code HEAD}
 * redirects are followed up to {@value #MAX_REDIRECTS} hops, each re-validated; other methods
 * return the 3xx. The call is bounded by {@link #CONNECT_TIMEOUT} to connect and
 * {@link #REQUEST_DEADLINE} overall ({@code Api.Timeout} on expiry or transport failure), and at
 * most {@value #MAX_RESPONSE_BYTES} bytes of the response are read — a larger response keeps its
 * truncated {@code responseBody} and is not parsed into {@code responseData}.
 *
 * <p><b>No secret in the result.</b> The result lands in flow state and in persisted step-log
 * snapshots, so every value a credential injects (query parameter, API-key header,
 * {@code Authorization}) is masked by {@link CredentialInjection} in the returned {@code url},
 * the response body and data, and every error message. The request sent upstream is unchanged.
 */
public class CallApiActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(CallApiActionHandler.class);
    private static final Set<String> ALLOWED_METHODS =
        Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final int MAX_RESPONSE_SIZE = 50_000;
    private static final Duration DEFAULT_IDEMPOTENCY_TTL = Duration.ofHours(24);
    /** Sent when the step config sets no {@code Accept} header. */
    private static final String DEFAULT_ACCEPT = "application/json, text/plain, */*";
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final int MAX_REDIRECTS = 3;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_DEADLINE = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper;
    private final PayloadMapperService payloadMapper;
    private final ApiSpecStore apiSpecStore;
    private final CredentialResolverPort credentialResolver;
    private final IdempotencyStore idempotencyStore;
    private final OutboundHttpClient httpClient;

    public CallApiActionHandler(ObjectMapper objectMapper,
                                 PayloadMapperService payloadMapper,
                                 ApiSpecStore apiSpecStore,
                                 CredentialResolverPort credentialResolver,
                                 IdempotencyStore idempotencyStore,
                                 OutboundUrlGuard urlGuard) {
        this(objectMapper, payloadMapper, apiSpecStore, credentialResolver, idempotencyStore,
            new OutboundHttpClient(urlGuard, new OutboundHttpClient.Limits(
                CONNECT_TIMEOUT, REQUEST_DEADLINE, MAX_RESPONSE_BYTES, MAX_REDIRECTS)));
    }

    /** With a pre-built client — tests use it to shorten the limits. */
    CallApiActionHandler(ObjectMapper objectMapper,
                         PayloadMapperService payloadMapper,
                         ApiSpecStore apiSpecStore,
                         CredentialResolverPort credentialResolver,
                         IdempotencyStore idempotencyStore,
                         OutboundHttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.payloadMapper = payloadMapper;
        this.apiSpecStore = apiSpecStore;
        this.credentialResolver = credentialResolver;
        this.idempotencyStore = idempotencyStore;
        this.httpClient = httpClient;
    }

    @Override
    public String getActionTypeKey() {
        return "CALL_API";
    }

    @Override
    public ActionResult execute(ActionContext context) {
        CredentialInjection injection = new CredentialInjection();
        try {
            Map<String, Object> config = objectMapper.readValue(
                context.actionConfigJson(), new TypeReference<>() {});
            Map<String, Object> stateData = context.resolvedData() != null
                ? context.resolvedData() : context.data();
            if (stateData == null) {
                stateData = new HashMap<>();
            }

            String mode = stringOrDefault(config.get("mode"), "raw");
            ResolvedRequest request = "operation".equalsIgnoreCase(mode)
                ? buildFromOperation(config, stateData, context.tenantId())
                : buildFromRaw(config, stateData);

            // Apply credential auth (if a credential ref was provided)
            String credentialRef = stringOrNull(config.get("credentialRef"));
            if (credentialRef != null && !credentialRef.isBlank()) {
                if (credentialResolver == null) {
                    return ActionResult.failure(
                        "Credential.NotConfigured: credential vault is not configured "
                            + "in this environment");
                }
                ResolvedCredential cred = credentialResolver.resolve(
                    context.tenantId(), credentialRef,
                    "CALL_API:" + (request.url == null ? "raw" : request.url));
                applyAuth(cred, injection);
                request.headers.putAll(injection.headers());
                request.url = appendQuery(request.url, injection.queryParams());
            }

            // Idempotency: compute key, look up cache, attach upstream header
            IdempotencyConfig idempotency = readIdempotency(config, request, context);
            if (idempotency != null && idempotencyStore != null) {
                Optional<CachedResponse> cached =
                    idempotencyStore.lookup(context.tenantId(), idempotency.key);
                if (cached.isPresent()) {
                    log.info("CALL_API idempotency hit for key {} — replaying cached {}",
                        idempotency.key, cached.get().statusCode());
                    return success(request, cached.get().statusCode(),
                        cached.get().responseBody(), false, config, stateData, injection);
                }
                request.headers.put("Idempotency-Key", idempotency.key);
            }
            request.headers.putIfAbsent("Accept", DEFAULT_ACCEPT);

            log.info("CALL_API: method={} url={} mode={} workflowRule={}",
                request.method, injection.redactUrl(request.url), mode, context.workflowRuleId());

            OutboundHttpClient.Response response;
            try {
                response = httpClient.send(new OutboundHttpClient.Request(
                    request.method, request.url, request.headers,
                    request.body == null ? null : request.body.getBytes(StandardCharsets.UTF_8)));
            } catch (OutboundUrlGuard.RejectedUrlException e) {
                log.warn("CALL_API refused: url={} workflowRule={} reason={}",
                    injection.redactUrl(request.url), context.workflowRuleId(),
                    injection.redact(e.getMessage()));
                return failure("Api.Refused: " + e.getMessage(), injection);
            } catch (OutboundHttpClient.OutboundRequestException e) {
                return failure("Api.Timeout: " + e.getMessage(), injection);
            }

            int status = response.statusCode();
            String body = response.body().length == 0 && !response.truncated()
                ? null : injection.redact(response.bodyAsString());
            if (status >= 400 && status < 500) {
                return failure(
                    "Api.HttpClientError: " + status + " — " + truncate(body == null ? "" : body),
                    injection);
            }
            if (status >= 500) {
                return failure(
                    "Api.HttpServerError: " + status + " — " + truncate(body == null ? "" : body),
                    injection);
            }

            if (idempotency != null && idempotencyStore != null && status / 100 == 2) {
                idempotencyStore.record(context.tenantId(), idempotency.key,
                    context.executionLogId(), context.workflowRuleId(),
                    status, body, idempotency.ttl);
            }

            return success(request, status, body, response.truncated(), config, stateData,
                injection);
        } catch (PayloadMapperException e) {
            return failure("Mapper.Failure: " + e.getMessage(), injection);
        } catch (CredentialFailure e) {
            return failure(e.code + ": " + e.getMessage(), injection);
        } catch (Exception e) {
            String message = injection.redact(e.getMessage());
            log.error("CALL_API failed: {}: {}", e.getClass().getSimpleName(), message);
            return ActionResult.failure("Api.Unexpected: " + message);
        }
    }

    /** Every failure message passes through here: it is persisted in step logs. */
    private static ActionResult failure(String message, CredentialInjection injection) {
        return ActionResult.failure(injection.redact(message));
    }

    @Override
    public void validate(String configJson) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                configJson, new TypeReference<>() {});
            String mode = stringOrDefault(config.get("mode"), "raw");
            if ("operation".equalsIgnoreCase(mode)) {
                if (stringOrNull(config.get("specId")) == null
                    || stringOrNull(config.get("operationId")) == null) {
                    throw new IllegalArgumentException(
                        "operation mode requires specId and operationId");
                }
            } else if ("raw".equalsIgnoreCase(mode)) {
                if (stringOrNull(config.get("url")) == null) {
                    throw new IllegalArgumentException("raw mode requires url");
                }
                String method = stringOrDefault(config.get("method"), "GET");
                if (!ALLOWED_METHODS.contains(method.toUpperCase())) {
                    throw new IllegalArgumentException("Invalid method: " + method);
                }
            } else {
                throw new IllegalArgumentException(
                    "mode must be 'operation' or 'raw'");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CALL_API config: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Request building
    // -----------------------------------------------------------------------

    private ResolvedRequest buildFromOperation(Map<String, Object> config,
                                                 Map<String, Object> stateData,
                                                 String tenantId) {
        if (apiSpecStore == null) {
            throw new RuntimeException(
                "Api.SpecLibraryUnavailable: spec library is not configured");
        }
        String specId = stringOrNull(config.get("specId"));
        String operationId = stringOrNull(config.get("operationId"));
        if (specId == null || operationId == null) {
            throw new IllegalArgumentException(
                "operation mode requires specId and operationId");
        }
        ApiSpec spec = apiSpecStore.findSpec(tenantId, specId)
            .orElseThrow(() -> new RuntimeException("Api.SpecNotFound: " + specId));
        ApiOperation operation = apiSpecStore.findOperation(tenantId, specId, operationId)
            .orElseThrow(() -> new RuntimeException(
                "Api.OperationNotFound: " + operationId + " in " + specId));

        Map<String, Object> pathParams = mapOf(payloadMapper.map(
            config.getOrDefault("pathParams", Map.of()), stateData));
        Map<String, Object> queryParams = mapOf(payloadMapper.map(
            config.getOrDefault("queryParams", Map.of()), stateData));
        Map<String, Object> headersMap = mapOf(payloadMapper.map(
            config.getOrDefault("headers", Map.of()), stateData));
        Object body = payloadMapper.map(config.get("requestBody"), stateData);

        String url = buildUrl(spec.baseUrl(), operation.pathTemplate(), pathParams, queryParams);
        Map<String, String> headers = newHeaders();
        headersMap.forEach((k, v) -> headers.put(k, v == null ? "" : v.toString()));
        if (!headers.containsKey("Content-Type") && body != null) {
            headers.put("Content-Type", "application/json");
        }
        String bodyString = serializeBody(body);
        String method = operation.httpMethod() == null
            ? "GET" : operation.httpMethod().toUpperCase(Locale.ROOT);
        return new ResolvedRequest(method, url, headers, bodyString);
    }

    private ResolvedRequest buildFromRaw(Map<String, Object> config,
                                          Map<String, Object> stateData) {
        String url = stringOrNull(payloadMapper.map(config.get("url"), stateData));
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("raw mode requires url");
        }
        String method = stringOrDefault(config.get("method"), "GET").toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new IllegalArgumentException("Invalid method: " + method);
        }

        Map<String, Object> queryParams = mapOf(payloadMapper.map(
            config.getOrDefault("queryParams", Map.of()), stateData));
        Map<String, Object> headersMap = mapOf(payloadMapper.map(
            config.getOrDefault("headers", Map.of()), stateData));

        Object body = payloadMapper.map(config.get("body"), stateData);

        String fullUrl = appendQuery(url, queryParams);
        Map<String, String> headers = newHeaders();
        headersMap.forEach((k, v) -> headers.put(k, v == null ? "" : v.toString()));
        if (!headers.containsKey("Content-Type") && body != null) {
            headers.put("Content-Type", "application/json");
        }
        return new ResolvedRequest(method, fullUrl, headers, serializeBody(body));
    }

    private String buildUrl(String baseUrl, String pathTemplate,
                             Map<String, Object> pathParams,
                             Map<String, Object> queryParams) {
        if (baseUrl == null) baseUrl = "";
        if (baseUrl.endsWith("/") && pathTemplate.startsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String filled = pathTemplate;
        for (Map.Entry<String, Object> entry : pathParams.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().toString();
            filled = filled.replace("{" + entry.getKey() + "}",
                URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return appendQuery(baseUrl + filled, queryParams);
    }

    private String appendQuery(String url, Map<String, Object> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, Object> entry : queryParams.entrySet()) {
            if (entry.getValue() == null) continue;
            if (!first) sb.append('&');
            first = false;
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(entry.getValue().toString(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private String serializeBody(Object body) {
        if (body == null) return null;
        if (body instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return body.toString();
        }
    }

    // -----------------------------------------------------------------------
    // Auth application
    // -----------------------------------------------------------------------

    /**
     * Credential types inject only through {@link CredentialInjection}, so every value they add
     * is redacted from what the step returns.
     */
    private void applyAuth(ResolvedCredential cred, CredentialInjection injection) {
        switch (cred.type()) {
            case "api_key" -> applyApiKey(cred, injection);
            case "bearer_token" -> {
                Object token = cred.secret("token");
                if (token != null) {
                    injection.header("Authorization", "Bearer " + token, token);
                }
            }
            case "basic_auth" -> {
                Object u = cred.secret("username");
                Object p = cred.secret("password");
                if (u != null && p != null) {
                    String encoded = Base64.getEncoder().encodeToString(
                        (u + ":" + p).getBytes(StandardCharsets.UTF_8));
                    injection.header("Authorization", "Basic " + encoded, encoded, u, p);
                }
            }
            case "oauth2_client_credentials", "oauth2_authorization_code" -> {
                // The resolver returns the *current* access token (refreshing
                // automatically if it was about to expire). For OAuth flows
                // the token is stored under "accessToken" in secretFields by
                // the worker's resolver adapter.
                Object accessToken = cred.secret("accessToken");
                if (accessToken == null) {
                    throw new CredentialFailure("Credential.NoAccessToken",
                        "OAuth credential has no current access token");
                }
                injection.header("Authorization", "Bearer " + accessToken, accessToken);
            }
            default -> {
                // SMTP / custom — credential isn't useful to a plain HTTP step.
                // Fall through silently; the user picked the wrong credential.
            }
        }
    }

    private void applyApiKey(ResolvedCredential cred, CredentialInjection injection) {
        String headerName = (String) cred.metadata("headerName");
        String location = (String) cred.metadata("location");
        String prefix = (String) cred.metadata("prefix");
        Object value = cred.secret("value");
        if (value == null || headerName == null) return;
        String composed = (prefix == null ? "" : prefix) + value;
        if ("query".equalsIgnoreCase(location)) {
            injection.queryParam(headerName, composed);
        } else {
            injection.header(headerName, composed, value);
        }
    }

    // -----------------------------------------------------------------------
    // Idempotency
    // -----------------------------------------------------------------------

    private IdempotencyConfig readIdempotency(Map<String, Object> config,
                                               ResolvedRequest request,
                                               ActionContext context) {
        Object raw = config.get("idempotency");
        if (!(raw instanceof Map<?, ?> map)) {
            // Default-on for non-idempotent methods if no explicit config.
            return defaultIdempotency(request, context);
        }
        Object enabled = map.get("enabled");
        if (Boolean.FALSE.equals(enabled)) {
            return null;
        }
        String explicitKey = stringOrNull(map.get("key"));
        long ttlSeconds = map.get("ttlSeconds") instanceof Number n
            ? n.longValue() : DEFAULT_IDEMPOTENCY_TTL.toSeconds();
        String key = explicitKey != null ? explicitKey
            : computeKey(request, context);
        return new IdempotencyConfig(key, Duration.ofSeconds(ttlSeconds));
    }

    private IdempotencyConfig defaultIdempotency(ResolvedRequest request, ActionContext context) {
        String method = request.method == null ? "GET" : request.method.toUpperCase();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return null;
        }
        return new IdempotencyConfig(computeKey(request, context), DEFAULT_IDEMPOTENCY_TTL);
    }

    private static String computeKey(ResolvedRequest request, ActionContext context) {
        String raw = request.method + " " + request.url + " "
            + (request.body == null ? "" : request.body) + " "
            + (context.executionLogId() == null ? "" : context.executionLogId()) + " "
            + (context.workflowRuleId() == null ? "" : context.workflowRuleId());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private record IdempotencyConfig(String key, Duration ttl) {
    }

    // -----------------------------------------------------------------------
    // Response shaping
    // -----------------------------------------------------------------------

    private ActionResult success(ResolvedRequest request, int status, String body,
                                  boolean truncated,
                                  Map<String, Object> config,
                                  Map<String, Object> stateData,
                                  CredentialInjection injection) {
        body = injection.redact(body);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("statusCode", status);
        output.put("url", injection.redactUrl(request.url));
        output.put("method", request.method);
        if (body != null) {
            output.put("responseBody", truncated ? truncateAlways(body) : truncate(body));
            if (!truncated) {
                try {
                    output.put("responseData", objectMapper.readValue(body, Object.class));
                } catch (Exception ignored) {
                    // not JSON, that's fine
                }
            }
        }

        Object responseMappingTemplate = config.get("responseMapping");
        if (responseMappingTemplate != null) {
            Map<String, Object> mappingState = new LinkedHashMap<>(stateData);
            mappingState.put("$response", output);
            try {
                Object mapped = payloadMapper.map(responseMappingTemplate, mappingState);
                output.put("mapped", mapped);
            } catch (PayloadMapperException e) {
                log.debug("Response mapping failed: {}", e.getMessage());
            }
        }
        return ActionResult.success(output);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    private static String stringOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s.isBlank() ? null : s;
        return o.toString();
    }

    private static String stringOrDefault(Object o, String fallback) {
        String s = stringOrNull(o);
        return s == null ? fallback : s;
    }

    private static String truncate(String body) {
        if (body == null) return null;
        if (body.length() <= MAX_RESPONSE_SIZE) return body;
        return body.substring(0, MAX_RESPONSE_SIZE) + "... [truncated]";
    }

    /** For a body the client already cut off at its byte cap. */
    private static String truncateAlways(String body) {
        return body.substring(0, Math.min(body.length(), MAX_RESPONSE_SIZE)) + "... [truncated]";
    }

    /** Header names are case-insensitive; a later value for the same name replaces the earlier. */
    private static Map<String, String> newHeaders() {
        return new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    private static class ResolvedRequest {
        String method;
        String url;
        Map<String, String> headers;
        String body;

        ResolvedRequest(String method, String url, Map<String, String> headers, String body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    private static class CredentialFailure extends RuntimeException {
        final String code;
        CredentialFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Suppress unused-warning on collections list import in IDE checks. */
    @SuppressWarnings("unused")
    private static List<String> _supportedTypes() {
        return List.of("api_key", "bearer_token", "basic_auth",
            "oauth2_client_credentials", "oauth2_authorization_code");
    }
}
