package io.kelta.worker.listener;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The generic write guard for owner-scoped collections (member data ownership slice 2): any
 * collection whose definition declares an {@code ownerField} and an {@code ownerScope} other than
 * {@code NONE}. Reads are scoped in SQL by {@code PhysicalTableStorageAdapter}; this hook covers
 * writes, for callers the scope actually limits ({@link CallerContext#ownerScoped} with
 * {@link CallerContext.Access#WRITE}):
 *
 * <ul>
 *   <li><b>create</b> — the owner field is stamped with the caller. A client-supplied
 *       <em>different</em> owner is a 400 {@code OWNER_MISMATCH}: creating for someone else is a
 *       client bug, not a probe.</li>
 *   <li><b>update / delete</b> — the stored owner must be the caller. A foreign or missing row is
 *       a <b>404</b>, never a 400/403, so owner scoping does not reveal that a row exists.</li>
 *   <li><b>owner field</b> — immutable for a scoped caller: a different value on update is a 400
 *       {@code OWNER_IMMUTABLE}.</li>
 * </ul>
 *
 * <p>No bound {@link CallerContext} is the internal tier (flows, NATS listeners, schedulers,
 * provisioning) and is admitted, as is any caller the scope does not limit (INTERNAL staff under
 * {@code PORTAL}; {@code MODIFY_ALL_DATA} under {@code ALL}).
 *
 * <p>Wildcard, order −100, so a denied write does no earlier side work. Collections without ownership return immediately after one registry read.
 */
public class OwnerScopeGuardHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(OwnerScopeGuardHook.class);

    static final String OWNER_MISMATCH = "OWNER_MISMATCH";
    static final String OWNER_IMMUTABLE = "OWNER_IMMUTABLE";

    private final CollectionRegistry collectionRegistry;
    private final QueryEngine queryEngine;

    public OwnerScopeGuardHook(CollectionRegistry collectionRegistry, QueryEngine queryEngine) {
        this.collectionRegistry = collectionRegistry;
        this.queryEngine = queryEngine;
    }

    @Override
    public String getCollectionName() {
        return "*";
    }

    @Override
    public int getOrder() {
        return -100;
    }

    /** A write the guard applies to: the owner-scoped collection and the scoped caller. */
    private record Scoped(CollectionDefinition definition, String callerId) {
        String ownerField() {
            return definition.ownerField();
        }
    }

    private Optional<Scoped> scoped(String collectionName) {
        Optional<CallerContext> caller = CallerContext.current();
        if (caller.isEmpty()) {
            return Optional.empty();
        }
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null || !definition.isOwnerScoped()
                || !caller.get().ownerScoped(definition.ownerScope(), CallerContext.Access.WRITE)) {
            return Optional.empty();
        }
        return Optional.of(new Scoped(definition, caller.get().userId()));
    }

    @Override
    public BeforeSaveResult beforeCreate(String collectionName, Map<String, Object> record, String tenantId) {
        Optional<Scoped> scoped = scoped(collectionName);
        if (scoped.isEmpty()) {
            return BeforeSaveResult.ok();
        }
        String ownerField = scoped.get().ownerField();
        String caller = scoped.get().callerId();
        String supplied = asId(record.get(ownerField));
        if (caller == null || (supplied != null && !supplied.equals(caller))) {
            log.warn("Blocked create on owner-scoped {}: owner {} != caller {}", collectionName, supplied, caller);
            return BeforeSaveResult.errors(List.of(new BeforeSaveResult.ValidationError(ownerField,
                    "Records can only be created for yourself", OWNER_MISMATCH)));
        }
        return BeforeSaveResult.withFieldUpdates(Map.of(ownerField, caller));
    }

    @Override
    public BeforeSaveResult beforeUpdate(String collectionName, String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        Optional<Scoped> scoped = scoped(collectionName);
        if (scoped.isEmpty()) {
            return BeforeSaveResult.ok();
        }
        String ownerField = scoped.get().ownerField();
        String caller = scoped.get().callerId();
        String stored = previous != null ? asId(previous.get(ownerField)) : null;
        if (caller == null || !caller.equals(stored)) {
            throw notFound(collectionName, id, "update");
        }
        if (record.containsKey(ownerField)) {
            String requested = asId(record.get(ownerField));
            if (requested != null && !requested.equals(caller)) {
                log.warn("Blocked re-owning {}/{} to {}", collectionName, id, requested);
                return BeforeSaveResult.errors(List.of(new BeforeSaveResult.ValidationError(ownerField,
                        "The owner of a record cannot be changed", OWNER_IMMUTABLE)));
            }
        }
        return BeforeSaveResult.ok();
    }

    @Override
    public BeforeSaveResult beforeDelete(String collectionName, String id, String tenantId) {
        Optional<Scoped> scoped = scoped(collectionName);
        if (scoped.isEmpty()) {
            return BeforeSaveResult.ok();
        }
        String caller = scoped.get().callerId();
        // beforeDelete carries no row snapshot. Read it through QueryEngine, which resolves the
        // tenant's table — and, when reads are owner-scoped too, already hides a foreign row.
        Optional<Map<String, Object>> row = queryEngine.getById(scoped.get().definition(), id);
        String stored = row.map(r -> asId(r.get(scoped.get().ownerField()))).orElse(null);
        if (caller == null || !caller.equals(stored)) {
            throw notFound(collectionName, id, "delete");
        }
        return BeforeSaveResult.ok();
    }

    private static ResponseStatusException notFound(String collectionName, String id, String action) {
        log.warn("Blocked {} of {}/{}: not owned by the caller", action, collectionName, id);
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found");
    }

    /** An owner value as an id string; blank is no value. Lookups may arrive as {@code {id: …}}. */
    private static String asId(Object value) {
        if (value instanceof Map<?, ?> map) {
            value = map.get("id");
        }
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
