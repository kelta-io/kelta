package io.kelta.auth.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins kelta-auth's effective datasource, Redis and actuator defaults (no env overrides).
 *
 * <p>Until KLT-838 runtime-core shipped these keys in its jar-packaged
 * {@code application.properties}, and a {@code .properties} file outranks a {@code .yml} in the
 * same {@code classpath:/} location, so those values beat this service's own
 * {@code application.yml}. The expected values below are what was in effect then; the yml now
 * has to supply them itself. The sources are layered the way Spring Boot layers them: every
 * {@code application.properties} on the classpath (runtime-core's jar) ahead of the shipped yml.
 */
@DisplayName("kelta-auth effective connection defaults")
class EffectiveConnectionDefaultsTest {

    private static PropertySourcesPropertyResolver resolver;

    @BeforeAll
    static void load() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        PropertiesPropertySourceLoader properties = new PropertiesPropertySourceLoader();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:application.properties")) {
            properties.load(resource.getURL().toString(), resource).forEach(sources::addLast);
        }
        // FileSystemResource: src/test/resources/application.yml shadows the shipped one on the
        // test classpath. Surefire runs with the module directory as its cwd.
        new YamlPropertySourceLoader()
                .load("application.yml", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(sources::addLast);
        resolver = new PropertySourcesPropertyResolver(sources);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {
            "spring.datasource.url                         | jdbc:postgresql://localhost:5432/kelta",
            "spring.datasource.username                    | kelta",
            "spring.datasource.password                    | kelta",
            "spring.datasource.driver-class-name           | org.postgresql.Driver",
            "spring.datasource.hikari.maximum-pool-size    | 10",
            "spring.datasource.hikari.minimum-idle         | 5",
            "spring.datasource.hikari.idle-timeout         | 300000",
            "spring.datasource.hikari.connection-timeout   | 20000",
            "spring.datasource.hikari.max-lifetime         | 1200000",
            "spring.data.redis.host                        | localhost",
            "spring.data.redis.port                        | 6379",
            "management.endpoints.web.exposure.include     | 'health,info,metrics'",
            "management.endpoint.health.show-details       | when-authorized",
    })
    @DisplayName("keeps the value that was in effect before runtime-core dropped its defaults")
    void effectiveValue(String key, String expected) {
        String value = resolver.getProperty(key);
        assertThat(value).as(key).isNotNull();
        // Relaxed binding: runtime-core wrote the enum as when_authorized, the yml as when-authorized.
        assertThat(value.replace('_', '-')).as(key).isEqualTo(expected);
    }
}
