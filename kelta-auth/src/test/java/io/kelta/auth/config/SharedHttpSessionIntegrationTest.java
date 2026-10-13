package io.kelta.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import io.kelta.auth.session.HttpSessionConfig;
import io.kelta.auth.session.SessionAttributeSerializer;
import io.kelta.auth.session.SessionCookieSerializer;
import io.kelta.auth.session.SignedInSessionTimeoutFilter;
import io.kelta.auth.session.TenantScopedSessionRegistry;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.session.autoconfigure.SessionAutoConfiguration;
import org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * HTTP sessions shared through Redis by several kelta-auth replicas, and kept across a restart.
 *
 * <p>Each "pod" is its own application context with the real authorization server chains
 * ({@link OidcSignOutFlowTest.ServerBeans}), Spring Boot's Spring Session auto-configuration
 * driven by the service's own {@code application.yml}, and {@link HttpSessionConfig}. The pods
 * share a Redis container, the JDBC authorization store and the signing key, as replicas do.
 *
 * <p>Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("HTTP sessions — shared across replicas and restarts through Redis")
class SharedHttpSessionIntegrationTest {

    /** Explicit port: a MockMvc request without one gets port 80 even for https. */
    private static final String HOST = "https://auth.example.test:443";
    private static final String COOKIE = OidcSignOutFlowTest.Config.COOKIE_NAME;
    private static final String NAMESPACE = "kelta:auth:session:";
    private static final String TENANT_ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String USER_ID = "11111111-0000-0000-0000-000000000001";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static EmbeddedDatabase database;
    private static JWKSource<SecurityContext> signingKey;

    private final List<Pod> pods = new ArrayList<>();

    @BeforeAll
    static void sharedInfrastructure() throws Exception {
        database = PasswordChangeSignOutFlowTest.PasswordBeans.database();
        signingKey = new ImmutableJWKSet<>(new JWKSet(new RSAKeyGenerator(2048).keyID("shared").generate()));
    }

    @AfterAll
    static void closeDatabase() {
        database.shutdown();
    }

    @BeforeEach
    void emptyRedis() throws Exception {
        assertThat(REDIS.execInContainer("redis-cli", "FLUSHALL").getExitCode()).isZero();
    }

    @AfterEach
    void stopPods() {
        pods.forEach(Pod::close);
    }

    @Test
    @DisplayName("the application.yml settings produce the indexed Redis store with the platform's parts")
    void wiring() throws Exception {
        Pod pod = start();

        RedisIndexedSessionRepository repository = pod.context.getBean(RedisIndexedSessionRepository.class);
        assertThat(repository.createSession().getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
        assertThat(ReflectionTestUtils.getField(repository, "namespace")).isEqualTo(NAMESPACE);
        assertThat(ReflectionTestUtils.getField(repository, "defaultSerializer"))
                .isInstanceOf(SessionAttributeSerializer.class);
        assertThat(ReflectionTestUtils.getField(repository, "indexResolver"))
                .isSameAs(pod.context.getBean("sessionIndexResolver"));
        assertThat(pod.context.getBean(SessionRegistry.class)).isInstanceOf(TenantScopedSessionRegistry.class);
        assertThat(pod.context.getBeansOfType(CookieSerializer.class).values())
                .singleElement().isInstanceOf(SessionCookieSerializer.class);
    }

    @Test
    @DisplayName("a sign-in started on one pod finishes on another and survives a restart; sign-out ends it everywhere")
    void signInAcrossPodsAndRestart() throws Exception {
        Pod podA = start();
        Pod podB = start();
        OidcSignOutFlowTest.Browser browser = new OidcSignOutFlowTest.Browser();
        String verifier = UUID.randomUUID() + "-" + UUID.randomUUID();

        // Pod A: the authorization request is saved in the session and the browser sent to sign in.
        MockHttpServletResponse toLogin = browser.send(podA.mvc,
                get(URI.create(HOST + OidcSignOutFlowTest.authorizeUrl(OidcSignOutFlowTest.sha256(verifier))))
                        .accept(MediaType.TEXT_HTML));
        assertThat(toLogin.getStatus()).isEqualTo(302);
        assertThat(toLogin.getRedirectedUrl()).isEqualTo("/login");
        String setCookie = sessionSetCookie(toLogin);
        assertThat(setCookie).contains("Path=/").contains("Secure").contains("HttpOnly").contains("SameSite=None")
                .doesNotContain("Domain=").doesNotContain("Max-Age");
        String anonymousSession = browser.sessionId();
        assertThat(redis().hasKey(NAMESPACE + "sessions:" + anonymousSession)).isTrue();
        assertThat(redis().getExpire(NAMESPACE + "sessions:expires:" + anonymousSession))
                .as("idle timeout before sign-in (seconds)").isBetween(1700L, 1800L);

        // Pod B: the password form resumes the request pod A saved.
        MockHttpServletResponse loggedIn = browser.send(podB.mvc, post(URI.create(HOST + "/login"))
                .param("username", OidcSignOutFlowTest.USER)
                .param("password", OidcSignOutFlowTest.PASSWORD)
                .with(csrf()));
        assertThat(loggedIn.getStatus()).isEqualTo(302);
        String resumed = loggedIn.getRedirectedUrl();
        assertThat(resumed).startsWith("https://auth.example.test/oauth2/authorize?");

        // Pod A restarts (a rollout); the replacement sees the signed-in session.
        podA.close();
        Pod podA2 = start();
        MockHttpServletResponse toCallback = browser.send(podA2.mvc,
                get(URI.create(resumed)).accept(MediaType.TEXT_HTML));
        assertThat(toCallback.getStatus()).as("%s", toCallback.getErrorMessage()).isEqualTo(302);
        assertThat(toCallback.getRedirectedUrl()).startsWith(OidcSignOutFlowTest.CALLBACK);
        String code = UriComponentsBuilder.fromUriString(toCallback.getRedirectedUrl())
                .build().getQueryParams().getFirst("code");

        // Pod B exchanges the code; the ID token's sid names the browser's session.
        MockHttpServletResponse token = podB.mvc.perform(post(URI.create(HOST + "/oauth2/token"))
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", OidcSignOutFlowTest.CALLBACK)
                        .param("client_id", "kelta-platform")
                        .param("code_verifier", verifier))
                .andReturn().getResponse();
        assertThat(token.getStatus()).as("%s", token.getContentAsString()).isEqualTo(200);
        String idToken = JsonMapper.builder().build().readTree(token.getContentAsString()).get("id_token").asString();
        String sessionId = browser.sessionId();
        assertThat(sessionId).as("session id changes at sign-in").isNotEqualTo(anonymousSession);
        assertThat(SignedJWT.parse(idToken).getJWTClaimsSet().getStringClaim("sid"))
                .isEqualTo(OidcSignOutFlowTest.sha256(sessionId));

        // Redis holds the session, indexed by tenant and user id (no email in any key), with the
        // signed-in idle timeout.
        String principalIndex = NAMESPACE + "index:" + FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME
                + ":" + TENANT_ID + ":" + USER_ID;
        RedisIndexedSessionRepository store = podB.context.getBean(RedisIndexedSessionRepository.class);
        assertThat(redis().hasKey(principalIndex)).isTrue();
        assertThat(store.findByPrincipalName(TENANT_ID + ":" + USER_ID)).containsOnlyKeys(sessionId);
        Set<String> keys = redis().keys(NAMESPACE + "*");
        assertThat(keys).noneMatch(key -> key.contains("@"));
        assertThat(redis().getExpire(NAMESPACE + "sessions:expires:" + sessionId))
                .as("idle timeout after sign-in (seconds)").isBetween(Duration.ofHours(8).toSeconds() - 100,
                        Duration.ofHours(8).toSeconds());
        System.out.printf("Redis memory for one signed-in session: %d bytes (hash), %d bytes (index entry set)%n",
                memoryUsage(NAMESPACE + "sessions:" + sessionId), memoryUsage(principalIndex));

        // Pod B ends the session; no pod finds it afterwards.
        String target = OidcSignOutFlowTest.UI_ORIGIN + "/" + OidcSignOutFlowTest.TENANT_SLUG + "?logged_out=true";
        MockHttpServletResponse signedOut = browser.send(podB.mvc,
                get(URI.create(HOST + "/connect/logout?id_token_hint=" + idToken
                        + "&post_logout_redirect_uri=" + target)));
        assertThat(signedOut.getStatus()).as("%s", signedOut.getErrorMessage()).isEqualTo(302);
        assertThat(signedOut.getRedirectedUrl()).isEqualTo(target);
        assertThat(podA2.context.getBean(RedisIndexedSessionRepository.class).findById(sessionId)).isNull();
        assertThat(podB.context.getBean(RedisIndexedSessionRepository.class).findById(sessionId)).isNull();
        assertThat(store.findByPrincipalName(TENANT_ID + ":" + USER_ID)).isEmpty();
        assertThat(redis().opsForSet().size(principalIndex)).as("index entries left").isZero();
    }

    @Test
    @DisplayName("a password change on one pod ends the user's other sessions everywhere and revokes earlier refresh tokens")
    void passwordChangeSignsOutEverywhereElse() throws Exception {
        Pod podA = start();
        Pod podB = start();
        OidcSignOutFlowTest.Browser laptop = new OidcSignOutFlowTest.Browser();
        String laptopRefresh = PasswordChangeSignOutFlowTest.signIn(podA.mvc, laptop, true, HOST)
                .get("refresh_token").asString();
        OidcSignOutFlowTest.Browser phone = new OidcSignOutFlowTest.Browser();
        String phoneRefresh = PasswordChangeSignOutFlowTest.signIn(podB.mvc, phone, true, HOST)
                .get("refresh_token").asString();
        RedisIndexedSessionRepository store = podB.context.getBean(RedisIndexedSessionRepository.class);
        String otherTenantSession = PasswordChangeSignOutFlowTest.PasswordBeans.otherTenantSession(store);
        String userIndex = TENANT_ID + ":" + USER_ID;
        assertThat(store.findByPrincipalName(userIndex)).containsOnlyKeys(laptop.sessionId(), phone.sessionId());

        // The laptop changes the password through pod B.
        MockHttpServletResponse changed = PasswordChangeSignOutFlowTest.changePassword(podB.mvc, laptop, HOST);
        assertThat(changed.getStatus()).as("%s", changed.getContentAsString()).isEqualTo(200);

        // Pod A no longer finds the phone's session; the laptop's survives; the other tenant's user is untouched.
        RedisIndexedSessionRepository storeA = podA.context.getBean(RedisIndexedSessionRepository.class);
        assertThat(storeA.findById(phone.sessionId())).isNull();
        assertThat(storeA.findById(laptop.sessionId())).isNotNull();
        assertThat(storeA.findById(otherTenantSession)).isNotNull();
        assertThat(store.findByPrincipalName(userIndex)).containsOnlyKeys(laptop.sessionId());

        // Refresh tokens issued before the change are refused on either pod.
        assertThat(PasswordChangeSignOutFlowTest.refresh(podA.mvc, phoneRefresh, HOST).getContentAsString())
                .contains("invalid_grant");
        assertThat(PasswordChangeSignOutFlowTest.refresh(podB.mvc, laptopRefresh, HOST).getContentAsString())
                .contains("invalid_grant");
    }

    @Test
    @DisplayName("a session cookie the store does not know (expired, or from before the store) starts a new sign-in")
    void unknownSessionCookieStartsOver() throws Exception {
        Pod pod = start();
        String unknown = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = pod.mvc.perform(
                        get(URI.create(HOST + OidcSignOutFlowTest.authorizeUrl("challenge")))
                                .accept(MediaType.TEXT_HTML)
                                .cookie(new Cookie(COOKIE, unknown), new Cookie("JSESSIONID", "0123456789ABCDEF")))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
        assertThat(response.getCookie(COOKIE)).isNotNull();
        assertThat(response.getCookie(COOKIE).getValue()).isNotEqualTo(unknown);
    }

    @Test
    @DisplayName("a stored sign-in that can no longer be read (classes changed in a release) means signing in again")
    void unreadableSignInMeansSigningInAgain() throws Exception {
        Pod pod = start();
        OidcSignOutFlowTest.Browser browser = new OidcSignOutFlowTest.Browser();
        browser.send(pod.mvc, get(URI.create(HOST + OidcSignOutFlowTest.authorizeUrl("challenge")))
                .accept(MediaType.TEXT_HTML));
        browser.send(pod.mvc, post(URI.create(HOST + "/login"))
                .param("username", OidcSignOutFlowTest.USER)
                .param("password", OidcSignOutFlowTest.PASSWORD)
                .with(csrf()));
        String sessionId = browser.sessionId();

        try (RedisConnection connection = pod.context.getBean(RedisConnectionFactory.class).getConnection()) {
            connection.hashCommands().hSet(
                    (NAMESPACE + "sessions:" + sessionId).getBytes(StandardCharsets.UTF_8),
                    "sessionAttr:SPRING_SECURITY_CONTEXT".getBytes(StandardCharsets.UTF_8),
                    new byte[] {(byte) 0xAC, (byte) 0xED, 0, 5, 's', 'r', 0, 1, 'X'});
        }

        MockHttpServletResponse response = browser.send(pod.mvc,
                get(URI.create(HOST + OidcSignOutFlowTest.authorizeUrl("challenge"))).accept(MediaType.TEXT_HTML));
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
    }

    private Pod start() throws Exception {
        Pod pod = new Pod();
        pods.add(pod);
        return pod;
    }

    private StringRedisTemplate redis() {
        return pods.getLast().context.getBean(StringRedisTemplate.class);
    }

    private static long memoryUsage(String key) throws Exception {
        return Long.parseLong(REDIS.execInContainer("redis-cli", "MEMORY", "USAGE", key).getStdout().trim());
    }

    private static String sessionSetCookie(MockHttpServletResponse response) {
        return response.getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(COOKIE + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + COOKIE + " cookie set"));
    }

    /** One kelta-auth replica. */
    private static final class Pod implements AutoCloseable {

        final AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        final MockMvc mvc;

        @SuppressWarnings("unchecked")
        Pod() throws Exception {
            context.setServletContext(new MockServletContext());
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("application.yml", new FileSystemResource("src/main/resources/application.yml"))) {
                context.getEnvironment().getPropertySources().addLast(source);
            }
            Map<String, Object> redis = new HashMap<>();
            redis.put("spring.data.redis.host", REDIS.getHost());
            redis.put("spring.data.redis.port", REDIS.getMappedPort(6379));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-redis", redis));
            context.register(PodConfig.class);
            context.refresh();
            FilterRegistrationBean<SignedInSessionTimeoutFilter> timeout =
                    context.getBean("signedInSessionTimeoutFilter", FilterRegistrationBean.class);
            mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(SessionRepositoryFilter.class), timeout.getFilter())
                    .apply(springSecurity())
                    .build();
        }

        @Override
        public void close() {
            if (context.isActive()) {
                context.close();
            }
        }
    }

    @Configuration
    @Import({OidcSignOutFlowTest.ServerBeans.class, HttpSessionConfig.class,
            PasswordChangeSignOutFlowTest.PasswordBeans.class})
    @ImportAutoConfiguration({DataRedisAutoConfiguration.class, SessionAutoConfiguration.class,
            SessionDataRedisAutoConfiguration.class})
    @EnableConfigurationProperties(AuthProperties.class)
    static class PodConfig {

        @Bean(destroyMethod = "")
        DataSource dataSource() {
            return database;
        }

        @Bean
        JWKSource<SecurityContext> jwkSource() {
            return signingKey;
        }

        @Bean
        CookieDomainResolver cookieDomainResolver() {
            return new CookieDomainResolver();
        }
    }
}
