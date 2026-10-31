package io.kelta.worker.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code spring.flyway.ignore-migration-patterns} replaces Flyway's default {@code *:future}, so the
 * shipped value must list it again. Without it, an ArgoCD rollback to an older worker image fails its
 * PreSync migrate Job: that image does not have the newest migration file, Flyway sees the applied
 * row as FUTURE, and validation fails with {@code APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED}.
 */
@DisplayName("Flyway ignore-migration-patterns — a rollback image can still migrate")
class FlywayMigrationPatternsTest {

    private static final String SHIPPED_YML = "src/main/resources/application.yml";
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Test
    @DisplayName("the shipped config ignores missing AND future migrations")
    void shippedPatternsKeepFuture() {
        assertThat(shippedPatterns()).contains("*:missing", "*:future");
    }

    @Test
    @DisplayName("an image one migration behind migrates with the shipped patterns, and fails without *:future")
    void rollbackImageMigrates(@TempDir Path olderImage) throws IOException, URISyntaxException {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            ds.setDriverClassName("org.postgresql.Driver");
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

            // The current release migrates the database to its head.
            flyway(ds, "classpath:db/migration", shippedPatterns()).migrate();

            // The older image ships every migration but the newest one.
            Path current = Path.of(Objects.requireNonNull(
                    getClass().getClassLoader().getResource("db/migration")).toURI());
            List<Path> scripts;
            try (Stream<Path> files = Files.list(current)) {
                scripts = files.filter(p -> VERSIONED.matcher(p.getFileName().toString()).matches())
                        .sorted(Comparator.comparingInt(FlywayMigrationPatternsTest::version))
                        .toList();
            }
            assertThat(scripts).hasSizeGreaterThan(1);
            for (Path script : scripts.subList(0, scripts.size() - 1)) {
                Files.copy(script, olderImage.resolve(script.getFileName()));
            }
            String olderLocation = "filesystem:" + olderImage.toAbsolutePath();

            MigrateResult result = flyway(ds, olderLocation, shippedPatterns()).migrate();
            assertThat(result.migrationsExecuted).isZero();

            assertThatThrownBy(() -> flyway(ds, olderLocation, new String[]{"*:missing"}).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("not resolved locally");
        }
    }

    private static int version(Path script) {
        Matcher m = VERSIONED.matcher(script.getFileName().toString());
        if (!m.matches()) {
            throw new IllegalArgumentException(script.toString());
        }
        return Integer.parseInt(m.group(1));
    }

    /** The patterns as the shipped application.yml declares them. */
    private static String[] shippedPatterns() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(SHIPPED_YML));
        Properties properties = Objects.requireNonNull(yaml.getObject());
        String value = properties.getProperty("spring.flyway.ignore-migration-patterns");
        assertThat(value).as("spring.flyway.ignore-migration-patterns").isNotBlank();
        return Arrays.stream(value.split(",")).map(String::trim).toArray(String[]::new);
    }

    /** Flyway as the migrate Job configures it (application.yml + application-migrate.yml). */
    private static Flyway flyway(DriverManagerDataSource ds, String location, String[] patterns) {
        return Flyway.configure()
                .dataSource(ds)
                .locations(location)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholderReplacement(false)
                .ignoreMigrationPatterns(patterns)
                .load();
    }
}
