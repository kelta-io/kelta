package io.kelta.gateway.authz.cerbos;

import dev.cerbos.sdk.CerbosBlockingClient;
import dev.cerbos.sdk.CheckResult;
import dev.cerbos.sdk.builders.Principal;
import dev.cerbos.sdk.builders.Resource;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.kelta.gateway.auth.GatewayPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CerbosAuthorizationService (Gateway)")
class CerbosAuthorizationServiceTest {

    @Mock
    private CerbosBlockingClient cerbosClient;

    @Mock
    private CheckResult checkResult;

    @Mock
    private CerbosChannel cerbosChannel;

    private CerbosAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new CerbosAuthorizationService(cerbosClient, cerbosChannel, new SimpleMeterRegistry());
    }

    private GatewayPrincipal principal() {
        return new GatewayPrincipal("user@test.com", List.of("group1"), Map.of())
                .withProfileId("profile-1")
                .withTenantId("tenant-1")
                .withProfileName("Test User");
    }

    @Nested
    @DisplayName("System permission caching")
    class SystemPermissionCaching {

        @Test
        @DisplayName("Should cache allowed system permission on first check")
        void cachesAllowedPermission() throws Exception {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("API_ACCESS")))
                    .thenReturn(checkResult);
            when(checkResult.isAllowed("API_ACCESS")).thenReturn(true);

            GatewayPrincipal p = principal();

            // First call — cache miss, calls Cerbos
            StepVerifier.create(service.checkSystemPermission(p, "API_ACCESS"))
                    .expectNext(true)
                    .verifyComplete();

            // Second call — cache hit, should NOT call Cerbos again
            StepVerifier.create(service.checkSystemPermission(p, "API_ACCESS"))
                    .expectNext(true)
                    .verifyComplete();

            verify(cerbosClient, times(1)).check(any(), any(), eq("API_ACCESS"));
        }

        @Test
        @DisplayName("Should cache denied system permission")
        void cachesDeniedPermission() throws Exception {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("API_ACCESS")))
                    .thenReturn(checkResult);
            when(checkResult.isAllowed("API_ACCESS")).thenReturn(false);

            GatewayPrincipal p = principal();

            StepVerifier.create(service.checkSystemPermission(p, "API_ACCESS"))
                    .expectNext(false)
                    .verifyComplete();

            StepVerifier.create(service.checkSystemPermission(p, "API_ACCESS"))
                    .expectNext(false)
                    .verifyComplete();

            verify(cerbosClient, times(1)).check(any(), any(), eq("API_ACCESS"));
        }
    }

    @Nested
    @DisplayName("Object permission caching")
    class ObjectPermissionCaching {

        @Test
        @DisplayName("Should cache collection permission and serve from cache")
        void cachesCollectionPermission() throws Exception {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("read")))
                    .thenReturn(checkResult);
            when(checkResult.isAllowed("read")).thenReturn(true);

            GatewayPrincipal p = principal();

            StepVerifier.create(service.checkObjectPermission(p, "col-1", "read"))
                    .expectNext(true)
                    .verifyComplete();

            StepVerifier.create(service.checkObjectPermission(p, "col-1", "read"))
                    .expectNext(true)
                    .verifyComplete();

            verify(cerbosClient, times(1)).check(any(), any(), eq("read"));
        }

        @Test
        @DisplayName("Should cache different actions separately")
        void cachesSeparateActions() throws Exception {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), anyString()))
                    .thenReturn(checkResult);
            when(checkResult.isAllowed("read")).thenReturn(true);
            when(checkResult.isAllowed("edit")).thenReturn(false);

            GatewayPrincipal p = principal();

            StepVerifier.create(service.checkObjectPermission(p, "col-1", "read"))
                    .expectNext(true)
                    .verifyComplete();

            StepVerifier.create(service.checkObjectPermission(p, "col-1", "edit"))
                    .expectNext(false)
                    .verifyComplete();

            verify(cerbosClient, times(2)).check(any(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("Cache eviction")
    class CacheEviction {

        @Test
        @DisplayName("Should evict cache entries for tenant and re-check Cerbos")
        void evictsForTenant() throws Exception {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("API_ACCESS")))
                    .thenReturn(checkResult);
            when(checkResult.isAllowed("API_ACCESS")).thenReturn(true);

            GatewayPrincipal p = principal();

            // Populate cache
            StepVerifier.create(service.checkSystemPermission(p, "API_ACCESS"))
                    .expectNext(true)
                    .verifyComplete();

            // Evict
            service.evictForTenant("tenant-1");

            // Should call Cerbos again
            StepVerifier.create(service.checkSystemPermission(p, "API_ACCESS"))
                    .expectNext(true)
                    .verifyComplete();

            verify(cerbosClient, times(2)).check(any(), any(), eq("API_ACCESS"));
        }

        @Test
        @DisplayName("Should not evict entries for other tenants")
        void doesNotEvictOtherTenants() throws Exception {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("API_ACCESS")))
                    .thenReturn(checkResult);
            when(checkResult.isAllowed("API_ACCESS")).thenReturn(true);

            GatewayPrincipal p = principal();

            // Populate cache for tenant-1
            StepVerifier.create(service.checkSystemPermission(p, "API_ACCESS"))
                    .expectNext(true)
                    .verifyComplete();

            // Evict for different tenant
            service.evictForTenant("tenant-other");

            // Should still serve from cache
            StepVerifier.create(service.checkSystemPermission(p, "API_ACCESS"))
                    .expectNext(true)
                    .verifyComplete();

            verify(cerbosClient, times(1)).check(any(), any(), eq("API_ACCESS"));
        }
    }

    @Nested
    @DisplayName("Fail-closed behavior")
    class FailClosed {

        @Test
        @DisplayName("Should deny on Cerbos exception and not cache the failure")
        void deniesOnException() throws Exception {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("API_ACCESS")))
                    .thenThrow(new RuntimeException("Connection refused"));

            GatewayPrincipal p = principal();

            StepVerifier.create(service.checkSystemPermission(p, "API_ACCESS"))
                    .expectNext(false)
                    .verifyComplete();

            // On retry after Cerbos recovers, should call again (not cached)
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("API_ACCESS")))
                    .thenReturn(checkResult);
            when(checkResult.isAllowed("API_ACCESS")).thenReturn(true);

            // Need to wait for circuit breaker to close or not yet tripped
            // (only 1 failure, threshold is 3)
            StepVerifier.create(service.checkSystemPermission(p, "API_ACCESS"))
                    .expectNext(true)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Transient-failure retry")
    class TransientRetry {

        private StatusRuntimeException status(Status status) {
            return status.withDescription("test").asRuntimeException();
        }

        @Test
        @DisplayName("One DEADLINE_EXCEEDED then success resets the connection and allows")
        void retriesDeadlineExceededOnce() {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("API_ACCESS")))
                    .thenThrow(status(Status.DEADLINE_EXCEEDED))
                    .thenReturn(checkResult);
            when(checkResult.isAllowed("API_ACCESS")).thenReturn(true);

            StepVerifier.create(service.checkSystemPermission(principal(), "API_ACCESS"))
                    .expectNext(true)
                    .verifyComplete();

            verify(cerbosClient, times(2)).check(any(), any(), eq("API_ACCESS"));
            verify(cerbosChannel).resetConnection();
        }

        @Test
        @DisplayName("One UNAVAILABLE then success allows an object check")
        void retriesUnavailableOnce() {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("read")))
                    .thenThrow(status(Status.UNAVAILABLE))
                    .thenReturn(checkResult);
            when(checkResult.isAllowed("read")).thenReturn(true);

            StepVerifier.create(service.checkObjectPermission(principal(), "col-1", "read"))
                    .expectNext(true)
                    .verifyComplete();

            verify(cerbosClient, times(2)).check(any(), any(), eq("read"));
            verify(cerbosChannel).resetConnection();
        }

        @Test
        @DisplayName("Two consecutive transient failures still deny (fail-closed) and are not cached")
        void twoFailuresDeny() {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("API_ACCESS")))
                    .thenThrow(status(Status.DEADLINE_EXCEEDED))
                    .thenThrow(status(Status.UNAVAILABLE))
                    .thenReturn(checkResult);
            when(checkResult.isAllowed("API_ACCESS")).thenReturn(true);

            StepVerifier.create(service.checkSystemPermission(principal(), "API_ACCESS"))
                    .expectNext(false)
                    .verifyComplete();
            verify(cerbosClient, times(2)).check(any(), any(), eq("API_ACCESS"));

            StepVerifier.create(service.checkSystemPermission(principal(), "API_ACCESS"))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Non-transient errors (INVALID_ARGUMENT) are not retried")
        void doesNotRetryInvalidArgument() {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("API_ACCESS")))
                    .thenThrow(status(Status.INVALID_ARGUMENT));

            StepVerifier.create(service.checkSystemPermission(principal(), "API_ACCESS"))
                    .expectNext(false)
                    .verifyComplete();

            verify(cerbosClient, times(1)).check(any(), any(), eq("API_ACCESS"));
            verify(cerbosChannel, never()).resetConnection();
        }

        @Test
        @DisplayName("Does not retry while already in a failure streak (bounded during outages)")
        void doesNotRetryDuringFailureStreak() {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("API_ACCESS")))
                    .thenThrow(status(Status.UNAVAILABLE));

            StepVerifier.create(service.checkSystemPermission(principal(), "API_ACCESS"))
                    .expectNext(false)
                    .verifyComplete();
            StepVerifier.create(service.checkSystemPermission(principal(), "API_ACCESS"))
                    .expectNext(false)
                    .verifyComplete();

            // First request: attempt + retry. Second request: single attempt, no retry.
            verify(cerbosClient, times(3)).check(any(), any(), eq("API_ACCESS"));
            verify(cerbosChannel, times(1)).resetConnection();
        }

        @Test
        @DisplayName("Classifies transient status codes")
        void classifiesTransient() {
            assertThat(CerbosAuthorizationService.isTransient(status(Status.DEADLINE_EXCEEDED))).isTrue();
            assertThat(CerbosAuthorizationService.isTransient(status(Status.UNAVAILABLE))).isTrue();
            assertThat(CerbosAuthorizationService.isTransient(
                    new java.util.concurrent.ExecutionException(status(Status.UNAVAILABLE)))).isTrue();
            assertThat(CerbosAuthorizationService.isTransient(new java.util.concurrent.TimeoutException())).isTrue();
            assertThat(CerbosAuthorizationService.isTransient(status(Status.INVALID_ARGUMENT))).isFalse();
            assertThat(CerbosAuthorizationService.isTransient(new RuntimeException("boom"))).isFalse();
        }
    }
}
