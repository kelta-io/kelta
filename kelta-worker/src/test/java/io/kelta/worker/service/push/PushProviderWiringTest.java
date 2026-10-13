package io.kelta.worker.service.push;

import io.kelta.worker.repository.PushRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-wiring guard for the push providers.
 *
 * <p>Adding {@link WebPushProvider} put a <b>second</b> {@code PushProvider} bean in
 * the context, which makes the single-typed constructor injection in
 * {@link DefaultPushService} ambiguous — the worker then fails to start, and only
 * when VAPID keys are configured. Nothing else in the suite exercises the Spring
 * context, so without this test that failure would first appear in a deployed
 * environment.
 */
@DisplayName("Push provider wiring")
class PushProviderWiringTest {

    private static final String VAPID_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String VAPID_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";

    @Configuration
    static class Stubs {
        @Bean
        PushRepository pushRepository() {
            return mock(PushRepository.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        io.kelta.runtime.outbound.OutboundUrlGuard outboundUrlGuard() {
            return new io.kelta.runtime.outbound.OutboundUrlGuard("");
        }

        /** Boot's autoconfiguration normally supplies this. */
        @Bean
        org.springframework.web.client.RestClient.Builder restClientBuilder() {
            return org.springframework.web.client.RestClient.builder();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Stubs.class)
            .withBean(DefaultPushService.class)
            .withUserConfiguration(LogOnlyPushProvider.class, FcmPushProvider.class,
                    ApnsPushProvider.class, WebPushProvider.class);

    @Test
    @DisplayName("starts with both a mobile and a web provider registered")
    void startsWithBothProviders() {
        runner.withPropertyValues(
                        "kelta.push.vapid.public-key=" + VAPID_PUBLIC,
                        "kelta.push.vapid.private-key=" + VAPID_PRIVATE,
                        "kelta.push.vapid.subject=mailto:ops@example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(PushProvider.class)).hasSize(2);
                    assertThat(context).hasSingleBean(WebPushProvider.class);
                    assertThat(context).hasSingleBean(DefaultPushService.class);
                });
    }

    @Test
    @DisplayName("web push stays inert when no VAPID key is configured")
    void webProviderInertWithoutKeys() {
        // The bean must exist unconditionally — on the native image a
        // @ConditionalOnProperty is fixed at AOT build time and a deploy-time
        // key could never enable it — but an unconfigured provider must not
        // claim web devices or advertise a public key.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WebPushProvider.class);
            WebPushProvider web = context.getBean(WebPushProvider.class);
            assertThat(web.supports("web")).isFalse();
            assertThat(web.publicKey()).isEmpty();
        });
    }

    @Test
    @DisplayName("the selected mobile transport stays the default alongside web push")
    void mobileTransportStaysDefault() {
        // fcm + web: the specialist must not displace the configured transport for
        // ios/android traffic.
        runner.withPropertyValues(
                        "kelta.push.provider=fcm",
                        "kelta.push.fcm.project-id=p",
                        "kelta.push.vapid.public-key=" + VAPID_PUBLIC,
                        "kelta.push.vapid.private-key=" + VAPID_PRIVATE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FcmPushProvider.class);
                    assertThat(context).doesNotHaveBean(LogOnlyPushProvider.class);
                    assertThat(context.getBeansOfType(PushProvider.class)).hasSize(2);
                });
    }
}
