package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Action handler that makes a generic HTTP request with response capture.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "url": "https://api.example.com/endpoint",
 *   "method": "POST",
 *   "headers": {"Authorization": "Bearer token123"},
 *   "body": "{\"key\": \"value\"}",
 *   "responseVariable": "apiResponse"
 * }
 * </pre>
 *
 * <p>The response body is captured and available via {@code responseVariable}
 * for downstream actions in the workflow; {@code responseBody} keeps at most
 * {@value #MAX_RESPONSE_SIZE} characters.
 *
 * <p><b>Outbound safety.</b> The URL comes from tenant flow configuration, so the request goes
 * through {@link OutboundHttpClient} with the worker's {@link OutboundUrlGuard}: a URL whose host
 * resolves to a non-public address (loopback, private, link-local/metadata, …) is refused unless
 * the operator allow-listed it, and nothing is sent. {@code GET}/{@code HEAD} redirects are
 * followed up to {@value #MAX_REDIRECTS} hops, each re-validated; other methods do not follow
 * redirects. The call is bounded by {@link #CONNECT_TIMEOUT} to connect and
 * {@link #REQUEST_DEADLINE} overall, and at most {@value #MAX_RESPONSE_BYTES} bytes of the
 * response are read — a larger response is captured truncated and not parsed. A 4xx/5xx status
 * fails the step.
 *
 * @since 1.0.0
 */
public class HttpCalloutActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpCalloutActionHandler.class);
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    static final int MAX_RESPONSE_SIZE = 50_000;
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final int MAX_REDIRECTS = 3;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_DEADLINE = Duration.ofSeconds(60);

    private static final String TRUNCATED_SUFFIX = "... [truncated]";

    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;

    public HttpCalloutActionHandler(ObjectMapper objectMapper, OutboundUrlGuard urlGuard) {
        this(objectMapper, new OutboundHttpClient(urlGuard, new OutboundHttpClient.Limits(
                CONNECT_TIMEOUT, REQUEST_DEADLINE, MAX_RESPONSE_BYTES, MAX_REDIRECTS)));
    }

    /** With a pre-built client — tests use it to shorten the limits. */
    HttpCalloutActionHandler(ObjectMapper objectMapper, OutboundHttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String getActionTypeKey() {
        return "HTTP_CALLOUT";
    }

    @Override
    public ActionResult execute(ActionContext context) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                context.actionConfigJson(), new TypeReference<>() {});

            String url = (String) config.get("url");
            if (url == null || url.isBlank()) {
                return ActionResult.failure("URL is required for HTTP callout");
            }

            String method = (String) config.getOrDefault("method", "GET");
            if (!ALLOWED_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
                return ActionResult.failure("Invalid HTTP method: " + method);
            }

            // Build headers
            Map<String, String> headers = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, String> configHeaders = (Map<String, String>) config.get("headers");
            if (configHeaders != null) {
                headers.putAll(configHeaders);
            }
            if (headers.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
                headers.put("Content-Type", "application/json");
            }

            // Build body
            String body = null;
            Object bodyConfig = config.get("body");
            if (bodyConfig != null) {
                body = bodyConfig instanceof String
                    ? (String) bodyConfig
                    : objectMapper.writeValueAsString(bodyConfig);
            }

            log.info("HTTP callout: method={}, url={}, workflowRule={}",
                method, url, context.workflowRuleId());

            OutboundHttpClient.Response response;
            try {
                response = httpClient.send(new OutboundHttpClient.Request(
                    method.toUpperCase(Locale.ROOT), url, headers,
                    body == null ? null : body.getBytes(StandardCharsets.UTF_8)));
            } catch (OutboundUrlGuard.RejectedUrlException e) {
                log.warn("HTTP callout refused: url={}, workflowRule={}, reason={}",
                    url, context.workflowRuleId(), e.getMessage());
                return ActionResult.failure("HTTP callout refused: " + e.getMessage());
            } catch (OutboundHttpClient.OutboundRequestException e) {
                log.warn("HTTP callout failed: url={}, workflowRule={}, reason={}",
                    url, context.workflowRuleId(), e.getMessage());
                return ActionResult.failure("HTTP callout failed: " + e.getMessage());
            }

            int status = response.statusCode();
            if (status >= 400) {
                log.warn("HTTP callout returned an error status: url={}, status={}", url, status);
                return ActionResult.failure("HTTP callout returned status " + status);
            }

            Map<String, Object> outputData = new HashMap<>();
            outputData.put("statusCode", status);
            outputData.put("url", url);
            outputData.put("method", method);

            if (response.body().length > 0 || response.truncated()) {
                String responseText = response.bodyAsString();
                String responseBody = !response.truncated() && responseText.length() <= MAX_RESPONSE_SIZE
                    ? responseText
                    : responseText.substring(0, Math.min(responseText.length(), MAX_RESPONSE_SIZE))
                        + TRUNCATED_SUFFIX;
                outputData.put("responseBody", responseBody);

                if (!response.truncated()) {
                    try {
                        Object parsedBody = objectMapper.readValue(responseText, Object.class);
                        outputData.put("responseData", parsedBody);
                    } catch (Exception e) {
                        // Response is not JSON, that's OK
                    }
                }
            }

            String responseVariable = (String) config.get("responseVariable");
            if (responseVariable != null && !responseVariable.isBlank()) {
                outputData.put("responseVariable", responseVariable);
            }

            log.info("HTTP callout completed: url={}, status={}", url, status);

            return ActionResult.success(outputData);
        } catch (Exception e) {
            log.error("Failed to execute HTTP callout action: {}", e.getMessage(), e);
            return ActionResult.failure(e);
        }
    }

    @Override
    public void validate(String configJson) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                configJson, new TypeReference<>() {});

            if (config.get("url") == null) {
                throw new IllegalArgumentException("Config must contain 'url'");
            }

            String method = (String) config.get("method");
            if (method != null && !ALLOWED_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Invalid HTTP method: " + method
                    + ". Allowed methods: " + ALLOWED_METHODS);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid config JSON: " + e.getMessage(), e);
        }
    }
}
