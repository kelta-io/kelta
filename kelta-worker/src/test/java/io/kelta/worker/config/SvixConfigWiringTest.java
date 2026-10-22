package io.kelta.worker.config;

import io.kelta.worker.controller.SvixPortalController;
import io.kelta.worker.listener.SvixWebhookPublisher;
import io.kelta.worker.service.SvixTenantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The Svix integration is on only with a non-blank {@code kelta.svix.auth-token}. The property is
 * declared as {@code ${SVIX_AUTH_TOKEN:}}, so "unset" arrives blank — which
 * {@code @ConditionalOnProperty} without {@code havingValue} still matched. Every deployment
 * without Svix then built an unauthenticated client and posted every record event to it.
 */
@DisplayName("SvixConfig wiring")
class SvixConfigWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SvixConfig.class, SvixPortalController.class)
            .withBean(ObjectMapper.class, () -> mock(ObjectMapper.class));

    @Test
    @DisplayName("registers the client, publishers and portal controller for a configured token")
    void configured() {
        runner.withPropertyValues("kelta.svix.auth-token=test-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SvixTenantService.class);
                    assertThat(context).hasSingleBean(SvixWebhookPublisher.class);
                    assertThat(context).hasSingleBean(SvixPortalController.class);
                });
    }

    @Test
    @DisplayName("a blank token registers nothing — the portal controller included, so the worker boots")
    void blankToken() {
        runner.withPropertyValues("kelta.svix.auth-token=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SvixTenantService.class);
                    assertThat(context).doesNotHaveBean(SvixWebhookPublisher.class);
                    assertThat(context).doesNotHaveBean(SvixPortalController.class);
                });
    }
}
