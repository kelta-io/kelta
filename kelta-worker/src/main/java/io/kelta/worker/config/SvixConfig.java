package io.kelta.worker.config;

import tools.jackson.databind.ObjectMapper;
import io.kelta.worker.listener.RecordWebhookPublisher;
import io.kelta.worker.listener.SvixTenantLifecycleHook;
import io.kelta.worker.listener.SvixWebhookPublisher;
import io.kelta.worker.service.SvixTenantService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Spring configuration for the Svix webhook service client.
 *
 * <p>Builds a {@link RestClient} bean pre-authenticated against the Svix
 * server and registers webhook event types on application startup.
 *
 * <p>Talks to the Svix REST API directly rather than using the Svix Java
 * SDK 1.68.0 — the SDK ships Jackson-2 model annotations and doesn't
 * deserialize cleanly under Spring Boot 4 / Jackson 3.
 *
 * <p>Enabled when {@code kelta.svix.auth-token} is non-blank ({@link SvixTokenConfigured}). It is
 * declared as {@code ${SVIX_AUTH_TOKEN:}}, and {@code @ConditionalOnProperty} without
 * {@code havingValue} matches a blank value, so that gate built an unauthenticated client in
 * every deployment without Svix and posted every record event to it. A native image evaluates this
 * at build time; the production worker image is JVM, where it is read at startup.
 *
 * @since 1.0.0
 */
@Configuration
@Conditional(SvixConfig.SvixTokenConfigured.class)
public class SvixConfig {

    /**
     * True when {@code kelta.svix.auth-token} has text. Also gates {@code SvixPortalController},
     * the one Svix bean outside this class, which needs {@link SvixTenantService}.
     */
    public static class SvixTokenConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty("kelta.svix.auth-token"));
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SvixConfig.class);

    private static final List<String> EVENT_TYPES = List.of(
            "collection.created",
            "collection.updated",
            "record.created",
            "record.updated",
            "record.deleted"
    );

    @Value("${kelta.svix.server-url:http://localhost:8071}")
    private String serverUrl;

    @Value("${kelta.svix.auth-token}")
    private String authToken;

    private RestClient svixRestClient;

    @Bean("svixRestClient")
    public RestClient svixRestClient() {
        log.info("Configuring Svix RestClient with server URL: {}", serverUrl);
        this.svixRestClient = RestClient.builder()
                .baseUrl(serverUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                .build();
        return this.svixRestClient;
    }

    @Bean
    public SvixTenantService svixTenantService(RestClient svixRestClient) {
        return new SvixTenantService(svixRestClient);
    }

    /** Registered with the hook registry by {@link BeforeSaveHookRegistrar}, like every hook bean. */
    @Bean
    public SvixTenantLifecycleHook svixTenantLifecycleHook(SvixTenantService svixTenantService) {
        return new SvixTenantLifecycleHook(svixTenantService);
    }

    @Bean
    public SvixWebhookPublisher svixWebhookPublisher(RestClient svixRestClient, ObjectMapper objectMapper) {
        return new SvixWebhookPublisher(svixRestClient, objectMapper);
    }

    @Bean
    public RecordWebhookPublisher recordWebhookPublisher(RestClient svixRestClient, ObjectMapper objectMapper) {
        return new RecordWebhookPublisher(svixRestClient, objectMapper);
    }

    /**
     * Registers webhook event types with Svix on startup. Idempotent —
     * Svix returns 409 for an existing type, which we log and ignore.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerEventTypes() {
        if (svixRestClient == null) {
            log.warn("Svix RestClient not initialized — skipping event type registration");
            return;
        }
        for (String eventType : EVENT_TYPES) {
            try {
                Map<String, Object> body = Map.of(
                        "name", eventType,
                        "description", "Collection " + eventType.split("\\.")[1] + " event"
                );
                svixRestClient.post()
                        .uri("/api/v1/event-type/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("Registered Svix event type: {}", eventType);
            } catch (Exception e) {
                log.debug("Svix event type '{}' already exists or registration failed: {}",
                        eventType, e.getMessage());
            }
        }
    }
}
