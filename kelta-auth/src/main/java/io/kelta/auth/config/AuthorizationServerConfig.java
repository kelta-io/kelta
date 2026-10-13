package io.kelta.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import io.kelta.auth.controller.ForcePasswordChangeController;
import io.kelta.auth.controller.MfaController;
import io.kelta.auth.federation.DynamicClientRegistrationRepository;
import io.kelta.auth.federation.FederatedLoginSuccessHandler;
import io.kelta.auth.federation.FederatedUserMapper;
import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.AuthDomainResolver;
import io.kelta.auth.service.PasswordPolicyService;
import io.kelta.auth.service.TotpService;
import io.kelta.auth.service.WorkerClient;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationProvider;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Configuration
public class AuthorizationServerConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationServerConfig.class);

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            AuthDomainResolver authDomainResolver,
            RegisteredClientRepository registeredClientRepository,
            SessionRegistry sessionRegistry) throws Exception {
        // Configure OIDC on the configurer BEFORE capturing endpoint matchers,
        // so that OIDC discovery endpoints (/.well-known/openid-configuration)
        // are included in the security matcher for this chain.
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();
        authorizationServerConfigurer
                // The platform UI signs out to a tenant-scoped URI on its own origin, which
                // Spring's exact-match post-logout check would reject before ending the session.
                .oidc(oidc -> oidc.logoutEndpoint(logout -> logout
                        .authenticationProviders(configurePostLogoutRedirectUriValidator(authDomainResolver))))
                // Spring AS has no built-in client-authentication path for public-client
                // refresh_token requests (PublicClientAuthenticationConverter matches PKCE
                // authorization_code only), so the SPA's silent refresh would 302 to the
                // HTML login page. These custom converter/provider close that gap; the
                // refresh token itself is the credential and is rotated on every use.
                .clientAuthentication(clientAuth -> clientAuth
                        .authenticationConverter(new PublicClientRefreshTokenAuthenticationConverter())
                        .authenticationProvider(new PublicClientRefreshTokenAuthenticationProvider(
                                registeredClientRepository)))
                .authorizationEndpoint(endpoint -> endpoint
                        .consentPage("/oauth2/consent")
                        .authenticationProviders(configureRedirectUriValidator(authDomainResolver)));

        RequestMatcher endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();

        http
            .securityMatcher(endpointsMatcher)
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            .csrf(csrf -> csrf.ignoringRequestMatchers(endpointsMatcher))
            .cors(Customizer.withDefaults())
            .exceptionHandling(exceptions -> exceptions
                .defaultAuthenticationEntryPointFor(
                        new IdpHintAwareLoginEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                ))
            // Spring AS writes the ID token's sid from the user's most recently active session
            // in the SessionRegistry, and only registers a session on its FIRST authorization.
            // Refreshing lastRequest on every request here keeps a session that authorizes
            // again (a new tab signing in silently) the most recent one, so its ID token names
            // its own session rather than one the same user opened later on another device.
            // Nothing marks a session expired, so this filter never logs anyone out.
            .addFilter(new ConcurrentSessionFilter(sessionRegistry))
            .with(authorizationServerConfigurer, Customizer.withDefaults());

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(
            HttpSecurity http,
            FederatedUserMapper federatedUserMapper,
            WorkerClient workerClient,
            ClientRegistrationRepository clientRegistrationRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) TotpService totpService,
            PasswordPolicyService passwordPolicyService,
            org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient<
                    org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest>
                    oidcLoginTokenResponseClient,
            org.springframework.security.oauth2.client.userinfo.OAuth2UserService<
                    org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest,
                    org.springframework.security.oauth2.core.oidc.user.OidcUser> oidcLoginUserService,
            org.springframework.security.oauth2.client.userinfo.OAuth2UserService<
                    org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest,
                    org.springframework.security.oauth2.core.user.OAuth2User> oauth2LoginUserService) throws Exception {
        org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler
                savedRequestHandler = new org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler();

        http
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/login",
                                "/change-password",
                                "/mfa-challenge",
                                "/mfa-challenge/recovery",
                                "/mfa-setup",
                                "/mfa-setup/complete",
                                "/portal/login",
                                "/portal/login/request",
                                "/portal/login/verify",
                                "/portal/api/login/request",
                                "/portal/api/login/verify",
                                "/portal/api/signup",
                                "/portal/api/challenge",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/auth/session",
                                "/auth/direct-login",
                                "/error",
                                "/css/**",
                                "/js/**",
                                "/images/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler((request, response, authentication) -> {
                            // Check MFA requirements after successful password authentication
                            if (authentication.getPrincipal() instanceof KeltaUserDetails userDetails
                                    && totpService != null) {
                                String userId = userDetails.getId();
                                String tenantId = userDetails.getTenantId();
                                String email = userDetails.getEmail();

                                boolean mfaEnrolled = totpService.isEnrolled(userId);
                                boolean mfaRequired = isMfaRequiredForTenant(passwordPolicyService, tenantId);

                                if (mfaEnrolled) {
                                    // User has MFA — redirect to challenge
                                    HttpSession session = request.getSession();
                                    session.setAttribute(MfaController.SESSION_MFA_PENDING, true);
                                    session.setAttribute(MfaController.SESSION_MFA_USER_ID, userId);
                                    session.setAttribute(MfaController.SESSION_MFA_TENANT_ID, tenantId);
                                    session.setAttribute(MfaController.SESSION_MFA_EMAIL, email);
                                    SecurityContextHolder.clearContext();
                                    response.sendRedirect("/mfa-challenge");
                                    return;
                                }

                                if (mfaRequired && !mfaEnrolled) {
                                    // Tenant requires MFA but user not enrolled — redirect to setup
                                    HttpSession session = request.getSession();
                                    session.setAttribute(MfaController.SESSION_MFA_PENDING, true);
                                    session.setAttribute(MfaController.SESSION_MFA_SETUP_REQUIRED, true);
                                    session.setAttribute(MfaController.SESSION_MFA_USER_ID, userId);
                                    session.setAttribute(MfaController.SESSION_MFA_TENANT_ID, tenantId);
                                    session.setAttribute(MfaController.SESSION_MFA_EMAIL, email);
                                    SecurityContextHolder.clearContext();
                                    response.sendRedirect("/mfa-setup");
                                    return;
                                }
                            }
                            // No MFA required — replay the original saved request (e.g. /oauth2/authorize?...)
                            // so the authorization code flow completes and redirects back to the UI.
                            savedRequestHandler.onAuthenticationSuccess(request, response, authentication);
                        })
                        .failureHandler((request, response, exception) -> {
                            if (exception.getCause() instanceof CredentialsExpiredException
                                    || exception instanceof CredentialsExpiredException) {
                                String email = request.getParameter("username");
                                request.getSession().setAttribute(
                                        ForcePasswordChangeController.SESSION_ATTR_EMAIL, email);
                                response.sendRedirect("/change-password");
                            } else {
                                response.sendRedirect("/login?error");
                            }
                        })
                )
                // A login form left open past the session timeout fails CSRF on submit;
                // resume the user's authorization request instead of a dead-end 403.
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler(new ExpiredLoginFormHandler()))
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/auth/session", "/auth/direct-login",
                                "/portal/api/login/request", "/portal/api/login/verify",
                                // Easy to miss: permitAll alone is not enough — without
                                // a CSRF exemption this POST 403s for every caller.
                                "/portal/api/signup")
                );

        // Enable federated OAuth2 login only when OIDC federation can run: FederationConfig
        // supplies a DynamicClientRegistrationRepository only when encryption is configured.
        if (clientRegistrationRepository instanceof DynamicClientRegistrationRepository) {
            // Calls to the identity provider go over FederationConfig's guarded clients
            // (OidcLoginHttp); the ID-token decoder factory is picked up as a bean.
            http.oauth2Login(oauth2 -> oauth2
                    .loginPage("/login")
                    .clientRegistrationRepository(clientRegistrationRepository)
                    .tokenEndpoint(token -> token.accessTokenResponseClient(oidcLoginTokenResponseClient))
                    .userInfoEndpoint(userInfo -> userInfo
                            .oidcUserService(oidcLoginUserService)
                            .userService(oauth2LoginUserService))
                    .successHandler(new FederatedLoginSuccessHandler(
                            federatedUserMapper, workerClient))
            );
        }

        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        JdbcRegisteredClientRepository repository = new JdbcRegisteredClientRepository(jdbcTemplate);

        // Spring AS 7.x defaults to its Jackson 3 row/params mappers
        // (JsonMapperRegisteredClientRowMapper, JsonMapperRegisteredClientParametersMapper).
        // Jackson 3's BasicPolymorphicTypeValidator denies the JDK collection types
        // (Collections$UnmodifiableMap etc.) that Spring Security wrote in earlier
        // versions, so existing oauth2_registered_client rows fail to deserialize.
        // Spring Security's allowlist modules (SecurityJackson2Modules) are
        // Jackson 2-only — substitute the Jackson 2 mappers and wire those modules.
        com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        ClassLoader classLoader = JdbcRegisteredClientRepository.class.getClassLoader();
        objectMapper.registerModules(
                org.springframework.security.jackson2.SecurityJackson2Modules.getModules(classLoader));
        objectMapper.registerModule(
                new org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module());

        JdbcRegisteredClientRepository.RegisteredClientRowMapper rowMapper =
                new JdbcRegisteredClientRepository.RegisteredClientRowMapper();
        rowMapper.setObjectMapper(objectMapper);
        repository.setRegisteredClientRowMapper(rowMapper);

        JdbcRegisteredClientRepository.RegisteredClientParametersMapper paramsMapper =
                new JdbcRegisteredClientRepository.RegisteredClientParametersMapper();
        paramsMapper.setObjectMapper(objectMapper);
        repository.setRegisteredClientParametersMapper(paramsMapper);

        // Wrap the JDBC store so connected-app clients resolve dynamically from the
        // connected_app table (both client_credentials and authorization_code),
        // taking effect without a kelta-auth restart. The JDBC store still holds
        // the config-registered clients (platform UI, Superset, internal).
        return new io.kelta.auth.service.ConnectedAppRegisteredClientRepository(repository, jdbcTemplate);
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        JdbcOAuth2AuthorizationService service =
                new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);

        // The JDBC authorization service round-trips OAuth2Authorization through Jackson.
        // Both the read (RowMapper) and write (ParametersMapper) paths must use the
        // same ObjectMapper — otherwise the writer succeeds but the reader cannot
        // deserialize the stored principal (KeltaUserDetails), surfacing as a 500
        // at /oauth2/token.
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = authorizationObjectMapper();

        JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper rowMapper =
                new JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper(registeredClientRepository);
        rowMapper.setObjectMapper(objectMapper);
        service.setAuthorizationRowMapper(rowMapper);

        JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper parametersMapper =
                new JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper();
        parametersMapper.setObjectMapper(objectMapper);
        service.setAuthorizationParametersMapper(parametersMapper);

        return service;
    }

    /**
     * The ObjectMapper the JDBC authorization store reads and writes
     * {@code oauth2_authorization.attributes} with: the Security Jackson modules + the platform
     * mixin, so KeltaUserDetails is in the allowlist for both directions (and its password
     * hash is never written — see {@link io.kelta.auth.model.KeltaUserDetailsMixin}).
     */
    static com.fasterxml.jackson.databind.ObjectMapper authorizationObjectMapper() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        ClassLoader classLoader = JdbcOAuth2AuthorizationService.class.getClassLoader();
        objectMapper.registerModules(org.springframework.security.jackson2.SecurityJackson2Modules.getModules(classLoader));
        // SecurityJackson2Modules detects WebServletJackson2Module reflectively; in
        // the GraalVM native image that detection silently fails, so we register the
        // module explicitly. Without it, WebAuthenticationDetails (set on the
        // authentication during the form-login flow and persisted with the
        // OAuth2Authorization) is rejected by AllowlistTypeIdResolver and
        // /oauth2/token returns 500.
        objectMapper.registerModule(new org.springframework.security.web.jackson2.WebServletJackson2Module());
        objectMapper.registerModule(new org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module());
        objectMapper.addMixIn(io.kelta.auth.model.KeltaUserDetails.class, io.kelta.auth.model.KeltaUserDetailsMixin.class);
        return objectMapper;
    }

    /**
     * The OIDC session registry Spring AS uses for the ID token's {@code sid} claim and the
     * end-session endpoint's {@code sid} check (it adopts a {@link SessionRegistry} bean in
     * place of its private default). HTTP sessions live in this pod's servlet container, and
     * so does this registry. Sessions are found by principal, which relies on
     * {@link KeltaUserDetails#equals(Object)}.
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Publishes servlet session lifecycle events so {@link #sessionRegistry()} forgets a
     * session when it is invalidated or times out, and follows a session-id change.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(AuthProperties properties) {
        AuthorizationServerSettings.Builder builder = AuthorizationServerSettings.builder();
        // When KELTA_AUTH_ISSUER_URI is unset, Spring Authorization Server derives
        // the issuer from each request's base URL — exactly what we need so that
        // logins on a customer custom domain (e.g. https://acme.com) issue tokens
        // with iss=https://acme.com instead of the platform default.
        String issuer = properties.getIssuerUri();
        if (issuer != null && !issuer.isBlank()) {
            builder.issuer(issuer);
        } else {
            log.info("AuthorizationServerSettings.issuer not set — issuer will be derived from each request (custom-domain mode enabled)");
        }
        return builder.build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(AuthProperties properties) throws ParseException {
        String jwkSetJson = properties.getJwkSet();
        if (jwkSetJson != null && !jwkSetJson.isBlank()) {
            JWKSet jwkSet = JWKSet.parse(jwkSetJson);
            return new ImmutableJWKSet<>(jwkSet);
        }
        // Generate a key pair for development/testing
        RSAKey rsaKey = generateRsaKey();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * The authorization server's token generator — Spring's default chain (JWT access/ID
     * tokens with the Kelta claims customizer, opaque access tokens) except for refresh
     * tokens, which come from {@link PublicClientRefreshTokenGenerator} so the public
     * platform UI client receives one. Spring picks this bean up in place of its default.
     */
    @Bean
    public OAuth2TokenGenerator<OAuth2Token> tokenGenerator(
            JwtEncoder jwtEncoder, OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer) {
        JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
        jwtGenerator.setJwtCustomizer(jwtCustomizer);
        return new DelegatingOAuth2TokenGenerator(
                jwtGenerator, new OAuth2AccessTokenGenerator(), new PublicClientRefreshTokenGenerator());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // DelegatingPasswordEncoder understands the {bcrypt} prefix used by
        // connected-app client secrets (ConnectedAppRegisteredClientRepository builds
        // "{bcrypt}" + bcryptHash). encode() now emits a {bcrypt}-prefixed value.
        DelegatingPasswordEncoder encoder =
                (DelegatingPasswordEncoder) PasswordEncoderFactories.createDelegatingPasswordEncoder();
        // Back-compat: existing internal/Superset secrets were stored as BARE
        // bcrypt hashes (no {id} prefix). By default the delegating encoder
        // throws on prefix-less hashes; treat them as bcrypt so they still match.
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return encoder;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    /**
     * Replaces the default redirect_uri validator on the authorization code request
     * authentication provider with our multi-tenant-aware validator.
     */
    private Consumer<List<AuthenticationProvider>> configureRedirectUriValidator(AuthDomainResolver resolver) {
        return authenticationProviders -> {
            for (AuthenticationProvider provider : authenticationProviders) {
                if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider authCodeProvider) {
                    authCodeProvider.setAuthenticationValidator(new PlatformRedirectUriValidator(resolver));
                }
            }
        };
    }

    /**
     * Replaces the default post_logout_redirect_uri validator on the OIDC logout
     * authentication provider with {@link PlatformPostLogoutRedirectUriValidator}.
     */
    private Consumer<List<AuthenticationProvider>> configurePostLogoutRedirectUriValidator(
            AuthDomainResolver resolver) {
        return authenticationProviders -> {
            for (AuthenticationProvider provider : authenticationProviders) {
                if (provider instanceof OidcLogoutAuthenticationProvider logoutProvider) {
                    logoutProvider.setAuthenticationValidator(new PlatformPostLogoutRedirectUriValidator(resolver));
                }
            }
        };
    }

    private boolean isMfaRequiredForTenant(PasswordPolicyService policyService, String tenantId) {
        return policyService.isMfaRequired(tenantId);
    }

    private static RSAKey generateRsaKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate RSA key pair", ex);
        }
    }
}
