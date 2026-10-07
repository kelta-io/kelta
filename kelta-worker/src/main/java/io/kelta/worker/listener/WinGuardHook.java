package io.kelta.worker.listener;

import io.kelta.runtime.router.UserIdResolver;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owner guard for the {@code wins} system collection.
 *
 * <p>{@code wins} is reachable through the generic dynamic route, so without this hook any
 * authenticated tenant user could create, edit, or delete anyone else's wins — including
 * re-owning one to themselves or flipping another member's private win to {@code isPublic} on
 * the ticker. {@code WinController} scopes the purpose-built API; the generic route bypasses it,
 * so both doors need locking.
 *
 * <p>Rule: on any HTTP write carrying a gateway-stamped identity, the row's {@code memberId}
 * must equal the caller's canonical {@code platform_user} UUID. An identity that is present but
 * unresolvable is rejected — <b>fail closed</b>. Writes with no HTTP request context (flows,
 * schedulers) are admitted: the internal tier, the same contract {@link WatchGuardHook} and
 * {@code UserPreferenceGuardHook} follow. Deliberately mirrors {@link WatchGuardHook} so a
 * reviewer who has read one can check the other at a glance.
 */
public class WinGuardHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(WinGuardHook.class);

    static final String COLLECTION = "wins";

    /** Sentinel for "identity present but unresolvable" — deliberately not a valid UUID. */
    private static final String CALLER_REJECTED = OwnerGuardCaller.REJECTED;

    private final UserIdResolver userIdResolver;
    private final JdbcTemplate jdbcTemplate;

    public WinGuardHook(UserIdResolver userIdResolver, JdbcTemplate jdbcTemplate) {
        this.userIdResolver = userIdResolver;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getCollectionName() {
        return COLLECTION;
    }

    /** Runs before other hooks so a denied write does no earlier side work. */
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
        Object owner = record.get("memberId");
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
        Object currentOwner = previous != null ? previous.get("memberId") : null;
        if (currentOwner != null && !caller.equals(currentOwner)) {
            return reject("update", "win belongs to " + currentOwner);
        }
        Object newOwner = record.get("memberId");
        if (newOwner != null && !caller.equals(newOwner)) {
            // Blocks the subtler attack: editing your own win to hand it to someone else.
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
        // beforeDelete carries no row snapshot — look the owner up (RLS scopes by tenant).
        List<String> owners = jdbcTemplate.query(
                "SELECT member_id FROM win WHERE id = ?",
                (rs, i) -> rs.getString(1), id);
        if (!owners.isEmpty() && !caller.equals(owners.get(0))) {
            return reject("delete", "win belongs to " + owners.get(0));
        }
        return BeforeSaveResult.ok();
    }

    /**
     * The caller's platform_user UUID, {@code null} when there is no HTTP request identity
     * (internal tier), or {@link #CALLER_REJECTED} when a header is present but cannot be
     * resolved. {@code UserIdResolver} returns the original identifier on failure, so the result
     * is re-validated as a UUID.
     */
    private String callerUuid(String tenantId) {
        return OwnerGuardCaller.resolve(userIdResolver, tenantId);
    }

    private BeforeSaveResult reject(String action, String reason) {
        log.warn("Blocked {} on {}: {}", action, COLLECTION, reason);
        return BeforeSaveResult.error("memberId", "Wins can only be modified by their owner");
    }
}
