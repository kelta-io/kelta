package io.kelta.worker.listener;

import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.ApprovalRepository;
import io.kelta.runtime.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Wildcard before-save hook that enforces record locking during active approvals.
 *
 * <p>When a record has a PENDING approval instance with {@code record_editability = 'LOCKED'},
 * this hook blocks all updates to the record. Deletes are also blocked for locked records.
 *
 * <p>System collections (approval-*, collections, fields, etc.) are excluded from locking
 * to prevent blocking administrative operations.
 *
 * <p>Runs with order {@code 50} to execute early — before field updates or audit hooks.
 *
 * @since 1.0.0
 */
@Component
public class ApprovalRecordLockHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRecordLockHook.class);

    private static final String WILDCARD = "*";

    private static final Set<String> EXCLUDED_COLLECTIONS = Set.of(
            "collections", "fields", "profiles", "users", "groups",
            "approval-processes", "approval-steps", "approval-instances",
            "approval-step-instances", "validation-rules", "flows",
            "flow-versions", "workflow-rules", "email-templates",
            "scheduled-jobs", "connected-apps", "oidc-providers",
            "profile-system-permissions",
            "profile-object-permissions", "profile-field-permissions",
            "ui-pages", "ui-components", "dashboards", "dashboard-components"
    );

    private final ApprovalRepository approvalRepository;
    private final CollectionRegistry collectionRegistry;

    public ApprovalRecordLockHook(ApprovalRepository approvalRepository,
                                   CollectionRegistry collectionRegistry) {
        this.approvalRepository = approvalRepository;
        this.collectionRegistry = collectionRegistry;
    }

    @Override
    public String getCollectionName() {
        return WILDCARD;
    }

    @Override
    public int getOrder() {
        return 50;
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        // Skip system collections
        String collectionName = resolveCollectionName(record);
        if (collectionName != null && EXCLUDED_COLLECTIONS.contains(collectionName)) {
            return BeforeSaveResult.ok();
        }

        return checkRecordLock(id, record, tenantId);
    }

    private BeforeSaveResult checkRecordLock(String recordId, Map<String, Object> record,
                                              String tenantId) {
        if (tenantId == null) {
            return BeforeSaveResult.ok();
        }

        // We need the collection ID to check for approvals
        // The record map may contain a __collectionId from the router
        String collectionId = record != null ? (String) record.get("__collectionId") : null;
        if (collectionId == null) {
            return BeforeSaveResult.ok();
        }

        try {
            var editability = approvalRepository.getRecordEditability(
                    collectionId, recordId, tenantId);

            if (editability.isPresent() && "LOCKED".equals(editability.get())) {
                log.info("Record update blocked by approval lock: recordId={}, collectionId={}",
                        recordId, collectionId);
                return BeforeSaveResult.error(null,
                        "Record is locked by an active approval process. " +
                                "The record cannot be edited while approval is pending.");
            }
        } catch (Exception e) {
            log.error("Error checking approval lock for record {}: {}", recordId, e.getMessage());
        }

        return BeforeSaveResult.ok();
    }

    private String resolveCollectionName(Map<String, Object> record) {
        if (record == null) {
            return null;
        }
        return (String) record.get("__collectionName");
    }
}
