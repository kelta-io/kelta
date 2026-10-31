package io.kelta.worker.listener;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.flow.FlowEngine;
import io.kelta.worker.service.FlowActorResolver;
import io.kelta.runtime.flow.InitialStateBuilder;
import io.kelta.worker.service.TenantSlugResolver;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NATS listener that starts {@code NATS_TRIGGERED} flows.
 *
 * <p>Consumes the platform trigger namespace
 * {@code kelta.trigger.<tenantId>.<topic>} (queue group — exactly one worker
 * pod handles each message). The tenant id and topic are read from the
 * subject; every active NATS_TRIGGERED flow of that tenant whose trigger
 * config {@code topic} matches starts an execution with the message body as
 * {@code $.input}.
 *
 * <p>Mirrors {@link FlowEventListener}'s per-tenant config cache, invalidated
 * by the same {@code kelta.config.flow.changed.<tenantId>} broadcasts.
 *
 * @since 1.0.0
 */
@Component
@ConditionalOnBean(FlowEngine.class)
public class NatsTriggerFlowListener {

    private static final Logger log = LoggerFactory.getLogger(NatsTriggerFlowListener.class);

    /** Subject prefix for external flow triggers. */
    public static final String SUBJECT_PREFIX = "kelta.trigger.";

    private final FlowEngine flowEngine;
    private final FlowActorResolver flowActorResolver;
    private final InitialStateBuilder initialStateBuilder;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TenantSlugResolver tenantSlugResolver;

    /** Cache: tenantId → active NATS_TRIGGERED flow configs. */
    private final ConcurrentHashMap<String, List<NatsFlowConfig>> configCache =
            new ConcurrentHashMap<>();

    private static final String SELECT_ACTIVE_NATS_FLOWS = """
            SELECT id, tenant_id, definition, trigger_config
            FROM flow
            WHERE tenant_id = ?
              AND flow_type = 'NATS_TRIGGERED'
              AND active = true
            """;

    public NatsTriggerFlowListener(FlowEngine flowEngine,
                                   InitialStateBuilder initialStateBuilder,
                                   JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper,
                                   TenantSlugResolver tenantSlugResolver,
                                   FlowActorResolver flowActorResolver) {
        this.flowEngine = flowEngine;
        this.flowActorResolver = flowActorResolver;
        this.initialStateBuilder = initialStateBuilder;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.tenantSlugResolver = tenantSlugResolver;
    }

    /**
     * Handles a message on {@code kelta.trigger.<tenantId>.<topic>}.
     *
     * @param subject the concrete subject the message arrived on
     * @param message the raw message body (JSON expected; non-JSON is wrapped
     *                as {@code {"raw": "<text>"}})
     */
    public void handleTriggerMessage(String subject, String message) {
        if (subject == null || !subject.startsWith(SUBJECT_PREFIX)) {
            log.warn("Ignoring trigger message on unexpected subject '{}'", subject);
            return;
        }
        String remainder = subject.substring(SUBJECT_PREFIX.length());
        int dot = remainder.indexOf('.');
        if (dot <= 0 || dot == remainder.length() - 1) {
            log.warn("Ignoring trigger message on malformed subject '{}' (expected kelta.trigger.<tenantId>.<topic>)",
                    subject);
            return;
        }
        String tenantId = remainder.substring(0, dot);
        String topic = remainder.substring(dot + 1);

        Map<String, Object> payload = parsePayload(message);

        String tenantSlug = tenantSlugResolver.resolveSlug(tenantId).orElse(null);
        if (tenantSlug == null) {
            log.warn("Could not resolve slug for tenant {} — NATS trigger will fall back to public schema",
                    tenantId);
        }
        TenantContext.runWithTenant(tenantId, tenantSlug, () -> {
            List<NatsFlowConfig> configs = getActiveConfigs(tenantId);
            for (NatsFlowConfig config : configs) {
                if (!topic.equals(config.topic())) {
                    continue;
                }
                try {
                    String executionId = UUID.randomUUID().toString();
                    Map<String, Object> initialState = initialStateBuilder.buildFromNatsMessage(
                            payload, subject, topic, tenantId, config.flowId(), executionId);

                    log.info("Starting flow execution: flowId={}, executionId={}, trigger=NATS_MESSAGE topic={}",
                            config.flowId(), executionId, topic);

                    FlowActorResolver.Resolved actor = flowActorResolver.resolve(tenantId, config.flowId(), null);
                    flowEngine.startExecution(tenantId, config.flowId(), config.definitionJson(),
                            initialState, actor.userId(), actor.source(), null, false);
                } catch (Exception e) {
                    log.error("Error starting NATS-triggered flow {}: {}", config.flowId(), e.getMessage(), e);
                }
            }
        });
    }

    /**
     * Handles {@code kelta.config.flow.changed.<tenantId>} broadcasts by
     * dropping the tenant's cached configs.
     */
    public void handleFlowConfigChanged(String message) {
        try {
            var tree = objectMapper.readTree(message);
            String tenantId = tree.path("tenantId").asText(null);
            if (tenantId == null || tenantId.isBlank()) {
                return;
            }
            configCache.remove(tenantId);
            log.debug("Invalidated NATS trigger cache for tenant {}", tenantId);
        } catch (Exception e) {
            log.error("Failed to handle flow config changed event: {}", e.getMessage(), e);
        }
    }

    private Map<String, Object> parsePayload(String message) {
        if (message == null || message.isBlank()) {
            return Map.of();
        }
        try {
            var tree = objectMapper.readTree(message);
            if (tree.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.treeToValue(tree, Map.class);
                return parsed;
            }
        } catch (Exception e) {
            log.debug("Trigger message body is not JSON, wrapping raw: {}", e.getMessage());
        }
        return Map.of("raw", message);
    }

    @SuppressWarnings("unchecked")
    private List<NatsFlowConfig> getActiveConfigs(String tenantId) {
        return configCache.computeIfAbsent(tenantId, tid -> {
            try {
                return jdbcTemplate.query(SELECT_ACTIVE_NATS_FLOWS, (rs, rowNum) -> {
                    String flowId = rs.getString("id");
                    String definitionJson = rs.getString("definition");
                    String triggerConfigJson = rs.getString("trigger_config");

                    String topic = null;
                    if (triggerConfigJson != null && !triggerConfigJson.isBlank()) {
                        try {
                            Map<String, Object> triggerConfig =
                                    objectMapper.readValue(triggerConfigJson, Map.class);
                            // Same defensive jsonb unwrap as FlowEventListener
                            if ("jsonb".equals(triggerConfig.get("type"))
                                    && triggerConfig.containsKey("value")
                                    && triggerConfig.get("value") instanceof String innerJson) {
                                triggerConfig = objectMapper.readValue(innerJson, Map.class);
                            }
                            Object t = triggerConfig.get("topic");
                            topic = t != null ? t.toString() : null;
                        } catch (Exception e) {
                            log.warn("Failed to parse trigger_config for flow {}: {}", flowId, e.getMessage());
                        }
                    }
                    return new NatsFlowConfig(flowId, definitionJson, topic);
                }, tid).stream().filter(c -> c.topic() != null && !c.topic().isBlank()).toList();
            } catch (Exception e) {
                log.error("Failed to load NATS-triggered flows for tenant {}: {}", tid, e.getMessage(), e);
                return List.of();
            }
        });
    }

    record NatsFlowConfig(String flowId, String definitionJson, String topic) {}
}
