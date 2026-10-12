package io.kelta.worker.interceptor;

import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.runtime.router.SubResourceAuthorizer;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.CollectionLifecycleManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The router's sub-resource authorizer arrives by optional setter injection, so a worker that
 * failed to register {@link CerbosSubResourceAuthorizer} would boot and serve the child handlers
 * unchecked. This pins that the bean comes up from the worker's existing beans and reaches the
 * router, and that the field-write advice and the atomic operations endpoint get their guard.
 */
@DisplayName("Child-resource and field-write authorization wiring")
class SubResourceAuthorizerWiringTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withBean(CerbosAuthorizationService.class, () -> mock(CerbosAuthorizationService.class))
            .withBean(CollectionLifecycleManager.class, () -> mock(CollectionLifecycleManager.class))
            .withBean(CollectionRegistry.class, () -> mock(CollectionRegistry.class))
            .withBean(QueryEngine.class, () -> mock(QueryEngine.class))
            .withBean(CerbosPermissionResolver.class)
            .withBean(FieldWriteGuard.class)
            .withBean(CerbosFieldWriteSecurityAdvice.class)
            .withBean(CerbosSubResourceAuthorizer.class)
            .withBean(DynamicCollectionRouter.class);

    @Test
    @DisplayName("the router receives the worker's authorizer")
    void routerGetsAuthorizer() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SubResourceAuthorizer.class);
            Object wired = ReflectionTestUtils.getField(
                    context.getBean(DynamicCollectionRouter.class), "subResourceAuthorizer");
            assertThat(wired).isSameAs(context.getBean(CerbosSubResourceAuthorizer.class));
            assertThat(context).hasSingleBean(CerbosFieldWriteSecurityAdvice.class);
        });
    }
}
