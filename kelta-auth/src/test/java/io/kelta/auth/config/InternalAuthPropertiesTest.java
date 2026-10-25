package io.kelta.auth.config;

import com.sun.net.httpserver.HttpServer;
import io.kelta.auth.controller.LoginController;
import io.kelta.auth.federation.DynamicClientRegistrationRepository;
import io.kelta.auth.service.AuthDomainResolver;
import io.kelta.auth.service.WorkerClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code kelta.auth.internal-auth.enabled} is declared in the shipped application.yml and read
 * at run time. kelta-auth is a native image: a {@code @ConditionalOnProperty} on the flag was
 * frozen at AOT build time, so flipping {@code KELTA_AUTH_INTERNAL_AUTH_ENABLED} at deploy time
 * did nothing.
 */
@DisplayName("kelta-auth internal-auth flag")
class InternalAuthPropertiesTest {

    private static final String SHIPPED_YML = "src/main/resources/application.yml";
    private static final String TOKEN = "internal-access-token";

    private HttpServer server;
    private final Map<String, String> authorizationByPath = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        // Stands in for both kelta-auth's token endpoint and the worker.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            byte[] body = ("{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authorizationByPath.put(exchange.getRequestURI().getPath(), auth == null ? "" : auth);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The shipped application.yml plus the given variables as a relaxed-binding environment. */
    private ApplicationContextRunner runner(Map<String, Object> env) {
        Map<String, Object> vars = new HashMap<>(env);
        vars.put("KELTA_AUTH_ISSUER_URI", baseUrl());
        vars.put("WORKER_SERVICE_URL", baseUrl());
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        var sources = context.getEnvironment().getPropertySources();
                        new YamlPropertySourceLoader().load("shipped", new FileSystemResource(SHIPPED_YML))
                                .forEach(sources::addLast);
                        sources.addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", vars));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withUserConfiguration(AuthProperties.class, InternalWorkerClientConfig.class,
                        WorkerClient.class, LoginController.class)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(AuthDomainResolver.class, () -> mock(AuthDomainResolver.class))
                .withBean("clientRegistrationRepository", ClientRegistrationRepository.class,
                        () -> mock(DynamicClientRegistrationRepository.class));
    }

    private void call(RestClient client, String path) {
        client.get().uri(path).retrieve().toBodilessEntity();
    }

    @Test
    @DisplayName("application.yml declares each key as an env-overridable placeholder defaulting to off")
    void declaredInShippedYml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        // FileSystemResource: src/test/resources/application.yml shadows the shipped one on the
        // test classpath, and the shipped file is what the AOT build reads.
        factory.setResources(new FileSystemResource(SHIPPED_YML));
        factory.afterPropertiesSet();
        Properties props = factory.getObject();
        assertThat(props).isNotNull();
        assertThat(props.getProperty("kelta.auth.internal-auth.enabled"))
                .isEqualTo("${KELTA_AUTH_INTERNAL_AUTH_ENABLED:false}");
        assertThat(props.getProperty("kelta.auth.internal-auth.client-id"))
                .startsWith("${KELTA_AUTH_INTERNAL_AUTH_CLIENT_ID:");
        assertThat(props.getProperty("kelta.auth.internal-auth.client-secret"))
                .isEqualTo("${KELTA_AUTH_INTERNAL_AUTH_CLIENT_SECRET:}");
    }

    @Test
    @DisplayName("off by default: starts without a client secret and sends no bearer to /internal/**")
    void offByDefault() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InternalAuthProperties.class).enabled()).isFalse();
            assertThat(context).hasSingleBean(WorkerClient.class);
            assertThat(context).hasSingleBean(LoginController.class);
            assertThat(context).hasSingleBean(ClientRegistrationRepository.class);

            RestClient client = context.getBean("internalWorkerRestClient", RestClient.class);
            call(client, "/internal/bootstrap");
            assertThat(authorizationByPath).containsEntry("/internal/bootstrap", "");
        });
    }

    @Test
    @DisplayName("KELTA_AUTH_INTERNAL_AUTH_ENABLED=true attaches the client_credentials bearer to /internal/** only")
    void onViaEnvironment() {
        runner(Map.of(
                "KELTA_AUTH_INTERNAL_AUTH_ENABLED", "true",
                "KELTA_AUTH_INTERNAL_AUTH_CLIENT_SECRET", "s3cret")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InternalAuthProperties.class).enabled()).isTrue();
            // The auth-internal registration stays private, so LoginController still resolves
            // the single federation repository.
            assertThat(context).hasSingleBean(ClientRegistrationRepository.class);
            assertThat(context.getBean(ClientRegistrationRepository.class))
                    .isInstanceOf(DynamicClientRegistrationRepository.class);
            assertThat(context).hasSingleBean(LoginController.class);

            RestClient client = context.getBean("internalWorkerRestClient", RestClient.class);
            call(client, "/internal/bootstrap");
            call(client, "/api/internal/email/send");
            assertThat(authorizationByPath).containsEntry("/internal/bootstrap", "Bearer " + TOKEN);
            assertThat(authorizationByPath).containsEntry("/api/internal/email/send", "");
        });
    }

    @Test
    @DisplayName("on without a client secret fails startup")
    void onWithoutSecretFailsFast() {
        runner(Map.of("KELTA_AUTH_INTERNAL_AUTH_ENABLED", "true")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("kelta.auth.internal-auth.client-secret");
        });
    }
}
