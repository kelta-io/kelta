package io.kelta.modules.billing;

import io.kelta.runtime.workflow.ActionContext;

import java.util.Map;

/**
 * Reads a handler's inputs out of {@link ActionContext#resolvedData()}.
 *
 * <p>The flow engine passes the whole <b>state envelope</b> as resolvedData, not the caller's bare
 * values — {@code {"input": {...}, "context": {...}, "trigger": {...}}}. A handler that reads
 * {@code resolvedData.get("planCode")} therefore finds nothing when invoked from a flow, which is
 * the platform's documented {@code $.input.<key>} rule. Manual, MCP and HTTP invocation double-wrap
 * the same way.
 *
 * <p>The platform's own webhook dispatch puts its values at the top level instead, so this checks
 * the nested {@code input} map first and falls back to the top level — one accessor that works on
 * both paths, rather than each handler guessing which one it is on.
 */
final class ActionInputs {

    private ActionInputs() {
    }

    /** The nested {@code input} map when present, else the envelope itself. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> of(ActionContext context) {
        Map<String, Object> resolved = context.resolvedData();
        if (resolved == null || resolved.isEmpty()) {
            return Map.of();
        }
        Object nested = resolved.get("input");
        if (nested instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return resolved;
    }

    /** A trimmed string value, or null when absent or blank. */
    static String string(Map<String, Object> inputs, String key) {
        Object value = inputs.get(key);
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
