package io.kelta.runtime.messaging.nats;

import io.kelta.runtime.event.PlatformEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Boot auto-configuration for NATS JetStream messaging.
 *
 * <p>Provides connection management, event publishing, subscription management,
 * stream initialization, and health checking beans.
 *
 * <p>Trace propagation: the publisher and subscription manager accept a
 * {@link NatsTracing} SPI bean. {@link NatsTracingAutoConfiguration} wires
 * {@link OtelNatsTracing} when OpenTelemetry is on the classpath; otherwise this
 * class falls back to {@link NatsTracing#NOOP}.
 *
 * @since 1.0.0
 */
@AutoConfiguration
@EnableConfigurationProperties(NatsProperties.class)
public class NatsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NatsConnectionManager natsConnectionManager(NatsProperties properties) throws Exception {
        return new NatsConnectionManager(properties);
    }

    @Bean
    @ConditionalOnMissingBean(NatsTracing.class)
    public NatsTracing natsTracing() {
        return NatsTracing.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean(PlatformEventPublisher.class)
    public NatsEventPublisher natsEventPublisher(NatsConnectionManager connectionManager,
                                                  ObjectMapper objectMapper,
                                                  NatsProperties properties,
                                                  NatsTracing tracing,
                                                  ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new NatsEventPublisher(connectionManager, objectMapper,
                properties.getMaxInflightPublishes(),
                meterRegistryProvider.getIfAvailable(),
                tracing,
                properties.getMaxEventPayloadBytes());
    }

    @Bean
    @ConditionalOnMissingBean
    public NatsSubscriptionManager natsSubscriptionManager(NatsConnectionManager connectionManager,
                                                            ObjectMapper objectMapper,
                                                            NatsTracing tracing,
                                                            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new NatsSubscriptionManager(connectionManager, objectMapper, tracing,
                meterRegistryProvider.getIfAvailable());
    }

    @Bean
    public JetStreamInitializer jetStreamInitializer(NatsConnectionManager connectionManager) {
        return new JetStreamInitializer(connectionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public NatsHealthIndicator natsHealthIndicator(NatsConnectionManager connectionManager) {
        return new NatsHealthIndicator(connectionManager);
    }
}
