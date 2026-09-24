package io.kelta.worker.listener;

import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.RecordVersionRepository;
import io.kelta.worker.service.CollectionLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Wildcard lifecycle hook that captures a full-record snapshot into {@code record_version}
 * for collections with collection-level {@code trackHistory} enabled — every field is
 * tracked, superseding per-field {@code field.track_history} (see {@link FieldHistoryHook}).
 *
 * <ul>
 *   <li><b>create</b> — version 1: snapshot of the new record, changed fields = all non-null
 *       user fields.</li>
 *   <li><b>update</b> — snapshot of the updated record, changed fields = user fields whose
 *       value differs from the previous state. A no-op update writes no version.</li>
 *   <li><b>delete</b> — captured in {@link #beforeDelete}: the record is re-read while it
 *       still exists and its last state is written as a {@code DELETED} version with an
 *       empty changed-field list.</li>
 * </ul>
 *
 * <p>System collections are skipped — only tenant business data is versioned. Best-effort:
 * any snapshot-write failure is logged, never propagated (it must not break the underlying
 * CRUD). Runs late ({@code order = 910}, after {@link FieldHistoryHook}) so validation/veto
 * hooks decide first; RLS scopes writes to the tenant.
 */
public class RecordVersionHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(RecordVersionHook.class);
    private static final String WILDCARD = "*";
    private static final String SYSTEM_USER = "system";

    /**
     * Keys never counted as changed fields: identity, record-type, and audit columns
     * (both the camelCase names the router injects and the snake_case names the storage
     * adapter returns for tenant collections).
     */
    private static final Set<String> EXCLUDED_FIELDS = Set.of(
            "id", "recordTypeId", "record_type_id",
            "createdAt", "created_at", "createdBy", "created_by",
            "updatedAt", "updated_at", "updatedBy", "updated_by",
            "createdGeo", "created_geo", "updatedGeo", "updated_geo");

    private final RecordVersionRepository versionRepository;
    private final CollectionRegistry collectionRegistry;
    private final CollectionLifecycleManager lifecycleManager;
    private final QueryEngine queryEngine;
    private final ObjectMapper objectMapper;

    public RecordVersionHook(RecordVersionRepository versionRepository,
                             CollectionRegistry collectionRegistry,
                             CollectionLifecycleManager lifecycleManager,
                             QueryEngine queryEngine,
                             ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
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
        return 910;
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
        List<String> changedFields = versionedFieldNames(ctx.definition).stream()
                .filter(field -> record.get(field) != null)
                .toList();
        write(tenantId, ctx.collectionId, recordId, "CREATED", record, changedFields,
                resolveUser(record));
    }

    @Override
    public void afterUpdate(String collectionName, String id, Map<String, Object> record,
                            Map<String, Object> previous, String tenantId) {
        Context ctx = context(collectionName);
        if (ctx == null || id == null || record == null || previous == null) {
            return;
        }
        List<String> changedFields = versionedFieldNames(ctx.definition).stream()
                .filter(field -> !Objects.equals(previous.get(field), record.get(field)))
                .toList();
        if (changedFields.isEmpty()) {
            return; // no-op update — don't mint a version
        }
        write(tenantId, ctx.collectionId, id, "UPDATED", record, changedFields,
                resolveUser(record));
    }

    @Override
    public BeforeSaveResult beforeDelete(String collectionName, String id, String tenantId) {
        Context ctx = context(collectionName);
        if (ctx == null || id == null) {
            return BeforeSaveResult.ok();
        }
        try {
            Map<String, Object> record = queryEngine.getById(ctx.definition, id).orElse(null);
            if (record != null) {
                write(tenantId, ctx.collectionId, id, "DELETED", record, List.of(),
                        resolveUser(record));
            }
        } catch (RuntimeException e) {
            log.warn("Failed to capture delete version for {}/{}: {}", collectionName, id, e.getMessage());
        }
        return BeforeSaveResult.ok();
    }

    /** Resolves the collection when collection-level tracking applies, or null to skip. */
    private Context context(String collectionName) {
        if (collectionName == null) {
            return null;
        }
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null || definition.systemCollection() || !definition.trackHistory()) {
            return null;
        }
        String collectionId = lifecycleManager.getCollectionIdByName(collectionName);
        if (collectionId == null) {
            return null;
        }
        return new Context(collectionId, definition);
    }

    /** User field names eligible for change detection (audit/identity keys excluded). */
    private List<String> versionedFieldNames(CollectionDefinition definition) {
        return definition.fields().stream()
                .map(io.kelta.runtime.model.FieldDefinition::name)
                .filter(name -> !EXCLUDED_FIELDS.contains(name))
                .toList();
    }

    private void write(String tenantId, String collectionId, String recordId, String changeType,
                       Map<String, Object> snapshot, List<String> changedFields, String changedBy) {
        try {
            versionRepository.recordVersion(tenantId, collectionId, recordId, changeType,
                    toJson(snapshot), toJson(changedFields), changedBy, changeSource(changedBy));
        } catch (RuntimeException e) {
            // A lost version is a gap in the History tab that nothing else reports (#1578).
            log.error("Failed to record version for {}/{}: {}", collectionId, recordId, e.getMessage());
        }
    }

    /** Change author from the record's audit fields, falling back to the platform user. */
    private String resolveUser(Map<String, Object> record) {
        String user = asString(record.get("updatedBy"));
        if (user == null) {
            user = asString(record.get("updated_by"));
        }
        if (user == null) {
            user = asString(record.get("createdBy"));
        }
        if (user == null) {
            user = asString(record.get("created_by"));
        }
        return user != null ? user : SYSTEM_USER;
    }

    private String changeSource(String changedBy) {
        return SYSTEM_USER.equals(changedBy) ? "SYSTEM" : "UI";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize record version payload: {}", e.getMessage());
            return null;
        }
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private record Context(String collectionId, CollectionDefinition definition) {
    }
}
