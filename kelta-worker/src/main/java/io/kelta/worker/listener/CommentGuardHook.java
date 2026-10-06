package io.kelta.worker.listener;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner guard for spotopened's {@code facility-comments} tenant collection.
 * Identical shape to {@link PhotoGuardHook} -- see that class's javadoc (and
 * {@link FieldReportGuardHook}'s) for the full reasoning, including why a
 * Guest create needs no special case here (the guest sentinel is UUID-shaped
 * specifically so it doesn't).
 */
public class CommentGuardHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(CommentGuardHook.class);

    static final String COLLECTION = "facility-comments";
    private static final String OWNER_FIELD = "createdBy";

    private final UserIdResolver userIdResolver;
    private final CollectionRegistry collectionRegistry;
    private final QueryEngine queryEngine;

    public CommentGuardHook(UserIdResolver userIdResolver, CollectionRegistry collectionRegistry,
                             QueryEngine queryEngine) {
        this.userIdResolver = userIdResolver;
        this.collectionRegistry = collectionRegistry;
        this.queryEngine = queryEngine;
    }

    @Override
    public String getCollectionName() {
        return COLLECTION;
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        String caller = callerUuid(tenantId);
        if (caller == null) {
            return BeforeSaveResult.ok();
        }
        if (CALLER_REJECTED.equals(caller)) {
            return reject("create", "unresolvable identity");
        }
        Object owner = record.get(OWNER_FIELD);
        if (!caller.equals(owner)) {
            return reject("create", "owner " + owner + " != caller");
        }
        return BeforeSaveResult.ok();
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        String caller = callerUuid(tenantId);
        if (caller == null) {
            return BeforeSaveResult.ok();
        }
        if (CALLER_REJECTED.equals(caller)) {
            return reject("update", "unresolvable identity");
        }
        Object currentOwner = previous != null ? previous.get(OWNER_FIELD) : null;
        if (currentOwner != null && !caller.equals(currentOwner)) {
            return reject("update", "row belongs to " + currentOwner);
        }
        Object newOwner = record.get(OWNER_FIELD);
        if (newOwner != null && !caller.equals(newOwner)) {
            return reject("update", "cannot re-own to " + newOwner);
        }
        return BeforeSaveResult.ok();
    }

    @Override
    public BeforeSaveResult beforeDelete(String id, String tenantId) {
        String caller = callerUuid(tenantId);
        if (caller == null) {
            return BeforeSaveResult.ok();
        }
        if (CALLER_REJECTED.equals(caller)) {
            return reject("delete", "unresolvable identity");
        }
        CollectionDefinition definition = collectionRegistry.get(COLLECTION);
        if (definition == null) {
            log.warn("facility-comments collection not found in registry -- allowing delete of {}", id);
            return BeforeSaveResult.ok();
        }
        Optional<Map<String, Object>> row = queryEngine.getById(definition, id);
        Object owner = row.map(r -> r.get(OWNER_FIELD)).orElse(null);
        if (owner != null && !caller.equals(owner)) {
            return reject("delete", "row belongs to " + owner);
        }
        return BeforeSaveResult.ok();
    }

    private static final String CALLER_REJECTED = OwnerGuardCaller.REJECTED;

    private String callerUuid(String tenantId) {
        return OwnerGuardCaller.resolve(userIdResolver, tenantId);
    }

    private BeforeSaveResult reject(String action, String reason) {
        log.warn("Blocked {} on {}: {}", action, COLLECTION, reason);
        return BeforeSaveResult.error(OWNER_FIELD, "Comments can only be modified by their author");
    }
}
