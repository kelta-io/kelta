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
 * Owner guard for spotopened's {@code field-reports} tenant collection — a
 * member's quick check-in on a facility (rate paid, signal, gate/road status).
 * The collection is reachable by any tenant user through the generic dynamic
 * route, so without this hook anyone could edit or delete anyone else's report.
 *
 * <p>{@code field-reports} is a plain tenant collection (created via
 * {@code kelta collections create}, no Flyway migration, no system-collection
 * registration), unlike every other collection this pattern has previously
 * guarded ({@code wins}, {@code user-ui-preferences}) — hence
 * {@link #beforeDelete} reads the existing row through {@link QueryEngine},
 * not a raw JDBC query against a hardcoded table name. A tenant collection's
 * physical table is schema-qualified by tenant slug and only resolved inside
 * {@code PhysicalTableStorageAdapter} (package-private); hand-rolling that
 * resolution here would risk a cross-tenant query if gotten wrong.
 * {@code QueryEngine} already does it correctly for every caller, including
 * the generic write path this hook itself guards.
 *
 * <p>Rule: on any HTTP write carrying a gateway-stamped identity, the row's
 * {@code createdBy} must equal the caller's canonical {@code platform_user}
 * UUID ({@code X-User-Id} header — an email — resolved through
 * {@link UserIdResolver}). {@code createdBy} rather than a hand-declared
 * {@code memberId} field on purpose: {@code DynamicCollectionRouter} stamps
 * {@code createdBy} onto every create from the resolved caller identity
 * <em>before</em> {@code queryEngine.create} runs any before-save hook, and
 * it overwrites whatever the client attributes contained — so a client
 * cannot forge it, and this hook does not need its own field for the same
 * fact the platform already guarantees. An identity that is present but
 * unresolvable is rejected (fail-closed). Writes with no HTTP request
 * context (flows, schedulers, provisioning) are admitted — the same
 * internal-tier contract every other guard hook in this package uses.
 *
 * <p>Registering a hook under the literal name {@code "field-reports"}
 * applies to that collection name in <em>every</em> tenant that happens to
 * create one, not just spotopened — {@link CollectionRegistry}'s own javadoc
 * flags this as expected platform behavior, not something this hook needs to
 * work around.
 */
public class FieldReportGuardHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(FieldReportGuardHook.class);

    static final String COLLECTION = "field-reports";
    private static final String OWNER_FIELD = "createdBy";

    private final UserIdResolver userIdResolver;
    private final CollectionRegistry collectionRegistry;
    private final QueryEngine queryEngine;

    public FieldReportGuardHook(UserIdResolver userIdResolver, CollectionRegistry collectionRegistry,
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
        // beforeDelete carries no row snapshot -- look the owner up through
        // QueryEngine, which resolves the tenant-scoped table itself rather
        // than this hook guessing at schema/table naming.
        CollectionDefinition definition = collectionRegistry.get(COLLECTION);
        if (definition == null) {
            log.warn("field-reports collection not found in registry -- allowing delete of {}", id);
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
        return BeforeSaveResult.error(OWNER_FIELD, "Field reports can only be modified by their author");
    }
}
