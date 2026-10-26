package io.kelta.worker.controller;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.flow.FlowEngine;
import io.kelta.worker.service.FlowActorResolver;
import io.kelta.runtime.flow.InitialStateBuilder;
import io.kelta.worker.repository.FlowRepository;
import io.kelta.worker.service.FlowWebhookPolicy;
import io.kelta.worker.service.TenantSlugResolver;
import io.kelta.worker.util.TenantContextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles inbound webhook requests that trigger AUTOLAUNCHED flows,
 * and provides an endpoint for the UI to retrieve the webhook URL.
 *
 * <p>{@code POST /api/webhooks/{flowId}} receives webhook payloads from
 * external systems and triggers the corresponding flow execution; a flow can
 * opt in to {@code GET}, form bodies, a per-flow secret and a custom response
 * through {@code triggerConfig.webhook} ({@link FlowWebhookPolicy}).
 *
 * <p>{@code GET /api/flows/{flowId}/webhook-url} returns the publicly
 * accessible webhook URL for a given flow.
 *
 * @since 1.0.0
 */
@RestController
@ConditionalOnBean(FlowEngine.class)
public class FlowWebhookController {

    private static final Logger log = LoggerFactory.getLogger(FlowWebhookController.class);

    private final FlowEngine flowEngine;
    private final FlowActorResolver flowActorResolver;
    private final FlowRepository flowRepository;
    private final InitialStateBuilder initialStateBuilder;
    private final ObjectMapper objectMapper;
    private final TenantSlugResolver tenantSlugResolver;
    private final String externalBaseUrl;

    public FlowWebhookController(FlowEngine flowEngine,
                                  FlowRepository flowRepository,
                                  InitialStateBuilder initialStateBuilder,
                                  ObjectMapper objectMapper,
                                  TenantSlugResolver tenantSlugResolver,
                                  FlowActorResolver flowActorResolver,
                                  @Value("${kelta.external-base-url:http://localhost:8080}") String externalBaseUrl) {
        this.flowEngine = flowEngine;
        this.flowActorResolver = flowActorResolver;
        this.flowRepository = flowRepository;
        this.initialStateBuilder = initialStateBuilder;
        this.objectMapper = objectMapper;
        this.tenantSlugResolver = tenantSlugResolver;
        this.externalBaseUrl = externalBaseUrl;
    }

    /**
     * Receives a webhook request and triggers the corresponding flow.
     * This endpoint is unauthenticated — external callers do not have JWT tokens;
     * a flow can require a per-flow secret ({@link FlowWebhookPolicy}).
     */
    @PostMapping("/api/webhooks/{flowId}")
    public ResponseEntity<?> receiveWebhook(
            @PathVariable String flowId,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        return handleWebhook(flowId, body, request);
    }

    /**
     * Receives a GET webhook (an emailed link, say). Only flows whose
     * {@code triggerConfig.webhook.methods} lists GET accept it; others answer 405.
     */
    @GetMapping("/api/webhooks/{flowId}")
    public ResponseEntity<?> receiveWebhookGet(@PathVariable String flowId, HttpServletRequest request) {
        return handleWebhook(flowId, null, request);
    }

    private ResponseEntity<?> handleWebhook(String flowId, byte[] body, HttpServletRequest request) {
        log.info("Webhook received for flowId={} method={}", flowId, request.getMethod());

        // Load the flow definition
        Optional<Map<String, Object>> flowOpt = flowRepository.findFlowById(flowId);
        if (flowOpt.isEmpty()) {
            log.warn("Webhook received for unknown flow: {}", flowId);
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> flow = flowOpt.get();

        // Verify it's an AUTOLAUNCHED flow
        String flowType = (String) flow.get("flow_type");
        if (!"AUTOLAUNCHED".equals(flowType)) {
            log.warn("Webhook received for non-AUTOLAUNCHED flow: flowId={}, type={}", flowId, flowType);
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request",
                            "Flow is not configured for webhook triggers"));
        }

        // Verify flow is active
        Boolean active = (Boolean) flow.get("active");
        if (!Boolean.TRUE.equals(active)) {
            log.warn("Webhook received for inactive flow: flowId={}", flowId);
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request", "Flow is not active"));
        }

        String definitionJson = (String) flow.get("definition");
        if (definitionJson == null || definitionJson.isBlank()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request", "Flow has no definition"));
        }

        FlowWebhookPolicy policy;
        try {
            policy = FlowWebhookPolicy.parse((String) flow.get("trigger_config"), objectMapper);
        } catch (FlowWebhookPolicy.InvalidConfigException e) {
            log.warn("Webhook refused for flow {}: {}", flowId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    JsonApiResponseBuilder.error("403", "Forbidden",
                            "Webhook configuration is invalid: " + e.getMessage()));
        }

        if (!policy.allowsMethod(request.getMethod())) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .header(HttpHeaders.ALLOW, String.join(", ", new TreeSet<>(policy.methods())))
                    .body(JsonApiResponseBuilder.error("405", "Method Not Allowed",
                            "This flow's webhook does not accept " + request.getMethod()));
        }

        Map<String, List<String>> query;
        try {
            query = FlowWebhookPolicy.decodeParameters(request.getQueryString());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request", "Malformed query string"));
        }

        boolean hasBody = body != null && body.length > 0;
        FlowWebhookPolicy.BodyType bodyType = null;
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            bodyType = bodyType(request.getContentType(), hasBody);
            if (bodyType != null && !policy.allowsBody(bodyType)) {
                bodyType = null;
            }
            if (bodyType == null && (hasBody || isForm(request.getContentType()))) {
                return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(
                        JsonApiResponseBuilder.error("415", "Unsupported Media Type",
                                "This flow's webhook does not accept " + request.getContentType() + " bodies"));
            }
        }

        if (!policy.authenticates(request.getHeader(FlowWebhookPolicy.SECRET_HEADER), query)) {
            log.warn("Webhook rejected for flow {}: missing or invalid secret", flowId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    JsonApiResponseBuilder.error("401", "Unauthorized", "Missing or invalid webhook secret"));
        }

        Map<String, Object> input;
        if (bodyType == FlowWebhookPolicy.BodyType.FORM) {
            input = FlowWebhookPolicy.toStateValues(formFields(request, query));
        } else if (bodyType == FlowWebhookPolicy.BodyType.JSON && hasBody) {
            Optional<Map<String, Object>> parsed = parseJsonObject(body);
            if (parsed.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        JsonApiResponseBuilder.error("400", "Bad Request", "Body must be a JSON object"));
            }
            input = parsed.get();
        } else {
            input = Map.of();
        }
        query.remove(FlowWebhookPolicy.SIGNATURE_PARAM);

        String tenantId = (String) flow.get("tenant_id");
        String executionId = UUID.randomUUID().toString();

        // Extract selected headers
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", request.getContentType() != null ? request.getContentType() : "");
        headers.put("user-agent", request.getHeader("User-Agent") != null ? request.getHeader("User-Agent") : "");

        // Build initial state from webhook
        Map<String, Object> initialState = initialStateBuilder.buildFromWebhook(
                input, headers, FlowWebhookPolicy.toStateValues(query), tenantId, flowId, executionId);

        // Webhooks are unauthenticated and bypass the tenant filter, so the slug
        // is not yet bound. Resolve it before invoking the engine — the engine's
        // thread-pool propagation snapshots TenantContext at submit time, and
        // schema-per-tenant SQL inside the flow needs the slug to resolve to the
        // right schema.
        String tenantSlug = tenantSlugResolver.resolveSlug(tenantId).orElse(null);
        if (tenantSlug == null) {
            log.warn("Could not resolve slug for tenant {} on webhook for flow {} — flow execution will fall back to public schema",
                    tenantId, flowId);
        }
        AtomicReference<String> resultRef = new AtomicReference<>();
        try {
            // Actor (run-as user -> flow owner) replaces the legacy "webhook"
            // provenance marker so records the flow writes get audit users.
            String actor = flowActorResolver.resolve(tenantId, flowId, null);
            TenantContextUtils.withTenant(tenantId, tenantSlug, () ->
                    resultRef.set(flowEngine.startExecution(
                            tenantId, flowId, definitionJson, initialState, actor, null, false)));
        } catch (Exception e) {
            log.error("Failed to start webhook-triggered flow execution: flowId={}", flowId, e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Internal Server Error",
                            "Failed to start flow execution"));
        }
        String resultExecutionId = resultRef.get();
        log.info("Started webhook-triggered flow execution: flowId={}, executionId={}", flowId, resultExecutionId);

        if (policy.response() instanceof FlowWebhookPolicy.Redirect redirect) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(redirect.location()).build();
        }
        if (policy.response() instanceof FlowWebhookPolicy.Content content) {
            ResponseEntity.BodyBuilder reply = ResponseEntity.status(content.status())
                    .header("X-Content-Type-Options", "nosniff");
            if (content.body() == null) {
                return reply.build();
            }
            return reply.contentType(new MediaType(MediaType.parseMediaType(content.contentType()), StandardCharsets.UTF_8))
                    .body(content.body());
        }

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("flowId", flowId);
        attrs.put("status", "RUNNING");
        return ResponseEntity.ok(
                JsonApiResponseBuilder.single("flow-executions", resultExecutionId, attrs));
    }

    /** The body kind a POST's content type names, or null when it is neither JSON nor a form. */
    private static FlowWebhookPolicy.BodyType bodyType(String contentType, boolean hasBody) {
        if (isForm(contentType)) {
            return FlowWebhookPolicy.BodyType.FORM;
        }
        if (contentType == null || contentType.isBlank()) {
            return hasBody ? null : FlowWebhookPolicy.BodyType.JSON;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            boolean json = MediaType.APPLICATION_JSON.isCompatibleWith(type)
                    || ("application".equals(type.getType()) && type.getSubtype().endsWith("+json"));
            return json || !hasBody ? FlowWebhookPolicy.BodyType.JSON : null;
        } catch (InvalidMediaTypeException e) {
            return null;
        }
    }

    private static boolean isForm(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            return MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    /**
     * The form body's fields. The servlet container has already parsed them into the parameter
     * map, merged with the query string's (whose values come first), so the query's share is
     * removed to leave only what the body carried.
     */
    private static Map<String, List<String>> formFields(HttpServletRequest request, Map<String, List<String>> query) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            int fromQuery = query.getOrDefault(name, List.of()).size();
            if (values.length > fromQuery) {
                fields.put(name, List.of(values).subList(fromQuery, values.length));
            }
        });
        return fields;
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> parseJsonObject(byte[] body) {
        try {
            Object parsed = objectMapper.readValue(body, Object.class);
            return parsed instanceof Map<?, ?> map ? Optional.of((Map<String, Object>) map) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns the webhook URL for a given flow.
     * Used by the UI to display the URL in the flow designer.
     */
    @GetMapping("/api/flows/{flowId}/webhook-url")
    public ResponseEntity<Map<String, Object>> getWebhookUrl(
            @PathVariable String flowId) {

        Optional<Map<String, Object>> flowOpt = flowRepository.findFlowById(flowId);
        if (flowOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> flow = flowOpt.get();
        String flowType = (String) flow.get("flow_type");
        if (!"AUTOLAUNCHED".equals(flowType)) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request",
                            "Webhook URLs are only available for AUTOLAUNCHED flows"));
        }

        String webhookUrl = externalBaseUrl + "/api/webhooks/" + flowId;

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("webhookUrl", webhookUrl);
        attrs.put("flowId", flowId);

        return ResponseEntity.ok(JsonApiResponseBuilder.single("webhook-urls", flowId, attrs));
    }
}
