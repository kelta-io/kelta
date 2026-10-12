package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.GovernorLimitsRepository;
import io.kelta.worker.service.TenantQuotaResolver;
import io.kelta.worker.service.TenantTierQuotas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Rejects {@code collections} create when the tenant is at or above its
 * tier-resolved {@code maxCollections} quota. Runs early (order -100) so the
 * reject happens before any expensive downstream hook (table creation, route
 * publishing) fires.
 *
 * <p>Quota source of truth: {@link TenantQuotaResolver} merges tier defaults
 * from {@link TenantTierQuotas} with customer-specific overrides from
 * {@code tenant.limits}.
 */
@Component
public class CollectionQuotaEnforcementHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(CollectionQuotaEnforcementHook.class);

    private final TenantQuotaResolver quotaResolver;
    private final GovernorLimitsRepository repository;

    public CollectionQuotaEnforcementHook(TenantQuotaResolver quotaResolver,
                                          GovernorLimitsRepository repository) {
        this.quotaResolver = quotaResolver;
        this.repository = repository;
    }

    @Override
    public String getCollectionName() {
        return "collections";
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return BeforeSaveResult.ok();
        }
        int limit = quotaResolver.intQuota(tenantId, TenantTierQuotas.KEY_MAX_COLLECTIONS);
        int active = repository.countActiveCollections(tenantId);
        if (active >= limit) {
            log.info("Rejecting collection create for tenant {} — at quota ({}/{})", tenantId, active, limit);
            return BeforeSaveResult.error(null,
                    "Tenant collection quota exceeded (" + active + "/" + limit + "). "
                            + "Upgrade the tenant tier or raise tenant.limits.maxCollections.");
        }
        return BeforeSaveResult.ok();
    }
}
