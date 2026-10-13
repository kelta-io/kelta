package io.kelta.auth.federation;

import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.service.WorkerClient.SamlProviderInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("DynamicRelyingPartyRegistrationRepository")
@ExtendWith(MockitoExtension.class)
class DynamicRelyingPartyRegistrationRepositoryTest {

    @Mock private WorkerClient workerClient;

    private static String resource(String path) {
        try (InputStream in = DynamicRelyingPartyRegistrationRepositoryTest.class
                .getResourceAsStream(path)) {
            assertThat(in).as("test fixture %s present", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SamlProviderInfo provider(String idpCertPem) {
        return provider(idpCertPem, null);
    }

    private static SamlProviderInfo provider(String idpCertPem, String sloUrl) {
        return new SamlProviderInfo(
                "saml-1", "Acme IdP", "acme",
                "https://idp.acme.example/entity",
                "https://idp.acme.example/sso",
                sloUrl,
                idpCertPem,
                "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress",
                "email", null, true);
    }

    private DynamicRelyingPartyRegistrationRepository repoWithSpSigning() {
        SamlSpCredentials creds = SamlSpCredentials.fromPem(resource("/saml/sp.crt"), resource("/saml/sp.key"));
        return new DynamicRelyingPartyRegistrationRepository(workerClient, creds);
    }

    @Test
    @DisplayName("builds a registration from per-tenant provider config")
    void buildsRegistration() {
        when(workerClient.lookupActiveSamlProviders("tenant-1"))
                .thenReturn(Optional.of(List.of(provider(resource("/saml/idp.crt")))));

        RelyingPartyRegistration reg = repoWithSpSigning().findByRegistrationId("tenant-1:saml-1");

        assertThat(reg).isNotNull();
        assertThat(reg.getRegistrationId()).isEqualTo("tenant-1:saml-1");
        assertThat(reg.getAssertingPartyMetadata().getEntityId())
                .isEqualTo("https://idp.acme.example/entity");
        assertThat(reg.getAssertingPartyMetadata().getSingleSignOnServiceLocation())
                .isEqualTo("https://idp.acme.example/sso");
        assertThat(reg.getAssertingPartyMetadata().getVerificationX509Credentials()).isNotEmpty();
        assertThat(reg.getNameIdFormat())
                .isEqualTo("urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress");
    }

    @Test
    @DisplayName("wires Single Logout (both sides) when the provider has an SLO URL")
    void wiresSingleLogoutWhenSloUrlPresent() {
        when(workerClient.lookupActiveSamlProviders("tenant-1"))
                .thenReturn(Optional.of(List.of(provider(resource("/saml/idp.crt"), "https://idp.acme.example/slo"))));

        RelyingPartyRegistration reg = repoWithSpSigning().findByRegistrationId("tenant-1:saml-1");

        // IdP side: the SLO endpoint the SP sends LogoutRequests to.
        assertThat(reg.getAssertingPartyMetadata().getSingleLogoutServiceLocation())
                .isEqualTo("https://idp.acme.example/slo");
        // SP side: the endpoint this SP receives IdP LogoutRequest/Response on
        // (advertised in metadata), resolved against the request base URL.
        assertThat(reg.getSingleLogoutServiceLocation())
                .isEqualTo("{baseUrl}/logout/saml2/slo/{registrationId}");
    }

    @Test
    @DisplayName("omits Single Logout when the provider has no SLO URL")
    void omitsSingleLogoutWhenNoSloUrl() {
        when(workerClient.lookupActiveSamlProviders("tenant-1"))
                .thenReturn(Optional.of(List.of(provider(resource("/saml/idp.crt")))));

        RelyingPartyRegistration reg = repoWithSpSigning().findByRegistrationId("tenant-1:saml-1");

        assertThat(reg.getAssertingPartyMetadata().getSingleLogoutServiceLocation()).isNull();
    }

    @Test
    @DisplayName("attaches SP signing credential and requests signing when SP keypair configured")
    void signsWhenSpKeypairPresent() {
        when(workerClient.lookupActiveSamlProviders("tenant-1"))
                .thenReturn(Optional.of(List.of(provider(resource("/saml/idp.crt")))));

        RelyingPartyRegistration reg = repoWithSpSigning().findByRegistrationId("tenant-1:saml-1");

        assertThat(reg.getSigningX509Credentials()).as("SP signing credential attached").isNotEmpty();
        assertThat(reg.getAssertingPartyMetadata().getWantAuthnRequestsSigned()).isTrue();
    }

    @Test
    @DisplayName("omits SP signing and request signing when no SP keypair configured")
    void noSigningWhenSpKeypairAbsent() {
        when(workerClient.lookupActiveSamlProviders("tenant-1"))
                .thenReturn(Optional.of(List.of(provider(resource("/saml/idp.crt")))));
        var repo = new DynamicRelyingPartyRegistrationRepository(workerClient, SamlSpCredentials.none());

        RelyingPartyRegistration reg = repo.findByRegistrationId("tenant-1:saml-1");

        assertThat(reg.getSigningX509Credentials()).isEmpty();
        assertThat(reg.getAssertingPartyMetadata().getWantAuthnRequestsSigned()).isFalse();
    }

    @Test
    @DisplayName("returns null for an unknown provider id")
    void returnsNullWhenProviderMissing() {
        when(workerClient.lookupActiveSamlProviders("tenant-1"))
                .thenReturn(Optional.of(List.of(provider(resource("/saml/idp.crt")))));

        assertThat(repoWithSpSigning().findByRegistrationId("tenant-1:nope")).isNull();
    }

    @Test
    @DisplayName("returns null for a malformed registration id")
    void returnsNullForBadRegistrationId() {
        assertThat(repoWithSpSigning().findByRegistrationId("no-colon")).isNull();
        verifyNoInteractions(workerClient);
    }

    @Test
    @DisplayName("returns null (skips provider) when the IdP certificate is unparseable")
    void returnsNullForBadCertificate() {
        when(workerClient.lookupActiveSamlProviders("tenant-1"))
                .thenReturn(Optional.of(List.of(provider("-----BEGIN CERTIFICATE-----\nnot-a-cert\n-----END CERTIFICATE-----"))));

        assertThat(repoWithSpSigning().findByRegistrationId("tenant-1:saml-1")).isNull();
    }

    @Test
    @DisplayName("caches the registration so repeat lookups don't re-hit the worker")
    void cachesRegistration() {
        when(workerClient.lookupActiveSamlProviders("tenant-1"))
                .thenReturn(Optional.of(List.of(provider(resource("/saml/idp.crt")))));
        var repo = repoWithSpSigning();

        repo.findByRegistrationId("tenant-1:saml-1");
        repo.findByRegistrationId("tenant-1:saml-1");

        verify(workerClient, times(1)).lookupActiveSamlProviders("tenant-1");
    }

    @Nested
    @DisplayName("unknown registration ids")
    class UnknownRegistrations {

        private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

        private DynamicRelyingPartyRegistrationRepository repo() {
            return new DynamicRelyingPartyRegistrationRepository(workerClient, SamlSpCredentials.none(),
                    new UnknownRegistrationCache(Duration.ofSeconds(30), 100, clock));
        }

        @Test
        @DisplayName("a second miss for the same id does not call the worker")
        void secondMissSkipsWorker() {
            when(workerClient.lookupActiveSamlProviders("tenant-1")).thenReturn(Optional.of(List.of()));
            var repo = repo();

            assertThat(repo.findByRegistrationId("tenant-1:nope")).isNull();
            assertThat(repo.findByRegistrationId("tenant-1:nope")).isNull();

            verify(workerClient, times(1)).lookupActiveSamlProviders("tenant-1");
        }

        @Test
        @DisplayName("the miss expires, so a provider created afterwards is found")
        void missExpires() {
            when(workerClient.lookupActiveSamlProviders("tenant-1"))
                    .thenReturn(Optional.of(List.of()))
                    .thenReturn(Optional.of(List.of(provider(resource("/saml/idp.crt")))));
            var repo = repo();

            assertThat(repo.findByRegistrationId("tenant-1:saml-1")).isNull();
            clock.advance(Duration.ofSeconds(31));

            assertThat(repo.findByRegistrationId("tenant-1:saml-1")).isNotNull();
            verify(workerClient, times(2)).lookupActiveSamlProviders("tenant-1");
        }

        @Test
        @DisplayName("a failed worker lookup is not remembered as a miss")
        void failureNotCached() {
            when(workerClient.lookupActiveSamlProviders("tenant-1")).thenReturn(Optional.empty());
            var repo = repo();

            repo.findByRegistrationId("tenant-1:saml-1");
            repo.findByRegistrationId("tenant-1:saml-1");

            verify(workerClient, times(2)).lookupActiveSamlProviders("tenant-1");
        }

        @Test
        @DisplayName("rendering a login button for the provider forgets a recorded miss")
        void buttonsForgetMiss() {
            when(workerClient.lookupActiveSamlProviders("tenant-1"))
                    .thenReturn(Optional.of(List.of()))
                    .thenReturn(Optional.of(List.of(provider(resource("/saml/idp.crt")))));
            when(workerClient.findActiveSamlProviders("tenant-1"))
                    .thenReturn(List.of(provider(resource("/saml/idp.crt"))));
            var repo = repo();

            assertThat(repo.findByRegistrationId("tenant-1:saml-1")).isNull();
            repo.findButtonsByTenantId("tenant-1");

            assertThat(repo.findByRegistrationId("tenant-1:saml-1")).isNotNull();
        }

        @Test
        @DisplayName("a hit is still cached alongside remembered misses")
        void positiveStillCached() {
            when(workerClient.lookupActiveSamlProviders("tenant-1"))
                    .thenReturn(Optional.of(List.of(provider(resource("/saml/idp.crt")))));
            var repo = repo();

            assertThat(repo.findByRegistrationId("tenant-1:nope")).isNull();
            assertThat(repo.findByRegistrationId("tenant-1:saml-1")).isNotNull();
            assertThat(repo.findByRegistrationId("tenant-1:saml-1")).isNotNull();
            assertThat(repo.findByRegistrationId("tenant-1:nope")).isNull();

            verify(workerClient, times(2)).lookupActiveSamlProviders("tenant-1");
        }
    }

    @Test
    @DisplayName("findButtonsByTenantId returns registrationId + display name per provider")
    void buildsLoginButtons() {
        when(workerClient.findActiveSamlProviders("tenant-1"))
                .thenReturn(List.of(provider(resource("/saml/idp.crt"))));

        List<DynamicRelyingPartyRegistrationRepository.SamlButton> buttons =
                repoWithSpSigning().findButtonsByTenantId("tenant-1");

        assertThat(buttons).hasSize(1);
        assertThat(buttons.get(0).registrationId()).isEqualTo("tenant-1:saml-1");
        assertThat(buttons.get(0).name()).isEqualTo("Acme IdP");
    }
}
