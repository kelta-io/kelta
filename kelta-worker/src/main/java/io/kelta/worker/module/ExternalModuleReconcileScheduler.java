package io.kelta.worker.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * Drives {@link ExternalModuleRegistry#reconcile()} once when the pod is ready and then on a fixed
 * delay ({@code kelta.modules.external.reconcile-interval-ms}, 60 s) on every pod
 * ({@code specs/module-platform/9-external-modules.md} §3.5).
 *
 * <p>Per pod by design, like {@link ModuleLoadRetryScheduler}: each pod keeps its own registrations
 * and breakers, so each re-reads the shared rows itself. The rows are only read here (Critical
 * Rule 1); a breaker's status write happens on the call path, compare-and-set.
 *
 * <p>Gated by a runtime check of {@code kelta.modules.external.reconcile-enabled} rather than a
 * {@code @ConditionalOnProperty}, which a native image would freeze at build time.
 */
public class ExternalModuleReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExternalModuleReconcileScheduler.class);

    private final ExternalModuleRegistry registry;
    private final boolean enabled;

    public ExternalModuleReconcileScheduler(
            ExternalModuleRegistry registry,
            @Value("${kelta.modules.external.reconcile-enabled:true}") boolean enabled) {
        this.registry = Objects.requireNonNull(registry);
        this.enabled = enabled;
        if (!enabled) {
            log.warn("External module reconcile is DISABLED (kelta.modules.external.reconcile-enabled=false): "
                    + "this pod registers external modules only from module.changed events, so one it misses "
                    + "stays stale until the pod restarts");
        }
    }

    /** The first full read: external modules are registered from their rows, never loaded at boot. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${kelta.modules.external.reconcile-interval-ms:60000}",
               initialDelayString = "${kelta.modules.external.reconcile-interval-ms:60000}")
    public void reconcile() {
        if (!enabled) {
            return;
        }
        try {
            registry.reconcile();
        } catch (Exception e) {
            log.error("External module reconcile failed: {}", e.getMessage(), e);
        }
    }

    /** Whether reconciles run. */
    public boolean isEnabled() {
        return enabled;
    }
}
