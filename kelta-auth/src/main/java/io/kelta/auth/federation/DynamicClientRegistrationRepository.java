package io.kelta.auth.federation;

import io.kelta.auth.service.OidcDiscoveryService;
import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.service.WorkerClient.OidcProviderInfo;
import io.kelta.crypto.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamically loads OAuth2 client registrations from the OIDC provider database.
 *
 * <p>Each OIDC provider configured for a tenant becomes an OAuth2 client registration
 * that kelta-auth can use to redirect users to external IdPs for authentication.
 *
 * <p>The registration ID is the OIDC provider's database ID, which is also used
 * as the OAuth2 login link: {@code /oauth2/authorization/{providerId}}.
 *
 * <p>Hits are cached for {@code CACHE_TTL}; an id the worker reports as unknown is
 * remembered briefly in an {@link UnknownRegistrationCache} so repeated requests for it
 * skip the worker.
 */
public class DynamicClientRegistrationRepository implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamicClientRegistrationRepository.class);

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private record CachedRegistration(ClientRegistration registration, Instant cachedAt) {}

    private final WorkerClient workerClient;
    private final OidcDiscoveryService discoveryService;
    private final EncryptionService encryptionService;
    private final Map<String, CachedRegistration> registrationCache = new ConcurrentHashMap<>();
    private final UnknownRegistrationCache unknownRegistrations;

    public DynamicClientRegistrationRepository(
            WorkerClient workerClient,
            OidcDiscoveryService discoveryService,
            EncryptionService encryptionService) {
        this(workerClient, discoveryService, encryptionService, new UnknownRegistrationCache());
    }

    DynamicClientRegistrationRepository(
            WorkerClient workerClient,
            OidcDiscoveryService discoveryService,
            EncryptionService encryptionService,
            UnknownRegistrationCache unknownRegistrations) {
        this.workerClient = workerClient;
        this.discoveryService = discoveryService;
        this.encryptionService = encryptionService;
        this.unknownRegistrations = unknownRegistrations;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        // Check cache first — entries expire after CACHE_TTL so secret rotations
        // and provider config changes take effect without a pod restart.
        CachedRegistration cached = registrationCache.get(registrationId);
        if (cached != null && cached.cachedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.registration();
        }

        // The registrationId format is: {tenantId}:{providerId}
        // This allows us to look up the correct provider for the tenant
        String[] parts = registrationId.split(":", 2);
        if (parts.length != 2) {
            log.warn("Invalid registration ID format: {} (expected tenantId:providerId)", registrationId);
            return null;
        }

        String tenantId = parts[0];
        String providerId = parts[1];

        if (unknownRegistrations.isKnownUnknown(registrationId)) {
            log.debug("OIDC registration {} recently reported unknown — skipping lookup", registrationId);
            return null;
        }

        Optional<List<OidcProviderInfo>> providers = workerClient.lookupActiveOidcProviders(tenantId);
        if (providers.isEmpty()) {
            // The worker could not answer: not evidence the provider is missing, so not cached.
            log.warn("OIDC provider lookup failed for registration: {}", registrationId);
            return null;
        }
        for (OidcProviderInfo provider : providers.get()) {
            if (providerId.equals(provider.id())) {
                ClientRegistration registration = buildClientRegistration(registrationId, provider);
                if (registration != null) {
                    registrationCache.put(registrationId, new CachedRegistration(registration, Instant.now()));
                }
                return registration;
            }
        }

        unknownRegistrations.recordUnknown(registrationId);
        log.warn("No OIDC provider found for registration: {}", registrationId);
        return null;
    }

    /**
     * Loads all client registrations for a tenant.
     *
     * @param tenantId the tenant UUID
     * @return list of client registrations for the tenant's external IdPs
     */
    public List<ClientRegistration> findByTenantId(String tenantId) {
        List<OidcProviderInfo> providers = workerClient.findActiveOidcProviders(tenantId);
        List<ClientRegistration> registrations = providers.stream()
                .filter(p -> p.clientId() != null && p.clientSecretEnc() != null)
                .map(p -> {
                    String registrationId = tenantId + ":" + p.id();
                    ClientRegistration reg = buildClientRegistration(registrationId, p);
                    if (reg != null) {
                        registrationCache.put(registrationId, new CachedRegistration(reg, Instant.now()));
                    }
                    return reg;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        // These providers exist now, so a miss recorded moments ago must not hide them.
        unknownRegistrations.forget(providers.stream().map(p -> tenantId + ":" + p.id()).toList());
        return registrations;
    }

    /**
     * Evicts cached registrations, hits and misses (call when OIDC provider config changes).
     */
    public void evictAll() {
        registrationCache.clear();
        unknownRegistrations.clear();
    }

    private ClientRegistration buildClientRegistration(String registrationId, OidcProviderInfo provider) {
        try {
            // Decrypt client secret
            String clientSecret = null;
            if (provider.clientSecretEnc() != null && !provider.clientSecretEnc().isBlank()) {
                clientSecret = encryptionService.decrypt(provider.clientSecretEnc());
            }

            if (provider.clientId() == null || clientSecret == null) {
                log.debug("Skipping OIDC provider {} — missing clientId or clientSecret", provider.id());
                return null;
            }

            // Resolve endpoints using Discovery + overrides
            OidcDiscoveryService.EndpointOverrides overrides = new OidcDiscoveryService.EndpointOverrides(
                    provider.authorizationUri(),
                    provider.tokenUri(),
                    provider.userinfoUri(),
                    provider.jwksUri(),
                    provider.endSessionUri()
            );
            OidcDiscoveryService.ResolvedEndpoints endpoints =
                    discoveryService.resolve(provider.issuer(), overrides);

            if (endpoints.authorizationUri() == null || endpoints.tokenUri() == null) {
                log.warn("OIDC provider {} missing required endpoints (authorization or token)", provider.id());
                return null;
            }

            return ClientRegistration.withRegistrationId(registrationId)
                    .clientId(provider.clientId())
                    .clientSecret(clientSecret)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid", "email", "profile")
                    .authorizationUri(endpoints.authorizationUri())
                    .tokenUri(endpoints.tokenUri())
                    .userInfoUri(endpoints.userinfoUri())
                    .jwkSetUri(endpoints.jwksUri())
                    .userNameAttributeName(IdTokenClaimNames.SUB)
                    .clientName(provider.name() != null ? provider.name() : provider.issuer())
                    .issuerUri(provider.issuer())
                    .build();

        } catch (Exception e) {
            log.error("Failed to build client registration for OIDC provider {}: {}",
                    provider.id(), e.getMessage());
            return null;
        }
    }
}
