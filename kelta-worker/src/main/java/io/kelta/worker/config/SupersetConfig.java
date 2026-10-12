package io.kelta.worker.config;

import tools.jackson.databind.ObjectMapper;
import io.kelta.worker.listener.SupersetCollectionSyncListener;
import io.kelta.worker.listener.SupersetTenantLifecycleHook;
import io.kelta.worker.service.SupersetApiClient;
import io.kelta.worker.service.SupersetDatabaseUserService;
import io.kelta.worker.service.SupersetDatasetService;
import io.kelta.worker.service.SupersetGuestTokenService;
import io.kelta.worker.service.SupersetTenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * Spring configuration for the Apache Superset integration.
 *
 * <p>Creates client, service, and lifecycle beans for managing Superset
 * database connections, datasets, and guest tokens per tenant.
 *
 * <p>Enabled when {@code kelta.superset.url} is set.
 *
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(name = "kelta.superset.url", matchIfMissing = false)
public class SupersetConfig {

    private static final Logger log = LoggerFactory.getLogger(SupersetConfig.class);

    @Value("${kelta.superset.url}")
    private String supersetUrl;

    @Value("${kelta.superset.public-url:${kelta.superset.url}}")
    private String supersetPublicUrl;

    @Value("${kelta.superset.admin-username:admin}")
    private String adminUsername;

    @Value("${kelta.superset.admin-password:}")
    private String adminPassword;

    @Bean
    public SupersetApiClient supersetApiClient() {
        log.info("Configuring Superset API client with URL: {}", supersetUrl);
        return new SupersetApiClient(supersetUrl, adminUsername, adminPassword, new RestTemplate());
    }

    @Bean
    public SupersetDatabaseUserService supersetDatabaseUserService(
            JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Value(
                    "${kelta.worker.superset.database-name:${spring.datasource.name:emf_control_plane}}")
            String databaseName) {
        return new SupersetDatabaseUserService(jdbcTemplate, databaseName);
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
    @EventListener(ApplicationReadyEvent.class)
    public void verifySupersetConnection() {
        try {
            var apiClient = supersetApiClient();
            apiClient.healthCheck();
            log.info("Superset connectivity verified at {}", supersetUrl);
        } catch (Exception e) {
            log.warn("Superset health check failed — integration may not work: {}", e.getMessage());
        }
    }
}
