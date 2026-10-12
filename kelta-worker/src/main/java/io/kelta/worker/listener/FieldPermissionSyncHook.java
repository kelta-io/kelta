package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.worker.service.CerbosPolicySyncCoalescer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Before-save hook that triggers Cerbos policy sync when per-field permissions
 * change.
 *
 * <p>Field visibility (VISIBLE / READ_ONLY / HIDDEN / MASKED) is edited via the
 * {@code profile-field-permissions} system collection, not the {@code profiles}
 * collection — so {@link CerbosPolicySyncHook} never fires for it and, before
 * this hook existed, a visibility change did not reach Cerbos until some
 * unrelated profile save. Sync regenerates the tenant's policies and publishes
 * the existing {@code kelta.cerbos.policies.changed.<tenantId>} subject, which
 * every pod consumes to evict its field-access cache — the multi-pod broadcast
 * rule is satisfied with no new subject.
 *
 * <p>A permission matrix is saved one row at a time (the UI PATCHes each changed
 * row in parallel; bulk imports loop per record), so syncs go through
 * {@link CerbosPolicySyncCoalescer} — it debounces the burst into a single
 * serialized sync per tenant, avoiding both N redundant full policy
 * regenerations and the race where a stale concurrent sync drops a just-saved deny.
 */
@Component
public class FieldPermissionSyncHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(FieldPermissionSyncHook.class);

    private final CerbosPolicySyncCoalescer syncCoalescer;

    public FieldPermissionSyncHook(CerbosPolicySyncCoalescer syncCoalescer) {
        this.syncCoalescer = syncCoalescer;
    }

    @Override
    public String getCollectionName() {
        return "profile-field-permissions";
    }

    @Override
    public int getOrder() {
        return 100; // Run after validation and audit hooks, like CerbosPolicySyncHook
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        log.debug("Field permission created — scheduling Cerbos policy sync for tenant {}", tenantId);
        syncCoalescer.requestSync(tenantId);
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                             Map<String, Object> previous, String tenantId) {
        log.debug("Field permission {} updated — scheduling Cerbos policy sync for tenant {}", id, tenantId);
        syncCoalescer.requestSync(tenantId);
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        log.debug("Field permission {} deleted — scheduling Cerbos policy sync for tenant {}", id, tenantId);
        syncCoalescer.requestSync(tenantId);
    }
}
