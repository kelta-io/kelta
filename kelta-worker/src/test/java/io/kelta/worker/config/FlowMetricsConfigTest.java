package io.kelta.worker.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FlowMetricsConfigTest {

    private MeterRegistry meterRegistry;
    private FlowMetricsConfig metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new FlowMetricsConfig(meterRegistry);
    }

    @Test
    void shouldRegisterActiveExecutionGauge() {
        assertNotNull(meterRegistry.find("kelta_flow_execution_active").gauge());
        assertEquals(0, meterRegistry.find("kelta_flow_execution_active").gauge().value());
    }

    @Test
    void shouldTrackActiveExecutions() {
        metrics.executionStarted();
        metrics.executionStarted();
        assertEquals(2, meterRegistry.find("kelta_flow_execution_active").gauge().value());

        metrics.executionEnded();
        assertEquals(1, meterRegistry.find("kelta_flow_execution_active").gauge().value());
    }

    @Test
    void shouldRecordExecutionMetrics() {
        metrics.recordExecution("flow-1", "My Flow", "COMPLETED", 1500, false);

        var counter = meterRegistry.find("kelta_flow_execution_total")
                .tag("flow_id", "flow-1")
                .tag("status", "COMPLETED")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());

        var timer = meterRegistry.find("kelta_flow_execution_duration_seconds")
                .tag("flow_id", "flow-1")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void shouldNotRecordTestExecutions() {
        metrics.recordExecution("flow-1", "My Flow", "COMPLETED", 1000, true);

        var counter = meterRegistry.find("kelta_flow_execution_total")
                .tag("flow_id", "flow-1")
                .counter();
        assertNull(counter);
    }

    @Test
    void shouldRecordStepMetrics() {
        metrics.recordStep("flow-1", "Task", "HTTP_CALLOUT", "SUCCEEDED", 250);

        var counter = meterRegistry.find("kelta_flow_step_total")
                .tag("flow_id", "flow-1")
                .tag("state_type", "Task")
                .tag("resource", "HTTP_CALLOUT")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());

        var timer = meterRegistry.find("kelta_flow_step_duration_seconds")
                .tag("state_type", "Task")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void shouldRecordStepMetricsWithNullResource() {
        metrics.recordStep("flow-1", "Choice", null, "SUCCEEDED", 5);

        var counter = meterRegistry.find("kelta_flow_step_total")
                .tag("state_type", "Choice")
                .tag("resource", "none")
                .counter();
        assertNotNull(counter);
    }

    @Test
    void shouldRecordErrorMetrics() {
        metrics.recordError("flow-1", "HttpTimeout");

        var counter = meterRegistry.find("kelta_flow_error_total")
                .tag("flow_id", "flow-1")
                .tag("error_code", "HttpTimeout")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void shouldRecordErrorMetricsWithNullCode() {
        metrics.recordError("flow-1", null);

        var counter = meterRegistry.find("kelta_flow_error_total")
                .tag("error_code", "unknown")
                .counter();
        assertNotNull(counter);
    }

    // ---------------------------------------------------------------------------
    // FlowExecutionListener interface tests
    // ---------------------------------------------------------------------------

    @Test
    void listenerOnExecutionStartedIncrementsGauge() {
        metrics.onExecutionStarted("flow-1");
        assertEquals(1, meterRegistry.find("kelta_flow_execution_active").gauge().value());
    }

    @Test
    void listenerOnExecutionCompletedDecrementsGaugeAndRecords() {
        metrics.onExecutionStarted("flow-1");
        metrics.onExecutionCompleted("flow-1", "COMPLETED", 2000, false);

        assertEquals(0, meterRegistry.find("kelta_flow_execution_active").gauge().value());

        var counter = meterRegistry.find("kelta_flow_execution_total")
                .tag("flow_id", "flow-1")
                .tag("status", "COMPLETED")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void listenerOnStepCompletedRecordsStep() {
        metrics.onStepCompleted("flow-1", "Task", "EMAIL_ALERT", "SUCCEEDED", 300);

        var counter = meterRegistry.find("kelta_flow_step_total")
                .tag("flow_id", "flow-1")
                .tag("state_type", "Task")
                .tag("resource", "EMAIL_ALERT")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void listenerOnExecutionErrorRecordsError() {
        metrics.onExecutionError("flow-1", "TimeoutException");

        var counter = meterRegistry.find("kelta_flow_error_total")
                .tag("flow_id", "flow-1")
                .tag("error_code", "TimeoutException")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void shouldRecordMultipleExecutionsForSameFlow() {
        metrics.recordExecution("flow-1", "My Flow", "COMPLETED", 1000, false);
        metrics.recordExecution("flow-1", "My Flow", "COMPLETED", 2000, false);
        metrics.recordExecution("flow-1", "My Flow", "FAILED", 500, false);

        var completedCounter = meterRegistry.find("kelta_flow_execution_total")
                .tag("flow_id", "flow-1")
                .tag("status", "COMPLETED")
                .counter();
        assertNotNull(completedCounter);
        assertEquals(2.0, completedCounter.count());

        var failedCounter = meterRegistry.find("kelta_flow_execution_total")
                .tag("flow_id", "flow-1")
                .tag("status", "FAILED")
                .counter();
        assertNotNull(failedCounter);
        assertEquals(1.0, failedCounter.count());
    }

    // ---------------------------------------------------------------------------
    // flow_name resolution
    // ---------------------------------------------------------------------------

    @Test
    void listenerTagsExecutionWithResolvedFlowName() {
        metrics = new FlowMetricsConfig(meterRegistry, flowId -> Optional.of("Nightly Sync"));

        metrics.onExecutionCompleted("flow-1", "COMPLETED", 100, false);

        var counter = meterRegistry.find("kelta_flow_execution_total")
                .tag("flow_id", "flow-1")
                .tag("flow_name", "Nightly Sync")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
        assertNotNull(meterRegistry.find("kelta_flow_execution_duration_seconds")
                .tag("flow_id", "flow-1")
                .tag("flow_name", "Nightly Sync")
                .timer());
    }

    @Test
    void flowNameLookupIsCachedAcrossExecutions() {
        AtomicInteger lookups = new AtomicInteger();
        metrics = new FlowMetricsConfig(meterRegistry, flowId -> {
            lookups.incrementAndGet();
            return Optional.of("Nightly Sync");
        });

        for (int i = 0; i < 5; i++) {
            metrics.onExecutionCompleted("flow-1", "COMPLETED", 100, false);
        }

        assertEquals(1, lookups.get());
        assertEquals(5.0, meterRegistry.find("kelta_flow_execution_total")
                .tag("flow_name", "Nightly Sync").counter().count());
    }

    @Test
    void failedLookupFallsBackToUnknownWithoutThrowing() {
        metrics = new FlowMetricsConfig(meterRegistry, flowId -> {
            throw new IllegalStateException("database unavailable");
        });

        assertDoesNotThrow(() -> metrics.onExecutionCompleted("flow-1", "FAILED", 100, false));

        var counter = meterRegistry.find("kelta_flow_execution_total")
                .tag("flow_id", "flow-1")
                .tag("flow_name", "unknown")
                .tag("status", "FAILED")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void failedLookupIsNotCachedSoTheNextExecutionRetries() {
        AtomicInteger lookups = new AtomicInteger();
        metrics = new FlowMetricsConfig(meterRegistry, flowId -> {
            if (lookups.incrementAndGet() == 1) {
                throw new IllegalStateException("transient");
            }
            return Optional.of("Nightly Sync");
        });

        metrics.onExecutionCompleted("flow-1", "COMPLETED", 100, false);
        metrics.onExecutionCompleted("flow-1", "COMPLETED", 100, false);

        assertEquals(1.0, meterRegistry.find("kelta_flow_execution_total")
                .tag("flow_name", "unknown").counter().count());
        assertEquals(1.0, meterRegistry.find("kelta_flow_execution_total")
                .tag("flow_name", "Nightly Sync").counter().count());
    }

    @Test
    void missingFlowFallsBackToUnknown() {
        metrics = new FlowMetricsConfig(meterRegistry, flowId -> Optional.empty());

        metrics.onExecutionCompleted("flow-1", "COMPLETED", 100, false);

        assertNotNull(meterRegistry.find("kelta_flow_execution_total")
                .tag("flow_id", "flow-1")
                .tag("flow_name", "unknown")
                .counter());
    }

    @Test
    void testExecutionsSkipTheNameLookup() {
        AtomicInteger lookups = new AtomicInteger();
        metrics = new FlowMetricsConfig(meterRegistry, flowId -> {
            lookups.incrementAndGet();
            return Optional.of("Nightly Sync");
        });

        metrics.onExecutionCompleted("flow-1", "COMPLETED", 100, true);

        assertEquals(0, lookups.get());
        assertNull(meterRegistry.find("kelta_flow_execution_total").counter());
    }
}
