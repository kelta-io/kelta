package io.kelta.worker.config;

import io.kelta.runtime.event.EventSubscription;
import io.kelta.runtime.messaging.nats.NatsSubscriptionManager;
import io.kelta.worker.listener.AvailabilityEventListener;
import io.kelta.worker.listener.CerbosCacheInvalidationListener;
import io.kelta.worker.listener.CollectionSchemaListener;
import io.kelta.worker.listener.CredentialCacheInvalidationListener;
import io.kelta.worker.listener.CustomDomainCacheInvalidationListener;
import io.kelta.worker.listener.FlowEventListener;
import io.kelta.worker.listener.LayoutCacheInvalidationListener;
import io.kelta.worker.listener.NatsTriggerFlowListener;
import io.kelta.worker.listener.SearchIndexListener;
import io.kelta.worker.listener.SupersetCollectionSyncListener;
import io.kelta.worker.listener.RecordWebhookPublisher;
import io.kelta.worker.listener.SvixWebhookPublisher;
import io.kelta.worker.listener.MenuCacheInvalidationListener;
import io.kelta.worker.listener.SystemFeatureCacheInvalidationListener;
import io.kelta.worker.listener.SystemCollectionCacheInvalidationListener;
import io.kelta.worker.listener.TranslationCacheInvalidationListener;
import io.kelta.worker.listener.TenantEmailCacheInvalidationListener;
import io.kelta.worker.module.ModuleEventListener;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Registers all worker NATS subscriptions with the {@link NatsSubscriptionManager}.
 *
 * <p>Uses explicit NATS subscription registrations (rather than annotation-driven
 * listeners), supporting both queue-group (load-balanced) and broadcast (all-pods)
 * delivery modes.
 *
 * @since 1.0.0
 */
@Configuration
@Profile("!migrate")
public class NatsSubscriptionConfig {

    private static final Logger log = LoggerFactory.getLogger(NatsSubscriptionConfig.class);

    private final NatsSubscriptionManager subscriptionManager;
    private final FlowEventListener flowEventListener;
    private final NatsTriggerFlowListener natsTriggerFlowListener;
    private final SearchIndexListener searchIndexListener;
    private final CollectionSchemaListener collectionSchemaListener;
    private final ModuleEventListener moduleEventListener;
    private final CerbosCacheInvalidationListener cerbosCacheInvalidationListener;
    private final CustomDomainCacheInvalidationListener customDomainCacheInvalidationListener;
    private final SystemFeatureCacheInvalidationListener systemFeatureCacheInvalidationListener;
    private final LayoutCacheInvalidationListener layoutCacheInvalidationListener;
    private final MenuCacheInvalidationListener menuCacheInvalidationListener;
    private final TranslationCacheInvalidationListener translationCacheInvalidationListener;
    private final SystemCollectionCacheInvalidationListener systemCollectionCacheInvalidationListener;
    private final AvailabilityEventListener availabilityEventListener;

    @Autowired(required = false)
    private CredentialCacheInvalidationListener credentialCacheInvalidationListener;

    @Autowired(required = false)
    private SupersetCollectionSyncListener supersetCollectionSyncListener;

    @Autowired(required = false)
    private SvixWebhookPublisher svixWebhookPublisher;

    @Autowired(required = false)
    private RecordWebhookPublisher recordWebhookPublisher;

    @Autowired(required = false)
    private TenantEmailCacheInvalidationListener tenantEmailCacheInvalidationListener;

    public NatsSubscriptionConfig(NatsSubscriptionManager subscriptionManager,
                                   FlowEventListener flowEventListener,
                                   NatsTriggerFlowListener natsTriggerFlowListener,
                                   SearchIndexListener searchIndexListener,
                                   CollectionSchemaListener collectionSchemaListener,
                                   ModuleEventListener moduleEventListener,
                                   CerbosCacheInvalidationListener cerbosCacheInvalidationListener,
                                   CustomDomainCacheInvalidationListener customDomainCacheInvalidationListener,
                                   SystemFeatureCacheInvalidationListener systemFeatureCacheInvalidationListener,
                                   LayoutCacheInvalidationListener layoutCacheInvalidationListener,
                                   MenuCacheInvalidationListener menuCacheInvalidationListener,
                                   TranslationCacheInvalidationListener translationCacheInvalidationListener,
                                   SystemCollectionCacheInvalidationListener systemCollectionCacheInvalidationListener,
                                   AvailabilityEventListener availabilityEventListener) {
        this.subscriptionManager = subscriptionManager;
        this.flowEventListener = flowEventListener;
        this.natsTriggerFlowListener = natsTriggerFlowListener;
        this.searchIndexListener = searchIndexListener;
        this.collectionSchemaListener = collectionSchemaListener;
        this.moduleEventListener = moduleEventListener;
        this.cerbosCacheInvalidationListener = cerbosCacheInvalidationListener;
        this.customDomainCacheInvalidationListener = customDomainCacheInvalidationListener;
        this.systemFeatureCacheInvalidationListener = systemFeatureCacheInvalidationListener;
        this.layoutCacheInvalidationListener = layoutCacheInvalidationListener;
        this.menuCacheInvalidationListener = menuCacheInvalidationListener;
        this.translationCacheInvalidationListener = translationCacheInvalidationListener;
        this.systemCollectionCacheInvalidationListener = systemCollectionCacheInvalidationListener;
        this.availabilityEventListener = availabilityEventListener;
    }

    @PostConstruct
    public void registerSubscriptions() {
        // Queue group subscriptions — load-balanced across worker instances
        subscriptionManager.register(EventSubscription.queueGroup(
                "worker-flows", "kelta.record.changed.>", "worker-flows",
                flowEventListener::handleRecordChanged));

        subscriptionManager.register(EventSubscription.queueGroup(
                "worker-search-index", "kelta.record.changed.>", "worker-search-index",
                searchIndexListener::handleRecordChanged));

        // External flow triggers — NATS_TRIGGERED flows. Subject carries the
        // routing (kelta.trigger.<tenantId>.<topic>), so the handler is
        // subject-aware. Queue group: one pod starts each execution.
        subscriptionManager.register(EventSubscription.queueGroupWithSubject(
                "worker-nats-trigger", "kelta.trigger.>", "worker-nats-trigger",
                natsTriggerFlowListener::handleTriggerMessage));

        // Broadcast subscriptions — every pod receives every message
        subscriptionManager.register(EventSubscription.broadcast(
                "worker-schema", "kelta.config.collection.changed.*",
                collectionSchemaListener::handleCollectionChanged));

        subscriptionManager.register(EventSubscription.broadcast(
                "worker-modules", "kelta.config.module.changed.>",
                moduleEventListener::handleModuleChanged));

        subscriptionManager.register(EventSubscription.broadcast(
                "worker-cerbos", "kelta.cerbos.policies.changed.*",
                cerbosCacheInvalidationListener::handlePolicyChanged));

        // Credential cache invalidation — every pod drops local cache entries
        // when a credential row changes upstream. Only present when
        // kelta.encryption.key is configured (credential beans are conditional
        // on EncryptionService).
        if (credentialCacheInvalidationListener != null) {
            subscriptionManager.register(EventSubscription.broadcast(
                    "worker-credential-cache", "kelta.config.credential.changed.>",
                    credentialCacheInvalidationListener::handleCredentialChanged));
            log.info("Registered NATS subscription for credential cache invalidation");
        }

        // Flow trigger cache invalidation — every pod drops the per-tenant
        // FlowEventListener cache when a flow row changes (create/update/delete).
        // Without this, flow edits made via the UI are invisible to pods that
        // already populated their cache for that tenant until the pod restarts.
        subscriptionManager.register(EventSubscription.broadcast(
                "worker-flow-cache", "kelta.config.flow.changed.>",
                flowEventListener::handleFlowConfigChanged));

        // NATS-trigger cache invalidation — every pod drops the per-tenant
        // NatsTriggerFlowListener cache when a flow row changes.
        subscriptionManager.register(EventSubscription.broadcast(
                "worker-nats-trigger-cache", "kelta.config.flow.changed.>",
                natsTriggerFlowListener::handleFlowConfigChanged));

        // Custom domain cache invalidation — every pod drops cached domain
        // resolutions when a domain row changes upstream.
        subscriptionManager.register(EventSubscription.broadcast(
                "worker-domain-cache", "kelta.config.domain.changed.*",
                customDomainCacheInvalidationListener::handleDomainChanged));

        // System feature cache invalidation — every pod evicts tenant-scoped
        // caches that may depend on feature flags when features change.
        subscriptionManager.register(EventSubscription.broadcast(
                "worker-feature-cache", "kelta.config.feature.changed.*",
                systemFeatureCacheInvalidationListener::handleFeatureChanged));

        subscriptionManager.register(EventSubscription.broadcast(
                "worker-layout-cache", "kelta.config.layout.changed.*",
                layoutCacheInvalidationListener::handleLayoutChanged));

        // System-collection response cache invalidation — every pod evicts its
        // cached JSON:API responses for a system collection when one of its rows
        // changes anywhere in the fleet. Without this, DynamicCollectionRouter's
        // same-pod eviction left the other replicas serving stale field/collection
        // config until the 10-minute TTL.
        subscriptionManager.register(EventSubscription.broadcast(
                "worker-system-collection-cache", "kelta.record.changed.>",
                systemCollectionCacheInvalidationListener::handleRecordChanged));

        // Menu/app config invalidation (apps/nav v2) — every pod evicts its cached
        // ui-menus / ui-menu-items responses when navigation config changes anywhere.
        subscriptionManager.register(EventSubscription.broadcast(
                "worker-menu-cache", "kelta.config.menu.changed.*",
                menuCacheInvalidationListener::handleMenuChanged));

        // Tenant translation invalidation (tenant i18n authoring) — every pod evicts
        // its cached ui-translations responses when a translation changes anywhere.
        subscriptionManager.register(EventSubscription.broadcast(
                "worker-translation-cache", "kelta.config.translation.changed.*",
                translationCacheInvalidationListener::handleTranslationChanged));

        // Availability observations from external pollers (consumer-alerting
        // slice 4) — QUEUE GROUP, not broadcast: an observation must be handled by
        // exactly one pod, or every pod races to claim the same alerts. Subject-aware
        // because the tenant and source live in the subject, not the body.
        subscriptionManager.register(EventSubscription.queueGroupWithSubject(
                "worker-availability", "kelta.availability.event.>", "worker-availability",
                availabilityEventListener::handleAvailabilityEvent));

        // Optional queue group subscriptions — only registered when the integration is enabled
        if (supersetCollectionSyncListener != null) {
            subscriptionManager.register(EventSubscription.queueGroup(
                    "worker-superset", "kelta.config.collection.changed.*", "worker-superset",
                    supersetCollectionSyncListener::onCollectionChanged));
            log.info("Registered NATS subscription for Superset collection sync");
        }

        if (svixWebhookPublisher != null) {
            subscriptionManager.register(EventSubscription.queueGroup(
                    "worker-svix", "kelta.config.collection.changed.*", "worker-svix",
                    svixWebhookPublisher::onCollectionChanged));
            log.info("Registered NATS subscription for Svix webhook publisher");
        }

        if (recordWebhookPublisher != null) {
            subscriptionManager.register(EventSubscription.queueGroup(
                    "worker-svix-records", "kelta.record.changed.>", "worker-svix-records",
                    recordWebhookPublisher::onRecordChanged));
            log.info("Registered NATS subscription for Svix record webhook publisher");
        }

        // Tenant email config invalidation — every pod evicts its
        // SmtpEmailProvider sender cache when tenant email columns change,
        // and broadly when any credential rotates (small cache, cheap to refill).
        if (tenantEmailCacheInvalidationListener != null) {
            subscriptionManager.register(EventSubscription.broadcast(
                    "worker-tenant-email", "kelta.config.tenant.email.changed.>",
                    tenantEmailCacheInvalidationListener::handleTenantEmailChanged));
            subscriptionManager.register(EventSubscription.broadcast(
                    "worker-tenant-email-credential", "kelta.config.credential.changed.>",
                    tenantEmailCacheInvalidationListener::handleCredentialChanged));
            log.info("Registered NATS subscriptions for tenant email cache invalidation");
        }

        log.info("Registered {} worker NATS subscriptions", 9
                + (credentialCacheInvalidationListener != null ? 1 : 0)
                + (supersetCollectionSyncListener != null ? 1 : 0)
                + (svixWebhookPublisher != null ? 1 : 0)
                + (recordWebhookPublisher != null ? 1 : 0)
                + (tenantEmailCacheInvalidationListener != null ? 2 : 0));
    }
}
