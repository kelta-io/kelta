package io.kelta.auth.config;

import io.kelta.runtime.context.TenantAwareDataSource;
import io.kelta.runtime.context.TenantAwareDataSourcePostProcessor;
import io.kelta.runtime.context.TenantContext;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the tenant that {@link TenantContextFilter} resolved for the request to the database
 * connection, so the row-level security policies on {@code platform_user} and the per-user
 * credential tables filter to it.
 *
 * <p>kelta-auth used to run every statement on the platform session — the
 * {@code connection-init-sql} {@code SET app.current_tenant_id = ''} selected
 * {@code admin_bypass} on every connection, so a login for tenant A was one missing
 * {@code AND tenant_id = ?} away from reading tenant B's users. The service still needs that
 * platform session for the paths that genuinely have no tenant (client back-channel token
 * requests, startup client registration), and {@link TenantAwareDataSource} keeps it for
 * exactly those: no tenant bound, no change.
 *
 * <p>The mechanics live in {@link TenantAwareDataSource} (runtime-core), shared with
 * kelta-worker and kelta-ai.
 */
@Configuration
public class TenantAwareDataSourceConfig {

    @Bean
    public static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new TenantAwareDataSourcePostProcessor();
    }
}
