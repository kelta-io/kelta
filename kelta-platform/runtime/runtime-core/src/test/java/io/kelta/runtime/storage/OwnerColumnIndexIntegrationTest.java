package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner-scoped reads filter on {@code CollectionDefinition.ownerField()}; this checks against a
 * real Postgres that the owner column is indexed both when a collection is created owned and when
 * ownership is enabled later through {@code migrateSchema}, and that re-running either is safe.
 *
 * <p>Runs only under the integration profile (Testcontainers needs Docker).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Owner-field column index (Postgres)")
class OwnerColumnIndexIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private PhysicalTableStorageAdapter adapter;

    private static CollectionDefinition collection(String name, String ownerField) {
        return new CollectionDefinitionBuilder().name(name)
                .addField(FieldDefinition.requiredString("title"))
                .addField(FieldDefinition.lookup("assignee", "users", "Assignee"))
                .ownerField(ownerField)
                .build();
    }

    /** Index definitions on {@code table} whose key is exactly {@code column}. */
    private List<String> indexesOn(String table, String column) {
        return jdbc.queryForList("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = ? AND indexdef LIKE ?
                """, String.class, table, "%(" + column + ")");
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS owned_on_create");
        jdbc.execute("DROP TABLE IF EXISTS owned_later");
        adapter = new PhysicalTableStorageAdapter(jdbc, new SchemaMigrationEngine(jdbc),
                JsonMapper.builder().build());
    }

    @Test
    @DisplayName("a collection created with an ownerField gets an index on the owner column")
    void indexedOnCreate() {
        CollectionDefinition owned = collection("owned_on_create", "createdBy");

        adapter.initializeCollection(owned);
        adapter.initializeCollection(owned);

        assertThat(indexesOn("owned_on_create", "created_by")).hasSize(1);
    }

    @Test
    @DisplayName("setting ownerField on an existing collection through migrateSchema indexes it")
    void indexedWhenOwnershipEnabled() {
        CollectionDefinition unowned = collection("owned_later", null);
        CollectionDefinition owned = collection("owned_later", "assignee");
        adapter.initializeCollection(unowned);
        assertThat(indexesOn("owned_later", "assignee")).isEmpty();

        adapter.updateCollectionSchema(unowned, owned);
        adapter.updateCollectionSchema(unowned, owned);

        assertThat(indexesOn("owned_later", "assignee")).hasSize(1);
    }
}
