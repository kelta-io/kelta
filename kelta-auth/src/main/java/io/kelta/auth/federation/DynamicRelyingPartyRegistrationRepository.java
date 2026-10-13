package io.kelta.auth.federation;

import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.service.WorkerClient.SamlProviderInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamically loads SAML 2.0 relying-party registrations from per-tenant
 * {@code saml_provider} config served by the worker.
 *
 * <p>The SAML mirror of {@link DynamicClientRegistrationRepository}. Each active
 * SAML provider configured for a tenant becomes a {@link RelyingPartyRegistration}
 * whose {@code registrationId} is {@code {tenantId}:{providerId}} — the same
 * convention as OIDC — so the SSO initiation URL is
 * {@code /saml2/authenticate/{tenantId}:{providerId}} and the assertion-consumer
 * URL is {@code /login/saml2/sso/{tenantId}:{providerId}}.
 *
 * <p>The platform is a single SP: every registration carries the same platform SP
 * signing credential ({@link SamlSpCredentials}) and a per-tenant verification
 * credential parsed from the IdP's stored certificate.
 *
 * <p>Hits are cached for {@code CACHE_TTL}; an id the worker reports as unknown is
 * remembered briefly in an {@link UnknownRegistrationCache} so repeated requests for it
 * skip the worker.
 */
public class DynamicRelyingPartyRegistrationRepository implements RelyingPartyRegistrationRepository {

    private static final Logger log =
            LoggerFactory.getLogger(DynamicRelyingPartyRegistrationRepository.class);

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private record CachedRegistration(RelyingPartyRegistration registration, Instant cachedAt) {}

    private final WorkerClient workerClient;
    private final SamlSpCredentials spCredentials;
    private final Map<String, CachedRegistration> registrationCache = new ConcurrentHashMap<>();
    private final UnknownRegistrationCache unknownRegistrations;

    public DynamicRelyingPartyRegistrationRepository(WorkerClient workerClient,
                                                     SamlSpCredentials spCredentials) {
        this(workerClient, spCredentials, new UnknownRegistrationCache());
    }

    DynamicRelyingPartyRegistrationRepository(WorkerClient workerClient,
                                              SamlSpCredentials spCredentials,
                                              UnknownRegistrationCache unknownRegistrations) {
        this.workerClient = workerClient;
        this.spCredentials = spCredentials;
        this.unknownRegistrations = unknownRegistrations;
    }

    @Override
    public RelyingPartyRegistration findByRegistrationId(String registrationId) {
        // Cache entries expire after CACHE_TTL so IdP cert/config changes take
        // effect without a pod restart (mirrors the OIDC repository).
        CachedRegistration cached = registrationCache.get(registrationId);
        if (cached != null && cached.cachedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.registration();
        }

        // registrationId format: {tenantId}:{providerId}
        String[] parts = registrationId.split(":", 2);
        if (parts.length != 2) {
            log.warn("Invalid SAML registration ID format: {} (expected tenantId:providerId)", registrationId);
            return null;
        }

        String tenantId = parts[0];
        String providerId = parts[1];

        if (unknownRegistrations.isKnownUnknown(registrationId)) {
            log.debug("SAML registration {} recently reported unknown — skipping lookup", registrationId);
            return null;
        }

        Optional<List<SamlProviderInfo>> providers = workerClient.lookupActiveSamlProviders(tenantId);
        if (providers.isEmpty()) {
            // The worker could not answer: not evidence the provider is missing, so not cached.
            log.warn("SAML provider lookup failed for registration: {}", registrationId);
            return null;
        }
        for (SamlProviderInfo provider : providers.get()) {
            if (providerId.equals(provider.id())) {
                RelyingPartyRegistration registration = buildRegistration(registrationId, provider);
                if (registration != null) {
                    registrationCache.put(registrationId, new CachedRegistration(registration, Instant.now()));
                }
                return registration;
            }
        }

        unknownRegistrations.recordUnknown(registrationId);
        log.warn("No active SAML provider found for registration: {}", registrationId);
        return null;
    }

    /**
     * Loads all relying-party registrations for a tenant (used to render SSO
     * buttons on the login page).
     *
     * @param tenantId the tenant UUID
     * @return registrations for the tenant's active SAML IdPs
     */
    public List<RelyingPartyRegistration> findByTenantId(String tenantId) {
        List<SamlProviderInfo> providers = workerClient.findActiveSamlProviders(tenantId);
        forgetUnknown(tenantId, providers);
        return providers.stream()
                .map(p -> {
                    String registrationId = tenantId + ":" + p.id();
                    RelyingPartyRegistration reg = buildRegistration(registrationId, p);
                    if (reg != null) {
                        registrationCache.put(registrationId, new CachedRegistration(reg, Instant.now()));
                    }
                    return reg;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** A login-page SSO button for one SAML provider. */
    public record SamlButton(String registrationId, String name) {}

    /**
     * Returns the SSO button descriptors (registrationId + display name) for a
     * tenant's active SAML providers, without building full registrations — used
     * to render login-page buttons that link to {@code /saml2/authenticate/{registrationId}}.
     */
    public List<SamlButton> findButtonsByTenantId(String tenantId) {
        List<SamlProviderInfo> providers = workerClient.findActiveSamlProviders(tenantId);
        // The login page is about to link to these, so a miss recorded moments ago
        // (e.g. just before the provider was created) must not break the button.
        forgetUnknown(tenantId, providers);
        return providers.stream()
                .map(p -> new SamlButton(
                        tenantId + ":" + p.id(),
                        p.name() != null && !p.name().isBlank() ? p.name() : p.registrationId()))
                .toList();
    }

    /** Evicts cached registrations, hits and misses (call when SAML provider config changes). */
    public void evictAll() {
        registrationCache.clear();
        unknownRegistrations.clear();
    }

    private void forgetUnknown(String tenantId, List<SamlProviderInfo> providers) {
        unknownRegistrations.forget(providers.stream().map(p -> tenantId + ":" + p.id()).toList());
    }

    private RelyingPartyRegistration buildRegistration(String registrationId, SamlProviderInfo provider) {
        try {
            if (provider.idpEntityId() == null || provider.ssoUrl() == null) {
                log.warn("SAML provider {} missing entityId or ssoUrl — skipping", provider.id());
                return null;
            }

            X509Certificate idpCertificate = SamlCertificates.parseCertificate(provider.idpCertificate());
            boolean hasSlo = provider.sloUrl() != null && !provider.sloUrl().isBlank();

            RelyingPartyRegistration.Builder builder = RelyingPartyRegistration
                    .withRegistrationId(registrationId)
                    // Per-tenant SP entityId + ACS, resolved against the request base
                    // URL so custom-domain logins advertise the right metadata.
                    .entityId("{baseUrl}/saml2/service-provider-metadata/{registrationId}")
                    .assertionConsumerServiceLocation("{baseUrl}/login/saml2/sso/{registrationId}")
                    .signingX509Credentials(creds -> spCredentials.signing().ifPresent(creds::add))
                    .assertingPartyMetadata(party -> {
                        party.entityId(provider.idpEntityId())
                                .singleSignOnServiceLocation(provider.ssoUrl())
                                .singleSignOnServiceBinding(Saml2MessageBinding.REDIRECT)
                                // Sign AuthnRequests only when the platform SP keypair is
                                // configured; otherwise send them unsigned.
                                .wantAuthnRequestsSigned(spCredentials.hasSigning())
                                .verificationX509Credentials(creds ->
                                        creds.add(Saml2X509Credential.verification(idpCertificate)));
                        // Advertise the IdP's SingleLogoutService so the SP can send
                        // SP-initiated LogoutRequests and validate IdP LogoutResponses.
                        if (hasSlo) {
                            party.singleLogoutServiceLocation(provider.sloUrl())
                                    .singleLogoutServiceBinding(Saml2MessageBinding.REDIRECT);
                        }
                    });

            if (provider.nameIdFormat() != null && !provider.nameIdFormat().isBlank()) {
                builder.nameIdFormat(provider.nameIdFormat());
            }

            // SP-side SLO endpoint (where this SP receives IdP LogoutRequest/Response).
            // Only set when the IdP advertises an SLO URL, so providers without SLO keep
            // their exact prior metadata (no SLO element).
            if (hasSlo) {
                builder.singleLogoutServiceLocation("{baseUrl}/logout/saml2/slo/{registrationId}")
                        .singleLogoutServiceBinding(Saml2MessageBinding.REDIRECT);
            }

            return builder.build();
        } catch (Exception e) {
            log.error("Failed to build SAML registration for provider {}: {}",
                    provider.id(), e.getMessage());
            return null;
        }
    }
}
