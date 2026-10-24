package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Action handler that publishes a custom event for consumers outside the platform.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "topic": "order.approved",
 *   "eventType": "order.approved",
 *   "dataPayload": {"key1": "value1"}
 * }
 * </pre>
 *
 * <p>The event goes to {@code kelta.event.<tenantId>.<topic>}: the handler, not the flow
 * author, supplies the tenant id, so a flow can only publish into its own tenant's namespace.
 * {@code topic} is one or more dot-separated tokens of letters, digits, {@code -} and
 * {@code _}; wildcards ({@code *}, {@code >}), empty tokens and anything else are rejected so
 * a topic cannot escape that namespace. The message key is {@code tenantId:collectionId}.
 * The envelope is documented in {@code kelta-marketing/.../docs/api/external-events.md}.
 *
 * <p>The {@code EventPublisher} comes from the ModuleContext extensions; without one every
 * execution fails rather than reporting an event that was never sent.
 *
 * @since 1.0.0
 */
public class PublishEventActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(PublishEventActionHandler.class);

    /**
     * Functional interface for event publishing. Implementations can use NATS, SNS, etc.
     */
    @FunctionalInterface
    public interface EventPublisher {
        void publish(String subject, String key, Map<String, Object> eventData);
    }

    /** Subject prefix of every published event; followed by {@code <tenantId>.<topic>}. */
    public static final String SUBJECT_PREFIX = "kelta.event.";

    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");
    private static final Pattern TENANT_TOKEN = Pattern.compile("[A-Za-z0-9_-]+");

    private final ObjectMapper objectMapper;
    private final EventPublisher eventPublisher;

    public PublishEventActionHandler(ObjectMapper objectMapper, EventPublisher eventPublisher) {
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getActionTypeKey() {
        return "PUBLISH_EVENT";
    }

    @Override
    public ActionResult execute(ActionContext context) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                context.actionConfigJson(), new TypeReference<>() {});

            String topic = (String) config.get("topic");
            if (topic == null || topic.isBlank()) {
                return ActionResult.failure("Event 'topic' is required");
            }
            if (!TOPIC.matcher(topic).matches()) {
                return ActionResult.failure("Event 'topic' '" + topic + "' is invalid: use dot-separated "
                    + "tokens of letters, digits, '-' and '_' (no wildcards or empty tokens)");
            }
            String tenantId = context.tenantId();
            if (tenantId == null || !TENANT_TOKEN.matcher(tenantId).matches()) {
                return ActionResult.failure("PUBLISH_EVENT needs a tenant to scope the event subject");
            }
            if (eventPublisher == null) {
                return ActionResult.failure("PUBLISH_EVENT: no event publisher is configured — "
                    + "the event to topic '" + topic + "' was not sent");
            }
            String subject = SUBJECT_PREFIX + tenantId + "." + topic;

            String eventType = (String) config.getOrDefault("eventType", "workflow.custom.event");

            // Build event payload
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("eventId", UUID.randomUUID().toString());
            eventData.put("eventType", eventType);
            eventData.put("tenantId", tenantId);
            eventData.put("collectionId", context.collectionId());
            eventData.put("collectionName", context.collectionName());
            eventData.put("recordId", context.recordId());
            eventData.put("workflowRuleId", context.workflowRuleId());
            eventData.put("timestamp", Instant.now().toString());

            @SuppressWarnings("unchecked")
            Map<String, Object> dataPayload = (Map<String, Object>) config.get("dataPayload");
            if (dataPayload != null) {
                eventData.put("data", dataPayload);
            }

            eventData.put("recordData", context.data());

            String messageKey = tenantId + ":" + context.collectionId();

            eventPublisher.publish(subject, messageKey, eventData);
            log.info("Custom event published: subject={}, eventType={}", subject, eventType);

            return ActionResult.success(Map.of(
                "topic", topic,
                "subject", subject,
                "eventType", eventType,
                "messageKey", messageKey
            ));
        } catch (Exception e) {
            log.error("Failed to execute publish event action: {}", e.getMessage(), e);
            return ActionResult.failure(e);
        }
    }

    @Override
    public void validate(String configJson) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                configJson, new TypeReference<>() {});

            if (config.get("topic") == null) {
                throw new IllegalArgumentException("Config must contain 'topic'");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid config JSON: " + e.getMessage(), e);
        }
    }
}
