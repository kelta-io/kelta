package io.kelta.worker.service.entitlement;

import io.kelta.runtime.module.service.MemberEntitlements;
import io.kelta.runtime.module.service.ModuleServiceRegistry;
import io.kelta.worker.service.TenantSlugResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean wiring for entitlement resolution, checked in a real Spring context.
 *
 * <p>Nothing else in the worker suite starts a context, so a bean that cannot be constructed
 * compiles, passes every unit test, and fails only at pod startup ({@code concerns.md} → Test
 * Coverage Gaps). Pinned here: {@code EntitlementService} injection points get the module-backed
 * service, and it still constructs — answering EMPTY — when runtime modules are switched off and
 * neither a {@link ModuleServiceRegistry} nor a {@link TenantSlugResolver} bean exists.
 */
@DisplayName("Entitlement service wiring")
class EntitlementServiceWiringTest {

    @Configuration
    static class WithModuleRegistry {
        @Bean
        ModuleServiceRegistry moduleServiceRegistry() {
            return new ModuleServiceRegistry();
        }

        @Bean
        TenantSlugResolver tenantSlugResolver() {
            return mock(TenantSlugResolver.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ModuleEntitlementService.class);

    @Test
    @DisplayName("EntitlementService resolves to the module-backed service")
    void moduleBackedServiceIsTheEntitlementService() {
        runner.withUserConfiguration(WithModuleRegistry.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EntitlementService.class))
                    .isInstanceOf(ModuleEntitlementService.class);
        });
    }

    @Test
    @DisplayName("still starts with runtime modules disabled, and answers EMPTY")
    void startsWithoutModuleRegistry() {
        // ModuleConfig is @ConditionalOnProperty(kelta.modules.runtime.enabled), so the registry
        // can legitimately be absent. A required dependency here would fail the pod, not a test.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EntitlementService.class).resolve("t1", "u1"))
                    .isEqualTo(MemberEntitlements.EMPTY);
        });
    }
}
