package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.service.TenantQuotaResolver;
import io.kelta.worker.service.TenantTierQuotas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * Rejects {@code fields} create when the parent collection is at or above the
 * tenant's {@code maxFieldsPerCollection} quota. Runs early (order -100).
 *
 * <p>Counts existing fields with the same {@code tenant_id} and
 * {@code collection_id} to scope the cap per-collection rather than per-tenant.
 */
public class FieldQuotaEnforcementHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(FieldQuotaEnforcementHook.class);

    private static final String COUNT_FIELDS =
            "SELECT COUNT(*) FROM field WHERE tenant_id = ? AND collection_id = ?";

    private final TenantQuotaResolver quotaResolver;
    private final JdbcTemplate jdbcTemplate;

    public FieldQuotaEnforcementHook(TenantQuotaResolver quotaResolver, JdbcTemplate jdbcTemplate) {
        this.quotaResolver = quotaResolver;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getCollectionName() {
        return "fields";
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
        Object collectionIdObj = record.get("collectionId");
        if (collectionIdObj == null) {
            // Unknown parent — cannot enforce; allow and let downstream validation
            // surface the missing-required-field error.
            return BeforeSaveResult.ok();
        }
        String collectionId = collectionIdObj.toString();
        int limit = quotaResolver.intQuota(tenantId, TenantTierQuotas.KEY_MAX_FIELDS_PER_COLLECTION);
        int active;
        try {
            Integer count = jdbcTemplate.queryForObject(COUNT_FIELDS, Integer.class, tenantId, collectionId);
            active = count != null ? count : 0;
        } catch (Exception e) {
            log.warn("Failed to count fields for tenant {} collection {}, allowing: {}",
                    tenantId, collectionId, e.getMessage());
            return BeforeSaveResult.ok();
        }
        if (active >= limit) {
            log.info("Rejecting field create for tenant {} collection {} — at quota ({}/{})",
                    tenantId, collectionId, active, limit);
            return BeforeSaveResult.error(null,
                    "Field quota exceeded for this collection (" + active + "/" + limit + "). "
                            + "Upgrade the tenant tier or raise tenant.limits.maxFieldsPerCollection.");
        }
        return BeforeSaveResult.ok();
    }
}
