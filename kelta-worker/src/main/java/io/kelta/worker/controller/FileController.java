package io.kelta.worker.controller;

import io.kelta.worker.service.S3StorageService;
import io.kelta.worker.service.S3StorageService.StorageObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;

/**
 * Streams files directly from S3 through the API with authentication
 * and tenant-scoped access control.
 *
 * <p>Eliminates the requirement for publicly accessible S3 by serving
 * files through the authenticated API instead of presigned URLs.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/files")
@ConditionalOnBean(S3StorageService.class)
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    private static final Set<String> INLINE_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml",
            "application/pdf", "text/plain"
    );

    private final S3StorageService storageService;

    public FileController(S3StorageService storageService) {
        this.storageService = storageService;
    }

    // Depth-1..3 patterns are explicit and the catch-all only backstops deeper keys. DynamicCollectionRouter
    // maps all-variable GETs up to four segments under "/api", and Spring's PathPattern comparator ranks any
    // pattern containing "**" last -- so a bare "/**" here never wins, and every request was answered by the
    // router as a record read of a collection named "files"/"images" (404). See concerns.md -> Fragile Areas.
    @GetMapping({"/{p1}", "/{p1}/{p2}", "/{p1}/{p2}/{p3}", "/**"})
    public void serveFile(HttpServletRequest request, HttpServletResponse response,
                          @RequestHeader(value = "X-Cerbos-Scope", required = false) String tenantId,
                          @RequestHeader(value = "X-User-Email", required = false) String userEmail,
                          @RequestHeader(value = "Range", required = false) String rangeHeader) throws IOException {

        // Extract storage key from path (everything after /api/files/)
        String storageKey = request.getRequestURI().substring("/api/files/".length());

        // Authentication check
        if (userEmail == null || userEmail.isBlank() || tenantId == null || tenantId.isBlank()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }

        // Path traversal prevention
        if (containsPathTraversal(storageKey)) {
            securityLog.warn("security_event=PATH_TRAVERSAL_ATTEMPT user={} storageKey={}", userEmail, storageKey);
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return;
        }

        // Tenant-scoped access: storageKey must start with the user's tenant ID
        String keyTenant = extractTenantFromKey(storageKey);
        if (!tenantId.equals(keyTenant)) {
            // Return 404 (not 403) to avoid information leakage
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        try {
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                serveRange(storageKey, rangeHeader, response, userEmail);
            } else {
                serveFull(storageKey, response, userEmail);
            }
        } catch (NoSuchKeyException e) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
        } catch (Exception e) {
            log.error("Failed to serve file: {}", storageKey, e);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        }
    }

    private void serveFull(String storageKey, HttpServletResponse response, String userEmail) throws IOException {
        try (StorageObject obj = storageService.streamObject(storageKey)) {
            setResponseHeaders(response, obj.contentType(), obj.contentLength(), obj.fileName());
            response.setStatus(HttpStatus.OK.value());

            try (OutputStream out = response.getOutputStream()) {
                obj.content().transferTo(out);
            }

            securityLog.info("security_event=FILE_SERVED user={} storageKey={} size={}",
                    userEmail, storageKey, obj.contentLength());
        }
    }

    private void serveRange(String storageKey, String rangeHeader, HttpServletResponse response,
                             String userEmail) throws IOException {
        try (StorageObject obj = storageService.streamObjectRange(storageKey, rangeHeader)) {
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setContentType(obj.contentType());
            if (obj.contentLength() > 0) {
                response.setContentLengthLong(obj.contentLength());
            }
            // The store's Content-Range names the bytes served and the total size
            // (RFC 9110 §14.4); the request's Range header is not a valid value for it.
            if (obj.contentRange() != null) {
                response.setHeader("Content-Range", obj.contentRange());
            }
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Cache-Control", "private, max-age=3600");

            try (OutputStream out = response.getOutputStream()) {
                obj.content().transferTo(out);
            }

            securityLog.info("security_event=FILE_SERVED_PARTIAL user={} storageKey={} range={}",
                    userEmail, storageKey, rangeHeader);
        }
    }

    private void setResponseHeaders(HttpServletResponse response, String contentType,
                                     long contentLength, String fileName) {
        response.setContentType(contentType);
        if (contentLength > 0) {
            response.setContentLengthLong(contentLength);
        }

        String sanitizedName = sanitizeFileName(fileName);
        String disposition = INLINE_CONTENT_TYPES.contains(contentType)
                ? "inline; filename=\"" + sanitizedName + "\""
                : "attachment; filename=\"" + sanitizedName + "\"";
        response.setHeader("Content-Disposition", disposition);
        response.setHeader("Cache-Control", "private, max-age=3600");
        response.setHeader("Accept-Ranges", "bytes");
    }

    /**
     * Sanitizes filename for Content-Disposition header.
     * Only allows alphanumeric, dash, underscore, and dot.
     */
    static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "download";
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Checks for path traversal sequences in the storage key.
     */
    static boolean containsPathTraversal(String key) {
        if (key == null) return true;
        String decoded = key.replace("%2e", ".").replace("%2E", ".")
                .replace("%2f", "/").replace("%2F", "/");
        return decoded.contains("..") || decoded.startsWith("/");
    }

    private String extractTenantFromKey(String storageKey) {
        int firstSlash = storageKey.indexOf('/');
        return firstSlash > 0 ? storageKey.substring(0, firstSlash) : storageKey;
    }
}
