package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.service.PackageImportService;
import io.kelta.worker.service.PackageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/packages")
public class PackageController {

    private static final Logger log = LoggerFactory.getLogger(PackageController.class);

    /** Metadata authoring permission — packages move whole-tenant config. */
    private static final String PERMISSION = "CUSTOMIZE_APPLICATION";

    private final PackageService packageService;
    private final ObjectMapper objectMapper;
    private final io.kelta.worker.service.CerbosPermissionResolver permissionResolver;
    private final io.kelta.worker.repository.BootstrapRepository bootstrapRepository;

    public PackageController(PackageService packageService, ObjectMapper objectMapper,
                             io.kelta.worker.service.CerbosPermissionResolver permissionResolver,
                             io.kelta.worker.repository.BootstrapRepository bootstrapRepository) {
        this.packageService = packageService;
        this.objectMapper = objectMapper;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    private void requirePermission(jakarta.servlet.http.HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, PERMISSION + " permission required");
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(jakarta.servlet.http.HttpServletRequest request) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        requirePermission(request);

        List<Map<String, Object>> history = packageService.getHistory(tenantId);
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("packages", history));
    }

    @PostMapping("/export")
    public ResponseEntity<?> exportPackage(@RequestBody(required = false) Map<String, Object> body,
                                           jakarta.servlet.http.HttpServletRequest request) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        requirePermission(request);

        // Unwrap JSON:API envelope if present. An empty body is the common case
        // (`kelta metadata export`): the service fills in name/version and, with
        // no id lists named, exports the whole tenant.
        Map<String, Object> options = unwrapJsonApiBody(body == null ? Map.of() : body);

        Map<String, Object> pkg = packageService.exportPackage(tenantId, options);
        String name = (String) pkg.get("name");
        String version = (String) pkg.get("version");

        // Return as downloadable JSON
        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(pkg);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-" + version + ".json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonBytes);
        } catch (Exception e) {
            log.error("Failed to serialize package", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to serialize package"));
        }
    }

    @PostMapping("/import/preview")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> previewImport(@RequestParam("file") MultipartFile file,
                                           jakarta.servlet.http.HttpServletRequest request) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        requirePermission(request);

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is required"));
        }

        try {
            Map<String, Object> pkg = objectMapper.readValue(file.getInputStream(), Map.class);
            Map<String, Object> preview = packageService.previewImport(tenantId, pkg);
            return ResponseEntity.ok(preview);
        } catch (Exception e) {
            log.error("Failed to parse package file for preview", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid package file: " + e.getMessage()));
        }
    }

    @PostMapping("/import")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> importPackage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            @RequestParam(value = "conflictMode", defaultValue = "skip") String conflictMode,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            jakarta.servlet.http.HttpServletRequest request) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        requirePermission(request);

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is required"));
        }

        PackageImportService.ConflictMode mode;
        try {
            mode = PackageImportService.ConflictMode.valueOf(conflictMode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "conflictMode must be skip or overwrite"));
        }

        try {
            Map<String, Object> pkg = objectMapper.readValue(file.getInputStream(), Map.class);
            Map<String, Object> result = packageService.importPackage(tenantId, pkg,
                    new PackageImportService.ImportOptions(mode, dryRun, null, null, userId));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to import package", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Import failed: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapJsonApiBody(Map<String, Object> body) {
        // Check if wrapped in JSON:API format: { data: { type: "...", attributes: { ... } } }
        Object dataObj = body.get("data");
        if (dataObj instanceof Map<?, ?> data) {
            Object attrObj = data.get("attributes");
            if (attrObj instanceof Map<?, ?> attributes) {
                return new LinkedHashMap<>((Map<String, Object>) attributes);
            }
        }
        // Already unwrapped
        return body;
    }
}
