package io.kelta.worker.controller;

import io.kelta.worker.module.ExternalModuleProxy;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The module route and webhook controllers must be registered whether or not bean scan order
 * cooperates.
 *
 * <p>The route controller was originally gated with {@code @ConditionalOnBean} on the module
 * manager. That annotation is evaluated during component scanning, before every
 * {@code @Configuration} bean method has been registered, so whether it matches depends on scan
 * order — and this controller lost that race in the deployed image. The symptom was awful: every
 * module route returned 404 while the module reported {@code ACTIVE} with its routes registered, so
 * nothing anywhere pointed at the controller being absent.
 *
 * <p>Nothing else in the worker suite starts a context, which is why this reached a deployed
 * environment before it was noticed.
 */
@DisplayName("ModuleHttpController / ModuleWebhookController wiring")
class ModuleHttpControllerWiringTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withBean(ModuleHttpController.class)
            .withBean(ModuleWebhookController.class);

    @Test
    @DisplayName("register when module support is present")
    void registerWithModuleSupport() {
        runner.withBean(ExternalModuleProxy.class, () -> mock(ExternalModuleProxy.class))
                .withBean(CerbosPermissionResolver.class, () -> mock(CerbosPermissionResolver.class))
                .withBean(BootstrapRepository.class, () -> mock(BootstrapRepository.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModuleHttpController.class);
                    assertThat(context).hasSingleBean(ModuleWebhookController.class);
                });
    }

    @Test
    @DisplayName("still register with module support switched off, rather than vanishing")
    void registerWithoutModuleSupport() {
        // Module support can legitimately be switched off (ModuleConfig is
        // @ConditionalOnProperty). The controllers must still exist and answer 404 — the previous
        // behaviour was for the whole controller to disappear, which is indistinguishable from a
        // route that was never declared.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ModuleHttpController.class);
            assertThat(context).hasSingleBean(ModuleWebhookController.class);
        });
    }
}
