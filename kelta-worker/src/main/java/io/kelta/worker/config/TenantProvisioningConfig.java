package io.kelta.worker.config;

import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.worker.listener.TenantProvisioningHook;
import io.kelta.worker.service.CerbosPolicySyncService;
import io.kelta.worker.service.UserInviteService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spring configuration for tenant provisioning.
 *
 * <p>Registers the {@link TenantProvisioningHook} which seeds default profiles,
 * an internal OIDC provider, and an admin user (no usable password; claimed by
 * invite) when a new tenant is created.
 *
 * @since 1.0.0
 */
@Configuration
public class TenantProvisioningConfig {

    @Bean
    public TenantProvisioningHook tenantProvisioningHook(
            BeforeSaveHookRegistry hookRegistry,
            JdbcTemplate jdbcTemplate,
            CerbosPolicySyncService cerbosPolicySyncService,
            UserInviteService userInviteService,
            @Value("${kelta.auth.issuer-uri:https://auth.kelta.io}") String authIssuerUri) {
        TenantProvisioningHook hook = new TenantProvisioningHook(
                jdbcTemplate, authIssuerUri, cerbosPolicySyncService, userInviteService);
        hookRegistry.register(hook);
        return hook;
    }
}
