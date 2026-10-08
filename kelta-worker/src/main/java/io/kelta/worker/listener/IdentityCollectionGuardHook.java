package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.SelfProfileWriteContext;
import io.kelta.worker.service.delegated.DelegatedWriteContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Set;

/**
 * Last-line write guard for identity collections ({@code users}, {@code user-permission-sets},
 * {@code group-memberships}, {@code delegated-admin-scopes}).
 *
 * <p>Every QueryEngine write path fires before-save hooks — the dynamic collection router, the
 * JSON:API atomic-operations batch endpoint, CSV import, and the delegated admin controller. The
 * gateway's Cerbos object-permission check covers only dynamic collection routes; static routes
 * (notably {@code POST /api/operations}) reach QueryEngine with no per-collection authorization.
 * This hook closes that: a write arriving on an HTTP request thread with a gateway-stamped
 * profile identity is admitted only when
 * <ul>
 *   <li>the profile grants {@code MANAGE_USERS} or {@code MODIFY_ALL_DATA} (for
 *       {@code delegated-admin-scopes}: {@code MANAGE_DELEGATED_ADMINS}), or</li>
 *   <li>the write runs inside a scope-validated {@link DelegatedWriteContext} bound by
 *       {@code DelegatedUserAdminController}, or</li>
 *   <li>it is a {@code users} <b>update</b> inside a {@link SelfProfileWriteContext} bound by
 *       {@code MyProfileController}, whose target id is the grant's own user id and whose
 *       changed fields are all in the grant's allow-list. Any other write under that context —
 *       another id, another field, a create or delete — is judged as if it were unbound.</li>
 * </ul>
 *
 * <p>Writes with no HTTP request context (NATS listeners, flows, schedulers, provisioning) and
 * request-context writes with no identity headers (SCIM and other internal callers — the gateway
 * always stamps identity on authenticated {@code /api} traffic) are admitted unchanged.
 */
public class IdentityCollectionGuardHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(IdentityCollectionGuardHook.class);

    static final Set<String> GUARDED = Set.of(
            "users", "user-permission-sets", "group-memberships", "delegated-admin-scopes");

    /** Stamped by {@code QueryEngine.update} on every write, not chosen by the caller. */
    private static final Set<String> AUDIT_FIELDS = Set.of("updatedAt");

    private static final String PROFILE_ID_HEADER = "X-User-Profile-Id";

    private final BootstrapRepository bootstrapRepository;

    public IdentityCollectionGuardHook(BootstrapRepository bootstrapRepository) {
        this.bootstrapRepository = bootstrapRepository;
    }

    @Override
    public String getCollectionName() {
        return BeforeSaveHookRegistry.WILDCARD;
    }

    /** Runs before all other hooks so denied writes do no earlier side work. */
    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public BeforeSaveResult beforeCreate(String collectionName, Map<String, Object> record,
                                         String tenantId) {
        return guard(collectionName, "create");
    }

    @Override
    public BeforeSaveResult beforeUpdate(String collectionName, String id,
                                         Map<String, Object> record,
                                         Map<String, Object> previous, String tenantId) {
        if (isOwnSelfProfileUpdate(collectionName, id, record)) {
            return BeforeSaveResult.ok();
        }
        return guard(collectionName, "update");
    }

    @Override
    public BeforeSaveResult beforeDelete(String collectionName, String id, String tenantId) {
        return guard(collectionName, "delete");
    }

    private BeforeSaveResult guard(String collectionName, String action) {
        if (!GUARDED.contains(collectionName)) {
            return BeforeSaveResult.ok();
        }
        if (DelegatedWriteContext.isAuthorized()) {
            return BeforeSaveResult.ok();
        }
        String profileId = requestProfileId();
        if (profileId == null || profileId.isBlank()) {
            // No HTTP request identity: internal path (flows, schedulers, SCIM, provisioning).
            return BeforeSaveResult.ok();
        }
        Set<String> required = "delegated-admin-scopes".equals(collectionName)
                ? Set.of("MANAGE_DELEGATED_ADMINS", "MODIFY_ALL_DATA")
                : Set.of("MANAGE_USERS", "MODIFY_ALL_DATA");
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> required.contains((String) p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            log.warn("Blocked {} on identity collection '{}' by profile {} (requires one of {})",
                    action, collectionName, profileId, required);
            return BeforeSaveResult.error(null,
                    "Insufficient permissions to modify " + collectionName);
        }
        return BeforeSaveResult.ok();
    }

    private static boolean isOwnSelfProfileUpdate(String collectionName, String id,
                                                  Map<String, Object> record) {
        if (!"users".equals(collectionName)) {
            return false;
        }
        return SelfProfileWriteContext.current()
                .filter(grant -> grant.userId().equals(id))
                .filter(grant -> record.keySet().stream()
                        .allMatch(f -> AUDIT_FIELDS.contains(f) || grant.allowedFields().contains(f)))
                .isPresent();
    }

    private String requestProfileId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        return request.getHeader(PROFILE_ID_HEADER);
    }
}
