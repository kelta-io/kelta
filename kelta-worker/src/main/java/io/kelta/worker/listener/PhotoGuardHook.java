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
 * Owner guard for spotopened's {@code facility-photos} tenant collection.
 * Same shape as {@link FieldReportGuardHook} -- see that class's javadoc for
 * the full reasoning (createdBy over a hand-declared owner field, QueryEngine
 * for beforeDelete, fail-closed on an unresolvable identity, internal tier
 * admitted).
 *
 * <p>This collection is also granted {@code create} (and {@code read}) to
 * spotopened's Guest profile (emf#1368), so a create can legitimately
 * arrive with {@code createdBy} set to the platform's nil-UUID guest
 * sentinel ({@code JwtAuthenticationFilter.GUEST_USER_ID} in kelta-gateway).
 * That value is UUID-shaped on purpose specifically so it needs no special
 * case here: {@code JdbcUserIdResolver.resolve} short-circuits UUID-shaped
 * input and returns it unchanged, so both {@code DynamicCollectionRouter}'s
 * createdBy stamping and this hook's own {@link #callerUuid} resolve it to
 * the identical value with no extra branch. (An earlier version of this
 * sentinel was the plain string {@code "guest"}, which needed exactly such a
 * branch -- and, worse, silently auto-provisioned a real platform_user for
 * every tenant with Guest access, because {@code LoginTrackingFilter} tracks
 * any non-UUID X-User-Id as a login. Fixed at the source in
 * {@code JwtAuthenticationFilter}, not patched around here.)
 *
 * <p>It is Cerbos, not this hook, that keeps a guest from ever reaching
 * {@link #beforeUpdate}/{@link #beforeDelete} at all: Guest has no
 * edit/delete grant on this collection, so those requests never get past
 * {@code RouteAuthorizationFilter}.
 */
public class PhotoGuardHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(PhotoGuardHook.class);

    static final String COLLECTION = "facility-photos";
    private static final String OWNER_FIELD = "createdBy";

    private final UserIdResolver userIdResolver;
    private final CollectionRegistry collectionRegistry;
    private final QueryEngine queryEngine;

    public PhotoGuardHook(UserIdResolver userIdResolver, CollectionRegistry collectionRegistry,
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
            log.warn("facility-photos collection not found in registry -- allowing delete of {}", id);
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
        return BeforeSaveResult.error(OWNER_FIELD, "Photos can only be modified by their uploader");
    }
}
