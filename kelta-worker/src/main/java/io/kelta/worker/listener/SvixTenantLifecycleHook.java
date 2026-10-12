package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.worker.service.SvixTenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Before-save hook for the "tenants" system collection that creates/deletes
 * Svix applications in sync with tenant lifecycle events.
 *
 * <p>Runs after the TenantLifecycleHook (order 200) to ensure the tenant
 * record and PostgreSQL schema are fully created before creating the Svix
 * application.
 *
 * @since 1.0.0
 */
public class SvixTenantLifecycleHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(SvixTenantLifecycleHook.class);

    private final SvixTenantService svixTenantService;

    public SvixTenantLifecycleHook(SvixTenantService svixTenantService) {
        this.svixTenantService = svixTenantService;
    }

    @Override
    public String getCollectionName() {
        return "tenants";
    }

    /**
     * After the tenant config publishers (200, 201), before Superset (300). Hooks are
     * Spring-discovered, so no two hooks on one collection may share an order.
     */
    @Override
    public int getOrder() {
        return 202;
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        String id = getString(record, "id");
        String name = getString(record, "name");
        if (id != null) {
            svixTenantService.ensureApplication(id, name);
        }
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        svixTenantService.deleteApplication(id);
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}
