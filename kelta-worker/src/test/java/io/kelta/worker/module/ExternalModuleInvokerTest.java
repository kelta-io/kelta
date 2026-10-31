package io.kelta.worker.module;

import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ModuleTimeoutException;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.service.TenantSlugResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static io.kelta.worker.module.ExternalModuleFixtures.json;
import static io.kelta.worker.module.ExternalModuleFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ExternalModuleInvoker")
class ExternalModuleInvokerTest {

    private final ModuleStore store = mock(ModuleStore.class);
    private final ModuleHttpClient http = mock(ModuleHttpClient.class);
    private final ModuleAccessTokenService tokens = mock(ModuleAccessTokenService.class);
    private final TenantSlugResolver slugs = mock(TenantSlugResolver.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ExternalModuleFixtures.MutableClock clock = new ExternalModuleFixtures.MutableClock();
    private ExternalModuleInvoker invoker;

    private final ModuleHttpClient.Call call = ModuleHttpClient.Call.postJson("/kelta/v1/hooks", new byte[0],
            Duration.ofMillis(2000), null);

    @BeforeEach
    void setUp() {
        when(tokens.accessToken(any())).thenReturn("token");
        when(slugs.resolveSlug(ExternalModuleFixtures.TENANT)).thenReturn(Optional.of("acme"));
        // Status writes run inline here; in production they leave the caller's transaction.
        invoker = new ExternalModuleInvoker(store, http, tokens, slugs, meters, clock, Runnable::run);
    }

    @Test
    @DisplayName("a disabled install is refused before anything is sent")
    void disabledIsRefused() {
        assertThatThrownBy(() -> invoker.call(row(TenantModuleData.STATUS_DISABLED), ExternalModuleInvoker.Kind.ACTION,
                call, r -> r)).isExactlyInstanceOf(ModuleUnavailableException.class).hasMessageContaining("DISABLED");
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("a timeout is ModuleTimeoutException (the flow error code) and counts against the breaker")
    void timeoutIsItsOwnError() {
        when(http.invoke(any(), eq("acme"), any(), eq("token")))
                .thenThrow(new ModuleHttpClient.ModuleCallException(0, "timed out", true));

        assertThatThrownBy(() -> invoker.call(row(TenantModuleData.STATUS_ACTIVE), ExternalModuleInvoker.Kind.HOOK,
                call, r -> r)).isInstanceOf(ModuleTimeoutException.class).hasMessageContaining("2000 ms");
        assertThat(invoker.breakerState(ExternalModuleFixtures.INSTALL)).containsEntry("consecutiveFailures", 1);
        assertThat(meters.find(ExternalModuleInvoker.METRIC_CALLS).tag("outcome", "timeout").timer().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("five failures open the breaker, write DEGRADED once (compare-and-set), then refuse without calling")
    void breakerOpensAndDegrades() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(500, "{\"error\":\"down\"}"));
        TenantModuleData active = row(TenantModuleData.STATUS_ACTIVE);

        for (int i = 0; i < ModuleCircuitBreaker.FAILURE_THRESHOLD; i++) {
            assertThatThrownBy(() -> invoker.call(active, ExternalModuleInvoker.Kind.ACTION, call, r -> r))
                    .isExactlyInstanceOf(ModuleUnavailableException.class).hasMessageContaining("500");
        }
        verify(store, times(1)).compareAndSetStatus(eq(ExternalModuleFixtures.INSTALL),
                eq(TenantModuleData.STATUS_ACTIVE), eq(TenantModuleData.STATUS_DEGRADED), anyString());

        assertThatThrownBy(() -> invoker.call(active, ExternalModuleInvoker.Kind.ACTION, call, r -> r))
                .isExactlyInstanceOf(ModuleUnavailableException.class).hasMessageContaining("circuit breaker is open");
        verify(http, times(ModuleCircuitBreaker.FAILURE_THRESHOLD)).invoke(any(), any(), any(), any());
        assertThat(invoker.breakerOpen(ExternalModuleFixtures.INSTALL)).isTrue();
        assertThat(meters.find(ExternalModuleInvoker.METRIC_BREAKER).tag("transition", "opened").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a probe that succeeds after 30 s closes the breaker and writes ACTIVE back")
    void recoveryWritesActive() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(503, ""));
        TenantModuleData active = row(TenantModuleData.STATUS_ACTIVE);
        for (int i = 0; i < ModuleCircuitBreaker.FAILURE_THRESHOLD; i++) {
            try {
                invoker.call(active, ExternalModuleInvoker.Kind.ACTION, call, r -> r);
            } catch (ModuleUnavailableException expected) {
                // opening
            }
        }
        clock.advance(ModuleCircuitBreaker.OPEN_DURATION);
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, "{}"));

        String answer = invoker.call(active, ExternalModuleInvoker.Kind.ACTION, call, r -> "ok");
        assertThat(answer).isEqualTo("ok");
        verify(store).compareAndSetStatus(ExternalModuleFixtures.INSTALL, TenantModuleData.STATUS_DEGRADED,
                TenantModuleData.STATUS_ACTIVE, null);
        assertThat(invoker.breakerOpen(ExternalModuleFixtures.INSTALL)).isFalse();
    }

    @Test
    @DisplayName("a DEGRADED snapshot is cleared by the first success, once per snapshot")
    void degradedSnapshotClearedOnce() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, "{}"));
        TenantModuleData degraded = row(TenantModuleData.STATUS_DEGRADED, Instant.parse("2026-10-30T10:00:00Z"));

        invoker.call(degraded, ExternalModuleInvoker.Kind.HOOK, call, r -> r);
        invoker.call(degraded, ExternalModuleInvoker.Kind.HOOK, call, r -> r);

        verify(store, times(1)).compareAndSetStatus(ExternalModuleFixtures.INSTALL, TenantModuleData.STATUS_DEGRADED,
                TenantModuleData.STATUS_ACTIVE, null);
    }

    @Test
    @DisplayName("a pass-through call returns a module 4xx to the caller and does not count it")
    void passThroughKeepsClientErrors() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(404, "{\"error\":\"no such report\"}"));

        int status = invoker.call(row(TenantModuleData.STATUS_ACTIVE), ExternalModuleInvoker.Kind.ROUTE, call,
                r -> r.statusCode());

        assertThat(status).isEqualTo(404);
        assertThat(invoker.breakerState(ExternalModuleFixtures.INSTALL)).containsEntry("consecutiveFailures", 0);
    }

    @Test
    @DisplayName("a pass-through 5xx is returned for the caller to map, and counts as a failure")
    void passThroughServerErrorCounts() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(502, ""));

        int status = invoker.call(row(TenantModuleData.STATUS_ACTIVE), ExternalModuleInvoker.Kind.WEBHOOK, call,
                r -> r.statusCode());

        assertThat(status).isEqualTo(502);
        assertThat(invoker.breakerState(ExternalModuleFixtures.INSTALL)).containsEntry("consecutiveFailures", 1);
    }

    @Test
    @DisplayName("an unreadable body is a module failure")
    void invalidResponseCounts() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, "not json"));

        assertThatThrownBy(() -> invoker.call(row(TenantModuleData.STATUS_ACTIVE), ExternalModuleInvoker.Kind.SERVICE,
                call, r -> {
                    throw new ExternalModuleInvoker.InvalidResponseException("not an object");
                })).isExactlyInstanceOf(ModuleUnavailableException.class).hasMessageContaining("invalid body");
        assertThat(invoker.breakerState(ExternalModuleFixtures.INSTALL)).containsEntry("consecutiveFailures", 1);
    }

    @Test
    @DisplayName("no access token is unavailable but never the module's fault: the breaker is untouched")
    void tokenFailureNotCounted() {
        when(tokens.accessToken(any())).thenThrow(new IllegalStateException("kelta-auth down"));

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> invoker.call(row(TenantModuleData.STATUS_ACTIVE),
                    ExternalModuleInvoker.Kind.ACTION, call, r -> r))
                    .isExactlyInstanceOf(ModuleUnavailableException.class).hasMessageContaining("no access token");
        }
        assertThat(invoker.breakerOpen(ExternalModuleFixtures.INSTALL)).isFalse();
        verify(http, never()).invoke(any(), any(), any(), any());
        verify(store, never()).compareAndSetStatus(any(), any(), any(), isNull());
    }

    @Test
    @DisplayName("forget drops an uninstalled install's breaker")
    void forgetDropsTheBreaker() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(500, ""));
        try {
            invoker.call(row(TenantModuleData.STATUS_ACTIVE), ExternalModuleInvoker.Kind.ACTION, call, r -> r);
        } catch (ModuleUnavailableException expected) {
            // one failure
        }
        invoker.forget(ExternalModuleFixtures.INSTALL);
        assertThat(invoker.breakerState(ExternalModuleFixtures.INSTALL))
                .isEqualTo(Map.of("breakerState", "CLOSED", "consecutiveFailures", 0));
    }
}
