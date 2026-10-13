package io.kelta.auth.aot;

import io.kelta.auth.controller.ConsentController;
import io.kelta.auth.controller.LoginController;
import io.kelta.auth.model.KeltaSession;
import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.model.KeltaUserDetailsMixin;
import io.kelta.auth.model.SamlLogoutInfo;
import io.kelta.auth.service.WorkerClient;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.lang.Nullable;

import java.io.Serializable;
import java.util.List;

/**
 * GraalVM native-image hints for kelta-auth.
 *
 * Imported via @ImportRuntimeHints on AuthApplication. Covers reflection /
 * serialization targets that the framework's auto-generated hints don't see:
 * Kelta domain classes hit by Jackson + Spring Security's serializer chain,
 * the Spring Security Jackson 2 module mixin/deserializer classes (which
 * Jackson instantiates reflectively at runtime), and Thymeleaf templates.
 */
public class AuthRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        registerKeltaModels(hints);
        registerWorkerClientPayloads(hints);
        registerSpringSecurityJackson2(hints);
        registerOAuth2DomainTypes(hints);
        registerJdkPolymorphicTypes(hints);
        registerSerialization(hints);
        registerHttpSessionSerialization(hints);
        registerResources(hints);
        // SAML federation (FederationConfig) initializes OpenSAML at startup.
        new OpenSamlRuntimeHints().registerHints(hints, classLoader);
    }

    private void registerKeltaModels(RuntimeHints hints) {
        // Template view models: Thymeleaf's SpEL reads their record accessors
        // reflectively (login SSO buttons, consent scope list).
        for (Class<?> viewModel : List.of(
                LoginController.SsoProviderInfo.class,
                ConsentController.ScopeDisplay.class)) {
            hints.reflection().registerType(viewModel,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.DECLARED_FIELDS);
        }

        // KeltaUserDetails is reflectively serialized by Spring Authorization
        // Server's JdbcOAuth2AuthorizationRowMapper (via its custom ObjectMapper
        // configured in AuthorizationServerConfig). Needs full reflective access.
        hints.reflection().registerType(KeltaUserDetails.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.DECLARED_FIELDS);

        hints.reflection().registerType(KeltaUserDetailsMixin.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.DECLARED_FIELDS);

        hints.reflection().registerType(KeltaSession.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.DECLARED_FIELDS);

        hints.reflection().registerType(KeltaSession.Builder.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.DECLARED_FIELDS);

        // SamlLogoutInfo is stored in the Redis-backed HTTP session (SP-initiated
        // SAML Single Logout). JDK deserialization invokes the record's canonical
        // constructor reflectively.
        hints.reflection().registerType(SamlLogoutInfo.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.DECLARED_FIELDS);
    }

    private void registerWorkerClientPayloads(RuntimeHints hints) {
        Class<?>[] payloads = {
                WorkerClient.OidcProviderInfo.class,
                WorkerClient.UserIdentity.class,
                WorkerClient.ProfileInfo.class,
                WorkerClient.JitProvisionResult.class
        };
        for (Class<?> payload : payloads) {
            hints.reflection().registerType(payload,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS);
        }
    }

    /**
     * Spring Security's Jackson 2 modules instantiate their mixins and
     * deserializers reflectively. The framework ships no native-image hints
     * for these packages (Jackson 3 has its own pipeline), so we register
     * every Jackson 2 helper class auth uses.
     */
    private void registerSpringSecurityJackson2(RuntimeHints hints) {
        String[] classNames = {
                // spring-security-core
                "org.springframework.security.jackson2.CoreJackson2Module",
                "org.springframework.security.jackson2.SecurityJackson2Modules",
                "org.springframework.security.jackson2.SecurityJackson2Modules$AllowlistTypeIdResolver",
                "org.springframework.security.jackson2.SecurityJackson2Modules$AllowlistTypeResolverBuilder",
                "org.springframework.security.jackson2.AbstractUnmodifiableCollectionDeserializer",
                "org.springframework.security.jackson2.AnonymousAuthenticationTokenMixin",
                "org.springframework.security.jackson2.BadCredentialsExceptionMixin",
                "org.springframework.security.jackson2.FactorGrantedAuthorityMixin",
                "org.springframework.security.jackson2.RememberMeAuthenticationTokenMixin",
                "org.springframework.security.jackson2.SimpleGrantedAuthorityMixin",
                "org.springframework.security.jackson2.UnmodifiableListDeserializer",
                "org.springframework.security.jackson2.UnmodifiableListMixin",
                "org.springframework.security.jackson2.UnmodifiableMapDeserializer",
                "org.springframework.security.jackson2.UnmodifiableMapMixin",
                "org.springframework.security.jackson2.UnmodifiableSetDeserializer",
                "org.springframework.security.jackson2.UnmodifiableSetMixin",
                "org.springframework.security.jackson2.UserDeserializer",
                "org.springframework.security.jackson2.UserMixin",
                "org.springframework.security.jackson2.UsernamePasswordAuthenticationTokenDeserializer",
                "org.springframework.security.jackson2.UsernamePasswordAuthenticationTokenMixin",

                // spring-security-web (servlet) — registers mixins for
                // WebAuthenticationDetails, DefaultSavedRequest, SavedCookie,
                // Cookie, and SwitchUserGrantedAuthority. All persisted on the
                // OAuth2Authorization row when form login completes.
                "org.springframework.security.web.jackson2.WebServletJackson2Module",
                "org.springframework.security.web.jackson2.WebJackson2Module",
                "org.springframework.security.web.jackson2.CookieMixin",
                "org.springframework.security.web.jackson2.CookieDeserializer",
                "org.springframework.security.web.jackson2.DefaultCsrfTokenMixin",
                "org.springframework.security.web.jackson2.DefaultSavedRequestMixin",
                "org.springframework.security.web.jackson2.PreAuthenticatedAuthenticationTokenDeserializer",
                "org.springframework.security.web.jackson2.PreAuthenticatedAuthenticationTokenMixin",
                "org.springframework.security.web.jackson2.SavedCookieMixin",
                "org.springframework.security.web.jackson2.SwitchUserGrantedAuthorityMixIn",
                "org.springframework.security.web.jackson2.WebAuthenticationDetailsMixin",

                // spring-security-oauth2-authorization-server
                "org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module",
                "org.springframework.security.oauth2.server.authorization.jackson2.DurationMixin",
                "org.springframework.security.oauth2.server.authorization.jackson2.HashSetMixin",
                "org.springframework.security.oauth2.server.authorization.jackson2.JsonNodeUtils",
                "org.springframework.security.oauth2.server.authorization.jackson2.JwsAlgorithmMixin",
                "org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationRequestDeserializer",
                "org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationRequestMixin",
                "org.springframework.security.oauth2.server.authorization.jackson2.OAuth2TokenExchangeActorMixin",
                "org.springframework.security.oauth2.server.authorization.jackson2.OAuth2TokenExchangeCompositeAuthenticationTokenMixin",
                "org.springframework.security.oauth2.server.authorization.jackson2.OAuth2TokenFormatMixin",
                "org.springframework.security.oauth2.server.authorization.jackson2.StringArrayMixin",
                "org.springframework.security.oauth2.server.authorization.jackson2.UnmodifiableMapDeserializer",
                "org.springframework.security.oauth2.server.authorization.jackson2.UnmodifiableMapMixin",

                // spring-security-oauth2-client
                "org.springframework.security.oauth2.client.jackson2.OAuth2ClientJackson2Module",
                "org.springframework.security.oauth2.client.jackson2.ClientRegistrationDeserializer",
                "org.springframework.security.oauth2.client.jackson2.ClientRegistrationMixin",
                "org.springframework.security.oauth2.client.jackson2.DefaultOAuth2UserMixin",
                "org.springframework.security.oauth2.client.jackson2.DefaultOidcUserMixin",
                "org.springframework.security.oauth2.client.jackson2.JsonNodeUtils",
                "org.springframework.security.oauth2.client.jackson2.OAuth2AccessTokenMixin",
                "org.springframework.security.oauth2.client.jackson2.OAuth2AuthenticationExceptionMixin",
                "org.springframework.security.oauth2.client.jackson2.OAuth2AuthenticationTokenMixin",
                "org.springframework.security.oauth2.client.jackson2.OAuth2AuthorizationRequestDeserializer",
                "org.springframework.security.oauth2.client.jackson2.OAuth2AuthorizationRequestMixin",
                "org.springframework.security.oauth2.client.jackson2.OAuth2AuthorizedClientMixin",
                "org.springframework.security.oauth2.client.jackson2.OAuth2ErrorMixin",
                "org.springframework.security.oauth2.client.jackson2.OAuth2RefreshTokenMixin",
                "org.springframework.security.oauth2.client.jackson2.OAuth2UserAuthorityMixin",
                "org.springframework.security.oauth2.client.jackson2.OidcIdTokenMixin",
                "org.springframework.security.oauth2.client.jackson2.OidcUserAuthorityMixin",
                "org.springframework.security.oauth2.client.jackson2.OidcUserInfoMixin",
                "org.springframework.security.oauth2.client.jackson2.StdConverters",
                "org.springframework.security.oauth2.client.jackson2.StdConverters$AccessTokenTypeConverter",
                "org.springframework.security.oauth2.client.jackson2.StdConverters$AuthenticationMethodConverter",
                "org.springframework.security.oauth2.client.jackson2.StdConverters$AuthorizationGrantTypeConverter",
                "org.springframework.security.oauth2.client.jackson2.StdConverters$ClientAuthenticationMethodConverter"
        };
        for (String name : classNames) {
            hints.reflection().registerType(TypeReference.of(name), MemberCategory.values());
        }
    }

    /**
     * Domain classes referenced by @class type IDs in serialized OAuth2 client
     * settings + authorization payloads. AllowlistTypeIdResolver calls
     * Class.forName() on these names, so the native image must keep them.
     */
    private void registerOAuth2DomainTypes(RuntimeHints hints) {
        String[] classNames = {
                // Token + client settings values
                "org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat",
                "org.springframework.security.oauth2.server.authorization.settings.ConfigurationSettingNames",
                "org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings",
                "org.springframework.security.oauth2.server.authorization.settings.ClientSettings",
                "org.springframework.security.oauth2.server.authorization.settings.TokenSettings",
                "org.springframework.security.oauth2.jose.jws.SignatureAlgorithm",
                "org.springframework.security.oauth2.jose.jws.MacAlgorithm",
                "org.springframework.security.oauth2.jose.jws.JwsAlgorithm",

                // OAuth2 core types
                "org.springframework.security.oauth2.core.AuthorizationGrantType",
                "org.springframework.security.oauth2.core.ClientAuthenticationMethod",
                "org.springframework.security.oauth2.core.AuthenticationMethod",
                "org.springframework.security.oauth2.core.OAuth2AccessToken",
                "org.springframework.security.oauth2.core.OAuth2AccessToken$TokenType",
                "org.springframework.security.oauth2.core.OAuth2RefreshToken",
                "org.springframework.security.oauth2.core.OAuth2Token",
                "org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest",
                "org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType",
                "org.springframework.security.oauth2.core.oidc.IdTokenClaimNames",
                "org.springframework.security.oauth2.core.oidc.OidcIdToken",

                // Authorization server authentication tokens
                "org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeActor",
                "org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeCompositeAuthenticationToken",
                "org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken",
                "org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken",

                // Spring Security core authentication tokens commonly serialized
                "org.springframework.security.authentication.UsernamePasswordAuthenticationToken",
                "org.springframework.security.authentication.AnonymousAuthenticationToken",
                "org.springframework.security.authentication.RememberMeAuthenticationToken",
                "org.springframework.security.core.authority.SimpleGrantedAuthority",
                "org.springframework.security.core.authority.AuthorityUtils",
                // Spring Security 7 stamps authentication tokens with a
                // FactorGrantedAuthority describing the auth factor (password,
                // OTT, passkey, ...). The JdbcOAuth2AuthorizationService
                // serializes the principal's authorities, and the strict
                // AllowlistTypeIdResolver loads each authority class by name on
                // read — without these entries the native image can't resolve
                // FactorGrantedAuthority and /oauth2/token returns 500.
                "org.springframework.security.core.authority.FactorGrantedAuthority",
                "org.springframework.security.core.authority.FactorGrantedAuthority$Builder",
                "org.springframework.security.core.userdetails.User",
                "org.springframework.security.web.savedrequest.DefaultSavedRequest",
                "org.springframework.security.web.savedrequest.SavedCookie",
                "org.springframework.security.web.authentication.WebAuthenticationDetails"
        };
        for (String name : classNames) {
            hints.reflection().registerType(TypeReference.of(name), MemberCategory.values());
        }
    }

    /**
     * JDK types that show up as @class type IDs after Spring Security wraps
     * collections in unmodifiable views or stores enums/durations.
     */
    private void registerJdkPolymorphicTypes(RuntimeHints hints) {
        String[] classNames = {
                "java.time.Duration",
                "java.time.Instant",
                "java.util.HashSet",
                "java.util.LinkedHashSet",
                "java.util.TreeSet",
                "java.util.HashMap",
                "java.util.LinkedHashMap",
                "java.util.TreeMap",
                "java.util.ArrayList",
                "java.util.LinkedList",
                "java.util.Collections$UnmodifiableMap",
                "java.util.Collections$UnmodifiableList",
                "java.util.Collections$UnmodifiableSet",
                "java.util.Collections$UnmodifiableCollection",
                "java.util.Collections$UnmodifiableRandomAccessList",
                "java.util.Collections$EmptyMap",
                "java.util.Collections$EmptyList",
                "java.util.Collections$EmptySet",
                "java.util.Collections$SingletonMap",
                "java.util.Collections$SingletonList",
                "java.util.Collections$SingletonSet"
        };
        for (String name : classNames) {
            hints.reflection().registerType(TypeReference.of(name), MemberCategory.values());
        }
    }

    private void registerSerialization(RuntimeHints hints) {
        // KeltaSession travels through Spring Session's Redis serializer.
        hints.serialization().registerType(KeltaSession.class);

        // SP-initiated SAML Single Logout stores these in the Redis-backed HTTP
        // session (JDK serialization): our capture of the SAML subject identity,
        // and Spring Security's LogoutRequest saved by HttpSessionLogoutRequestRepository
        // for the returning LogoutResponse's InResponseTo validation.
        hints.serialization().registerType(SamlLogoutInfo.class);
        hints.serialization().registerType(TypeReference.of(
                "org.springframework.security.saml2.provider.service.authentication.logout.Saml2LogoutRequest"));
        hints.serialization().registerType(TypeReference.of(
                "org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding"));
    }

    /**
     * Every class Java serialization writes for an HTTP session value, now that sessions are
     * stored in Redis ({@code io.kelta.auth.session.SessionAttributeSerializer}). On top of
     * Spring Session's own hints ({@code org.springframework.session.aot.hint}: the security
     * context, username/password token, saved request, CSRF token, common JDK types).
     * {@code SessionAttributeHintsTest} serializes one value of every kind the service keeps in a
     * session and fails on any class missing here; an unregistered class makes that value
     * unstorable in the native image (it is dropped with a warning, so the user signs in again).
     *
     * <p>An authentication exception the framework stores after a failed federated login is
     * registered without its cause: a cause of another type makes that one value unstorable,
     * which nothing reads back.
     */
    private void registerHttpSessionSerialization(RuntimeHints hints) {
        // The principal and the factor authorities Spring Security 7 adds at sign-in.
        for (Class<? extends Serializable> type : List.<Class<? extends Serializable>>of(
                KeltaUserDetails.class,
                org.springframework.security.core.authority.FactorGrantedAuthority.class,
                org.springframework.security.authentication.AccountStatusException.class)) {
            hints.serialization().registerType(type);
        }
        String[] frameworkTypes = {
                // OIDC federation: the saved authorization request, and the login itself (held
                // only until FederatedLoginSuccessHandler replaces it, or when it stops early).
                "org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest",
                "org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType",
                "org.springframework.security.oauth2.core.AuthorizationGrantType",
                "org.springframework.security.oauth2.core.AbstractOAuth2Token",
                "org.springframework.security.oauth2.core.OAuth2Error",
                "org.springframework.security.oauth2.core.oidc.OidcIdToken",
                "org.springframework.security.oauth2.core.oidc.OidcUserInfo",
                "org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser",
                "org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority",
                "org.springframework.security.oauth2.core.user.DefaultOAuth2User",
                "org.springframework.security.oauth2.core.user.OAuth2UserAuthority",
                // SAML federation: the saved AuthnRequest, the login (until
                // SamlFederatedLoginSuccessHandler replaces it), and a failed login.
                "org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest",
                "org.springframework.security.saml2.provider.service.authentication.Saml2RedirectAuthenticationRequest",
                "org.springframework.security.saml2.provider.service.authentication.Saml2PostAuthenticationRequest",
                "org.springframework.security.saml2.provider.service.authentication.Saml2Authentication",
                "org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal",
                "org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException",
                "org.springframework.security.saml2.core.Saml2Error",
                // Flash attributes (MFA error messages): SessionFlashMapManager.
                "org.springframework.web.servlet.FlashMap",
                "org.springframework.util.LinkedMultiValueMap",
                "org.springframework.util.MultiValueMapAdapter"
        };
        for (String name : frameworkTypes) {
            hints.serialization().registerType(TypeReference.of(name));
        }
        String[] jdkTypes = {
                "java.lang.Boolean",
                "java.lang.Enum",
                "java.lang.String[]",
                "java.lang.StackTraceElement[]",
                "java.net.URL",
                "java.net.URI",
                "java.util.HashMap",
                "java.util.LinkedHashMap",
                "java.util.HashSet",
                "java.util.LinkedHashSet",
                "java.util.LinkedList",
                "java.util.Arrays$ArrayList",
                "java.util.concurrent.CopyOnWriteArrayList",
                "java.util.Collections$UnmodifiableMap",
                "java.util.Collections$UnmodifiableSortedMap",
                "java.util.Collections$UnmodifiableSortedSet",
                "java.util.Collections$EmptyMap",
                "java.util.Collections$EmptySet",
                "java.util.Collections$SingletonList",
                "java.util.Collections$SingletonSet",
                "java.util.Collections$SingletonMap",
                // Written through writeReplace: the class itself is looked up when writing, the
                // serial-proxy class when reading.
                "java.time.Instant",
                "java.time.Duration",
                "java.time.Ser",
                "java.util.ImmutableCollections$List12",
                "java.util.ImmutableCollections$ListN",
                "java.util.ImmutableCollections$Set12",
                "java.util.ImmutableCollections$SetN",
                "java.util.ImmutableCollections$Map1",
                "java.util.ImmutableCollections$MapN",
                "java.util.CollSer"
        };
        for (String name : jdkTypes) {
            hints.serialization().registerType(TypeReference.of(name));
        }
    }

    private void registerResources(RuntimeHints hints) {
        // Thymeleaf templates rendered at runtime — must be in the native image.
        hints.resources().registerPattern("templates/*.html");

        // Password blocklist consulted by PasswordPolicyService.
        hints.resources().registerPattern("common-passwords.txt");

        // Logback config — Logback's own native hints register logback-spring.xml
        // by default; include it here explicitly in case the auto-hint misses it.
        hints.resources().registerPattern("logback-spring.xml");
    }
}
