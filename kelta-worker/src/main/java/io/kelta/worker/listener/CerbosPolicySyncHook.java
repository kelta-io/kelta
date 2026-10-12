package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.worker.service.CerbosPolicySyncCoalescer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Before-save hook that triggers Cerbos policy sync when profiles change.
 *
 * <p>Listens for create/update/delete on the "profiles" collection and re-syncs
 * the tenant's Cerbos policies through {@link CerbosPolicySyncCoalescer} (debounced
 * + serialized per tenant), so a bulk profile operation collapses to one sync
 * instead of one per record.
 */
@Component
public class CerbosPolicySyncHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(CerbosPolicySyncHook.class);

    private final CerbosPolicySyncCoalescer syncCoalescer;

    public CerbosPolicySyncHook(CerbosPolicySyncCoalescer syncCoalescer) {
        this.syncCoalescer = syncCoalescer;
    }

    @Override
    public String getCollectionName() {
        return "profiles";
    }

    @Override
    public int getOrder() {
        return 100; // Run after validation and audit hooks
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        log.debug("Profile created — scheduling Cerbos policy sync for tenant {}", tenantId);
        syncCoalescer.requestSync(tenantId);
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                             Map<String, Object> previous, String tenantId) {
        log.debug("Profile {} updated — scheduling Cerbos policy sync for tenant {}", id, tenantId);
        syncCoalescer.requestSync(tenantId);
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        log.debug("Profile {} deleted — scheduling Cerbos policy sync for tenant {}", id, tenantId);
        syncCoalescer.requestSync(tenantId);
    }
}
