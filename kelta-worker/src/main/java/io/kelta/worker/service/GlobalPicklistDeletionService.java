package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.PicklistValueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

/**
 * Deletes a global picklist together with its {@code picklist-values} rows, in one transaction.
 *
 * <p>{@code picklist_value.picklist_source_id} has no foreign key to {@code global_picklist}
 * (it also holds field ids), so nothing in the database cascades. Each value is deleted through
 * the {@link QueryEngine}, so its record-deleted event is published after commit and every pod
 * evicts its cached {@code picklist-values} responses. Any failure — in the picklist delete or
 * in the values cleanup — rolls back both.
 */
@Service
public class GlobalPicklistDeletionService {

    static final String VALUES_COLLECTION = "picklist-values";

    private static final Logger log = LoggerFactory.getLogger(GlobalPicklistDeletionService.class);

    private final PicklistValueRepository valueRepository;
    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final TransactionTemplate transactionTemplate;

    public GlobalPicklistDeletionService(PicklistValueRepository valueRepository,
                                         QueryEngine queryEngine,
                                         CollectionRegistry collectionRegistry,
                                         PlatformTransactionManager transactionManager) {
        this.valueRepository = valueRepository;
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Runs {@code deletePicklist} and, when it succeeds, deletes the picklist's values in the
     * same transaction. A non-2xx response (not found, read-only) leaves the values alone.
     */
    public ResponseEntity<Void> delete(String picklistId, Supplier<ResponseEntity<Void>> deletePicklist) {
        return transactionTemplate.execute(status -> {
            ResponseEntity<Void> response = deletePicklist.get();
            if (response.getStatusCode().is2xxSuccessful()) {
                int removed = deleteValues(picklistId);
                log.info("Deleted global picklist '{}' and its {} value(s)", picklistId, removed);
            }
            return response;
        });
    }

    private int deleteValues(String picklistId) {
        List<String> valueIds = valueRepository.findGlobalValueIds(TenantContext.get(), picklistId);
        if (valueIds.isEmpty()) {
            return 0;
        }
        CollectionDefinition values = collectionRegistry.get(VALUES_COLLECTION);
        if (values == null) {
            throw new IllegalStateException("System collection not initialized: " + VALUES_COLLECTION);
        }
        for (String valueId : valueIds) {
            queryEngine.delete(values, valueId);
        }
        return valueIds.size();
    }
}
