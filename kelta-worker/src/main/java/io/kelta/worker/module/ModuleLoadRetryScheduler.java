package io.kelta.worker.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * Drives {@link RuntimeModuleManager#retryPendingLoads()} on a fixed delay, on every pod.
 *
 * <p>The tick is cheap when nothing failed: the manager only touches modules whose transient-failure
 * backoff has elapsed, so a healthy pod does no I/O here. The per-module backoff lives in the
 * manager ({@code kelta.modules.load-retry.initial-backoff-ms} / {@code max-backoff-ms}); this
 * interval only bounds how late after its backoff a retry can start.
 *
 * <p>Per-pod by design, with no NATS broadcast and no claim: every pod loads its own classloaders,
 * so every pod has to retry its own failed loads. This is not configuration being mutated on one
 * pod (Critical Rule 1) — the shared state, the {@code tenant_module} row, is only read.
 *
 * <p>Gated by a runtime check of {@code kelta.modules.load-retry.enabled} rather than a
 * {@code @ConditionalOnProperty}, which a native image would freeze at build time.
 */
public class ModuleLoadRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ModuleLoadRetryScheduler.class);

    private final RuntimeModuleManager runtimeModuleManager;
    private final boolean enabled;

    public ModuleLoadRetryScheduler(
            RuntimeModuleManager runtimeModuleManager,
            @Value("${kelta.modules.load-retry.enabled:true}") boolean enabled) {
        this.runtimeModuleManager = Objects.requireNonNull(runtimeModuleManager);
        this.enabled = enabled;
        if (!enabled) {
            log.warn("Runtime module load retry is DISABLED (kelta.modules.load-retry.enabled="
                + "false): a module that fails to load for an infrastructure reason stays "
                + "unavailable on that pod until it restarts or the module is re-enabled");
        }
    }

    @Scheduled(fixedDelayString = "${kelta.modules.load-retry.poll-interval-ms:15000}",
               initialDelayString = "${kelta.modules.load-retry.poll-interval-ms:15000}")
    public void retryPendingLoads() {
        if (!enabled) {
            return;
        }
        try {
            runtimeModuleManager.retryPendingLoads();
        } catch (Exception e) {
            log.error("Module load retry cycle failed: {}", e.getMessage(), e);
        }
    }

    /** Whether retries run. */
    public boolean isEnabled() {
        return enabled;
    }
}
