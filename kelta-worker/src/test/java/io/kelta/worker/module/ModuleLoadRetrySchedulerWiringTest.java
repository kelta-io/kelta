package io.kelta.worker.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Proves {@link ModuleLoadRetryScheduler} is constructible by Spring the way {@code ModuleConfig}
 * registers it ({@code @Import}, so Spring resolves the constructor and its {@code @Value}), and
 * that its {@code @Scheduled} placeholders resolve to a registered task. The worker has no
 * {@code @SpringBootTest}, so nothing else would catch a wiring mistake before a pod failed to
 * start — or, worse, started with no retry at all.
 */
@DisplayName("ModuleLoadRetryScheduler Spring wiring")
class ModuleLoadRetrySchedulerWiringTest {

    @Configuration
    @EnableScheduling
    @Import(ModuleLoadRetryScheduler.class)
    static class SchedulerConfig {
    }

    private final RuntimeModuleManager manager = mock(RuntimeModuleManager.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RuntimeModuleManager.class, () -> manager)
            .withUserConfiguration(SchedulerConfig.class)
            // Keep the real scheduler from ticking during the test.
            .withPropertyValues("kelta.modules.load-retry.poll-interval-ms=3600000");

    @Test
    @DisplayName("constructs with defaults and registers the scheduled retry")
    void constructsAndSchedules() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ModuleLoadRetryScheduler scheduler = context.getBean(ModuleLoadRetryScheduler.class);
            assertThat(scheduler.isEnabled()).isTrue();
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                    .getScheduledTasks()).hasSize(1);

            scheduler.retryPendingLoads();
            verify(manager).retryPendingLoads();
        });
    }

    @Test
    @DisplayName("kelta.modules.load-retry.enabled=false turns the tick into a no-op")
    void disabledIsANoOp() {
        runner.withPropertyValues("kelta.modules.load-retry.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            ModuleLoadRetryScheduler scheduler = context.getBean(ModuleLoadRetryScheduler.class);
            assertThat(scheduler.isEnabled()).isFalse();
            scheduler.retryPendingLoads();
            verify(manager, never()).retryPendingLoads();
        });
    }
}
