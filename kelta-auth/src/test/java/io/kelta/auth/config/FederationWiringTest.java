package io.kelta.auth.config;

import io.kelta.auth.controller.SamlLogoutController;
import io.kelta.auth.federation.DynamicClientRegistrationRepository;
import io.kelta.auth.federation.DynamicRelyingPartyRegistrationRepository;
import io.kelta.auth.federation.FederatedUserMapper;
import io.kelta.auth.federation.OpenSamlStatus;
import io.kelta.auth.federation.SamlLogoutSupport;
import io.kelta.auth.service.OidcDiscoveryService;
import io.kelta.auth.service.WorkerClient;
import io.kelta.crypto.EncryptionAutoConfiguration;
import io.kelta.crypto.EncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.authentication.logout.Saml2LogoutRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the federation beans register in the order Spring Boot really processes
 * configuration: user configuration and component scan first, auto-configuration (which
 * supplies {@link EncryptionService}) last. A {@code @ConditionalOnBean(EncryptionService.class)}
 * in user configuration evaluates before the encryption auto-configuration runs, so it is
 * always false — which is how federation and the sign-out initiator were disabled everywhere.
 */
@DisplayName("Federation wiring — registration order, encryption and the sign-out initiator")
class FederationWiringTest {

    private static final String ORIGIN = "https://auth.example.test";
    private static final String INITIATOR = ORIGIN + "/logout/saml2/initiate";
    private static final String END_SESSION =
            ORIGIN + "/connect/logout?post_logout_redirect_uri=https%3A%2F%2Fapp.example.test";

    /** User configuration (as component scan registers it) + the encryption auto-configuration. */
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EncryptionAutoConfiguration.class))
            .withUserConfiguration(Infrastructure.class, CorsConfig.class, FederationConfig.class,
                    FederatedUserMapper.class, SamlLogoutController.class);

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class Infrastructure {
        @Bean
        WorkerClient workerClient() {
            return mock(WorkerClient.class);
        }

        @Bean
        OidcDiscoveryService oidcDiscoveryService() {
            return mock(OidcDiscoveryService.class);
        }

        @Bean
        AuthProperties authProperties() {
            AuthProperties properties = new AuthProperties();
            properties.setIssuerUri(ORIGIN);
            return properties;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        io.kelta.runtime.outbound.OutboundUrlGuard outboundUrlGuard() {
            return new io.kelta.runtime.outbound.OutboundUrlGuard("");
        }
    }

    private static boolean singleBeanOf(AssertableWebApplicationContext context, Class<?> type, Class<?>... generics) {
        return context.getBeanNamesForType(
                org.springframework.core.ResolvableType.forClassWithGenerics(type, generics)).length == 1;
    }

    private static MockMvc mockMvc(AssertableWebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** No session, a same-origin target → redirect to it; a foreign target → the safe default. */
    private static void assertInitiatorPassesThrough(AssertableWebApplicationContext context) throws Exception {
        MockMvc mvc = mockMvc(context);
        mvc.perform(get(INITIATOR).param("post_logout_redirect_uri", END_SESSION))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(END_SESSION));
        mvc.perform(get(INITIATOR).param("post_logout_redirect_uri", "https://elsewhere.example/landing"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(SamlLogoutSupport.DEFAULT_LOGOUT_SUCCESS_URL));
    }

    @Nested
    @DisplayName("with an encryption key")
    class WithKey {

        @Test
        @DisplayName("registers OIDC brokering, SAML, the mapper and the sign-out initiator")
        void registersFederation() {
            runner.withPropertyValues("kelta.encryption.key=" + randomKey()).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(EncryptionService.class);
                assertThat(context.getBean("clientRegistrationRepository"))
                        .isInstanceOf(DynamicClientRegistrationRepository.class);
                assertThat(context.getBean(RelyingPartyRegistrationRepository.class))
                        .isInstanceOf(DynamicRelyingPartyRegistrationRepository.class);
                assertThat(context.getBean(OpenSamlStatus.class).available()).isTrue();
                assertThat(context).hasSingleBean(FederatedUserMapper.class);
                assertThat(context).hasSingleBean(SamlLogoutController.class);
                assertThat(context).hasSingleBean(Saml2LogoutRequestResolver.class);
                assertThat(context).hasBean("samlSecurityFilterChain");
                // oauth2Login resolves these by generic type and silently falls back to
                // Spring's own clients when there is not exactly one.
                assertThat(singleBeanOf(context, org.springframework.security.oauth2.jwt.JwtDecoderFactory.class,
                        org.springframework.security.oauth2.client.registration.ClientRegistration.class)).isTrue();
                assertThat(singleBeanOf(context,
                        org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient.class,
                        org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest.class))
                        .isTrue();
                assertThat(singleBeanOf(context,
                        org.springframework.security.oauth2.client.userinfo.OAuth2UserService.class,
                        org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest.class,
                        org.springframework.security.oauth2.core.oidc.user.OidcUser.class)).isTrue();
                assertThat(singleBeanOf(context,
                        org.springframework.security.oauth2.client.userinfo.OAuth2UserService.class,
                        org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest.class,
                        org.springframework.security.oauth2.core.user.OAuth2User.class)).isTrue();
            });
        }

        @Test
        @DisplayName("GET /logout/saml2/initiate with no session redirects to a valid target only")
        void initiatorPassesThrough() {
            runner.withPropertyValues("kelta.encryption.key=" + randomKey())
                    .run(FederationWiringTest::assertInitiatorPassesThrough);
        }

        @Test
        @DisplayName("other SAML endpoints still require a session")
        void otherSamlEndpointsRequireAuthentication() {
            runner.withPropertyValues("kelta.encryption.key=" + randomKey()).run(context ->
                    mockMvc(context).perform(get(ORIGIN + "/logout/saml2/elsewhere"))
                            .andExpect(status().is3xxRedirection())
                            .andExpect(redirectedUrl("/login")));
        }
    }

    @Nested
    @DisplayName("without an encryption key")
    class WithoutKey {

        /** application.yml declares {@code key: ${KELTA_ENCRYPTION_KEY:}} — an empty value. */
        @Test
        @DisplayName("OIDC refuses every lookup with a clear error; SAML and the initiator stay registered")
        void oidcFailsClearly() {
            runner.withPropertyValues("kelta.encryption.key=").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeanProvider(EncryptionService.class).getIfAvailable()).isNull();

                ClientRegistrationRepository oidc =
                        context.getBean("clientRegistrationRepository", ClientRegistrationRepository.class);
                assertThat(oidc).isNotInstanceOf(DynamicClientRegistrationRepository.class);
                assertThatThrownBy(() -> oidc.findByRegistrationId("tenant:provider"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("KELTA_ENCRYPTION_KEY");

                assertThat(context.getBean(RelyingPartyRegistrationRepository.class))
                        .isInstanceOf(DynamicRelyingPartyRegistrationRepository.class);
                assertThat(context).hasSingleBean(SamlLogoutController.class);
                assertThat(context).hasBean("samlSecurityFilterChain");
            });
        }

        @Test
        @DisplayName("the key property absent altogether behaves the same")
        void keyPropertyAbsent() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean("clientRegistrationRepository"))
                        .isNotInstanceOf(DynamicClientRegistrationRepository.class);
                assertThat(context).hasSingleBean(SamlLogoutController.class);
            });
        }

        @Test
        @DisplayName("GET /logout/saml2/initiate with no session redirects to a valid target only")
        void initiatorPassesThrough() {
            runner.withPropertyValues("kelta.encryption.key=")
                    .run(FederationWiringTest::assertInitiatorPassesThrough);
        }
    }

    @Nested
    @DisplayName("when OpenSAML cannot initialize")
    class OpenSamlUnavailable {

        /** The federation beans as FederationConfig builds them for an unavailable OpenSAML. */
        @Configuration(proxyBeanMethods = false)
        static class DegradedSaml {
            private final FederationConfig federation = new FederationConfig();
            private final OpenSamlStatus unavailable = OpenSamlStatus.unavailable("test");

            @Bean
            RelyingPartyRegistrationRepository relyingPartyRegistrationRepository(
                    WorkerClient workerClient, AuthProperties authProperties) {
                return federation.relyingPartyRegistrationRepository(workerClient, authProperties, unavailable);
            }

            @Bean
            Saml2LogoutRequestResolver saml2LogoutRequestResolver(RelyingPartyRegistrationRepository repository) {
                return federation.saml2LogoutRequestResolver(repository, unavailable);
            }

            @Bean
            @Order(0)
            SecurityFilterChain samlSecurityFilterChain(HttpSecurity http, RelyingPartyRegistrationRepository repository,
                                                        FederatedUserMapper mapper, WorkerClient workerClient,
                                                        AuthProperties authProperties) throws Exception {
                return federation.samlSecurityFilterChain(http, repository, mapper, workerClient, authProperties,
                        unavailable);
            }
        }

        private final WebApplicationContextRunner degraded = new WebApplicationContextRunner()
                .withUserConfiguration(Infrastructure.class, CorsConfig.class, DegradedSaml.class,
                        FederatedUserMapper.class, SamlLogoutController.class);

        @Test
        @DisplayName("the initiator still passes through, the login page gets no SAML repository, other SAML paths are denied")
        void initiatorStillWorks() {
            degraded.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(RelyingPartyRegistrationRepository.class))
                        .isNotInstanceOf(DynamicRelyingPartyRegistrationRepository.class);
                assertInitiatorPassesThrough(context);
                mockMvc(context).perform(get(ORIGIN + "/saml2/authenticate/tenant:provider"))
                        .andExpect(status().isForbidden());
            });
        }
    }
}
