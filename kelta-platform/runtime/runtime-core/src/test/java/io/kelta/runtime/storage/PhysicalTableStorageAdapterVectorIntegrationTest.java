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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the VECTOR path against a real pgvector Postgres: extension creation,
 * {@code vector(N)} column + HNSW index, {@code ?::vector} writes, and cosine similarity search.
 *
 * <p>Runs only under the integration profile (Testcontainers needs Docker).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PhysicalTableStorageAdapter — VECTOR path (pgvector)")
class PhysicalTableStorageAdapterVectorIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    private PhysicalTableStorageAdapter adapter;

    private static CollectionDefinition docsCollection() {
        return new CollectionDefinitionBuilder().name("vec_docs")
                .addField(FieldDefinition.requiredString("title"))
                .addField(new FieldDefinition("embedding", FieldType.VECTOR,
                        true, false, false, null, null, null, null, Map.of("dimension", 3), null))
                .build();
    }

    private static Map<String, Object> record(String id, String title, List<Double> embedding) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("title", title);
        data.put("embedding", embedding);
        data.put("createdBy", "tester");
        data.put("updatedBy", "tester");
        data.put("createdAt", Instant.now());
        data.put("updatedAt", Instant.now());
        return data;
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS vec_docs");
        adapter = new PhysicalTableStorageAdapter(jdbc, new SchemaMigrationEngine(jdbc),
                JsonMapper.builder().build());
    }

    @Test
    @DisplayName("creates the extension + vector column + HNSW index, writes vectors, and ranks by cosine")
    void fullVectorFlow() {
        CollectionDefinition docs = docsCollection();

        // Creates the pgvector extension, a vector(3) column, and the HNSW index.
        adapter.initializeCollection(docs);

        adapter.create(docs, record("r1", "near x-axis", List.of(1.0, 0.0, 0.0)));
        adapter.create(docs, record("r2", "near y-axis", List.of(0.0, 1.0, 0.0)));
        adapter.create(docs, record("r3", "near z-axis", List.of(0.0, 0.0, 1.0)));

        // Query closest to the x-axis → r1 must rank first, with a distance attached.
        List<Map<String, Object>> results = adapter.semanticSearch(
                docs, "embedding", "[0.9,0.1,0.0]", 2, List.of());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("id")).isEqualTo("r1");
        assertThat(results.get(0)).containsKey("_distance");
        assertThat(((Number) results.get(0).get("_distance")).doubleValue())
                .isLessThan(((Number) results.get(1).get("_distance")).doubleValue());
    }

    @Test
    @DisplayName("clearVectorColumn NULLs every vector value (purges stale embeddings on masking toggle)")
    void clearsVectorColumn() {
        CollectionDefinition docs = docsCollection();
        adapter.initializeCollection(docs);
        adapter.create(docs, record("r1", "a", List.of(1.0, 0.0, 0.0)));
        adapter.create(docs, record("r2", "b", List.of(0.0, 1.0, 0.0)));

        int cleared = adapter.clearVectorColumn(docs, "embedding");

        assertThat(cleared).isEqualTo(2);
        assertThat(adapter.getById(docs, "r1").orElseThrow().get("embedding")).isNull();
        // Idempotent: a second call clears nothing (WHERE col IS NOT NULL).
        assertThat(adapter.clearVectorColumn(docs, "embedding")).isZero();
        // A non-VECTOR / unknown field is a safe no-op.
        assertThat(adapter.clearVectorColumn(docs, "title")).isZero();
        assertThat(adapter.clearVectorColumn(docs, "nope")).isZero();
    }
}
