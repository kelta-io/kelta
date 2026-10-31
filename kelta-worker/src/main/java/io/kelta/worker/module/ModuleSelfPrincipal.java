package io.kelta.worker.module;

import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.service.ModuleServicePrincipalService;

import java.util.Optional;

/**
 * Who is calling a {@code /api/modules/self/**} endpoint: the external module install whose
 * service principal holds the request's token, or nobody
 * ({@code specs/module-platform/9-external-modules.md} §3.3). The one rule every such endpoint
 * applies before anything else:
 *
 * <ul>
 *   <li>the token's {@code connected_app_id} (the gateway's {@code X-Connected-App-Id}, stripped
 *       from every client request) names exactly one {@code tenant_module} row of the tenant;</li>
 *   <li>that row is external ({@code base_url});</li>
 *   <li>the caller {@code CallerContextFilter} resolved is the row's {@code service_user_id};</li>
 *   <li>the app and its {@code SERVICE} user are still active
 *       ({@link ModuleServicePrincipalService#isActivePrincipal}), since a token outlives a
 *       deactivation by up to its 15-minute lifetime.</li>
 * </ul>
 *
 * <p>A user token, a PAT or a plain connected app carries no matching app id; another install's
 * principal names another row. Whether the install is callable ({@code ACTIVE}/{@code DEGRADED})
 * is the caller's next check, read from the same row.
 */
final class ModuleSelfPrincipal {

    private ModuleSelfPrincipal() {
    }

    /**
     * @param tenantId       the request's tenant
     * @param connectedAppId the token's {@code connected_app_id}, or null
     * @param callerUserId   the caller's resolved {@code platform_user.id}, or null
     * @return the calling install, or empty when the caller is not an external module's active principal
     */
    static Optional<TenantModuleData> resolve(ModuleStore moduleStore, ModuleServicePrincipalService principals,
                                              String tenantId, String connectedAppId, String callerUserId) {
        TenantModuleData install = connectedAppId == null || connectedAppId.isBlank() ? null
                : moduleStore.findByConnectedAppId(tenantId, connectedAppId).orElse(null);
        if (install == null || !install.isExternal() || callerUserId == null
                || !callerUserId.equals(install.serviceUserId())
                || !principals.isActivePrincipal(tenantId, connectedAppId, callerUserId)) {
            return Optional.empty();
        }
        return Optional.of(install);
    }
}
