package io.kelta.auth.config;

import io.kelta.auth.federation.DynamicClientRegistrationRepository;
import io.kelta.auth.federation.DynamicRelyingPartyRegistrationRepository;
import io.kelta.auth.federation.FederatedUserMapper;
import io.kelta.auth.federation.OpenSamlStatus;
import io.kelta.auth.federation.SamlFederatedLoginSuccessHandler;
import io.kelta.auth.federation.SamlLogoutSuccessHandler;
import io.kelta.auth.federation.SamlSpCredentials;
import io.kelta.auth.service.OidcDiscoveryService;
import io.kelta.auth.service.WorkerClient;
import io.kelta.crypto.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.authentication.logout.OpenSaml5LogoutRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.logout.Saml2LogoutRequestResolver;
import org.springframework.security.web.SecurityFilterChain;

import java.util.UUID;

/**
 * Identity federation: OIDC brokering, SAML 2.0 login + Single Logout, and the
 * {@code /logout/saml2/initiate} sign-out initiator.
 *
 * <p>Every bean here is registered unconditionally, and whether federation can run is
 * decided at runtime. Two earlier gates must not come back:
 * <ul>
 *   <li>{@code @ConditionalOnBean(EncryptionService.class)} — {@link EncryptionService} is
 *       an auto-configured bean, and auto-configurations are processed after user
 *       configuration and component scan, so the condition never saw it and every
 *       federation bean was skipped, in every environment.</li>
 *   <li>{@code @ConditionalOnProperty} on the encryption key — kelta-auth ships as a
 *       native image, which freezes conditions at build time, and the build has no key.</li>
 * </ul>
 *
 * <p>Runtime decisions:
 * <ul>
 *   <li><b>OIDC</b> needs {@link EncryptionService} to decrypt provider client secrets.
 *       Without it, {@link #clientRegistrationRepository} is a repository that refuses
 *       every lookup, the login page shows no OIDC buttons, and
 *       {@code AuthorizationServerConfig} does not enable {@code oauth2Login}. It never
 *       operates without encryption.</li>
 *   <li><b>SAML</b> stores no secrets (the IdP certificate is public; the SP signing key is
 *       configuration), so it does not depend on encryption. It depends on OpenSAML
 *       initializing ({@link OpenSamlStatus}); if that fails, SAML endpoints are denied and
 *       the initiator passes every sign-out straight through.</li>
 *   <li>The sign-out initiator always exists and is always {@code permitAll}, so the UI's
 *       logout works whether or not either protocol is available.</li>
 * </ul>
 */
@Configuration
public class FederationConfig {

    private static final Logger log = LoggerFactory.getLogger(FederationConfig.class);

    static final String OIDC_DISABLED_MESSAGE =
            "OAuth2 federation is disabled because KELTA_ENCRYPTION_KEY is not configured. "
                    + "Set the KELTA_ENCRYPTION_KEY environment variable to enable OIDC provider federation.";

    /** The SP-initiated Single Logout initiator, reachable without a session. */
    static final String SAML_LOGOUT_INITIATOR = "/logout/saml2/initiate";

    /**
     * OIDC provider registrations, loaded per tenant from the worker. Resolves
     * {@link EncryptionService} at runtime: the bean exists whether or not a key is set.
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            WorkerClient workerClient,
            OidcDiscoveryService discoveryService,
            ObjectProvider<EncryptionService> encryptionService) {
        EncryptionService encryption = encryptionService.getIfAvailable();
        if (encryption == null) {
            log.warn("EncryptionService not configured (KELTA_ENCRYPTION_KEY not set). "
                    + "OAuth2 federation is DISABLED. Set KELTA_ENCRYPTION_KEY to enable OIDC provider federation.");
            return disabledClientRegistrationRepository();
        }
        log.info("OIDC federation enabled");
        return new DynamicClientRegistrationRepository(workerClient, discoveryService, encryption);
    }

    /**
     * Refuses every lookup, so an attempt to use an OIDC provider without encryption fails
     * with a clear error instead of running without it.
     */
    static ClientRegistrationRepository disabledClientRegistrationRepository() {
        return registrationId -> {
            throw new IllegalStateException(OIDC_DISABLED_MESSAGE);
        };
    }

    /** Initializes OpenSAML once, at startup, so a failure disables SAML rather than the service. */
    @Bean
    public OpenSamlStatus openSamlStatus() {
        OpenSamlStatus status = OpenSamlStatus.probe();
        if (status.available()) {
            log.info("SAML federation enabled");
        } else {
            log.error("OpenSAML failed to initialize — SAML federation is DISABLED "
                    + "(sign-out still works, password and OIDC login are unaffected): {}", status.reason());
        }
        return status;
    }

    /**
     * Per-tenant SAML relying-party registrations, loaded at runtime from the worker. The
     * platform SP signing keypair (if configured) signs outbound AuthnRequests for every
     * tenant. When OpenSAML is unavailable this resolves nothing, so the login page shows no
     * SAML buttons.
     */
    @Bean
    public RelyingPartyRegistrationRepository relyingPartyRegistrationRepository(
            WorkerClient workerClient, AuthProperties authProperties, OpenSamlStatus openSamlStatus) {
        if (!openSamlStatus.available()) {
            return registrationId -> null;
        }
        SamlSpCredentials spCredentials = SamlSpCredentials.fromPem(
                authProperties.getSaml().getSpSigningCertificate(),
                authProperties.getSaml().getSpSigningPrivateKey());
        return new DynamicRelyingPartyRegistrationRepository(workerClient, spCredentials);
    }

    /**
     * Resolves signed SP-initiated SAML {@code LogoutRequest}s from the per-tenant
     * relying-party registrations. Used by {@link io.kelta.auth.controller.SamlLogoutController}.
     * When OpenSAML is unavailable it resolves nothing, so the initiator passes through.
     */
    @Bean
    public Saml2LogoutRequestResolver saml2LogoutRequestResolver(
            RelyingPartyRegistrationRepository relyingPartyRegistrationRepository,
            OpenSamlStatus openSamlStatus) {
        if (!openSamlStatus.available()) {
            return (request, authentication) -> null;
        }
        OpenSaml5LogoutRequestResolver resolver =
                new OpenSaml5LogoutRequestResolver(relyingPartyRegistrationRepository);
        // The initiator carries no inbound RelayState, so generate a per-request
        // nonce: HttpSessionLogoutRequestRepository keys the saved LogoutRequest by
        // RelayState and requires it non-empty, and the IdP echoes it on the
        // returning LogoutResponse for InResponseTo correlation.
        resolver.setRelayStateResolver(request -> UUID.randomUUID().toString());
        return resolver;
    }

    /**
     * Security filter chain for the SAML 2.0 SSO flow. Handles the AuthnRequest initiation
     * ({@code /saml2/authenticate/**}), the assertion consumer ({@code /login/saml2/sso/**}),
     * SP metadata ({@code /saml2/metadata/**}), Single Logout ({@code /logout/saml2/slo/**})
     * and the sign-out initiator. Runs before the default chain via its tight security
     * matcher.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain samlSecurityFilterChain(
            HttpSecurity http,
            RelyingPartyRegistrationRepository relyingPartyRegistrationRepository,
            FederatedUserMapper federatedUserMapper,
            WorkerClient workerClient,
            AuthProperties authProperties,
            OpenSamlStatus openSamlStatus) throws Exception {
        http
            .securityMatcher("/saml2/**", "/login/saml2/**", "/logout/saml2/**")
            // The assertion-consumer + SLO POSTs come cross-site from the IdP with no
            // CSRF token; the SAML message signature is the integrity guarantee.
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                    "/login/saml2/sso/**", "/saml2/**", "/logout/saml2/**"))
            .cors(Customizer.withDefaults());

        if (!openSamlStatus.available()) {
            // Only the initiator is served; it passes through to the post-logout target.
            http.authorizeHttpRequests(authorize -> authorize
                    .requestMatchers(SAML_LOGOUT_INITIATOR).permitAll()
                    .anyRequest().denyAll());
            return http.build();
        }

        http
            .authorizeHttpRequests(authorize -> authorize
                    // SP-initiated logout initiator: permitAll so an expired session
                    // still completes logout (falls through to the OIDC end-session)
                    // instead of bouncing to /login.
                    .requestMatchers(SAML_LOGOUT_INITIATOR).permitAll()
                    .anyRequest().authenticated())
            .saml2Login(saml2 -> saml2
                    .loginPage("/login")
                    .relyingPartyRegistrationRepository(relyingPartyRegistrationRepository)
                    .successHandler(new SamlFederatedLoginSuccessHandler(federatedUserMapper, workerClient)))
            // Single Logout: handles IdP-initiated LogoutRequests (kills the session +
            // returns a LogoutResponse) and, for SP-initiated logout (started by
            // SamlLogoutController), validates the IdP's LogoutResponse. On completion
            // the success handler redirects to the stashed post-logout target.
            .saml2Logout(Customizer.withDefaults())
            .logout(logout -> logout.logoutSuccessHandler(new SamlLogoutSuccessHandler(authProperties)))
            .saml2Metadata(Customizer.withDefaults());

        return http.build();
    }
}
