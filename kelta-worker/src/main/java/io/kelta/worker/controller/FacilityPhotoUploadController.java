package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.worker.service.SpotopenedMediaStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Presigned-upload access for spotopened's {@code facility-photos} collection.
 *
 * <p>Single step, unlike {@code AttachmentUploadController}'s upload-url +
 * finalize dance: this controller only ever mints a presigned PUT URL (see
 * {@link SpotopenedMediaStorageService}'s javadoc for why it's a separate
 * service). The client PUTs bytes to storage, then creates the metadata row
 * itself via the ordinary generic route ({@code POST /api/facility-photos}),
 * which is independently Cerbos-gated and owner-guarded by the collection's
 * ownership metadata ({@code OwnerScopeGuardHook}) -- there is nothing here to "finalize" because nothing here writes to a
 * database.
 *
 * <p>Nested under {@code /api/facility-photos}, NOT a standalone top-level
 * path -- the gateway's own routing layer (Spring Cloud Gateway routes,
 * {@code DynamicRouteLocator}) is separate from Cerbos authorization and is
 * driven entirely by {@code RouteRegistry} entries derived from registered
 * collections/system routes, each matched as a {@code /**} prefix
 * ({@code RouteDefinition}'s own javadoc: {@code "/api/users/**"}). A
 * standalone {@code /api/facility-photo-uploads} has no such entry at all
 * and 404s at the gateway before ever reaching the worker -- confirmed live
 * ("No static resource api/facility-photo-uploads") the first time this was
 * shipped that way. Nesting under {@code /api/facility-photos} rides the
 * collection's own already-registered route, exactly how
 * {@code /api/attachments/upload-url} already works today under the
 * {@code attachments} system collection's route.
 *
 * <p>Cerbos-wise this maps a POST here to the {@code facility-photos}
 * collection's {@code create} action (same for the download redirect below
 * and {@code read}) -- see {@code RouteAuthorizationFilter.mapMethodToAction}
 * -- which is the right gate anyway: only a caller who could create a
 * facility-photos row should be able to request an upload URL for one, and
 * spotopened's Guest profile already has both grants (emf#1368/#1369), so
 * an anonymous caller needs nothing additional.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/facility-photos")
public class FacilityPhotoUploadController {

    private static final Logger log = LoggerFactory.getLogger(FacilityPhotoUploadController.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    private static final Set<String> BLOCKED_CONTENT_TYPES = Set.of(
            "application/x-executable",
            "application/x-sharedlib",
            "application/x-msdos-program"
    );

    private static final Set<String> ALLOWED_CONTENT_TYPE_PREFIXES = Set.of("image/");

    private static final String DOWNLOAD_PATH_PREFIX = "/api/facility-photos/download/";

    private final SpotopenedMediaStorageService storageService;

    public FacilityPhotoUploadController(SpotopenedMediaStorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/upload-url")
    public ResponseEntity<Map<String, Object>> requestUploadUrl(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("X-User-Email") String userEmail,
            @RequestBody Map<String, Object> body) {

        if (!storageService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    JsonApiResponseBuilder.error("503", "Storage unavailable",
                            "spotopened-media storage is not enabled on this server"));
        }

        String fileName = getStringRequired(body, "fileName");
        String contentType = getStringRequired(body, "contentType");
        Long fileSize = getLongRequired(body, "fileSize");

        if (fileName == null || contentType == null || fileSize == null) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Missing required fields",
                            "Required: fileName, contentType, fileSize"));
        }

        if (BLOCKED_CONTENT_TYPES.contains(contentType)
                || ALLOWED_CONTENT_TYPE_PREFIXES.stream().noneMatch(contentType::startsWith)) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Invalid content type",
                            "Only image uploads are accepted"));
        }

        long maxFileSize = storageService.getMaxFileSize();
        if (fileSize <= 0 || fileSize > maxFileSize) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Invalid file size",
                            "File size must be between 1 and " + maxFileSize + " bytes"));
        }

        String storageKey = tenantId + "/facility-photos/" + UUID.randomUUID()
                + "/" + sanitizeFileName(fileName);

        String uploadUrl;
        try {
            uploadUrl = storageService.getPresignedUploadUrl(storageKey, contentType);
        } catch (Exception e) {
            log.error("Failed to generate presigned upload URL for tenant {}: {}",
                    tenantId, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Failed to generate upload URL", e.getMessage()));
        }

        securityLog.info("security_event=FACILITY_PHOTO_UPLOAD_URL_GENERATED user={} tenant={} "
                + "storageKey={}", userEmail, tenantId, storageKey);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("uploadUrl", uploadUrl);
        attributes.put("storageKey", storageKey);
        attributes.put("expiresInSeconds", storageService.getDefaultExpiry().toSeconds());
        attributes.put("method", "PUT");
        attributes.put("headers", Map.of("Content-Type", contentType));

        return ResponseEntity.ok(JsonApiResponseBuilder.single("facility-photo-upload-urls", storageKey, attributes));
    }

    /**
     * Redirects to a short-lived presigned GET for a spotopened-media object,
     * so a plain {@code <img src="/api/facility-photos/download/<storageKey>">}
     * loads it directly -- no bearer token needed (browsers don't attach one to
     * an {@code <img>} tag), no blob-URL fetch dance in the client either.
     * Nested under {@code /api/facility-photos} for the same gateway-routing
     * reason {@code /upload-url} above is -- see this class's own javadoc.
     *
     * <p>Wildcard-mapped and extracted from the raw URI, not a
     * {@code @PathVariable}, because storageKey itself contains {@code /}
     * (tenantId/facility-photos/uuid/filename) -- same reason
     * {@code FileController} does this for the shared emf-attachments bucket.
     * This is the spotopened-media equivalent of that controller, not a
     * generalization of it -- see {@code SpotopenedMediaStorageService}'s
     * javadoc for why they stay separate.
     */
    @GetMapping("/download/**")
    public ResponseEntity<Void> redirectToDownloadUrl(
            jakarta.servlet.http.HttpServletRequest request) {
        String storageKey = request.getRequestURI().substring(DOWNLOAD_PATH_PREFIX.length());
        if (storageKey.isBlank() || storageKey.contains("..")) {
            return ResponseEntity.badRequest().build();
        }
        if (!storageService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        String url;
        try {
            url = storageService.getPresignedDownloadUrl(storageKey);
        } catch (Exception e) {
            log.error("Failed to generate presigned download URL for key {}: {}", storageKey, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .build();
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "upload";
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String getStringRequired(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof String str && !str.isBlank()) {
            return str;
        }
        return null;
    }

    private static Long getLongRequired(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number num) {
            return num.longValue();
        }
        return null;
    }
}
