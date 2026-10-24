package io.kelta.worker.config;

import io.kelta.runtime.flow.*;
import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.module.core.CoreActionsModule;
import io.kelta.runtime.module.integration.IntegrationModule;
import io.kelta.runtime.messaging.nats.NatsConnectionManager;
import io.kelta.runtime.module.integration.api.OpenApiSpecParser;
import io.kelta.runtime.module.integration.handlers.PublishEventActionHandler;
import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.runtime.module.integration.spi.ScriptExecutor;
import io.kelta.runtime.module.integration.spi.graalvm.GraalVmScriptExecutor;
import io.kelta.runtime.module.schema.SchemaLifecycleModule;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.service.RollupSummaryService;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.module.KeltaModule;
import io.kelta.runtime.workflow.module.ModuleContext;
import io.kelta.runtime.workflow.module.ModuleRegistry;
import io.kelta.worker.flow.JdbcFlowStore;
import io.kelta.worker.handler.SubmitForApprovalActionHandler;
import io.kelta.worker.service.ApprovalService;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Spring configuration for the flow execution engine and module system.
 * <p>
 * Wires the {@link FlowEngine} with its dependencies, provides the
 * {@link ActionHandlerRegistry} and {@link BeforeSaveHookRegistry} shared
 * by the flow engine and compile-time modules, and initializes all
 * discovered {@link KeltaModule} beans.
 * <p>
 * Platform before-save hooks are <b>not</b> wired here: each is a Spring bean
 * (a {@code @Component}, or a {@code @Bean} in {@link MultiCollectionHookConfig}
 * when one class serves several collections) and {@link BeforeSaveHookRegistrar}
 * registers it with the {@link BeforeSaveHookRegistry}.
 * <p>
 * Enabled by default. Disable with {@code kelta.flow.enabled=false}.
 *
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(name = "kelta.flow.enabled", havingValue = "true", matchIfMissing = true)
public class FlowConfig {

    private static final Logger log = LoggerFactory.getLogger(FlowConfig.class);

    // ---------------------------------------------------------------------------
    // Core registries — shared by flows, modules, and before-save hooks
    // ---------------------------------------------------------------------------

    @Bean
    public ActionHandlerRegistry actionHandlerRegistry() {
        return new ActionHandlerRegistry();
    }

    /**
     * Empty at creation; filled by {@link BeforeSaveHookRegistrar} (platform hook beans),
     * {@code ModuleRegistry} (compile-time module hooks) and {@code RuntimeModuleManager}
     * (tenant-scoped runtime-module hooks). It cannot take the hooks as a constructor argument:
     * {@code QueryEngine} depends on this registry and several hooks depend on {@code QueryEngine}.
     */
    @Bean
    public BeforeSaveHookRegistry beforeSaveHookRegistry() {
        return new BeforeSaveHookRegistry();
    }

    // ---------------------------------------------------------------------------
    // Flow engine beans
    // ---------------------------------------------------------------------------

    @Bean
    public FlowStore flowStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                               ObjectProvider<MeterRegistry> meterRegistry,
                               @Value("${kelta.flow.step-log.max-snapshot-bytes:"
                                   + JdbcFlowStore.DEFAULT_MAX_SNAPSHOT_BYTES + "}") int maxSnapshotBytes) {
        log.info("Flow step-log snapshot cap: {} bytes", maxSnapshotBytes);
        return new JdbcFlowStore(jdbcTemplate, objectMapper, maxSnapshotBytes, meterRegistry.getIfAvailable());
    }

    @Bean
    public FlowEngine flowEngine(FlowStore flowStore,
                                  ActionHandlerRegistry actionHandlerRegistry,
                                  ObjectMapper objectMapper,
                                  FlowMetricsConfig flowMetricsConfig,
                                  @Value("${kelta.flow.executor.pool-size:10}") int poolSize) {
        log.info("Creating FlowEngine with thread pool size {}", poolSize);
        return new FlowEngine(flowStore, actionHandlerRegistry, objectMapper, poolSize, flowMetricsConfig);
    }

    @Bean
    public FlowTriggerEvaluator flowTriggerEvaluator(FormulaEvaluator formulaEvaluator) {
        return new FlowTriggerEvaluator(formulaEvaluator);
    }

    @Bean
    public InitialStateBuilder initialStateBuilder() {
        return new InitialStateBuilder();
    }

    // ---------------------------------------------------------------------------
    // Kelta compile-time module beans — registered as Spring beans so that the
    // ModuleRegistry can discover them. These modules live in runtime-module-*
    // JARs outside the worker's component-scan package.
    // ---------------------------------------------------------------------------

    /**
     * Tenant slug grammar for schema creation. Must start with a lowercase
     * letter, followed by lowercase letters / digits / hyphens only, and
     * bounded at 63 chars — Postgres's identifier length limit. Mirrors the
     * upstream validation in {@code TenantLifecycleHook.beforeCreate}, so a
     * slug that passed the lifecycle hook will pass here too; this regex is
     * defense-in-depth against any callback wiring that bypasses the hook.
     */
    private static final Pattern TENANT_SLUG_PATTERN =
            Pattern.compile("^[a-z][a-z0-9-]{0,62}$");

    @Bean
    public SchemaLifecycleModule schemaLifecycleModule(JdbcTemplate jdbcTemplate) {
        log.info("Schema-per-tenant active — tenant creation will auto-create PostgreSQL schemas");
        return new SchemaLifecycleModule(slug -> {
            if (slug == null || !TENANT_SLUG_PATTERN.matcher(slug).matches()) {
                // Fail loudly rather than silently creating a schema with a
                // truncated / empty / surprising name. The upstream hook already
                // validated the slug before calling us, so hitting this branch
                // means a platform invariant was broken somewhere.
                throw new IllegalArgumentException(
                        "Tenant slug '" + slug + "' does not match required pattern "
                                + TENANT_SLUG_PATTERN.pattern()
                                + " — refusing to CREATE SCHEMA");
            }
            // The regex guarantees the slug contains no double-quotes, but
            // double any defensively before splicing into the DDL string.
            String escaped = slug.replace("\"", "\"\"");
            jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS \"" + escaped + "\"");
        });
    }

    @Bean
    public CoreActionsModule coreActionsModule() {
        return new CoreActionsModule();
    }

    @Bean
    public IntegrationModule integrationModule() {
        return new IntegrationModule();
    }

    @Bean
    public org.springframework.transaction.support.TransactionTemplate flowTransactionTemplate(
            org.springframework.transaction.PlatformTransactionManager txManager) {
        return new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    @Bean
    public ScriptExecutor scriptExecutor(
            @Value("${kelta.script.timeout-seconds:30}") int timeoutSeconds) {
        return new GraalVmScriptExecutor(timeoutSeconds);
    }

    @Bean
    public ModuleRegistry moduleRegistry(ActionHandlerRegistry actionHandlerRegistry,
                                          BeforeSaveHookRegistry beforeSaveHookRegistry,
                                          @Autowired(required = false) List<KeltaModule> discoveredModules,
                                          QueryEngine queryEngine,
                                          CollectionRegistry collectionRegistry,
                                          @Autowired(required = false) FormulaEvaluator formulaEvaluator,
                                          ObjectMapper objectMapper,
                                          FlowEngine flowEngine,
                                          RollupSummaryService rollupSummaryService,
                                          JdbcTemplate jdbcTemplate,
                                          org.springframework.transaction.support.TransactionTemplate transactionTemplate,
                                          @Autowired(required = false) EmailService emailService,
                                          @Autowired(required = false) ScriptExecutor scriptExecutor,
                                          @Autowired(required = false)
                                              io.kelta.runtime.module.integration.spi.ApiSpecStore apiSpecStore,
                                          @Autowired(required = false)
                                              io.kelta.runtime.module.integration.spi.CredentialResolverPort credentialResolverPort,
                                          @Autowired(required = false)
                                              io.kelta.runtime.module.integration.spi.IdempotencyStore idempotencyStore,
                                          @Autowired(required = false)
                                              io.kelta.runtime.module.integration.spi.NotificationStore notificationStore,
                                          io.kelta.runtime.outbound.OutboundUrlGuard outboundUrlGuard,
                                          ObjectProvider<NatsConnectionManager> natsConnectionManager) {
        ModuleRegistry registry = new ModuleRegistry(actionHandlerRegistry, beforeSaveHookRegistry);

        if (discoveredModules != null && !discoveredModules.isEmpty()) {
            var extensions = new java.util.HashMap<Class<?>, Object>();
            extensions.put(FlowEngine.class, flowEngine);
            extensions.put(RollupSummaryService.class, rollupSummaryService);
            extensions.put(JdbcTemplate.class, jdbcTemplate);
            extensions.put(org.springframework.transaction.support.TransactionTemplate.class,
                    transactionTemplate);
            // Flow HTTP callouts fetch tenant-configured URLs through the same guard (and
            // allow-list) as every other outbound fetch of a tenant-supplied URL.
            extensions.put(io.kelta.runtime.outbound.OutboundUrlGuard.class, outboundUrlGuard);

            NatsConnectionManager nats = natsConnectionManager.getIfAvailable();
            if (nats != null) {
                extensions.put(PublishEventActionHandler.EventPublisher.class,
                    natsFlowEventPublisher(nats, objectMapper));
            } else {
                log.warn("No NATS connection — PUBLISH_EVENT flow steps will fail");
            }

            if (emailService != null) {
                extensions.put(EmailService.class, emailService);
                log.info("EmailService wired into module context — flow email alerts will use real delivery");
            }

            if (scriptExecutor != null) {
                extensions.put(ScriptExecutor.class, scriptExecutor);
                log.info("ScriptExecutor wired into module context — flow scripts will use GraalVM execution");
            }

            // PR 4: wire CALL_API SPI implementations so the integration
            // module can resolve specs, credentials, and idempotency state.
            if (apiSpecStore != null) {
                extensions.put(io.kelta.runtime.module.integration.spi.ApiSpecStore.class, apiSpecStore);
                log.info("ApiSpecStore wired into module context — CALL_API operation mode active");
            }
            if (credentialResolverPort != null) {
                extensions.put(io.kelta.runtime.module.integration.spi.CredentialResolverPort.class,
                    credentialResolverPort);
                log.info("CredentialResolverPort wired into module context — CALL_API can attach credentials");
            }
            if (idempotencyStore != null) {
                extensions.put(io.kelta.runtime.module.integration.spi.IdempotencyStore.class,
                    idempotencyStore);
                log.info("IdempotencyStore wired into module context — CALL_API will dedupe non-idempotent calls");
            }
            if (notificationStore != null) {
                extensions.put(io.kelta.runtime.module.integration.spi.NotificationStore.class,
                    notificationStore);
            } else {
                log.warn("No NotificationStore — SEND_NOTIFICATION flow steps will fail");
            }

            ModuleContext context = new ModuleContext(
                queryEngine, collectionRegistry, formulaEvaluator, objectMapper,
                actionHandlerRegistry, extensions);

            registry.initialize(discoveredModules, context);
            log.info("Module system initialized with {} modules", discoveredModules.size());
        } else {
            log.info("No KeltaModule beans found — module system inactive");
        }

        return registry;
    }

    /**
     * Publishes a PUBLISH_EVENT flow step's envelope as JSON to JetStream (stream
     * {@code KELTA_EVENTS}, {@code kelta.event.>}). The publish waits for the stream ack, so a
     * missing stream or a NATS outage fails the step instead of being logged and dropped.
     */
    static PublishEventActionHandler.EventPublisher natsFlowEventPublisher(
            NatsConnectionManager nats, ObjectMapper objectMapper) {
        return (subject, key, eventData) -> {
            Headers headers = new Headers();
            if (eventData.get("eventId") instanceof String eventId) {
                headers.add("Nats-Msg-Id", eventId);
            }
            if (eventData.get("tenantId") instanceof String tenantId) {
                headers.add(io.kelta.runtime.messaging.nats.NatsEventPublisher.TENANT_ID_HEADER, tenantId);
            }
            headers.add("Kelta-Message-Key", key);
            try {
                nats.jetStream().publish(NatsMessage.builder()
                    .subject(subject)
                    .headers(headers)
                    .data(objectMapper.writeValueAsBytes(eventData))
                    .build());
            } catch (java.io.IOException | io.nats.client.JetStreamApiException e) {
                throw new IllegalStateException("Publish to " + subject + " failed: " + e.getMessage(), e);
            }
        };
    }

    // ---------------------------------------------------------------------------
    // OpenAPI spec library parser (the spec NATS broadcast is ApiSpecConfigHook)
    // ---------------------------------------------------------------------------

    @Bean
    public OpenApiSpecParser openApiSpecParser(ObjectMapper objectMapper) {
        return new OpenApiSpecParser(objectMapper);
    }

    // ---------------------------------------------------------------------------
    // Approval workflow action handler
    // ---------------------------------------------------------------------------

    @Bean
    public SubmitForApprovalActionHandler submitForApprovalActionHandler(
            ActionHandlerRegistry actionHandlerRegistry,
            ApprovalService approvalService,
            ObjectMapper objectMapper) {
        SubmitForApprovalActionHandler handler =
                new SubmitForApprovalActionHandler(approvalService, objectMapper);
        actionHandlerRegistry.register(handler);
        return handler;
    }
}
