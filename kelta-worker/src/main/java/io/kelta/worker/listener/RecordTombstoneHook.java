package io.kelta.worker.listener;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.worker.repository.RecordTombstoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wildcard after-delete hook that records a tombstone whenever a user-collection record is deleted,
 * so the offline-sync changes feed ({@code GET /api/{collection}/_changes}) can tell clients which
 * records to prune. System collections are skipped — only tenant business data is offline-synced.
 *
 * <p>Best-effort: a tombstone-write failure is logged, never propagated (it must not break the
 * delete). Runs at order 250 (after attachment cascade cleanup at 200).
 */
@Component
public class RecordTombstoneHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(RecordTombstoneHook.class);
    private static final String WILDCARD = "*";

    private final RecordTombstoneRepository tombstoneRepository;
    private final CollectionRegistry collectionRegistry;

    public RecordTombstoneHook(RecordTombstoneRepository tombstoneRepository,
                               CollectionRegistry collectionRegistry) {
        this.tombstoneRepository = tombstoneRepository;
        this.collectionRegistry = collectionRegistry;
    }

    @Override
    public String getCollectionName() {
        return WILDCARD;
    }

    @Override
    public int getOrder() {
        return 250;
    }

    @Override
    public void afterDelete(String collectionName, String id, String tenantId) {
        if (collectionName == null || id == null || tenantId == null) {
            return;
        }
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition != null && definition.systemCollection()) {
            return; // platform metadata isn't part of the tenant's offline data set
        }
        try {
            tombstoneRepository.record(tenantId, collectionName, id);
        } catch (RuntimeException e) {
            log.warn("Failed to record tombstone for {}/{}: {}", collectionName, id, e.getMessage());
        }
    }
}
