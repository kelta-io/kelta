package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinitionBuilder;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ReferenceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several worker pods initialize the same new collection at the same moment (they all consume
 * the same NATS {@code collection.changed} event). Against real Postgres the losers fail in
 * whichever shape the race produced — {@code type "..." already exists}, a unique violation on
 * {@code pg_type_typname_nsp_index}, {@code constraint "..." already exists} — and every one of
 * them must initialize cleanly. Before the fix, production logged these as
 * "Failed to initialize collection" and the losing pod never registered the collection.
 *
 * <p>Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PhysicalTableStorageAdapter — concurrent initialization against Postgres")
class PhysicalTableStorageAdapterConcurrentDdlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15-alpine"));

    private static final int PODS = 6;
    private static final int ROUNDS = 8;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS race_customers (id VARCHAR(36) PRIMARY KEY)");
    }

    /** A collection whose initialization runs all three racy statements: table, unique index, FK. */
    private static CollectionDefinition orders(String name) {
        return new CollectionDefinitionBuilder()
                .name(name)
                .addField(new FieldDefinitionBuilder().name("code").type(FieldType.EXTERNAL_ID).build())
                .addField(new FieldDefinitionBuilder().name("customer").type(FieldType.LOOKUP)
                        .referenceConfig(ReferenceConfig.lookup("race_customers", "Customer")).build())
                .build();
    }

    @Test
    @DisplayName("every pod initializes cleanly when all of them race on the same collection")
    void concurrentInitializationNeverFails() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(PODS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                CollectionDefinition definition = orders("race_orders_" + round);
                CyclicBarrier start = new CyclicBarrier(PODS);
                List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
                List<Future<?>> pods = new ArrayList<>();
                for (int pod = 0; pod < PODS; pod++) {
                    // One adapter per "pod": nothing in-process is shared but the database.
                    PhysicalTableStorageAdapter adapter = new PhysicalTableStorageAdapter(
                            jdbc, new SchemaMigrationEngine(jdbc), JsonMapper.builder().build());
                    pods.add(pool.submit(() -> {
                        try {
                            start.await();
                            adapter.initializeCollection(definition);
                        } catch (Throwable t) {
                            failures.add(t);
                        }
                    }));
                }
                for (Future<?> f : pods) {
                    f.get();
                }

                assertThat(failures).as("round %d", round).isEmpty();
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass(?) AND contype = 'f'",
                        Integer.class, definition.name()))
                        .as("foreign key on %s", definition.name())
                        .isEqualTo(1);
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE tablename = ? AND indexdef LIKE 'CREATE UNIQUE INDEX%code%'",
                        Integer.class, definition.name()))
                        .as("unique index on %s", definition.name())
                        .isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
