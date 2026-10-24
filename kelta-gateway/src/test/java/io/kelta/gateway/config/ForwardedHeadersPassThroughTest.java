package io.kelta.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.filter.headers.RemoveXForwardedHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.TrustedProxies;
import org.springframework.cloud.gateway.filter.headers.XForwardedHeadersFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the forwarded-header settings in the shipped {@code application.yml}.
 *
 * <p>Spring Cloud Gateway 5.0.4 registers {@code RemoveXForwardedHeadersFilter} when
 * {@code spring.cloud.gateway.server.webflux.trusted-proxies} is unset, and its
 * {@code XForwardedHeadersFilter} strips the same headers from an untrusted peer. Both drop every
 * {@code X-Forwarded-*} header before the request reaches the worker — including the
 * {@code X-Forwarded-User} / {@code X-Forwarded-Groups} identity headers set by
 * {@code HeaderTransformationFilter}. The beans below use the gateway's own conditions, so this
 * fails exactly when the shipped config would select the stripping filter.
 */
@DisplayName("kelta-gateway forwarded-header pass-through")
class ForwardedHeadersPassThroughTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(ctx -> shippedConfig().forEach(
                    source -> ctx.getEnvironment().getPropertySources().addFirst(source)))
            .withUserConfiguration(GatewayHeaderFilters.class);

    @Test
    @DisplayName("selects the trusting X-Forwarded filter, not the stripping one")
    void selectsTrustingFilter() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(XForwardedHeadersFilter.class);
            assertThat(ctx).doesNotHaveBean(RemoveXForwardedHeadersFilter.class);
            assertThat(ctx).doesNotHaveBean("forwardedHeadersFilter");
        });
    }

    @Test
    @DisplayName("forwards identity and proxy headers unchanged and adds none")
    void forwardsHeadersUnchanged() {
        runner.run(ctx -> {
            HttpHeaders incoming = new HttpHeaders();
            incoming.set("X-Forwarded-User", "alice@example.com");
            incoming.set("X-Forwarded-Groups", "USER,ADMIN");
            incoming.set("X-Forwarded-Roles", "USER,ADMIN");
            incoming.set("X-Forwarded-For", "203.0.113.9");
            incoming.set("X-Forwarded-Proto", "https");
            incoming.set("X-Forwarded-Host", "acme.kelta.io");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("http://kelta-gateway:8080/api/accounts/1")
                            .remoteAddress(new InetSocketAddress("10.42.0.7", 51234))
                            .headers(incoming));

            HttpHeaders forwarded = ctx.getBean(XForwardedHeadersFilter.class)
                    .filter(incoming, exchange);

            assertThat(forwarded.toSingleValueMap()).isEqualTo(incoming.toSingleValueMap());
        });
    }

    private static List<PropertySource<?>> shippedConfig() {
        try {
            // The shipped file, not the classpath copy: it is what the native AOT build reads.
            return new YamlPropertySourceLoader().load("application",
                    new FileSystemResource("src/main/resources/application.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Not @Configuration: component scanning would pull it into every @SpringBootTest context,
    // where its xForwardedHeadersFilter collides with GatewayAutoConfiguration's.
    @EnableConfigurationProperties
    static class GatewayHeaderFilters {

        @Bean
        @Conditional(TrustedProxies.XForwardedTrustedProxiesCondition.class)
        XForwardedHeadersFilter xForwardedHeadersFilter(Environment env) {
            return new XForwardedHeadersFilter(env.getProperty(TrustedProxies.PROPERTY));
        }

        @Bean
        @Conditional(TrustedProxies.NotXForwardedTrustedProxiesCondition.class)
        RemoveXForwardedHeadersFilter removeXForwardedHeadersFilter() {
            return new RemoveXForwardedHeadersFilter();
        }

        @Bean
        @Conditional(TrustedProxies.ForwardedTrustedProxiesCondition.class)
        String forwardedHeadersFilter() {
            return "ForwardedHeadersFilter would append a Forwarded entry";
        }
    }
}
