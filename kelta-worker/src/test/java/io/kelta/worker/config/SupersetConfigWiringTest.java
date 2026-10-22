package io.kelta.worker.config;

import io.kelta.worker.controller.SupersetController;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.SupersetApiClient;
import io.kelta.worker.service.SupersetDatabaseTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Where Superset reaches Postgres for a tenant's connection comes from configuration: the worker's
 * own datasource unless {@code kelta.worker.superset.database-*} says otherwise. A wiring mistake
 * here surfaces only at worker boot, which no unit test starts.
 */
@DisplayName("SupersetConfig wiring")
class SupersetConfigWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SupersetConfig.class, SupersetController.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(CerbosPermissionResolver.class, () -> mock(CerbosPermissionResolver.class))
            .withBean(ObjectMapper.class, () -> mock(ObjectMapper.class))
            .withPropertyValues(
                    "kelta.superset.url=http://superset:8088",
                    "spring.datasource.url=jdbc:postgresql://postgres-rw.db.svc:5432/kelta");

    @Test
    @DisplayName("targets the worker's own datasource by default")
    void defaultsToDatasource() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SupersetApiClient.class);
            assertThat(context).hasSingleBean(SupersetController.class);
            assertThat(context.getBean(SupersetDatabaseTarget.class))
                    .isEqualTo(new SupersetDatabaseTarget("postgres-rw.db.svc", 5432, "kelta"));
        });
    }

    @Test
    @DisplayName("kelta.worker.superset.database-* override the datasource")
    void overrides() {
        runner.withPropertyValues(
                        "kelta.worker.superset.database-host=pg-direct.db.svc",
                        "kelta.worker.superset.database-port=6432",
                        "kelta.worker.superset.database-name=analytics")
                .run(context -> assertThat(context.getBean(SupersetDatabaseTarget.class))
                        .isEqualTo(new SupersetDatabaseTarget("pg-direct.db.svc", 6432, "analytics")));
    }

    @Test
    @DisplayName("an unresolvable target fails the context with the setting to fix")
    void unresolvableFailsBoot() {
        runner.withPropertyValues("spring.datasource.url=jdbc:h2:mem:test")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("SUPERSET_DATABASE_HOST"));
    }

    @Test
    @DisplayName("nothing is registered when kelta.superset.url is blank — the controller included, so the worker boots")
    void blankUrlRegistersNothing() {
        // application.yml declares the URL as ${SUPERSET_URL:}, so "unset" arrives as blank.
        runner.withPropertyValues("kelta.superset.url=", "spring.datasource.url=jdbc:h2:mem:test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SupersetApiClient.class);
                    assertThat(context).doesNotHaveBean(SupersetController.class);
                });
    }
}
