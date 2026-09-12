package io.kelta.worker.service.analytics;

import io.kelta.worker.repository.AnalyticsEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Boot-wiring guard for the analytics capture beans.
 *
 * <p>The unit test constructs {@link AnalyticsCaptureService} directly with a {@code new
 * ObjectMapper()}, so it cannot tell which {@code ObjectMapper} the Spring context actually
 * provides. The worker is on <b>Jackson 3</b> — its only {@code ObjectMapper} bean is
 * {@code tools.jackson.databind.ObjectMapper}; injecting the classic
 * {@code com.fasterxml.jackson.databind.ObjectMapper} compiles and unit-tests green but fails the
 * real worker at boot ("required a bean of type ObjectMapper that could not be found"). This test
 * exercises the actual constructor injection so that mistake fails here instead of in a deployed
 * container.
 */
@DisplayName("Analytics capture wiring")
class AnalyticsCaptureWiringTest {

    @Configuration
    static class Stubs {
        @Bean
        AnalyticsEventRepository analyticsEventRepository() {
            return mock(AnalyticsEventRepository.class);
        }

        @org.springframework.context.annotation.Bean
        io.kelta.runtime.router.UserIdResolver userIdResolver() {
            return mock(io.kelta.runtime.router.UserIdResolver.class);
        }

        /** The Jackson 3 ObjectMapper the platform actually exposes as a bean. */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Stubs.class)
            .withBean(AnalyticsCaptureService.class);

    @Test
    @DisplayName("AnalyticsCaptureService wires against the platform's tools.jackson ObjectMapper")
    void wiresWithJackson3ObjectMapper() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AnalyticsCaptureService.class);
        });
    }
}
