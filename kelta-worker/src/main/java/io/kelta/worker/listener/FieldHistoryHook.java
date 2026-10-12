package io.kelta.worker.listener;

import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.FieldHistoryRepository;
import io.kelta.worker.repository.FieldHistoryRepository.Change;
import io.kelta.worker.service.CollectionLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wildcard lifecycle hook that records value changes of {@code trackHistory}-enabled fields into
 * {@code field_history}, so per-record change timelines (admin + end-user) have data.
 *
 * <ul>
 *   <li><b>create</b> — one row per tracked field with a non-null initial value (old=null → new=value).</li>
 *   <li><b>update</b> — one row per tracked field whose value actually changed (old → new).</li>
 *   <li><b>delete</b> — captured in {@link #beforeDelete}: the record is re-read while it still exists
 *       and one row per tracked field with a prior value is written (old=value → new=null).</li>
 * </ul>
 *
 * <p>System collections are skipped — only tenant business data is tracked. Best-effort: any
 * history-write failure is logged, never propagated (it must not break the underlying CRUD). Runs
 * late ({@code order = 900}) so validation/veto hooks decide first; RLS scopes writes to the tenant.
 */
public class FieldHistoryHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(FieldHistoryHook.class);
    private static final String WILDCARD = "*";
    private static final String SYSTEM_USER = "system";

    private final FieldHistoryRepository historyRepository;
    private final CollectionRegistry collectionRegistry;
    private final CollectionLifecycleManager lifecycleManager;
    private final QueryEngine queryEngine;
    private final ObjectMapper objectMapper;

    public FieldHistoryHook(FieldHistoryRepository historyRepository,
                            CollectionRegistry collectionRegistry,
                            CollectionLifecycleManager lifecycleManager,
                            QueryEngine queryEngine,
                            ObjectMapper objectMapper) {
        this.historyRepository = historyRepository;
        this.collectionRegistry = collectionRegistry;
        this.lifecycleManager = lifecycleManager;
        this.queryEngine = queryEngine;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getCollectionName() {
        return WILDCARD;
    }

    @Override
    public int getOrder() {
        return 900;
    }

    @Override
    public void afterCreate(String collectionName, Map<String, Object> record, String tenantId) {
        Context ctx = context(collectionName);
        if (ctx == null || record == null) {
            return;
        }
        String recordId = asString(record.get("id"));
        if (recordId == null) {
            return;
        }
        List<Change> changes = ctx.trackedFields.stream()
                .filter(field -> record.get(field) != null)
                .map(field -> new Change(field, null, toJson(record.get(field))))
                .toList();
        write(tenantId, ctx.collectionId, recordId, resolveUser(record), changes);
    }

    @Override
    public void afterUpdate(String collectionName, String id, Map<String, Object> record,
                            Map<String, Object> previous, String tenantId) {
        Context ctx = context(collectionName);
        if (ctx == null || id == null || record == null || previous == null) {
            return;
        }
        List<Change> changes = ctx.trackedFields.stream()
                .filter(field -> !Objects.equals(previous.get(field), record.get(field)))
                .map(field -> new Change(field, toJson(previous.get(field)), toJson(record.get(field))))
                .toList();
        write(tenantId, ctx.collectionId, id, resolveUser(record), changes);
    }

    @Override
    public BeforeSaveResult beforeDelete(String collectionName, String id, String tenantId) {
        Context ctx = context(collectionName);
        if (ctx == null || id == null) {
            return BeforeSaveResult.ok();
        }
        try {
            CollectionDefinition definition = collectionRegistry.get(collectionName);
            Map<String, Object> record = queryEngine.getById(definition, id).orElse(null);
            if (record != null) {
                List<Change> changes = ctx.trackedFields.stream()
                        .filter(field -> record.get(field) != null)
                        .map(field -> new Change(field, toJson(record.get(field)), null))
                        .toList();
                write(tenantId, ctx.collectionId, id, resolveUser(record), changes);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to capture delete history for {}/{}: {}", collectionName, id, e.getMessage());
        }
        return BeforeSaveResult.ok();
    }

    /** Resolves the collection + its tracked fields, or null when nothing should be tracked. */
    private Context context(String collectionName) {
        if (collectionName == null) {
            return null;
        }
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null || definition.systemCollection()) {
            return null; // platform metadata isn't field-history tracked
        }
        if (definition.trackHistory()) {
            return null; // collection-level versioning supersedes per-field history (RecordVersionHook)
        }
        List<String> trackedFields = definition.fields().stream()
                .filter(FieldDefinition::trackHistory)
                .filter(field -> !field.serverOnly()) // a server-only value never enters history
                .map(FieldDefinition::name)
                .toList();
        if (trackedFields.isEmpty()) {
            return null;
        }
        String collectionId = lifecycleManager.getCollectionIdByName(collectionName);
        if (collectionId == null) {
            return null;
        }
        return new Context(collectionId, trackedFields);
    }

    private void write(String tenantId, String collectionId, String recordId,
                       String changedBy, List<Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        try {
            historyRepository.recordChanges(tenantId, collectionId, recordId, changedBy,
                    changeSource(changedBy), changes);
        } catch (RuntimeException e) {
            log.warn("Failed to record field history for {}/{}: {}", collectionId, recordId, e.getMessage());
        }
    }

    /** Change author from the record's audit fields, falling back to the platform user. */
    private String resolveUser(Map<String, Object> record) {
        String user = asString(record.get("updatedBy"));
        if (user == null) {
            user = asString(record.get("createdBy"));
        }
        return user != null ? user : SYSTEM_USER;
    }

    private String changeSource(String changedBy) {
        return SYSTEM_USER.equals(changedBy) ? "SYSTEM" : "UI";
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // Fall back to a JSON string so a serialization quirk never drops the whole change.
            try {
                return objectMapper.writeValueAsString(String.valueOf(value));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private record Context(String collectionId, List<String> trackedFields) {
    }
}
