package io.kelta.ai.config;

import io.kelta.runtime.context.TenantAwareDataSource;
import io.kelta.runtime.context.TenantAwareDataSourcePostProcessor;
import io.kelta.runtime.context.TenantContext;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the tenant that {@code TenantContextFilter} resolved from {@code X-Tenant-ID} to
 * the database connection, so the {@code ai_*} row-level security policies filter to it.
 *
 * <p>Until this existed, kelta-ai ran every query on the platform session: the
 * {@code connection-init-sql} {@code SET app.current_tenant_id = ''} was the only value the
 * variable ever took, so a conversation or token-usage row was reachable from any tenant's
 * request and the tenant scoping was whatever the repository's own {@code WHERE} clause
 * happened to say. The {@link TenantContext} the filter binds now reaches the connection.
 *
 * <p>The mechanics live in {@link TenantAwareDataSource} (runtime-core), shared with
 * kelta-worker and kelta-auth.
 */
@Configuration
public class TenantAwareDataSourceConfig {

    @Bean
    public static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new TenantAwareDataSourcePostProcessor();
    }
}
