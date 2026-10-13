package io.kelta.runtime.flow;

import java.util.Arrays;
import java.util.List;

/**
 * Per-flow step logging level, stored on the flow row ({@code flow.step_logging}).
 * Controls which {@code flow_step_log} rows the engine writes; the execution row
 * ({@code flow_execution}) is written at every level.
 *
 * <ul>
 *   <li>{@link #FULL} — one row per step with its input and output snapshots (the default).</li>
 *   <li>{@link #ERRORS_ONLY} — one row per step with state id/name/type, status and duration;
 *       snapshots only for a failed step.</li>
 *   <li>{@link #NONE} — only a failed step's row, with its snapshots.</li>
 * </ul>
 *
 * @since 1.0.0
 */
public enum FlowStepLogging {
    FULL,
    ERRORS_ONLY,
    NONE;

    /** The accepted values, in declaration order — the {@code flows.stepLogging} enum. */
    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /**
     * Parses a stored value. Null, blank or unknown values fall back to {@link #FULL}
     * so a flow is never logged less than its author asked for.
     */
    public static FlowStepLogging fromValue(String value) {
        if (value == null || value.isBlank()) {
            return FULL;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return FULL;
        }
    }
}
