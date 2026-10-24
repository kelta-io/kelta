package io.kelta.worker.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.kelta.runtime.flow.FlowExecutionListener;
import io.kelta.worker.repository.FlowRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registers Prometheus-compatible metrics for flow execution observability.
 * <p>
 * Implements {@link FlowExecutionListener} so it can be injected into the
 * {@link io.kelta.runtime.flow.FlowEngine} to record metrics automatically
 * as executions and steps complete.
 * <p>
 * Exposes the following metrics at {@code /actuator/prometheus}:
 * <ul>
 *   <li>{@code kelta_flow_execution_total} (counter) — total executions by flow, status</li>
 *   <li>{@code kelta_flow_execution_duration_seconds} (timer) — execution duration distribution</li>
 *   <li>{@code kelta_flow_step_total} (counter) — total step executions by type, resource, status</li>
 *   <li>{@code kelta_flow_step_duration_seconds} (timer) — step duration distribution</li>
 *   <li>{@code kelta_flow_error_total} (counter) — errors by flow and error code</li>
 *   <li>{@code kelta_flow_execution_active} (gauge) — currently running executions</li>
 * </ul>
 * <p>
 * The engine reports only a flow's id, so {@code flow_name} is resolved through a bounded,
 * expiring cache of flow names. A missing flow or a failed lookup is tagged {@code "unknown"}.
 *
 * @since 1.0.0
 */
@Component
public class FlowMetricsConfig implements FlowExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(FlowMetricsConfig.class);

    static final String UNKNOWN_FLOW_NAME = "unknown";
    private static final int FLOW_NAME_CACHE_MAX_SIZE = 10_000;
    private static final Duration FLOW_NAME_CACHE_TTL = Duration.ofMinutes(10);

    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeExecutions = new AtomicInteger(0);
    private final FlowNameLookup flowNameLookup;
    private final Cache<String, String> flowNames = Caffeine.newBuilder()
            .maximumSize(FLOW_NAME_CACHE_MAX_SIZE)
            .expireAfterWrite(FLOW_NAME_CACHE_TTL)
            .build();

    /**
     * Resolves a flow's display name by id. Runs on the engine thread, under the
     * execution's tenant.
     */
    @FunctionalInterface
    public interface FlowNameLookup {
        Optional<String> findFlowName(String flowId);
    }

    @Autowired
    public FlowMetricsConfig(MeterRegistry meterRegistry, FlowRepository flowRepository) {
        this(meterRegistry, flowRepository::findFlowNameById);
    }

    public FlowMetricsConfig(MeterRegistry meterRegistry) {
        this(meterRegistry, flowId -> Optional.empty());
    }

    public FlowMetricsConfig(MeterRegistry meterRegistry, FlowNameLookup flowNameLookup) {
        this.meterRegistry = meterRegistry;
        this.flowNameLookup = flowNameLookup;

        io.micrometer.core.instrument.Gauge.builder("kelta_flow_execution_active",
                        activeExecutions, AtomicInteger::get)
                .description("Currently running flow executions")
                .register(meterRegistry);
    }

    /**
     * Records a completed flow execution.
     *
     * @param flowId     the flow ID
     * @param flowName   the flow name
     * @param status     final status (COMPLETED, FAILED, CANCELLED)
     * @param durationMs execution duration in milliseconds
     * @param isTest     whether this was a test execution
     */
    public void recordExecution(String flowId, String flowName, String status,
                                 long durationMs, boolean isTest) {
        if (isTest) return; // Don't count test executions in metrics

        Counter.builder("kelta_flow_execution_total")
                .description("Total flow executions by outcome")
                .tag("flow_id", flowId)
                .tag("flow_name", flowName != null ? flowName : UNKNOWN_FLOW_NAME)
                .tag("status", status)
                .register(meterRegistry)
                .increment();

        Timer.builder("kelta_flow_execution_duration_seconds")
                .description("Flow execution duration distribution")
                .tag("flow_id", flowId)
                .tag("flow_name", flowName != null ? flowName : UNKNOWN_FLOW_NAME)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Records a completed step execution.
     *
     * @param flowId     the flow ID
     * @param stateType  state type (Task, Choice, Wait, etc.)
     * @param resource   resource key for Task states (null for non-Task)
     * @param status     step status (SUCCEEDED, FAILED)
     * @param durationMs step duration in milliseconds
     */
    public void recordStep(String flowId, String stateType, String resource,
                            String status, long durationMs) {
        Counter.builder("kelta_flow_step_total")
                .description("Total flow step executions")
                .tag("flow_id", flowId)
                .tag("state_type", stateType)
                .tag("resource", resource != null ? resource : "none")
                .tag("status", status)
                .register(meterRegistry)
                .increment();

        Timer.builder("kelta_flow_step_duration_seconds")
                .description("Flow step duration distribution")
                .tag("flow_id", flowId)
                .tag("state_type", stateType)
                .tag("resource", resource != null ? resource : "none")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Records a flow error.
     *
     * @param flowId    the flow ID
     * @param errorCode the error code or type
     */
    public void recordError(String flowId, String errorCode) {
        Counter.builder("kelta_flow_error_total")
                .description("Flow errors by type")
                .tag("flow_id", flowId)
                .tag("error_code", errorCode != null ? errorCode : "unknown")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Increments the active execution gauge. Call when an execution starts.
     */
    public void executionStarted() {
        activeExecutions.incrementAndGet();
    }

    /**
     * Decrements the active execution gauge. Call when an execution ends.
     */
    public void executionEnded() {
        activeExecutions.decrementAndGet();
    }

    /**
     * Returns the flow's display name from the cache, loading it on a miss. Never throws:
     * a missing flow or a failed lookup yields {@code "unknown"} and is not cached, so the
     * next execution retries.
     */
    String resolveFlowName(String flowId) {
        if (flowId == null) {
            return UNKNOWN_FLOW_NAME;
        }
        String name = flowNames.get(flowId, this::loadFlowName);
        return name != null ? name : UNKNOWN_FLOW_NAME;
    }

    private String loadFlowName(String flowId) {
        try {
            return flowNameLookup.findFlowName(flowId).filter(n -> !n.isBlank()).orElse(null);
        } catch (Exception e) {
            log.warn("Could not resolve name of flow {} for metrics: {}", flowId, e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------------------
    // FlowExecutionListener implementation — called by FlowEngine
    // ---------------------------------------------------------------------------

    @Override
    public void onExecutionStarted(String flowId) {
        executionStarted();
    }

    @Override
    public void onExecutionCompleted(String flowId, String status, long durationMs, boolean isTest) {
        executionEnded();
        if (isTest) return;
        recordExecution(flowId, resolveFlowName(flowId), status, durationMs, false);
    }

    @Override
    public void onStepCompleted(String flowId, String stateType, String resource,
                                String status, long durationMs) {
        recordStep(flowId, stateType, resource, status, durationMs);
    }

    @Override
    public void onExecutionError(String flowId, String errorCode) {
        recordError(flowId, errorCode);
    }
}
