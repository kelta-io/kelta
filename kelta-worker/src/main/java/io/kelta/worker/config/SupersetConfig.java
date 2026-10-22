package io.kelta.worker.config;

import tools.jackson.databind.ObjectMapper;
import io.kelta.worker.listener.SupersetCollectionSyncListener;
import io.kelta.worker.listener.SupersetTenantLifecycleHook;
import io.kelta.worker.service.SupersetApiClient;
import io.kelta.worker.service.SupersetDatabaseTarget;
import io.kelta.worker.service.SupersetDatabaseUserService;
import io.kelta.worker.service.SupersetDatasetService;
import io.kelta.worker.service.SupersetGuestTokenService;
import io.kelta.worker.service.SupersetTenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * Spring configuration for the Apache Superset integration.
 *
 * <p>Creates client, service, and lifecycle beans for managing Superset
 * database connections, datasets, and guest tokens per tenant.
 *
 * <p>Enabled when {@code kelta.superset.url} is set to a non-blank value. It is declared in
 * {@code application.yml} as {@code ${SUPERSET_URL:}}, and {@code @ConditionalOnProperty} without
 * {@code havingValue} matches any value but {@code false} — blank included — so that gate turned
 * the integration on in every deployment, Superset or not ({@link SupersetUrlConfigured}). A native
 * image evaluates this at build time; the production worker image is JVM, where it is read at
 * startup.
 *
 * @since 1.0.0
 */
@Configuration
@Conditional(SupersetConfig.SupersetUrlConfigured.class)
public class SupersetConfig {

    /**
     * True when {@code kelta.superset.url} has text. Also gates {@code SupersetController}, the one
     * Superset bean outside this class, which needs the services registered here.
     */
    public static class SupersetUrlConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty("kelta.superset.url"));
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SupersetConfig.class);

    @Value("${kelta.superset.url}")
    private String supersetUrl;

    @Value("${kelta.superset.public-url:${kelta.superset.url}}")
    private String supersetPublicUrl;

    @Value("${kelta.superset.admin-username:admin}")
    private String adminUsername;

    @Value("${kelta.superset.admin-password:}")
    private String adminPassword;

    /**
     * Where Superset reaches Postgres for tenant connections — the worker's own datasource unless
     * {@code kelta.worker.superset.database-*} override it. One value feeds both the connection
     * URI and the database the per-tenant users are granted {@code CONNECT} on, so the two cannot
     * disagree.
     */
    @Bean
    public SupersetDatabaseTarget supersetDatabaseTarget(
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${kelta.worker.superset.database-host:}") String host,
            @Value("${kelta.worker.superset.database-port:}") String port,
            @Value("${kelta.worker.superset.database-name:}") String database) {
        SupersetDatabaseTarget target = SupersetDatabaseTarget.resolve(datasourceUrl, host, port, database);
        log.info("Superset tenant connections will use Postgres at {}:{}/{}",
                target.host(), target.port(), target.database());
        return target;
    }

    @Bean
    public SupersetApiClient supersetApiClient(SupersetDatabaseTarget databaseTarget) {
        log.info("Configuring Superset API client with URL: {}", supersetUrl);
        return new SupersetApiClient(supersetUrl, adminUsername, adminPassword, new RestTemplate(),
                databaseTarget);
    }

    @Bean
    public SupersetDatabaseUserService supersetDatabaseUserService(
            JdbcTemplate jdbcTemplate, SupersetDatabaseTarget databaseTarget) {
        return new SupersetDatabaseUserService(jdbcTemplate, databaseTarget.database());
    }

    @Bean
    public SupersetTenantService supersetTenantService(SupersetApiClient apiClient,
                                                        SupersetDatabaseUserService dbUserService) {
        return new SupersetTenantService(apiClient, dbUserService);
    }

    @Bean
    public SupersetDatasetService supersetDatasetService(SupersetApiClient apiClient,
                                                          JdbcTemplate jdbcTemplate) {
        return new SupersetDatasetService(apiClient, jdbcTemplate);
    }

    @Bean
    public SupersetGuestTokenService supersetGuestTokenService(SupersetApiClient apiClient,
                                                                JdbcTemplate jdbcTemplate) {
        return new SupersetGuestTokenService(apiClient, jdbcTemplate, supersetPublicUrl);
    }

    @Bean
    public SupersetCollectionSyncListener supersetCollectionSyncListener(
            SupersetDatasetService datasetService,
            ObjectMapper objectMapper) {
        return new SupersetCollectionSyncListener(datasetService, objectMapper);
    }

    /** Registered with the hook registry by {@link BeforeSaveHookRegistrar}, like every hook bean. */
    @Bean
    public SupersetTenantLifecycleHook supersetTenantLifecycleHook(
            SupersetTenantService supersetTenantService) {
        return new SupersetTenantLifecycleHook(supersetTenantService);
    }

    /**
     * Verifies Superset connectivity on startup.
     */
    @EventListener
    public void verifySupersetConnection(ApplicationReadyEvent event) {
        try {
            var apiClient = event.getApplicationContext().getBean(SupersetApiClient.class);
            apiClient.healthCheck();
            log.info("Superset connectivity verified at {}", supersetUrl);
        } catch (Exception e) {
            log.warn("Superset health check failed — integration may not work: {}", e.getMessage());
        }
    }
}
