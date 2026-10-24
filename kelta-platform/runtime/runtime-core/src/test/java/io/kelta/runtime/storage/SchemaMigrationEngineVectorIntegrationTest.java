package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adding a VECTOR field to an existing collection against a real pgvector Postgres where the
 * {@code vector} extension has never been created: the migrate path must enable it before the
 * {@code ALTER TABLE ... ADD COLUMN ... vector(N)}.
 *
 * <p>Runs only under the integration profile (Testcontainers needs Docker).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("SchemaMigrationEngine — adding a VECTOR field (pgvector)")
class SchemaMigrationEngineVectorIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private PhysicalTableStorageAdapter adapter;

    private static CollectionDefinition notes(boolean withEmbedding) {
        CollectionDefinitionBuilder builder = new CollectionDefinitionBuilder().name("vec_notes")
                .addField(FieldDefinition.requiredString("title"));
        if (withEmbedding) {
            builder.addField(new FieldDefinition("embedding", FieldType.VECTOR,
                    true, false, false, null, null, null, null, Map.of("dimension", 3), null));
        }
        return builder.build();
    }

    private boolean vectorExtensionInstalled() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
        return count != null && count > 0;
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS vec_notes");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");
        adapter = new PhysicalTableStorageAdapter(jdbc, new SchemaMigrationEngine(jdbc),
                JsonMapper.builder().build());
    }

    @Test
    @DisplayName("migrateSchema enables the extension, then adds a vector(N) column")
    void addVectorFieldToExistingCollection() {
        adapter.initializeCollection(notes(false));
        assertThat(vectorExtensionInstalled()).isFalse();

        adapter.updateCollectionSchema(notes(false), notes(true));

        assertThat(vectorExtensionInstalled()).isTrue();
        String columnType = jdbc.queryForObject("""
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                WHERE a.attrelid = 'public.vec_notes'::regclass AND a.attname = 'embedding'
                """, String.class);
        assertThat(columnType).isEqualTo("vector(3)");
    }
}
