package io.kelta.worker.config;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code kelta.worker.internal-auth.enabled} is declared in the shipped application.yml and read
 * when the {@code /internal/**} chain is built, so a deploy-time env flip takes effect on JVM and
 * native builds alike.
 */
@DisplayName("kelta-worker internal-auth flag")
class InternalAuthPropertiesTest {

    private static final String SHIPPED_YML = "src/main/resources/application.yml";
    private static final String VALID_TOKEN = "valid-internal-token";

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Infrastructure {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!VALID_TOKEN.equals(token)) {
                    throw new BadJwtException("invalid token");
                }
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .claim("sub", "gateway-internal")
                        .claim("scope", "internal")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
            };
        }

        @Bean
        PingController pingController() {
            return new PingController();
        }
    }

    @RestController
    static class PingController {
        @GetMapping("/internal/ping")
        String internalPing() {
            return "ok";
        }

        @GetMapping("/api/ping")
        String apiPing() {
            return "ok";
        }
    }

    /** The shipped application.yml plus the given variables as a relaxed-binding environment. */
    private WebApplicationContextRunner runner(Map<String, Object> env) {
        return new WebApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        var sources = context.getEnvironment().getPropertySources();
                        new YamlPropertySourceLoader().load("shipped", new FileSystemResource(SHIPPED_YML))
                                .forEach(sources::addLast);
                        sources.addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", env));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withUserConfiguration(Infrastructure.class, InternalEndpointSecurityConfig.class);
    }

    private static MockMvc mockMvc(AssertableWebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    @DisplayName("application.yml declares the flag as an env-overridable placeholder defaulting to off")
    void declaredInShippedYml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        // FileSystemResource: src/test/resources/application.yml shadows the shipped one on the
        // test classpath, and the shipped file is what the AOT build reads.
        factory.setResources(new FileSystemResource(SHIPPED_YML));
        factory.afterPropertiesSet();
        Properties props = factory.getObject();
        assertThat(props).isNotNull();
        assertThat(props.getProperty("kelta.worker.internal-auth.enabled"))
                .isEqualTo("${KELTA_WORKER_INTERNAL_AUTH_ENABLED:false}");
    }

    @Test
    @DisplayName("off by default: /internal/** is reachable without a JWT")
    void offByDefault() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InternalAuthProperties.class).enabled()).isFalse();
            MockMvc mvc = mockMvc(context);
            mvc.perform(get("/internal/ping")).andExpect(status().isOk());
            mvc.perform(get("/api/ping")).andExpect(status().isOk());
        });
    }

    @Test
    @DisplayName("KELTA_WORKER_INTERNAL_AUTH_ENABLED=true: /internal/** returns 401 without a JWT and 200 with one")
    void onViaEnvironment() {
        runner(Map.of("KELTA_WORKER_INTERNAL_AUTH_ENABLED", "true")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InternalAuthProperties.class).enabled()).isTrue();
            MockMvc mvc = mockMvc(context);
            mvc.perform(get("/internal/ping")).andExpect(status().isUnauthorized());
            mvc.perform(get("/internal/ping").header("Authorization", "Bearer wrong"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/internal/ping").header("Authorization", "Bearer " + VALID_TOKEN))
                    .andExpect(status().isOk());
            // Gateway-routed traffic keeps the trusted-headers model.
            mvc.perform(get("/api/ping")).andExpect(status().isOk());
        });
    }
}
