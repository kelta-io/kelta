package io.kelta.worker.config;

import io.kelta.runtime.context.TenantAwareDataSource;
import io.kelta.runtime.context.TenantAwareDataSourcePostProcessor;
import io.kelta.runtime.context.TenantContext;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the worker's {@link TenantContext} to every database connection, so the row-level
 * security policies on the control plane see the tenant the request resolved to.
 *
 * <p>The mechanics live in {@link TenantAwareDataSource} (runtime-core), shared with
 * kelta-auth and kelta-ai.
 *
 * @since 1.0.0
 */
@Configuration
public class TenantAwareDataSourceConfig {

    @Bean
    public static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new TenantAwareDataSourcePostProcessor();
    }
}
