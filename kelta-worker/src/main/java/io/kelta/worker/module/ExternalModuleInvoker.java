package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.workflow.ModuleTimeoutException;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.service.TenantSlugResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * The one path every part-9d call to an external module takes
 * ({@code specs/module-platform/9-external-modules.md} §3.6): the install must be callable
 * ({@code ACTIVE} or {@code DEGRADED}), this pod's {@link ModuleCircuitBreaker} must admit the
 * call, the install's service-principal token is attached ({@link ModuleAccessTokenService}),
 * and {@link ModuleHttpClient#invoke} signs and sends it through the module-host guard.
 *
 * <p><b>Outcomes.</b> A protocol call (action, hook, service, health, event) fails on any non-2xx,
 * an unreadable body (the caller's {@link ResponseReader} throws {@link InvalidResponseException}),
 * a timeout or a connect error. A pass-through call (route, webhook) fails only on a 5xx, a timeout
 * or a connect error; any other status belongs to the module and goes back to the caller. Every
 * failure counts against the breaker and every call is timed. Failures surface as
 * {@link ModuleTimeoutException} or {@link ModuleUnavailableException}, whose simple class names
 * are the flow error codes — except a failed answer of a kind that reads its own failures (route,
 * webhook, event), which goes to the reader after counting: the route maps it to the caller, the
 * webhook passes it back to the sender, and event delivery keeps its status for the dead letter.
 *
 * <p><b>Status writes.</b> A breaker that opens writes {@code DEGRADED} with {@code last_error};
 * one that closes again writes {@code ACTIVE}. Both are compare-and-set on the current status, so
 * a breaker can never undo an admin's disable. They are reporting, not routing: every pod keeps
 * calling a {@code DEGRADED} install through its own breaker, so nothing is broadcast.
 */
public class ExternalModuleInvoker {

    private static final Logger log = LoggerFactory.getLogger(ExternalModuleInvoker.class);

    /** Metric: every module call, tagged {@code module}, {@code kind}, {@code outcome}. */
    static final String METRIC_CALLS = "kelta_worker_module_calls";
    /** Metric: breaker transitions, tagged {@code module} and {@code transition} (opened|recovered). */
    static final String METRIC_BREAKER = "kelta_worker_module_breaker_transitions";

    /** What a call is, for its failure rule and its metric tag. */
    public enum Kind {
        ACTION(false, false), HOOK(false, false), SERVICE(false, false), HEALTH(false, false),
        ROUTE(true, true), WEBHOOK(true, true), EVENT(false, true);

        /** Only a 5xx is a failure; any other status is the module's answer to pass on. */
        private final boolean passThrough;
        /** A failed answer still goes to the reader (after counting) instead of throwing. */
        private final boolean readsFailures;

        Kind(boolean passThrough, boolean readsFailures) {
            this.passThrough = passThrough;
            this.readsFailures = readsFailures;
        }

        boolean failed(int status) {
            return passThrough ? status >= 500 : status / 100 != 2;
        }

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Reads a response the invoker already accepted; throw {@link InvalidResponseException} to reject it. */
    @FunctionalInterface
    public interface ResponseReader<T> {
        T read(OutboundHttpClient.Response response);
    }

    /** A module answered with a body the protocol does not allow; counts as a module failure. */
    public static class InvalidResponseException extends RuntimeException {
        public InvalidResponseException(String message) {
            super(message);
        }
    }

    private final ModuleStore moduleStore;
    private final ModuleHttpClient http;
    private final ModuleAccessTokenService tokens;
    private final TenantSlugResolver tenantSlugResolver;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Executor statusWriter;
    private final Map<String, ModuleCircuitBreaker> breakers = new ConcurrentHashMap<>();
    /**
     * The {@code updated_at} of each DEGRADED snapshot this pod already tried to clear, so a
     * healthy module seen through a stale snapshot costs one status write, not one per call.
     */
    private final Map<String, java.time.Instant> degradedCleared = new ConcurrentHashMap<>();

    public ExternalModuleInvoker(ModuleStore moduleStore, ModuleHttpClient http, ModuleAccessTokenService tokens,
                                 TenantSlugResolver tenantSlugResolver, MeterRegistry meterRegistry) {
        this(moduleStore, http, tokens, tenantSlugResolver, meterRegistry, Clock.systemUTC(),
                work -> Thread.ofVirtual().name("module-status-write").start(work));
    }

    ExternalModuleInvoker(ModuleStore moduleStore, ModuleHttpClient http, ModuleAccessTokenService tokens,
                          TenantSlugResolver tenantSlugResolver, MeterRegistry meterRegistry, Clock clock,
                          Executor statusWriter) {
        this.moduleStore = Objects.requireNonNull(moduleStore);
        this.http = Objects.requireNonNull(http);
        this.tokens = Objects.requireNonNull(tokens);
        this.tenantSlugResolver = tenantSlugResolver;
        this.meterRegistry = meterRegistry;
        this.clock = Objects.requireNonNull(clock);
        this.statusWriter = Objects.requireNonNull(statusWriter);
    }

    /** True for a status the platform calls: {@code ACTIVE}, or {@code DEGRADED} (a breaker opened somewhere). */
    public static boolean callable(TenantModuleData module) {
        return module != null && (TenantModuleData.STATUS_ACTIVE.equals(module.status())
                || TenantModuleData.STATUS_DEGRADED.equals(module.status()));
    }

    /**
     * Calls the module and reads the answer.
     *
     * @throws ModuleTimeoutException     the module did not answer within {@code call.timeout()}
     * @throws ModuleUnavailableException the install is not callable, the breaker is open, no token
     *                                    could be had, the module could not be reached, or it
     *                                    answered with a failure for this kind of call
     */
    public <T> T call(TenantModuleData module, Kind kind, ModuleHttpClient.Call call, ResponseReader<T> reader) {
        if (!callable(module)) {
            record(module, kind, "unavailable", 0);
            throw ModuleUnavailableException.of(module.moduleId(),
                    "Module '" + module.moduleId() + "' is " + module.status());
        }
        String token;
        try {
            token = tokens.accessToken(module);
        } catch (RuntimeException e) {
            // kelta-auth, not the module: never counted against the module's breaker.
            record(module, kind, "unavailable", 0);
            throw ModuleUnavailableException.of(module.moduleId(), "Module '" + module.moduleId()
                    + "' has no access token: " + e.getMessage());
        }
        ModuleCircuitBreaker breaker = breaker(module.id());
        if (!breaker.tryAcquire()) {
            record(module, kind, "breaker_open", 0);
            throw ModuleUnavailableException.of(module.moduleId(), "Module '" + module.moduleId()
                    + "' is unavailable on this pod: its circuit breaker is open after "
                    + ModuleCircuitBreaker.FAILURE_THRESHOLD + " consecutive failures (last: "
                    + breaker.lastFailure() + "); next attempt at " + breaker.retryAt());
        }

        long started = System.nanoTime();
        OutboundHttpClient.Response response;
        try {
            response = http.invoke(module, slug(module.tenantId()), call, token);
        } catch (ModuleHttpClient.ModuleCallException e) {
            failed(module, breaker, e.getMessage());
            if (e.timedOut()) {
                record(module, kind, "timeout", started);
                throw new ModuleTimeoutException(module.moduleId(), "Module '" + module.moduleId()
                        + "' did not answer within " + call.timeout().toMillis() + " ms (" + call.path() + ")");
            }
            record(module, kind, "unreachable", started);
            throw ModuleUnavailableException.of(module.moduleId(), "Module '" + module.moduleId()
                    + "' could not be called: " + e.getMessage());
        }

        if (kind.failed(response.statusCode())) {
            String reason = call.method() + " " + call.path() + " answered " + response.statusCode()
                    + ModuleHttpClient.snippet(response);
            failed(module, breaker, reason);
            record(module, kind, "error", started);
            if (kind.readsFailures) {
                // The caller maps a failed answer itself (502 to a route caller, passed back to a
                // webhook sender so it retries, kept for an event's dead letter).
                return reader.read(response);
            }
            throw ModuleUnavailableException.of(module.moduleId(), "Module '" + module.moduleId() + "' " + reason);
        }

        T result;
        try {
            result = reader.read(response);
        } catch (InvalidResponseException e) {
            failed(module, breaker, "invalid response from " + call.path() + ": " + e.getMessage());
            record(module, kind, "invalid", started);
            throw ModuleUnavailableException.of(module.moduleId(), "Module '" + module.moduleId()
                    + "' answered " + call.path() + " with an invalid body: " + e.getMessage());
        }
        succeeded(module, breaker);
        record(module, kind, "success", started);
        return result;
    }

    /** This pod's breaker for an install, for the health endpoint. */
    public Map<String, Object> breakerState(String installId) {
        ModuleCircuitBreaker breaker = breakers.get(installId);
        if (breaker == null) {
            return Map.of("breakerState", ModuleCircuitBreaker.State.CLOSED.name(), "consecutiveFailures", 0);
        }
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("breakerState", breaker.state().name());
        state.put("consecutiveFailures", breaker.consecutiveFailures());
        state.put("breakerRetryAt", breaker.retryAt() == null ? null : breaker.retryAt().toString());
        state.put("lastFailure", breaker.lastFailure());
        return state;
    }

    /** True when this pod's breaker for the install is not closed. */
    boolean breakerOpen(String installId) {
        ModuleCircuitBreaker breaker = breakers.get(installId);
        return breaker != null && breaker.state() != ModuleCircuitBreaker.State.CLOSED;
    }

    /** Drops an uninstalled install's breaker. */
    void forget(String installId) {
        breakers.remove(installId);
        degradedCleared.remove(installId);
    }

    private ModuleCircuitBreaker breaker(String installId) {
        return breakers.computeIfAbsent(installId, id -> new ModuleCircuitBreaker(clock));
    }

    private void failed(TenantModuleData module, ModuleCircuitBreaker breaker, String reason) {
        log.warn("Module '{}' (tenant {}) call failed: {}", module.moduleId(), module.tenantId(), reason);
        if (breaker.onFailure(reason) == ModuleCircuitBreaker.Transition.OPENED) {
            String error = "circuit breaker opened after " + ModuleCircuitBreaker.FAILURE_THRESHOLD
                    + " consecutive failures: " + reason;
            log.error("Module '{}' (tenant {}) is DEGRADED on this pod: {}", module.moduleId(), module.tenantId(), error);
            writeStatus(module, TenantModuleData.STATUS_ACTIVE, TenantModuleData.STATUS_DEGRADED, error);
            transition(module, "opened");
        }
    }

    private void succeeded(TenantModuleData module, ModuleCircuitBreaker breaker) {
        if (breaker.onSuccess() == ModuleCircuitBreaker.Transition.RECOVERED) {
            log.info("Module '{}' (tenant {}) recovered; its circuit breaker closed", module.moduleId(),
                    module.tenantId());
            transition(module, "recovered");
            // Whatever the snapshot says: this pod wrote DEGRADED itself, after the snapshot was read.
            writeStatus(module, TenantModuleData.STATUS_DEGRADED, TenantModuleData.STATUS_ACTIVE, null);
            return;
        }
        // Also from a closed breaker: another pod (or a pod since restarted) may have written
        // DEGRADED, and only a success can clear it.
        if (TenantModuleData.STATUS_DEGRADED.equals(module.status())) {
            java.time.Instant seen = module.updatedAt() == null ? java.time.Instant.EPOCH : module.updatedAt();
            if (!seen.equals(degradedCleared.put(module.id(), seen))) {
                writeStatus(module, TenantModuleData.STATUS_DEGRADED, TenantModuleData.STATUS_ACTIVE, null);
            }
        }
    }

    /**
     * Off the calling thread, on purpose: a hook call runs inside the write's transaction, which
     * the hook's own failure then rolls back — a status written on that connection would vanish
     * with it, and would hold a second statement on a connection the write already holds.
     */
    private void writeStatus(TenantModuleData module, String expected, String status, String error) {
        statusWriter.execute(() -> {
            try {
                TenantContext.runWithTenant(module.tenantId(),
                        () -> moduleStore.compareAndSetStatus(module.id(), expected, status, error));
            } catch (RuntimeException e) {
                log.warn("Could not record status {} for module '{}' (tenant {}): {}", status, module.moduleId(),
                        module.tenantId(), e.getMessage());
            }
        });
    }

    private void transition(TenantModuleData module, String transition) {
        if (meterRegistry != null) {
            Counter.builder(METRIC_BREAKER).tag("module", module.moduleId()).tag("transition", transition)
                    .register(meterRegistry).increment();
        }
    }

    private void record(TenantModuleData module, Kind kind, String outcome, long started) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder(METRIC_CALLS).tag("module", module.moduleId()).tag("kind", kind.tag()).tag("outcome", outcome)
                .register(meterRegistry)
                .record(started == 0 ? Duration.ZERO : Duration.ofNanos(System.nanoTime() - started));
    }

    private String slug(String tenantId) {
        return tenantSlugResolver == null ? null : tenantSlugResolver.resolveSlug(tenantId).orElse(null);
    }
}
