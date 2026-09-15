package io.kelta.worker.service;

import io.kelta.worker.repository.PackageRepository;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PackageService")
class PackageServiceTest {

    private PackageRepository repository;
    private PackageImportService importService;
    private ObjectMapper objectMapper;
    private PackageService service;

    @BeforeEach
    void setUp() {
        repository = mock(PackageRepository.class);
        importService = mock(PackageImportService.class);
        when(repository.getJdbcTemplate()).thenReturn(mock(JdbcTemplate.class));
        objectMapper = new ObjectMapper();
        service = new PackageService(repository, objectMapper, importService);
    }

    private static PackageImportService.ImportReport report(PackageImportService.ItemResult... items) {
        int created = 0, updated = 0, skipped = 0, failed = 0;
        for (var item : items) {
            switch (item.action()) {
                case "CREATED" -> created++;
                case "UPDATED" -> updated++;
                case "SKIPPED" -> skipped++;
                case "FAILED" -> failed++;
            }
        }
        return new PackageImportService.ImportReport(created, updated, skipped, failed, List.of(items));
    }

    @Nested
    @DisplayName("exportPackage")
    class ExportPackage {

        @Test
        @DisplayName("Should export selected collections with their fields")
        void shouldExportCollectionsWithFields() {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("name", "test-export");
            options.put("version", "1.0.0");
            options.put("collectionIds", List.of("col-1"));

            Map<String, Object> collection = new LinkedHashMap<>();
            collection.put("id", "col-1");
            collection.put("name", "users");
            collection.put("tenant_id", "t1");
            when(repository.findCollectionsByIds("t1", List.of("col-1"))).thenReturn(List.of(collection));

            Map<String, Object> field = new LinkedHashMap<>();
            field.put("id", "f-1");
            field.put("collection_id", "col-1");
            field.put("collection_name", "users");
            field.put("name", "email");
            field.put("type", "STRING");
            when(repository.findFieldsWithNamesByCollectionIds("t1", List.of("col-1")))
                    .thenReturn(List.of(field));
            when(repository.save(any(), any(), any(), any(), any(), any(), any())).thenReturn("hist-1");

            var result = service.exportPackage("t1", options);

            assertThat(result.get("name")).isEqualTo("test-export");
            assertThat(result.get("version")).isEqualTo("1.0.0");
            assertThat(result.get("formatVersion")).isEqualTo(2);

            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) result.get("items");
            assertThat(items).hasSize(2);
            assertThat(items.get(0).get("type")).isEqualTo("COLLECTION");
            assertThat(items.get(1).get("type")).isEqualTo("FIELD");

            // Verify tenant_id is removed from exported data
            @SuppressWarnings("unchecked")
            var collectionData = (Map<String, Object>) items.get(0).get("data");
            assertThat(collectionData).doesNotContainKey("tenant_id");
        }

        @Test
        @DisplayName("Should record export in history")
        void shouldRecordExportHistory() {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("name", "hist-test");
            options.put("version", "2.0.0");
            options.put("description", "Test export");
            when(repository.save(any(), any(), any(), any(), any(), any(), any())).thenReturn("hist-1");

            service.exportPackage("t1", options);

            verify(repository).save(eq("t1"), eq("hist-test"), eq("2.0.0"), eq("Test export"),
                    eq("export"), eq("success"), any());
        }
    }

    @Nested
    @DisplayName("previewImport")
    class PreviewImport {

        @Test
        @DisplayName("Should classify dry-run creates")
        void shouldIdentifyCreates() {
            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("items", List.of(
                    Map.of("type", "COLLECTION", "data", Map.of("id", "col-1", "name", "new_col"))));
            when(importService.importPackage(eq("t1"), eq(pkg), any()))
                    .thenReturn(report(new PackageImportService.ItemResult(
                            "COLLECTION", "new_col", "CREATED", null)));

            var result = service.previewImport("t1", pkg);

            @SuppressWarnings("unchecked")
            var creates = (List<Map<String, Object>>) result.get("creates");
            assertThat(creates).hasSize(1);
            assertThat(creates.get(0).get("name")).isEqualTo("new_col");
        }

        @Test
        @DisplayName("Should classify skipped/failed items as conflicts")
        void shouldIdentifyConflicts() {
            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("items", List.of(
                    Map.of("type", "COLLECTION", "data", Map.of("id", "col-1", "name", "accounts"))));
            when(importService.importPackage(eq("t1"), eq(pkg), any()))
                    .thenReturn(report(new PackageImportService.ItemResult(
                            "COLLECTION", "accounts", "SKIPPED", null)));

            var result = service.previewImport("t1", pkg);

            @SuppressWarnings("unchecked")
            var conflicts = (List<Map<String, Object>>) result.get("conflicts");
            assertThat(conflicts).hasSize(1);
        }

        @Test
        @DisplayName("Should run the import engine in dry-run mode with no history writes")
        void shouldBeDryRunOnly() {
            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("items", List.of());
            when(importService.importPackage(eq("t1"), eq(pkg), any())).thenReturn(report());

            service.previewImport("t1", pkg);

            verify(importService).importPackage(eq("t1"), eq(pkg),
                    argThat(PackageImportService.ImportOptions::dryRun));
            verify(repository, never()).save(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should echo package provenance for the caller")
        void shouldEchoSource() {
            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("items", List.of());
            pkg.put("source", Map.of("instanceId", "inst-1", "tenantId", "other"));
            when(importService.importPackage(eq("t1"), eq(pkg), any())).thenReturn(report());

            var result = service.previewImport("t1", pkg);

            assertThat(result.get("source")).isEqualTo(pkg.get("source"));
        }
    }

    @Nested
    @DisplayName("importPackage")
    class ImportPackage {

        @Test
        @DisplayName("Should return report counts for dry run without history")
        void shouldReturnPreviewForDryRun() {
            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("name", "test-pkg");
            pkg.put("version", "1.0.0");
            pkg.put("items", List.of());
            when(importService.importPackage(eq("t1"), eq(pkg), any()))
                    .thenReturn(report(new PackageImportService.ItemResult(
                            "COLLECTION", "new_col", "CREATED", null)));

            var result = service.importPackage("t1", pkg, true);

            assertThat(result.get("success")).isEqualTo(true);
            assertThat(result.get("created")).isEqualTo(1);
            assertThat(result.get("errors")).isEqualTo(List.of());
            verify(repository, never()).save(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should record import in history")
        void shouldRecordImportHistory() {
            when(repository.save(any(), any(), any(), any(), any(), any(), any())).thenReturn("hist-1");

            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("name", "import-test");
            pkg.put("version", "1.0.0");
            pkg.put("items", List.of());
            when(importService.importPackage(eq("t1"), eq(pkg), any()))
                    .thenReturn(report(new PackageImportService.ItemResult(
                            "COLLECTION", "contacts", "CREATED", null)));

            service.importPackage("t1", pkg, false);

            verify(repository).save(eq("t1"), eq("import-test"), eq("1.0.0"), any(),
                    eq("import"), eq("success"), any());
        }

        @Test
        @DisplayName("Should surface per-item failures in the errors list")
        void shouldSurfaceErrors() {
            when(repository.save(any(), any(), any(), any(), any(), any(), any())).thenReturn("hist-1");

            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("name", "import-test");
            pkg.put("version", "1.0.0");
            pkg.put("items", List.of());
            when(importService.importPackage(eq("t1"), eq(pkg), any()))
                    .thenReturn(report(new PackageImportService.ItemResult(
                            "FIELD", "orders.ghost", "FAILED", "Collection not found in target: ghost")));

            var result = service.importPackage("t1", pkg, false);

            assertThat(result.get("success")).isEqualTo(false);
            @SuppressWarnings("unchecked")
            var errors = (List<Map<String, Object>>) result.get("errors");
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).get("message").toString()).contains("ghost");
            verify(repository).save(eq("t1"), eq("import-test"), eq("1.0.0"), any(),
                    eq("import"), eq("failed"), any());
        }
    }

    @Nested
    @DisplayName("getHistory")
    class GetHistory {

        @Test
        @DisplayName("Should return mapped history rows")
        void shouldReturnMappedHistory() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "pkg-1");
            row.put("name", "export-2024");
            row.put("version", "1.0.0");
            row.put("type", "export");
            row.put("status", "success");
            row.put("created_at", java.sql.Timestamp.from(java.time.Instant.parse("2024-01-15T10:30:00Z")));
            row.put("items", "[{\"type\":\"collection\",\"id\":\"col-1\",\"name\":\"users\"}]");

            when(repository.findAllByTenantId("t1")).thenReturn(List.of(row));

            var result = service.getHistory("t1");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("name")).isEqualTo("export-2024");
            assertThat(result.get(0).get("type")).isEqualTo("export");

            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) result.get(0).get("items");
            assertThat(items).hasSize(1);
            assertThat(items.get(0).get("name")).isEqualTo("users");
        }

        @Test
        @DisplayName("Should handle null items JSON")
        void shouldHandleNullItems() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "pkg-2");
            row.put("name", "empty");
            row.put("version", "1.0.0");
            row.put("type", "import");
            row.put("status", "failed");
            row.put("created_at", null);
            row.put("items", null);

            when(repository.findAllByTenantId("t1")).thenReturn(List.of(row));

            var result = service.getHistory("t1");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("items")).isEqualTo(List.of());
        }

        @Test
        @DisplayName("Should return empty list when no history")
        void shouldReturnEmptyWhenNoHistory() {
            when(repository.findAllByTenantId("t1")).thenReturn(List.of());

            var result = service.getHistory("t1");
            assertThat(result).isEmpty();
        }
    }
}
