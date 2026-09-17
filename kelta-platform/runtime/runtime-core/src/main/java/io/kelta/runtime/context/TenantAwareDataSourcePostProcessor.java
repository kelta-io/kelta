package io.kelta.runtime.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;

/**
 * Wraps the auto-configured {@code dataSource} bean in a {@link TenantAwareDataSource} so
 * every borrowed connection carries the request's tenant into
 * {@code app.current_tenant_id}, and with it the row-level security policies.
 *
 * <p>A {@link BeanPostProcessor} rather than a {@code @Primary @Bean}: a DataSource bean
 * that injects another DataSource is a circular dependency.
 *
 * <p>Each service that talks to the control-plane database registers this from its own
 * configuration — kelta-worker, kelta-auth and kelta-ai all do. It is deliberately not an
 * auto-configuration: a service that has no tenant to bind (or no DataSource at all) should
 * not silently acquire connection-level behaviour it never asked for.
 */
public class TenantAwareDataSourcePostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSourcePostProcessor.class);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if ("dataSource".equals(beanName) && bean instanceof DataSource ds) {
            log.info("Wrapping DataSource with tenant-aware RLS support "
                    + "(transaction-scoped SET LOCAL for tenant connections)");
            return new TenantAwareDataSource(ds);
        }
        return bean;
    }
}
