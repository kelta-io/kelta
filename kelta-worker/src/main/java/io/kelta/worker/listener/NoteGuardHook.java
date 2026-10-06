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
 * Owner guard for the {@code notes} system collection. {@code notes} is
 * generic-route CRUD with no dedicated controller and no per-row ownership
 * check today — any tenant user can edit or delete any other user's note on
 * any record. Same rule, same structure, same fail-closed/internal-tier
 * contract as {@link FieldReportGuardHook}; see that class's javadoc for the
 * full reasoning (createdBy over a hand-declared owner field, QueryEngine for
 * beforeDelete rather than a raw table-name query, admitting requests with no
 * HTTP identity).
 *
 * <p>Registered under the collection name {@code "notes"}, which — being a
 * system collection defined once in {@code SystemCollectionDefinitions} and
 * shared by every tenant — means this guard applies platform-wide the moment
 * it is registered, not just for spotopened.
 */
public class NoteGuardHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(NoteGuardHook.class);

    static final String COLLECTION = "notes";
    private static final String OWNER_FIELD = "createdBy";

    private final UserIdResolver userIdResolver;
    private final CollectionRegistry collectionRegistry;
    private final QueryEngine queryEngine;

    public NoteGuardHook(UserIdResolver userIdResolver, CollectionRegistry collectionRegistry,
                          QueryEngine queryEngine) {
        this.userIdResolver = userIdResolver;
        this.collectionRegistry = collectionRegistry;
        this.queryEngine = queryEngine;
    }

    @Override
    public String getCollectionName() {
        return COLLECTION;
    }

    /** Runs before all other hooks so denied writes do no earlier side work. */
    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        String caller = callerUuid(tenantId);
        if (caller == null) {
            return BeforeSaveResult.ok(); // internal tier (no HTTP identity)
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
            log.warn("notes collection not found in registry -- allowing delete of {}", id);
            return BeforeSaveResult.ok();
        }
        Optional<Map<String, Object>> row = queryEngine.getById(definition, id);
        Object owner = row.map(r -> r.get(OWNER_FIELD)).orElse(null);
        if (owner != null && !caller.equals(owner)) {
            return reject("delete", "row belongs to " + owner);
        }
        return BeforeSaveResult.ok();
    }

    /** Sentinel for "identity present but unresolvable" (distinct from "no identity"). */
    private static final String CALLER_REJECTED = OwnerGuardCaller.REJECTED;

    /**
     * Returns the caller's platform_user UUID, {@code null} when there is no HTTP request
     * identity (internal tier), or {@link #CALLER_REJECTED} when an identity header is
     * present but cannot be resolved to a UUID.
     */
    private String callerUuid(String tenantId) {
        return OwnerGuardCaller.resolve(userIdResolver, tenantId);
    }

    private BeforeSaveResult reject(String action, String reason) {
        log.warn("Blocked {} on {}: {}", action, COLLECTION, reason);
        return BeforeSaveResult.error(OWNER_FIELD, "Notes can only be modified by their author");
    }
}
