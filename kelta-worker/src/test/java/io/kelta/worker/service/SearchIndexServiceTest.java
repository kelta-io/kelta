package io.kelta.worker.service;

import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("SearchIndexService")
class SearchIndexServiceTest {

    private JdbcTemplate jdbcTemplate;
    private CollectionLifecycleManager lifecycleManager;
    private CollectionRegistry collectionRegistry;
    private StorageAdapter storageAdapter;
    private SearchIndexService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        lifecycleManager = mock(CollectionLifecycleManager.class);
        collectionRegistry = mock(CollectionRegistry.class);
        storageAdapter = mock(StorageAdapter.class);
        service = new SearchIndexService(jdbcTemplate, lifecycleManager, collectionRegistry, storageAdapter,
                new FieldMaskingService());
    }

    @Nested
    @DisplayName("buildSearchContent")
    class BuildSearchContent {

        @Test
        @DisplayName("should include display field and searchable fields")
        void shouldIncludeDisplayAndSearchableFields() {
            when(lifecycleManager.getSearchableFieldNames("products"))
                    .thenReturn(Set.of("description", "sku"));
            when(lifecycleManager.getDisplayFieldName("products"))
                    .thenReturn("name");

            Map<String, Object> data = Map.of(
                    "name", "Widget A",
                    "description", "A great widget",
                    "sku", "W-001",
                    "price", 9.99);

            String content = service.buildSearchContent("products", data);

            assertThat(content).contains("Widget A");
            assertThat(content).contains("A great widget");
            assertThat(content).contains("W-001");
            assertThat(content).doesNotContain("9.99");
        }

        @Test
        @DisplayName("should exclude masking-configured fields from search content")
        void shouldExcludeMaskingConfiguredFields() {
            when(lifecycleManager.getSearchableFieldNames("people"))
                    .thenReturn(Set.of("ssn", "bio"));
            when(lifecycleManager.getDisplayFieldName("people"))
                    .thenReturn("name");

            io.kelta.runtime.model.FieldDefinition ssn = new io.kelta.runtime.model.FieldDefinitionBuilder()
                    .name("ssn").type(io.kelta.runtime.model.FieldType.STRING)
                    .fieldTypeConfig(Map.of("masking", Map.of("type", "LAST4")))
                    .build();
            io.kelta.runtime.model.FieldDefinition bio = new io.kelta.runtime.model.FieldDefinitionBuilder()
                    .name("bio").type(io.kelta.runtime.model.FieldType.STRING).build();
            io.kelta.runtime.model.FieldDefinition name = new io.kelta.runtime.model.FieldDefinitionBuilder()
                    .name("name").type(io.kelta.runtime.model.FieldType.STRING).build();
            when(collectionRegistry.get("people")).thenReturn(
                    new io.kelta.runtime.model.CollectionDefinitionBuilder()
                            .name("people").fields(java.util.List.of(name, ssn, bio)).build());

            Map<String, Object> data = Map.of(
                    "name", "Jane Doe",
                    "ssn", "123-45-6789",
                    "bio", "loves widgets");

            String content = service.buildSearchContent("people", data);

            assertThat(content).contains("Jane Doe");
            assertThat(content).contains("loves widgets");
            assertThat(content).doesNotContain("123-45-6789");
        }

        @Test
        @DisplayName("should exclude server-only fields from search content even when marked searchable")
        void shouldExcludeServerOnlyFields() {
            when(lifecycleManager.getSearchableFieldNames("vault"))
                    .thenReturn(Set.of("secretEnc", "label"));
            when(lifecycleManager.getDisplayFieldName("vault")).thenReturn("secretEnc");
            when(collectionRegistry.get("vault")).thenReturn(
                    new io.kelta.runtime.model.CollectionDefinitionBuilder()
                            .name("vault")
                            .fields(java.util.List.of(
                                    io.kelta.runtime.model.FieldDefinition.string("label"),
                                    io.kelta.runtime.model.FieldDefinition.text("secretEnc")
                                            .withServerOnly(true)))
                            .build());

            Map<String, Object> data = Map.of("label", "prod key", "secretEnc", "enc:v1:abc");

            assertThat(service.buildSearchContent("vault", data))
                    .contains("prod key").doesNotContain("enc:v1:abc");
            assertThat(service.extractDisplayValue("vault", data)).isNotEqualTo("enc:v1:abc");
        }

        @Test
        @DisplayName("should use fallback display fields when no display field configured")
        void shouldUseFallbackDisplayFields() {
            when(lifecycleManager.getSearchableFieldNames("products"))
                    .thenReturn(Set.of());
            when(lifecycleManager.getDisplayFieldName("products"))
                    .thenReturn(null);

            Map<String, Object> data = Map.of(
                    "name", "Widget B",
                    "email", "test@example.com");

            String content = service.buildSearchContent("products", data);

            assertThat(content).contains("Widget B");
        }

        @Test
        @DisplayName("should not duplicate display field value")
        void shouldNotDuplicateDisplayField() {
            when(lifecycleManager.getSearchableFieldNames("products"))
                    .thenReturn(Set.of("name"));
            when(lifecycleManager.getDisplayFieldName("products"))
                    .thenReturn("name");

            Map<String, Object> data = Map.of("name", "Widget C");

            String content = service.buildSearchContent("products", data);

            // "Widget C" should appear only once
            int firstIndex = content.indexOf("Widget C");
            int lastIndex = content.lastIndexOf("Widget C");
            assertThat(firstIndex).isEqualTo(lastIndex);
        }

        @Test
        @DisplayName("should handle empty record data")
        void shouldHandleEmptyRecordData() {
            when(lifecycleManager.getSearchableFieldNames("products"))
                    .thenReturn(Set.of("name"));
            when(lifecycleManager.getDisplayFieldName("products"))
                    .thenReturn("name");

            String content = service.buildSearchContent("products", Map.of());

            assertThat(content).isEmpty();
        }
    }

    @Nested
    @DisplayName("extractDisplayValue")
    class ExtractDisplayValue {

        @Test
        @DisplayName("should return display field value when configured")
        void shouldReturnDisplayFieldValue() {
            when(lifecycleManager.getDisplayFieldName("orders"))
                    .thenReturn("orderNumber");

            Map<String, Object> data = Map.of(
                    "orderNumber", "ORD-100",
                    "name", "should not use this");

            String value = service.extractDisplayValue("orders", data);

            assertThat(value).isEqualTo("ORD-100");
        }

        @Test
        @DisplayName("should fallback to name field")
        void shouldFallbackToName() {
            when(lifecycleManager.getDisplayFieldName("orders"))
                    .thenReturn(null);

            Map<String, Object> data = Map.of(
                    "name", "Test Order",
                    "title", "Should not use");

            String value = service.extractDisplayValue("orders", data);

            assertThat(value).isEqualTo("Test Order");
        }

        @Test
        @DisplayName("should fallback to id when no display fields available")
        void shouldFallbackToId() {
            when(lifecycleManager.getDisplayFieldName("orders"))
                    .thenReturn(null);

            Map<String, Object> data = Map.of("id", "abc-123");

            String value = service.extractDisplayValue("orders", data);

            assertThat(value).isEqualTo("abc-123");
        }
    }

    @Nested
    @DisplayName("buildTsQuery")
    class BuildTsQuery {

        @Test
        @DisplayName("should convert single word to prefix match")
        void shouldConvertSingleWord() {
            String result = service.buildTsQuery("john");
            assertThat(result).isEqualTo("john:*");
        }

        @Test
        @DisplayName("should convert multiple words to AND with prefix match")
        void shouldConvertMultipleWords() {
            String result = service.buildTsQuery("john smith");
            assertThat(result).isEqualTo("john:* & smith:*");
        }

        @Test
        @DisplayName("should strip special characters")
        void shouldStripSpecialChars() {
            String result = service.buildTsQuery("john's");
            assertThat(result).isEqualTo("johns:*");
        }

        @Test
        @DisplayName("should preserve email-like characters")
        void shouldPreserveEmailChars() {
            String result = service.buildTsQuery("test@example.com");
            assertThat(result).isEqualTo("test@example.com:*");
        }

        @Test
        @DisplayName("should return null for blank query")
        void shouldReturnNullForBlank() {
            assertThat(service.buildTsQuery("")).isNull();
            assertThat(service.buildTsQuery("  ")).isNull();
            assertThat(service.buildTsQuery(null)).isNull();
        }
    }

    @Nested
    @DisplayName("indexRecord")
    class IndexRecord {

        @Test
        @DisplayName("should call jdbc update with correct parameters")
        void shouldCallJdbcUpdate() {
            when(lifecycleManager.getSearchableFieldNames("products"))
                    .thenReturn(Set.of());
            when(lifecycleManager.getDisplayFieldName("products"))
                    .thenReturn("name");

            Map<String, Object> data = Map.of("name", "Test Product");

            service.indexRecord("tenant-1", "col-1", "products", "rec-1", data);

            verify(jdbcTemplate).update(
                    contains("INSERT INTO search_index"),
                    any(String.class),  // id
                    eq("tenant-1"),
                    eq("col-1"),
                    eq("products"),
                    eq("rec-1"),
                    eq("Test Product"),  // displayValue
                    eq("Test Product")   // searchContent
            );
        }
    }

    @Nested
    @DisplayName("removeRecord")
    class RemoveRecord {

        @Test
        @DisplayName("should delete from search index")
        void shouldDeleteFromSearchIndex() {
            service.removeRecord("tenant-1", "products", "rec-1");

            verify(jdbcTemplate).update(
                    contains("DELETE FROM search_index"),
                    eq("tenant-1"),
                    eq("products"),
                    eq("rec-1")
            );
        }
    }
}
