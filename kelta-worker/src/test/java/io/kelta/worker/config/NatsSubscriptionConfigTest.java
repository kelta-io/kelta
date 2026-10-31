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
import io.kelta.worker.listener.MenuCacheInvalidationListener;
import io.kelta.worker.listener.SystemCollectionCacheInvalidationListener;
import io.kelta.worker.listener.TranslationCacheInvalidationListener;
import io.kelta.worker.listener.NatsTriggerFlowListener;
import io.kelta.worker.listener.SearchIndexListener;
import io.kelta.worker.listener.SystemFeatureCacheInvalidationListener;
import io.kelta.worker.module.ModuleEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("NatsSubscriptionConfig")
class NatsSubscriptionConfigTest {

    private NatsSubscriptionManager subscriptionManager;
    private NatsSubscriptionConfig config;

    @BeforeEach
    void setUp() {
        subscriptionManager = mock(NatsSubscriptionManager.class);
        config = new NatsSubscriptionConfig(
                subscriptionManager,
                mock(FlowEventListener.class),
                mock(NatsTriggerFlowListener.class),
                mock(SearchIndexListener.class),
                mock(CollectionSchemaListener.class),
                mock(ModuleEventListener.class),
                mock(CerbosCacheInvalidationListener.class),
                mock(CustomDomainCacheInvalidationListener.class),
                mock(SystemFeatureCacheInvalidationListener.class),
                mock(LayoutCacheInvalidationListener.class),
                mock(MenuCacheInvalidationListener.class),
                mock(TranslationCacheInvalidationListener.class),
                mock(SystemCollectionCacheInvalidationListener.class),
                mock(AvailabilityEventListener.class));
    }

    @Test
    @DisplayName("registerSubscriptions boots without CredentialCacheInvalidationListener (no encryption key)")
    void registerSubscriptions_withoutCredentialListener_skipsCredentialCacheRegistration() {
        // Optional listeners (incl. credential cache) left null — mirrors Spring wiring
        // when kelta.encryption.key is not configured.
        config.registerSubscriptions();

        // Fifteen always-on subscriptions: worker-flows, worker-nats-trigger,
        // worker-search-index, worker-schema, worker-modules, worker-cerbos,
        // worker-flow-cache, worker-nats-trigger-cache, worker-domain-cache,
        // worker-feature-cache, worker-layout-cache, worker-menu-cache,
        // worker-translation-cache, worker-system-collection-cache,
        // worker-availability.
        // Credential/Superset/Svix are conditional.
        verify(subscriptionManager, times(15)).register(any(EventSubscription.class));
    }

    @Test
    @DisplayName("registerSubscriptions registers credential cache subscription when listener is present")
    void registerSubscriptions_withCredentialListener_registersCredentialCache() {
        ReflectionTestUtils.setField(config, "credentialCacheInvalidationListener",
                mock(CredentialCacheInvalidationListener.class));

        config.registerSubscriptions();

        verify(subscriptionManager, times(16)).register(any(EventSubscription.class));
    }

    @Test
    @DisplayName("registerSubscriptions registers the external-module event consumer when modules are on")
    void registerSubscriptions_withModuleEventDelivery_registersQueueGroupWithRedelivery() {
        io.kelta.worker.module.ModuleEventDeliveryListener delivery =
                mock(io.kelta.worker.module.ModuleEventDeliveryListener.class);
        EventSubscription moduleEvents = EventSubscription.queueGroupWithRedelivery("worker-module-events",
                "kelta.record.changed.>", "worker-module-events", new EventSubscription.Redelivery(
                        java.util.List.of(java.time.Duration.ofSeconds(5)), java.time.Duration.ofSeconds(60),
                        (subject, body, attempt) -> { }));
        org.mockito.Mockito.when(delivery.subscription()).thenReturn(moduleEvents);
        ReflectionTestUtils.setField(config, "moduleEventDeliveryListener", delivery);

        config.registerSubscriptions();

        verify(subscriptionManager, times(16)).register(any(EventSubscription.class));
        verify(subscriptionManager).register(moduleEvents);
    }
}
