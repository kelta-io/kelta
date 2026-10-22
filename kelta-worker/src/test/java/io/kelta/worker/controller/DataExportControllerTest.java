package io.kelta.worker.controller;

import io.kelta.worker.interceptor.PermissionGatedControllers;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.DataExportRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.DataExportService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("DataExportController")
class DataExportControllerTest {

    private DataExportService dataExportService;
    private DataExportRepository dataExportRepository;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private HttpServletRequest request;
    private DataExportController controller;

    @BeforeEach
    void setUp() {
        dataExportService = mock(DataExportService.class);
        dataExportRepository = mock(DataExportRepository.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        request = mock(HttpServletRequest.class);
        controller = new DataExportController(dataExportService, dataExportRepository,
                permissionResolver, bootstrapRepository);

        // Default: caller holds VIEW_ALL_DATA so the existing behavior tests exercise the
        // handler bodies. The denial path is covered separately.
        grantExportPermission();
    }

    private void grantExportPermission() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "VIEW_ALL_DATA", "granted", true)));
    }

    @Test
    @DisplayName("gates every handler on VIEW_ALL_DATA (PermissionGatedController promise)")
    void everyHandlerIsPermissionGated() {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "VIEW_ALL_DATA", "granted", false)));

        PermissionGatedControllers.assertEveryHandlerForbidden(controller, request);
        verifyNoInteractions(dataExportService, dataExportRepository);
    }

    @Test
    @DisplayName("createExport should reject missing name")
    void createExportShouldRejectMissingName() {
        var body = new HashMap<String, Object>();
        body.put("format", "CSV");

        var response = controller.createExport("t1", "user@test.com", body, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("createExport should reject invalid format")
    void createExportShouldRejectInvalidFormat() {
        var body = new HashMap<String, Object>();
        body.put("name", "Export");
        body.put("format", "XML");
        body.put("exportScope", "FULL");

        var response = controller.createExport("t1", "user@test.com", body, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("createExport should reject SELECTIVE without collectionIds")
    void createExportShouldRejectSelectiveWithoutCollectionIds() {
        var body = new HashMap<String, Object>();
        body.put("name", "Export");
        body.put("format", "CSV");
        body.put("exportScope", "SELECTIVE");

        var response = controller.createExport("t1", "user@test.com", body, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("createExport should accept valid FULL export request")
    void createExportShouldAcceptValidFullRequest() {
        when(dataExportService.createExport(anyString(), anyString(), any(), anyString(),
                any(), anyString(), anyString())).thenReturn("exp-1");

        var body = new HashMap<String, Object>();
        body.put("name", "Full Export");
        body.put("format", "CSV");
        body.put("exportScope", "FULL");

        var response = controller.createExport("t1", "user@test.com", body, request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verify(dataExportService).createExport("t1", "Full Export", null, "FULL", null, "CSV", "user@test.com");
        verify(dataExportService).executeExport(eq("exp-1"), eq("t1"),
                eq(new DataExportService.ExportPrincipal("user@test.com", "profile-1")));
    }

    @Test
    @DisplayName("createExport should accept valid SELECTIVE export request")
    void createExportShouldAcceptValidSelectiveRequest() {
        when(dataExportService.createExport(anyString(), anyString(), any(), anyString(),
                any(), anyString(), anyString())).thenReturn("exp-2");

        var body = new HashMap<String, Object>();
        body.put("name", "Selective Export");
        body.put("format", "JSON");
        body.put("exportScope", "SELECTIVE");
        body.put("collectionIds", List.of("col-1", "col-2"));

        var response = controller.createExport("t1", "user@test.com", body, request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verify(dataExportService).createExport("t1", "Selective Export", null, "SELECTIVE",
                List.of("col-1", "col-2"), "JSON", "user@test.com");
    }

    @Test
    @DisplayName("createExport should reject caller without VIEW_ALL_DATA")
    void createExportShouldRejectWithoutPermission() {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "VIEW_ALL_DATA", "granted", false)));

        var body = new HashMap<String, Object>();
        body.put("name", "Full Export");
        body.put("format", "CSV");
        body.put("exportScope", "FULL");

        assertThatThrownBy(() -> controller.createExport("t1", "user@test.com", body, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("VIEW_ALL_DATA");
        verifyNoInteractions(dataExportService);
    }

    @Test
    @DisplayName("downloadExport should reject caller with no identity")
    void downloadExportShouldRejectNoIdentity() {
        when(permissionResolver.getProfileId(request)).thenReturn(null);

        assertThatThrownBy(() -> controller.downloadExport("exp-1", "t1", request))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(dataExportRepository);
    }

    @Test
    @DisplayName("getExport should return 404 when not found")
    void getExportShouldReturn404WhenNotFound() {
        when(dataExportRepository.findByIdAndTenant("exp-1", "t1")).thenReturn(Optional.empty());

        var response = controller.getExport("exp-1", "t1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("getExport should return export when found")
    void getExportShouldReturnWhenFound() {
        Map<String, Object> export = new HashMap<>();
        export.put("id", "exp-1");
        export.put("name", "Test");
        export.put("status", "COMPLETED");
        export.put("format", "CSV");
        export.put("export_scope", "FULL");
        export.put("total_records", 100);
        export.put("records_exported", 100);
        when(dataExportRepository.findByIdAndTenant("exp-1", "t1")).thenReturn(Optional.of(export));

        var response = controller.getExport("exp-1", "t1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("listExports should return paginated results")
    void listExportsShouldReturnPaginatedResults() {
        when(dataExportRepository.findByTenant("t1", 20, 0)).thenReturn(List.of());
        when(dataExportRepository.countByTenant("t1")).thenReturn(0);

        var response = controller.listExports("t1", 20, 0, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("downloadExport should return 404 when not found")
    void downloadExportShouldReturn404WhenNotFound() {
        when(dataExportRepository.findByIdAndTenant("exp-1", "t1")).thenReturn(Optional.empty());

        var response = controller.downloadExport("exp-1", "t1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("downloadExport should return 409 when not completed")
    void downloadExportShouldReturn409WhenNotCompleted() {
        Map<String, Object> export = new HashMap<>();
        export.put("status", "IN_PROGRESS");
        when(dataExportRepository.findByIdAndTenant("exp-1", "t1")).thenReturn(Optional.of(export));

        var response = controller.downloadExport("exp-1", "t1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("cancelExport should return 404 when not found")
    void cancelExportShouldReturn404WhenNotFound() {
        when(dataExportRepository.cancel("exp-1", "t1")).thenReturn(0);
        when(dataExportRepository.findByIdAndTenant("exp-1", "t1")).thenReturn(Optional.empty());

        var response = controller.cancelExport("exp-1", "t1", "user@test.com", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("cancelExport should return 422 when not pending")
    void cancelExportShouldReturn422WhenNotPending() {
        when(dataExportRepository.cancel("exp-1", "t1")).thenReturn(0);
        Map<String, Object> export = Map.of("status", "COMPLETED");
        when(dataExportRepository.findByIdAndTenant("exp-1", "t1")).thenReturn(Optional.of(export));

        var response = controller.cancelExport("exp-1", "t1", "user@test.com", request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    @DisplayName("cancelExport should succeed when pending")
    void cancelExportShouldSucceedWhenPending() {
        when(dataExportRepository.cancel("exp-1", "t1")).thenReturn(1);
        Map<String, Object> export = new HashMap<>();
        export.put("status", "CANCELLED");
        export.put("name", "Test");
        export.put("format", "CSV");
        export.put("export_scope", "FULL");
        export.put("total_records", 0);
        export.put("records_exported", 0);
        when(dataExportRepository.findByIdAndTenant("exp-1", "t1")).thenReturn(Optional.of(export));

        var response = controller.cancelExport("exp-1", "t1", "user@test.com", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
