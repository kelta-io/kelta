package io.kelta.worker.controller;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.SubResourceAuthorizer.Decision;
import io.kelta.runtime.router.SubResourceAuthorizer.Operation;
import io.kelta.worker.interceptor.CerbosSubResourceAuthorizer;
import io.kelta.worker.interceptor.FieldWriteGuard;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.CsvImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("CsvImportController")
class CsvImportControllerTest {

    private static final String EMAIL = "user@example.com";
    private static final String PROFILE = "profile-1";
    private static final String TENANT = "tenant-1";

    private QueryEngine queryEngine;
    private CollectionRegistry registry;
    private CerbosSubResourceAuthorizer authorizer;
    private FieldWriteGuard fieldWriteGuard;
    private CsvImportController controller;
    private MockHttpServletRequest request;
    private final CollectionDefinition contacts = new CollectionDefinitionBuilder()
            .name("contacts")
            .displayName("Contacts")
            .addField(FieldDefinition.string("name"))
            .addField(FieldDefinition.string("salary"))
            .build();

    @BeforeEach
    void setUp() {
        queryEngine = mock(QueryEngine.class);
        registry = mock(CollectionRegistry.class);
        when(registry.get("contacts")).thenReturn(contacts);
        authorizer = mock(CerbosSubResourceAuthorizer.class);
        fieldWriteGuard = mock(FieldWriteGuard.class);
        CsvImportService service = new CsvImportService(queryEngine, registry, JsonMapper.builder().build());
        controller = new CsvImportController(service, authorizer, fieldWriteGuard,
                new CerbosPermissionResolver(), true);
        request = new MockHttpServletRequest("POST", "/api/collections/contacts/import/csv");
        request.addHeader("X-User-Email", EMAIL);
        request.addHeader("X-User-Profile-Id", PROFILE);
        request.addHeader("X-Cerbos-Scope", TENANT);
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "contacts.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<Map<String, Object>> importCsv(String content) {
        return controller.importCsv("contacts", TENANT, EMAIL, csv(content), request);
    }

    @Test
    @DisplayName("without create on the collection the import is a 403 and nothing is written")
    void createDeniedIs403() {
        when(authorizer.authorizeCollection(eq(contacts), eq(Operation.CREATE), any()))
                .thenReturn(Decision.forbidden("Insufficient permissions for create on contacts"));

        ResponseEntity<Map<String, Object>> response = importCsv("name\nAda\n");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(queryEngine, fieldWriteGuard);
    }

    @Test
    @DisplayName("a not-found decision is a 404")
    void notFoundDecisionIs404() {
        when(authorizer.authorizeCollection(eq(contacts), eq(Operation.CREATE), any()))
                .thenReturn(Decision.notFound());

        assertThat(importCsv("name\nAda\n").getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(queryEngine);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("write-denied columns are left out of every record and reported")
    void writeDeniedColumnsAreSkipped() {
        when(authorizer.authorizeCollection(eq(contacts), eq(Operation.CREATE), any())).thenReturn(Decision.allow());
        when(fieldWriteGuard.stripUnwritable(anyMap(), isNull(), eq(EMAIL), eq(PROFILE), eq(TENANT), eq("contacts")))
                .thenReturn(List.of("salary"));

        ResponseEntity<Map<String, Object>> response = importCsv("name,salary\nAda,100\nGrace,200\n");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("rowsImported", 2)
                .containsEntry("skippedColumns", List.of("salary"));
        ArgumentCaptor<Map<String, Object>> records = ArgumentCaptor.forClass(Map.class);
        verify(queryEngine, times(2)).create(eq(contacts), records.capture());
        assertThat(records.getAllValues()).allSatisfy(r -> assertThat(r).containsOnlyKeys("name"));
    }

    @Test
    @DisplayName("an allowed import with every column writable creates full records")
    void allowedImport() {
        when(authorizer.authorizeCollection(eq(contacts), eq(Operation.CREATE), any())).thenReturn(Decision.allow());
        when(fieldWriteGuard.stripUnwritable(anyMap(), isNull(), any(), any(), any(), any())).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = importCsv("name,salary\nAda,100\n");

        assertThat(response.getBody()).containsEntry("rowsImported", 1).doesNotContainKey("skippedColumns");
        verify(queryEngine).create(contacts, Map.of("name", "Ada", "salary", "100"));
    }

    @Test
    @DisplayName("an unknown collection is a 400 before any check")
    void unknownCollection() {
        ResponseEntity<Map<String, Object>> response =
                controller.importCsv("nope", TENANT, EMAIL, csv("name\nAda\n"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(authorizer, queryEngine);
    }
}
