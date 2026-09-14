package io.kelta.worker.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetryResourceAttributes;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@code service.instance.id} on the OTLP resource.
 *
 * <p>Without it, every replica of a service exports metrics under the identical OTLP resource
 * (only {@code service.name} differs it), so Mimir stores all replicas' counters as one series.
 * {@code rate()} then treats every replica switch as a counter reset and adds the whole counter
 * value back — this is what inflated the "Requests per Tenant" panel to a phantom ~100 req/s on
 * 2026-09-14 (three worker replicas interleaved into one series with no {@code instance} label).
 */
@DisplayName("kelta-worker OTLP resource instance id")
class ResourceInstanceIdPropertiesTest {

    private static final String PROPERTY = "management.opentelemetry.resource-attributes.service.instance.id";

    private Properties load() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        // FileSystemResource, not ClassPathResource: src/test/resources/application.yml
        // shadows the shipped one on the test classpath, and the shipped file is what
        // the AOT build reads. Surefire runs with the module directory as its cwd.
        factory.setResources(new FileSystemResource("src/main/resources/application.yml"));
        factory.afterPropertiesSet();
        Properties props = factory.getObject();
        assertNotNull(props, "application.yml did not parse");
        return props;
    }

    @Test
    @DisplayName("declares service.instance.id so replicas do not merge into one metric series")
    void declaresServiceInstanceId() {
        String value = load().getProperty(PROPERTY);
        assertNotNull(value, PROPERTY + " must be declared in application.yml so every replica "
                + "exports a distinct OTLP resource.");
        assertTrue(!value.isBlank(), "service.instance.id must not be blank");
    }

    @Test
    @DisplayName("defaults to the hostname and stays overridable by the environment")
    void defaultsToHostnameAndIsOverridable() {
        String value = load().getProperty(PROPERTY);
        assertNotNull(value);
        assertTrue(value.startsWith("${HOSTNAME:") && value.endsWith("}"),
                "service.instance.id must default to ${HOSTNAME:<fallback>} so it is unique per "
                        + "pod without any deployment-specific override; found: " + value);
    }

    @Test
    @DisplayName("the configured resource-attributes map lands on the OTLP resource")
    void resourceAttributesReachTheOtlpResource() {
        // Mirrors what OpenTelemetrySdkAutoConfiguration does at startup: bind
        // management.opentelemetry.resource-attributes and feed it into
        // OpenTelemetryResourceAttributes#applyTo, which builds the actual OTLP resource.
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "kelta-worker");

        Map<String, String> configuredAttributes = new HashMap<>();
        configuredAttributes.put("service.instance.id", "worker-pod-7");

        OpenTelemetryResourceAttributes resourceAttributes =
                new OpenTelemetryResourceAttributes(environment, configuredAttributes);

        Map<String, String> resource = new HashMap<>();
        resourceAttributes.applyTo(resource::put);

        assertEquals("worker-pod-7", resource.get("service.instance.id"),
                "the configured service.instance.id must reach the OTLP resource");
        assertEquals("kelta-worker", resource.get("service.name"));
    }
}
