package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.PackageImportService;
import io.kelta.worker.service.PackageService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PackageController")
class PackageControllerTest {

    private PackageService packageService;
    private ObjectMapper objectMapper;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private HttpServletRequest request;
    private PackageController controller;

    @BeforeEach
    void setUp() {
        packageService = mock(PackageService.class);
        objectMapper = new ObjectMapper();
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        request = mock(HttpServletRequest.class);
        controller = new PackageController(packageService, objectMapper,
                permissionResolver, bootstrapRepository);
        TenantContext.set("t1");
        grantPermission();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void grantPermission() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "CUSTOMIZE_APPLICATION", "granted", true)));
    }

    private void revokePermission() {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "CUSTOMIZE_APPLICATION", "granted", false)));
    }

    @Nested
    @DisplayName("permission gate")
    class PermissionGate {

        @Test
        @DisplayName("Should reject callers without CUSTOMIZE_APPLICATION on every endpoint")
        void shouldRejectWithoutPermission() {
            revokePermission();
            MockMultipartFile file = new MockMultipartFile("file", "p.json",
                    "application/json", "{}".getBytes());

            assertForbidden(() -> controller.getHistory(request));
            assertForbidden(() -> controller.exportPackage(
                    Map.of("name", "p", "version", "1.0.0"), request));
            assertForbidden(() -> controller.previewImport(file, request));
            assertForbidden(() -> controller.importPackage(file, false, "skip", "user-1", request));
            verifyNoInteractions(packageService);
        }

        @Test
        @DisplayName("Should reject callers with no identity headers at all")
        void shouldRejectWithoutIdentity() {
            when(permissionResolver.getProfileId(request)).thenReturn(null);

            assertForbidden(() -> controller.getHistory(request));
            verifyNoInteractions(packageService);
        }

        private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
            assertThatThrownBy(call)
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("getHistory")
    class GetHistory {

        @Test
        @DisplayName("Should return package history in JSON:API format")
        void shouldReturnHistory() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", "pkg-1");
            entry.put("name", "test-export");
            entry.put("version", "1.0.0");
            entry.put("type", "export");
            entry.put("status", "success");
            entry.put("items", List.of());

            when(packageService.getHistory("t1")).thenReturn(List.of(entry));

            var response = controller.getHistory(request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);

            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getBody();
            assertThat(body).containsKey("data");

            @SuppressWarnings("unchecked")
            var data = (List<Map<String, Object>>) body.get("data");
            assertThat(data).hasSize(1);
            assertThat(data.get(0).get("type")).isEqualTo("packages");
        }

        @Test
        @DisplayName("Should return 400 when no tenant context")
        void shouldReturn400WhenNoTenant() {
            TenantContext.clear();
            var response = controller.getHistory(request);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("exportPackage")
    class ExportPackage {

        @Test
        @DisplayName("Should export package with valid options")
        void shouldExportPackage() {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("name", "my-package");
            options.put("version", "1.0.0");
            options.put("collectionIds", List.of("col-1"));

            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("formatVersion", 2);
            pkg.put("name", "my-package");
            pkg.put("version", "1.0.0");
            pkg.put("items", List.of());

            when(packageService.exportPackage(eq("t1"), any())).thenReturn(pkg);

            var response = controller.exportPackage(options, request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
        }

        @Test
        @DisplayName("Should unwrap JSON:API envelope")
        void shouldUnwrapJsonApiEnvelope() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "my-package");
            attributes.put("version", "1.0.0");

            Map<String, Object> wrappedBody = Map.of(
                    "data", Map.of("type", "export", "attributes", attributes));

            when(packageService.exportPackage(eq("t1"), any()))
                    .thenReturn(Map.of("name", "my-package", "version", "1.0.0", "items", List.of()));

            var response = controller.exportPackage(wrappedBody, request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(packageService).exportPackage(eq("t1"), argThat(opts ->
                    "my-package".equals(opts.get("name")) && "1.0.0".equals(opts.get("version"))));
        }

        @Test
        @DisplayName("Should export the whole tenant for an empty body, naming the file from the package")
        void shouldExportWholeTenantForEmptyBody() {
            when(packageService.exportPackage(eq("t1"), any())).thenReturn(Map.of(
                    "name", "acme", "version", "1.0.0", "items", List.of()));

            var response = controller.exportPackage(Map.of(), request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeaders().getFirst("Content-Disposition"))
                    .contains("acme-1.0.0.json");
            verify(packageService).exportPackage(eq("t1"), argThat(Map::isEmpty));
        }

        @Test
        @DisplayName("Should accept a missing request body")
        void shouldAcceptMissingBody() {
            when(packageService.exportPackage(eq("t1"), any())).thenReturn(Map.of(
                    "name", "acme", "version", "1.0.0", "items", List.of()));

            var response = controller.exportPackage(null, request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("previewImport")
    class PreviewImport {

        @Test
        @DisplayName("Should return preview for valid package file")
        void shouldReturnPreview() throws Exception {
            Map<String, Object> preview = Map.of(
                    "creates", List.of(Map.of("type", "COLLECTION", "name", "new_collection")),
                    "updates", List.of(),
                    "conflicts", List.of());

            when(packageService.previewImport(eq("t1"), any())).thenReturn(preview);

            String packageJson = objectMapper.writeValueAsString(Map.of(
                    "name", "test-pkg", "version", "1.0.0", "items", List.of()));
            MockMultipartFile file = new MockMultipartFile("file", "package.json",
                    "application/json", packageJson.getBytes());

            var response = controller.previewImport(file, request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);

            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getBody();
            assertThat(body).containsKeys("creates", "updates", "conflicts");
        }

        @Test
        @DisplayName("Should reject an empty file with no JSON alternative as MultipartException")
        void shouldReturn400ForEmptyFile() {
            MockMultipartFile file = new MockMultipartFile("file", "empty.json",
                    "application/json", new byte[0]);

            assertThatThrownBy(() -> controller.previewImport(file, request))
                    .isInstanceOf(MultipartException.class)
                    .hasMessageContaining("multipart/form-data with a file part is required");
        }

        @Test
        @DisplayName("Should reject a non-multipart, non-JSON request as MultipartException")
        void shouldRejectNonMultipartNonJsonRequest() {
            MockHttpServletRequest plainRequest = new MockHttpServletRequest();
            plainRequest.setContentType("text/plain");
            when(permissionResolver.getProfileId(plainRequest)).thenReturn("profile-1");

            assertThatThrownBy(() -> controller.previewImport(null, plainRequest))
                    .isInstanceOf(MultipartException.class)
                    .hasMessageContaining("multipart/form-data with a file part is required");
        }

        @Test
        @DisplayName("Should accept a raw JSON body as an alternative to multipart")
        void shouldAcceptJsonBody() throws Exception {
            Map<String, Object> preview = Map.of(
                    "creates", List.of(),
                    "updates", List.of(),
                    "conflicts", List.of());
            when(packageService.previewImport(eq("t1"), any())).thenReturn(preview);

            String packageJson = objectMapper.writeValueAsString(Map.of(
                    "name", "test-pkg", "version", "1.0.0", "items", List.of()));
            MockHttpServletRequest jsonRequest = new MockHttpServletRequest();
            jsonRequest.setContentType("application/json");
            jsonRequest.setContent(packageJson.getBytes());
            when(permissionResolver.getProfileId(jsonRequest)).thenReturn("profile-1");

            var response = controller.previewImport(null, jsonRequest);
            assertThat(response.getStatusCode().value()).isEqualTo(200);

            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getBody();
            assertThat(body).containsKeys("creates", "updates", "conflicts");
        }
    }

    @Nested
    @DisplayName("importPackage")
    class ImportPackage {

        @Test
        @DisplayName("Should import with the requested conflict mode and executing user")
        void shouldImportPackage() throws Exception {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("created", 2);
            result.put("updated", 1);
            result.put("skipped", 0);
            result.put("errors", List.of());

            when(packageService.importPackage(eq("t1"), any(),
                    any(PackageImportService.ImportOptions.class))).thenReturn(result);

            String packageJson = objectMapper.writeValueAsString(Map.of(
                    "name", "test-pkg", "version", "1.0.0", "items", List.of()));
            MockMultipartFile file = new MockMultipartFile("file", "package.json",
                    "application/json", packageJson.getBytes());

            var response = controller.importPackage(file, false, "overwrite", "user-1", request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);

            verify(packageService).importPackage(eq("t1"), any(), argThat(opts ->
                    opts.conflictMode() == PackageImportService.ConflictMode.OVERWRITE
                            && !opts.dryRun()
                            && "user-1".equals(opts.executingUserId())));
        }

        @Test
        @DisplayName("Should support dry run mode")
        void shouldSupportDryRun() throws Exception {
            when(packageService.importPackage(eq("t1"), any(),
                    any(PackageImportService.ImportOptions.class)))
                    .thenReturn(Map.of("success", true, "created", 1, "errors", List.of()));

            String packageJson = objectMapper.writeValueAsString(Map.of(
                    "name", "test-pkg", "version", "1.0.0", "items", List.of()));
            MockMultipartFile file = new MockMultipartFile("file", "package.json",
                    "application/json", packageJson.getBytes());

            var response = controller.importPackage(file, true, "skip", null, request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(packageService).importPackage(eq("t1"), any(),
                    argThat(PackageImportService.ImportOptions::dryRun));
        }

        @Test
        @DisplayName("Should reject an unknown conflict mode")
        void shouldRejectBadConflictMode() {
            MockMultipartFile file = new MockMultipartFile("file", "package.json",
                    "application/json", "{}".getBytes());

            var response = controller.importPackage(file, false, "merge", null, request);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(packageService);
        }

        @Test
        @DisplayName("Should reject a request with neither a file nor a JSON body")
        void shouldRejectMissingPayload() {
            MockHttpServletRequest plainRequest = new MockHttpServletRequest();
            when(permissionResolver.getProfileId(plainRequest)).thenReturn("profile-1");

            assertThatThrownBy(() -> controller.importPackage(null, false, "skip", null, plainRequest))
                    .isInstanceOf(MultipartException.class)
                    .hasMessageContaining("multipart/form-data with a file part is required");
            verifyNoInteractions(packageService);
        }

        @Test
        @DisplayName("Should accept a raw JSON body as an alternative to multipart")
        void shouldAcceptJsonBody() throws Exception {
            when(packageService.importPackage(eq("t1"), any(),
                    any(PackageImportService.ImportOptions.class)))
                    .thenReturn(Map.of("success", true, "created", 1, "errors", List.of()));

            String packageJson = objectMapper.writeValueAsString(Map.of(
                    "name", "test-pkg", "version", "1.0.0", "items", List.of()));
            MockHttpServletRequest jsonRequest = new MockHttpServletRequest();
            jsonRequest.setContentType("application/json");
            jsonRequest.setContent(packageJson.getBytes());
            when(permissionResolver.getProfileId(jsonRequest)).thenReturn("profile-1");

            var response = controller.importPackage(null, false, "skip", "user-1", jsonRequest);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }
}
