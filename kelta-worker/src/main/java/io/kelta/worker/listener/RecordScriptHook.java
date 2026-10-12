package io.kelta.worker.listener;

import io.kelta.runtime.module.integration.spi.ScriptExecutor;
import io.kelta.runtime.module.integration.spi.ScriptExecutor.ScriptExecutionRequest;
import io.kelta.runtime.module.integration.spi.ScriptExecutor.ScriptExecutionResult;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs tenant-defined record-event scripts (the {@code record-scripts} system collection) through
 * the sandboxed GraalVM {@link ScriptExecutor} as part of the record write lifecycle (unified
 * record experience, slice 7). Wildcard {@link BeforeSaveHook}:
 *
 * <ul>
 *   <li><b>BEFORE_CREATE / BEFORE_UPDATE</b> — a script may block the write (return
 *       {@code { error: "msg", field?: "name" }}) or transform it (return an object of field
 *       updates to merge). A script runtime error fails the write (fail-closed).</li>
 *   <li><b>AFTER_CREATE / AFTER_UPDATE / AFTER_DELETE</b> — side effects; errors are logged, never
 *       block.</li>
 * </ul>
 *
 * <p>Script bindings: {@code record}, {@code previousRecord} (updates), and {@code context}
 * ({@code tenantId}, {@code collectionName}, {@code recordId}, {@code event}).
 *
 * <p><b>Multi-pod consistency:</b> active scripts per collection are cached with a short TTL
 * (rather than a NATS refresh broadcast) — script config is not latency-critical, so a bounded
 * staleness window keeps every pod eventually consistent without extra messaging surface (mirrors
 * the per-tenant OTLP registry's TTL approach). The empty result is cached too, so writes to
 * collections without scripts don't re-query.
 */
@Component
public class RecordScriptHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(RecordScriptHook.class);
    private static final long CACHE_TTL_MS = 60_000L;

    private final JdbcTemplate jdbcTemplate;
    private final ScriptExecutor scriptExecutor;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public RecordScriptHook(JdbcTemplate jdbcTemplate, ScriptExecutor scriptExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.scriptExecutor = scriptExecutor;
    }

    @Override
    public String getCollectionName() {
        return BeforeSaveHookRegistry.WILDCARD;
    }

    @Override
    public int getOrder() {
        // After formula compute (251) and built-in/custom validation, before the audit hook (1000).
        return 300;
    }

    @Override
    public BeforeSaveResult beforeCreate(String collectionName, Map<String, Object> record, String tenantId) {
        return runBefore("BEFORE_CREATE", collectionName, record, null, tenantId);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String collectionName, String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        return runBefore("BEFORE_UPDATE", collectionName, record, previous, tenantId);
    }

    @Override
    public void afterCreate(String collectionName, Map<String, Object> record, String tenantId) {
        runAfter("AFTER_CREATE", collectionName, record, null, tenantId);
    }

    @Override
    public void afterUpdate(String collectionName, String id, Map<String, Object> record,
                             Map<String, Object> previous, String tenantId) {
        runAfter("AFTER_UPDATE", collectionName, record, previous, tenantId);
    }

    @Override
    public void afterDelete(String collectionName, String id, String tenantId) {
        Map<String, Object> record = new HashMap<>();
        record.put("id", id);
        runAfter("AFTER_DELETE", collectionName, record, null, tenantId);
    }

    // ---- execution -------------------------------------------------------------------------

    private BeforeSaveResult runBefore(String event, String collectionName,
                                        Map<String, Object> record, Map<String, Object> previous,
                                        String tenantId) {
        List<ScriptRow> scripts = scriptsFor(collectionName, event, tenantId);
        if (scripts.isEmpty()) {
            return BeforeSaveResult.ok();
        }
        Map<String, Object> merged = new HashMap<>();
        for (ScriptRow script : scripts) {
            ScriptExecutionResult result = safeExecute(script, event, collectionName, record, previous, tenantId);
            if (!result.success()) {
                // Fail-closed: a script error blocks the write.
                return BeforeSaveResult.error(null, safeMessage(result.errorMessage()));
            }
            Object ret = result.output() == null ? null : result.output().get("result");
            if (ret instanceof Map<?, ?> map) {
                Object error = map.get("error");
                if (error instanceof String errMsg) {
                    Object field = map.get("field");
                    return BeforeSaveResult.error(field instanceof String f ? f : null, errMsg);
                }
                // Otherwise treat the returned object as field updates to merge.
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() instanceof String key) {
                        merged.put(key, e.getValue());
                    }
                }
            }
        }
        return merged.isEmpty() ? BeforeSaveResult.ok() : BeforeSaveResult.withFieldUpdates(merged);
    }

    private void runAfter(String event, String collectionName, Map<String, Object> record,
                           Map<String, Object> previous, String tenantId) {
        List<ScriptRow> scripts = scriptsFor(collectionName, event, tenantId);
        for (ScriptRow script : scripts) {
            try {
                safeExecute(script, event, collectionName, record, previous, tenantId);
            } catch (RuntimeException ex) {
                log.warn("After-script {} for collection '{}' failed: {}", script.id(), collectionName,
                        ex.getMessage());
            }
        }
    }

    private ScriptExecutionResult safeExecute(ScriptRow script, String event, String collectionName,
                                               Map<String, Object> record, Map<String, Object> previous,
                                               String tenantId) {
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("record", record != null ? record : Map.of());
        bindings.put("previousRecord", previous != null ? previous : Map.of());
        bindings.put("context", Map.of(
                "tenantId", tenantId != null ? tenantId : "",
                "collectionName", collectionName != null ? collectionName : "",
                "recordId", record != null && record.get("id") != null ? record.get("id") : "",
                "event", event));
        try {
            return scriptExecutor.execute(new ScriptExecutionRequest(script.source(), bindings, script.timeoutSeconds()));
        } catch (RuntimeException ex) {
            return ScriptExecutionResult.failure(ex.getMessage(), 0L);
        }
    }

    private static String safeMessage(String message) {
        return message != null && !message.isBlank() ? message : "Record script rejected the change";
    }

    // ---- script lookup (TTL cache) ---------------------------------------------------------

    private List<ScriptRow> scriptsFor(String collectionName, String event, String tenantId) {
        if (collectionName == null || scriptExecutor == null) {
            return List.of();
        }
        String key = (tenantId != null ? tenantId : "") + "|" + collectionName;
        CacheEntry entry = cache.get(key);
        long now = System.currentTimeMillis();
        if (entry == null || now - entry.loadedAt() > CACHE_TTL_MS) {
            entry = new CacheEntry(now, loadScripts(collectionName));
            cache.put(key, entry);
        }
        return entry.byEvent().getOrDefault(event, List.of());
    }

    private Map<String, List<ScriptRow>> loadScripts(String collectionName) {
        Map<String, List<ScriptRow>> byEvent = new HashMap<>();
        try {
            jdbcTemplate.query(
                    "SELECT rs.trigger_type, rs.id, rs.script_source, rs.timeout_seconds "
                            + "FROM record_script rs JOIN collection c ON c.id = rs.collection_id "
                            + "WHERE c.name = ? AND rs.active = TRUE ORDER BY rs.order_sequence",
                    rs -> {
                        String triggerType = rs.getString("trigger_type");
                        byEvent.computeIfAbsent(triggerType, k -> new ArrayList<>())
                                .add(new ScriptRow(
                                        rs.getString("id"),
                                        rs.getString("script_source"),
                                        rs.getInt("timeout_seconds")));
                    },
                    collectionName);
        } catch (RuntimeException ex) {
            // Never let a script-lookup failure break the write path; treat as "no scripts".
            log.warn("Failed to load record scripts for collection '{}': {}", collectionName, ex.getMessage());
        }
        return byEvent;
    }

    /** Clears the per-collection script cache (test/refresh helper). */
    public void clearCache() {
        cache.clear();
    }

    private record ScriptRow(String id, String source, int timeoutSeconds) {}

    private record CacheEntry(long loadedAt, Map<String, List<ScriptRow>> byEvent) {}
}
