package io.kelta.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RootEndpointsFilter")
class RootEndpointsFilterTest {

    private final AtomicReference<ServerWebExchange> passedOn = new AtomicReference<>();
    private final WebFilterChain chain = exchange -> {
        passedOn.set(exchange);
        return Mono.empty();
    };

    private RootEndpointsFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RootEndpointsFilter("kelta-api", "https://kelta.io/docs");
    }

    @Test
    @DisplayName("GET /robots.txt answers 200 text/plain disallowing all crawling, with no token or tenant")
    void servesRobotsTxt() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/robots.txt"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(passedOn.get()).as("must short-circuit before tenant resolution and auth").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .isEqualTo("User-agent: *\nDisallow: /");
    }

    @Test
    @DisplayName("GET / answers 200 JSON naming the service and docs, with no version/build/host details")
    void servesRoot() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/").header("Host", "api.kelta.io"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(passedOn.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).isEqualTo("{\"service\":\"kelta-api\",\"docs\":\"https://kelta.io/docs\"}");
        assertThat(body.toLowerCase())
                .doesNotContain("version", "build", "commit", "host", "api.kelta.io");
    }

    @Test
    @DisplayName("the docs URL and service name come from properties")
    void rootBodyUsesConfiguredValues() {
        RootEndpointsFilter custom = new RootEndpointsFilter("acme-api", "https://docs.example.com/\"x\"");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));

        StepVerifier.create(custom.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getBodyAsString().block())
                .isEqualTo("{\"service\":\"acme-api\",\"docs\":\"https://docs.example.com/\\\"x\\\"\"}");
    }

    @Test
    @DisplayName("HEAD is answered like GET")
    void answersHead() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.head("/robots.txt"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(passedOn.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/some-tenant/api/users", "/some-tenant", "/some-tenant/", "/api/users",
            "/robots.txt/x", "/robots.txtx", "/some-tenant/robots.txt", "/actuator/health"})
    @DisplayName("every other path goes on to tenant resolution and auth untouched")
    void matchesExactPathsOnly(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(passedOn.get()).isSameAs(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("non-GET/HEAD requests to / go on down the chain")
    void ignoresOtherMethods() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, "/"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(passedOn.get()).isSameAs(exchange);
    }

    @Test
    @DisplayName("runs before custom-domain/slug resolution (-310/-300) and Spring Security (-100)")
    void ordersBeforeTenantResolutionAndAuth() {
        assertThat(filter.getOrder()).isLessThan(-310);
    }

    @Test
    @DisplayName("application.yml declares the root properties and gives PublicPathMatcher no bare '/' prefix")
    void applicationYmlKeepsRootOutOfPrefixMatchedPublicPaths() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        // The shipped file, not src/test/resources/application.yml (which shadows it on the classpath).
        factory.setResources(new FileSystemResource("src/main/resources/application.yml"));
        factory.afterPropertiesSet();
        Properties props = factory.getObject();

        assertThat(props.getProperty("kelta.gateway.root.docs-url")).contains("https://kelta.io/docs");
        assertThat(props.getProperty("kelta.gateway.root.service-name")).contains("kelta-api");
        for (String key : new String[]{"kelta.gateway.security.public-paths",
                "kelta.gateway.security.unauthenticated-paths",
                "kelta.gateway.security.anonymous-only-public-paths"}) {
            assertThat(Arrays.stream(props.getProperty(key, "").split(",")).map(String::trim))
                    .as("%s is prefix-matched; '/' there would make every route public", key)
                    .doesNotContain("/", "", "/robots.txt");
        }
    }
}
