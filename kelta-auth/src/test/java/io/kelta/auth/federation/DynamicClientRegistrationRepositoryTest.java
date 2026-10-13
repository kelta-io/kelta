package io.kelta.auth.federation;

import io.kelta.auth.service.OidcDiscoveryService;
import io.kelta.auth.service.OidcDiscoveryService.EndpointOverrides;
import io.kelta.auth.service.OidcDiscoveryService.ResolvedEndpoints;
import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.service.WorkerClient.OidcProviderInfo;
import io.kelta.crypto.EncryptionService;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("DynamicClientRegistrationRepository")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DynamicClientRegistrationRepositoryTest {

    @Mock private WorkerClient workerClient;
    @Mock private OidcDiscoveryService discoveryService;
    @Mock private EncryptionService encryptionService;
    /** Allows every URL unless a test says otherwise — keeps these tests off the network. */
    @Mock private OutboundUrlGuard urlGuard;

    private DynamicClientRegistrationRepository repo;

    @BeforeEach
    void setUp() {
        repo = new DynamicClientRegistrationRepository(workerClient, discoveryService, encryptionService, urlGuard);
    }

    private OidcProviderInfo provider(String id, String clientId, String clientSecretEnc) {
        return new OidcProviderInfo(
                id, "Test Provider", "https://idp.example.com", "https://idp.example.com/jwks",
                null, clientId, clientSecretEnc,
                null, null, null,
                "https://idp.example.com/auth", "https://idp.example.com/token",
                "https://idp.example.com/userinfo", null,
                null, null, null
        );
    }

    private ResolvedEndpoints validEndpoints() {
        return new ResolvedEndpoints(
                "https://idp.example.com/auth",
                "https://idp.example.com/token",
                "https://idp.example.com/userinfo",
                "https://idp.example.com/jwks",
                null, "discovered"
        );
    }

    @Nested
    @DisplayName("findByRegistrationId")
    class FindByRegistrationId {

        @Test
        @DisplayName("returns registration for valid provider")
        void returnsRegistration() {
            OidcProviderInfo prov = provider("prov-1", "client-id", "enc-secret");
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.of(List.of(prov)));
            when(encryptionService.decrypt("enc-secret")).thenReturn("decrypted-secret");
            when(discoveryService.resolve(eq("https://idp.example.com"), any(EndpointOverrides.class)))
                    .thenReturn(validEndpoints());

            ClientRegistration result = repo.findByRegistrationId("tenant1:prov-1");

            assertThat(result).isNotNull();
            assertThat(result.getRegistrationId()).isEqualTo("tenant1:prov-1");
            assertThat(result.getClientId()).isEqualTo("client-id");
            assertThat(result.getClientSecret()).isEqualTo("decrypted-secret");
        }

        @Test
        @DisplayName("returns cached registration on second call")
        void returnsCached() {
            OidcProviderInfo prov = provider("prov-1", "client-id", "enc-secret");
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.of(List.of(prov)));
            when(encryptionService.decrypt("enc-secret")).thenReturn("decrypted-secret");
            when(discoveryService.resolve(anyString(), any())).thenReturn(validEndpoints());

            repo.findByRegistrationId("tenant1:prov-1");
            ClientRegistration cached = repo.findByRegistrationId("tenant1:prov-1");

            assertThat(cached).isNotNull();
            // WorkerClient should only be called once (first call), not for cache hit
            verify(workerClient, times(1)).lookupActiveOidcProviders("tenant1");
        }

        @Test
        @DisplayName("returns null for invalid format")
        void returnsNullForInvalidFormat() {
            ClientRegistration result = repo.findByRegistrationId("no-colon-here");

            assertThat(result).isNull();
            verifyNoInteractions(workerClient);
        }

        @Test
        @DisplayName("returns null when provider not found")
        void returnsNullWhenNotFound() {
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.of(List.of()));

            ClientRegistration result = repo.findByRegistrationId("tenant1:missing-prov");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null when client secret cannot be decrypted")
        void returnsNullWhenDecryptionFails() {
            OidcProviderInfo prov = provider("prov-1", "client-id", "enc-secret");
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.of(List.of(prov)));
            when(encryptionService.decrypt("enc-secret")).thenThrow(new RuntimeException("decrypt error"));

            ClientRegistration result = repo.findByRegistrationId("tenant1:prov-1");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null when missing required endpoints")
        void returnsNullWhenMissingEndpoints() {
            OidcProviderInfo prov = provider("prov-1", "client-id", "enc-secret");
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.of(List.of(prov)));
            when(encryptionService.decrypt("enc-secret")).thenReturn("decrypted");
            when(discoveryService.resolve(anyString(), any())).thenReturn(
                    new ResolvedEndpoints(null, null, null, null, null, "manual"));

            ClientRegistration result = repo.findByRegistrationId("tenant1:prov-1");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null when clientSecretEnc is null")
        void returnsNullWhenClientSecretNull() {
            OidcProviderInfo prov = provider("prov-1", "client-id", null);
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.of(List.of(prov)));
            when(discoveryService.resolve(anyString(), any())).thenReturn(validEndpoints());

            ClientRegistration result = repo.findByRegistrationId("tenant1:prov-1");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null when clientSecretEnc is blank")
        void returnsNullWhenClientSecretBlank() {
            OidcProviderInfo prov = provider("prov-1", "client-id", "  ");
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.of(List.of(prov)));

            ClientRegistration result = repo.findByRegistrationId("tenant1:prov-1");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("findByTenantId")
    class FindByTenantId {

        @Test
        @DisplayName("returns registrations for all valid providers")
        void returnsAllValid() {
            OidcProviderInfo p1 = provider("prov-1", "client-1", "enc-1");
            OidcProviderInfo p2 = provider("prov-2", "client-2", "enc-2");
            when(workerClient.findActiveOidcProviders("tenant1")).thenReturn(List.of(p1, p2));
            when(encryptionService.decrypt(anyString())).thenReturn("decrypted");
            when(discoveryService.resolve(anyString(), any())).thenReturn(validEndpoints());

            List<ClientRegistration> result = repo.findByTenantId("tenant1");

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("filters out providers without clientId")
        void filtersOutMissingClientId() {
            OidcProviderInfo valid = provider("prov-1", "client-1", "enc-1");
            OidcProviderInfo noClient = provider("prov-2", null, "enc-2");
            when(workerClient.findActiveOidcProviders("tenant1")).thenReturn(List.of(valid, noClient));
            when(encryptionService.decrypt("enc-1")).thenReturn("decrypted");
            when(discoveryService.resolve(anyString(), any())).thenReturn(validEndpoints());

            List<ClientRegistration> result = repo.findByTenantId("tenant1");

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("returns empty list when no providers")
        void returnsEmptyWhenNoProviders() {
            when(workerClient.findActiveOidcProviders("tenant1")).thenReturn(List.of());

            List<ClientRegistration> result = repo.findByTenantId("tenant1");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("evictAll")
    class EvictAll {

        @Test
        @DisplayName("clears cache so next lookup hits provider again")
        void clearsCache() {
            OidcProviderInfo prov = provider("prov-1", "client-id", "enc-secret");
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.of(List.of(prov)));
            when(encryptionService.decrypt("enc-secret")).thenReturn("decrypted");
            when(discoveryService.resolve(anyString(), any())).thenReturn(validEndpoints());

            repo.findByRegistrationId("tenant1:prov-1");
            repo.evictAll();
            repo.findByRegistrationId("tenant1:prov-1");

            verify(workerClient, times(2)).lookupActiveOidcProviders("tenant1");
        }

        @Test
        @DisplayName("also clears remembered misses")
        void clearsUnknown() {
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.of(List.of()));

            repo.findByRegistrationId("tenant1:missing");
            repo.evictAll();
            repo.findByRegistrationId("tenant1:missing");

            verify(workerClient, times(2)).lookupActiveOidcProviders("tenant1");
        }
    }

    @Nested
    @DisplayName("endpoint check")
    class EndpointCheck {

        private ClientRegistration build(ResolvedEndpoints endpoints, String allowedCidrs) {
            repo = new DynamicClientRegistrationRepository(workerClient, discoveryService, encryptionService,
                    new OutboundUrlGuard(allowedCidrs));
            OidcProviderInfo prov = provider("prov-1", "client-id", "enc-secret");
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.of(List.of(prov)));
            when(encryptionService.decrypt("enc-secret")).thenReturn("decrypted-secret");
            when(discoveryService.resolve(anyString(), any())).thenReturn(endpoints);
            return repo.findByRegistrationId("tenant1:prov-1");
        }

        private static ResolvedEndpoints endpoints(String token, String userinfo, String jwks) {
            return new ResolvedEndpoints("https://idp.example.com/auth", token, userinfo, jwks, null, "discovered");
        }

        @Test
        @DisplayName("refuses a provider whose token endpoint is on a private address")
        void privateTokenEndpointRefused() {
            assertThat(build(endpoints("http://10.0.0.5/token", null, null), "")).isNull();
        }

        @Test
        @DisplayName("refuses a provider whose user-info endpoint is loopback")
        void loopbackUserinfoRefused() {
            assertThat(build(endpoints("https://203.0.113.10/token", "http://127.0.0.1:8080/userinfo", null), ""))
                    .isNull();
        }

        @Test
        @DisplayName("refuses a provider whose JWKS endpoint is the metadata address")
        void metadataJwksRefused() {
            assertThat(build(endpoints("https://203.0.113.10/token", null, "http://169.254.169.254/jwks"), ""))
                    .isNull();
        }

        @Test
        @DisplayName("refuses a provider whose endpoint is not http(s)")
        void nonHttpEndpointRefused() {
            assertThat(build(endpoints("file:///etc/passwd", null, null), "")).isNull();
        }

        @Test
        @DisplayName("builds the registration when every fetched endpoint is public")
        void publicEndpointsAccepted() {
            ClientRegistration registration = build(endpoints("https://203.0.113.10/token",
                    "https://203.0.113.10/userinfo", "https://203.0.113.10/jwks"), "");

            assertThat(registration).isNotNull();
            assertThat(registration.getProviderDetails().getTokenUri()).isEqualTo("https://203.0.113.10/token");
        }

        @Test
        @DisplayName("builds the registration for an allow-listed private provider")
        void allowListedPrivateAccepted() {
            assertThat(build(endpoints("http://10.0.0.5/token", "http://10.0.0.5/userinfo",
                    "http://10.0.0.5/jwks"), "10.0.0.5/32")).isNotNull();
        }

        @Test
        @DisplayName("does not decide on a host that does not resolve right now")
        void unresolvableLeftToFetchTime() {
            assertThat(build(endpoints("https://idp.invalid/token", null, null), "")).isNotNull();
        }
    }

    @Nested
    @DisplayName("unknown registration ids")
    class UnknownRegistrations {

        private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

        @BeforeEach
        void useClock() {
            repo = new DynamicClientRegistrationRepository(workerClient, discoveryService, encryptionService,
                    urlGuard,
                    new UnknownRegistrationCache(Duration.ofSeconds(30), 100, clock));
        }

        private void givenValidProvider() {
            when(encryptionService.decrypt("enc-secret")).thenReturn("decrypted");
            when(discoveryService.resolve(anyString(), any())).thenReturn(validEndpoints());
        }

        @Test
        @DisplayName("a second miss for the same id does not call the worker")
        void secondMissSkipsWorker() {
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.of(List.of()));

            assertThat(repo.findByRegistrationId("tenant1:missing")).isNull();
            assertThat(repo.findByRegistrationId("tenant1:missing")).isNull();

            verify(workerClient, times(1)).lookupActiveOidcProviders("tenant1");
        }

        @Test
        @DisplayName("the miss expires, so a provider created afterwards is found")
        void missExpires() {
            givenValidProvider();
            when(workerClient.lookupActiveOidcProviders("tenant1"))
                    .thenReturn(Optional.of(List.of()))
                    .thenReturn(Optional.of(List.of(provider("prov-1", "client-id", "enc-secret"))));

            assertThat(repo.findByRegistrationId("tenant1:prov-1")).isNull();
            clock.advance(Duration.ofSeconds(31));

            assertThat(repo.findByRegistrationId("tenant1:prov-1")).isNotNull();
            verify(workerClient, times(2)).lookupActiveOidcProviders("tenant1");
        }

        @Test
        @DisplayName("a failed worker lookup is not remembered as a miss")
        void failureNotCached() {
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.empty());

            repo.findByRegistrationId("tenant1:prov-1");
            repo.findByRegistrationId("tenant1:prov-1");

            verify(workerClient, times(2)).lookupActiveOidcProviders("tenant1");
        }

        @Test
        @DisplayName("a provider shown on the login page is usable even right after a miss")
        void tenantListingOverridesMiss() {
            givenValidProvider();
            when(workerClient.lookupActiveOidcProviders("tenant1")).thenReturn(Optional.of(List.of()));
            assertThat(repo.findByRegistrationId("tenant1:prov-1")).isNull();

            when(workerClient.findActiveOidcProviders("tenant1"))
                    .thenReturn(List.of(provider("prov-1", "client-id", "enc-secret")));
            repo.findByTenantId("tenant1");

            assertThat(repo.findByRegistrationId("tenant1:prov-1")).isNotNull();
        }

        @Test
        @DisplayName("a hit is still cached alongside remembered misses")
        void positiveStillCached() {
            givenValidProvider();
            when(workerClient.lookupActiveOidcProviders("tenant1"))
                    .thenReturn(Optional.of(List.of(provider("prov-1", "client-id", "enc-secret"))));

            assertThat(repo.findByRegistrationId("tenant1:missing")).isNull();
            assertThat(repo.findByRegistrationId("tenant1:prov-1")).isNotNull();
            assertThat(repo.findByRegistrationId("tenant1:prov-1")).isNotNull();
            assertThat(repo.findByRegistrationId("tenant1:missing")).isNull();

            verify(workerClient, times(2)).lookupActiveOidcProviders("tenant1");
        }
    }
}
