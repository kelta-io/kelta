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
 * Action handler that sends an outbound HTTP webhook request.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "url": "https://api.example.com/webhook",
 *   "method": "POST",
 *   "headers": {"Authorization": "Bearer token123"},
 *   "bodyTemplate": "{\"recordId\": \"{{id}}\"}"
 * }
 * </pre>
 *
 * <p>Default method is POST. If no bodyTemplate is specified, sends record data
 * as the default body.
 *
 * <p><b>Outbound safety.</b> The URL comes from tenant flow configuration, so the request goes
 * through {@link OutboundHttpClient} with the host service's {@link OutboundUrlGuard}: a host that
 * resolves to a non-public address is refused unless allow-listed, and nothing is sent.
 * {@code GET} redirects are followed up to {@value #MAX_REDIRECTS} hops, each re-validated; other
 * methods do not follow redirects. The call is bounded by {@link #CONNECT_TIMEOUT} to connect and
 * {@link #REQUEST_DEADLINE} overall, and at most {@value #MAX_RESPONSE_BYTES} bytes of the
 * response are read. A 4xx/5xx status fails the step.
 *
 * @since 1.0.0
 */
public class OutboundMessageActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(OutboundMessageActionHandler.class);
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    /** Response bodies up to this many characters are returned as {@code responseBody}. */
    static final int MAX_RESPONSE_SIZE = 10_000;
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    static final int MAX_REDIRECTS = 3;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_DEADLINE = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;

    public OutboundMessageActionHandler(ObjectMapper objectMapper, OutboundUrlGuard urlGuard) {
        this(objectMapper, new OutboundHttpClient(urlGuard, new OutboundHttpClient.Limits(
                CONNECT_TIMEOUT, REQUEST_DEADLINE, MAX_RESPONSE_BYTES, MAX_REDIRECTS)));
    }

    /** With a pre-built client — tests use it to shorten the limits. */
    OutboundMessageActionHandler(ObjectMapper objectMapper, OutboundHttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String getActionTypeKey() {
        return "OUTBOUND_MESSAGE";
    }

    @Override
    public ActionResult execute(ActionContext context) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                context.actionConfigJson(), new TypeReference<>() {});

            String url = (String) config.get("url");
            if (url == null || url.isBlank()) {
                return ActionResult.failure("URL is required for outbound message");
            }

            String method = (String) config.getOrDefault("method", "POST");
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
            String body;
            Object bodyTemplate = config.get("bodyTemplate");
            if (bodyTemplate != null) {
                body = bodyTemplate.toString();
            } else {
                Map<String, Object> payload = new HashMap<>();
                payload.put("recordId", context.recordId());
                payload.put("collectionId", context.collectionId());
                payload.put("collectionName", context.collectionName());
                payload.put("data", context.data());
                payload.put("workflowRuleId", context.workflowRuleId());
                body = objectMapper.writeValueAsString(payload);
            }

            log.info("Outbound message: method={}, url={}, workflowRule={}",
                method, url, context.workflowRuleId());

            OutboundHttpClient.Response response;
            try {
                response = httpClient.send(new OutboundHttpClient.Request(
                    method.toUpperCase(Locale.ROOT), url, headers, body.getBytes(StandardCharsets.UTF_8)));
            } catch (OutboundUrlGuard.RejectedUrlException e) {
                log.warn("Outbound message refused: url={}, workflowRule={}, reason={}",
                    url, context.workflowRuleId(), e.getMessage());
                return ActionResult.failure("Outbound message refused: " + e.getMessage());
            } catch (OutboundHttpClient.OutboundRequestException e) {
                log.warn("Outbound message failed: url={}, workflowRule={}, reason={}",
                    url, context.workflowRuleId(), e.getMessage());
                return ActionResult.failure("Outbound message failed: " + e.getMessage());
            }

            int status = response.statusCode();
            if (status >= 400) {
                log.warn("Outbound message returned an error status: url={}, status={}", url, status);
                return ActionResult.failure("Outbound message returned status " + status);
            }

            Map<String, Object> outputData = new HashMap<>();
            outputData.put("statusCode", status);
            outputData.put("url", url);
            outputData.put("method", method);
            if (!response.truncated() && response.body().length > 0) {
                String responseBody = response.bodyAsString();
                if (responseBody.length() <= MAX_RESPONSE_SIZE) {
                    outputData.put("responseBody", responseBody);
                }
            }

            log.info("Outbound message completed: url={}, status={}", url, status);

            return ActionResult.success(outputData);
        } catch (Exception e) {
            log.error("Failed to execute outbound message action: {}", e.getMessage(), e);
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
