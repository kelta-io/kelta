package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PhysicalTableStorageAdapter.semanticSearch")
class PhysicalTableStorageAdapterSemanticSearchTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SchemaMigrationEngine migrationEngine;

    private PhysicalTableStorageAdapter adapter;

    private static CollectionDefinition docsWithVector() {
        return new CollectionDefinitionBuilder().name("docs")
                .addField(new FieldDefinition("embedding", FieldType.VECTOR,
                        true, false, false, null, null, null, null, Map.of("dimension", 384), null))
                .addField(FieldDefinition.requiredString("title"))
                .build();
    }

    @BeforeEach
    void setUp() {
        adapter = new PhysicalTableStorageAdapter(jdbcTemplate, migrationEngine, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("builds a cosine-distance query ordered nearest-first with the vector bound first")
    void buildsCosineQuery() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(new ArrayList<>(List.of(
                        new HashMap<>(Map.of("id", "1", "title", "A", "_distance", 0.1)))));

        List<Map<String, Object>> rows = adapter.semanticSearch(
                docsWithVector(), "embedding", "[0.1,0.2]", 5, List.of());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture());

        assertThat(sql.getValue())
                .contains("\"embedding\" <=> CAST(? AS vector)) AS _distance")
                .contains("WHERE \"embedding\" IS NOT NULL")
                .contains("ORDER BY _distance ASC LIMIT ?");
        // Vector literal is bound before the limit (and before any filter params).
        assertThat(params.getValue()).containsExactly("[0.1,0.2]", 5);
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("builds an HNSW cosine index DDL for a VECTOR column")
    void buildsHnswIndexDdl() {
        String ddl = SqlIdentifiers.buildHnswIndexStatement(
                "hnsw_docs_embedding", "public.docs", "embedding");
        assertThat(ddl).isEqualTo(
                "CREATE INDEX IF NOT EXISTS hnsw_docs_embedding ON public.docs"
                        + " USING hnsw (\"embedding\" vector_cosine_ops)");
    }

    @Test
    @DisplayName("casts placeholders: ?::vector for VECTOR, ?::jsonb for JSON/ARRAY, ? otherwise")
    void castPlaceholders() {
        assertThat(SqlIdentifiers.castPlaceholder(io.kelta.runtime.model.FieldType.VECTOR))
                .isEqualTo("?::vector");
        assertThat(SqlIdentifiers.castPlaceholder(io.kelta.runtime.model.FieldType.JSON))
                .isEqualTo("?::jsonb");
        assertThat(SqlIdentifiers.castPlaceholder(io.kelta.runtime.model.FieldType.ARRAY))
                .isEqualTo("?::jsonb");
        assertThat(SqlIdentifiers.castPlaceholder(io.kelta.runtime.model.FieldType.STRING))
                .isEqualTo("?");
    }
}
