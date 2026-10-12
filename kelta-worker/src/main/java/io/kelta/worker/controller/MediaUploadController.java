package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.interceptor.CerbosRecordAuthorizationAdvice;
import io.kelta.worker.service.MediaFieldConfig;
import io.kelta.worker.service.MediaStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Direct-to-storage uploads for <b>media fields</b> — a STRING/TEXT field whose
 * {@code fieldTypeConfig.media.enabled} is {@code true} ({@link MediaFieldConfig}). Any tenant
 * collection can carry one; nothing here names a collection.
 *
 * <ol>
 *   <li>{@code POST /api/{collection}/upload-url} {@code {fileName, contentType, fileSize, field?}}
 *       returns a presigned PUT into the media bucket and the {@code storageKey}
 *       {@code <tenantId>/<collection>/<uuid>/<sanitized fileName>}.</li>
 *   <li>The client PUTs the bytes, then creates (or updates) a record through the ordinary
 *       collection route with the media field set to {@code storageKey} — Cerbos and owner
 *       scoping ({@code OwnerScopeGuardHook}) gate that write like any other.</li>
 *   <li>{@code GET /api/{collection}/download/<storageKey>} answers {@code 302} to a short-lived
 *       presigned GET, so a plain {@code <img src>} loads the object without a bearer token.</li>
 * </ol>
 *
 * <p><b>Authorization rides the collection's own route.</b> Both paths sit under
 * {@code /api/{collection}/}, so the gateway resolves the caller's collection route and applies
 * the collection-level Cerbos check — {@code create} for the POST, {@code read} for the GET —
 * exactly as for record calls; no gateway static route exists or is needed. The download then
 * re-checks at the record level: the key must belong to the caller's tenant and this collection,
 * and a record of this collection must reference it in a media field, read through the
 * {@link QueryEngine} (so RLS and owner-scoped reads apply) and passed through the same
 * record-level decision as a record {@code GET}
 * ({@link CerbosRecordAuthorizationAdvice#isRecordReadable}). An object no readable record
 * references is a 404, whoever asks.
 *
 * <p><b>Mappings are spelled out</b>: the download pattern has a fixed depth of six segments under
 * {@code /api}, deeper than any {@code DynamicCollectionRouter} mapping, and never uses
 * {@code /**} (concerns.md → Fragile Areas). {@code POST /api/attachments/upload-url}
 * ({@code AttachmentUploadController}) is a literal two-segment pattern and still wins over
 * {@code /api/{collection}/upload-url}.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api")
public class MediaUploadController {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadController.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    /** JSON:API type of the upload-url response; not a collection. */
    static final String UPLOAD_URL_TYPE = "media-upload-urls";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * The character set {@link #sanitizeFileName} emits; anything else was not minted here. No
     * length cap: keys minted before the 255-character truncation must stay readable.
     */
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private final CollectionRegistry collectionRegistry;
    private final QueryEngine queryEngine;
    private final MediaStorageService storageService;
    private final CerbosRecordAuthorizationAdvice recordAuthorization;

    public MediaUploadController(CollectionRegistry collectionRegistry,
                                 QueryEngine queryEngine,
                                 MediaStorageService storageService,
                                 CerbosRecordAuthorizationAdvice recordAuthorization) {
        this.collectionRegistry = collectionRegistry;
        this.queryEngine = queryEngine;
        this.storageService = storageService;
        this.recordAuthorization = recordAuthorization;
    }

    @PostMapping("/{collectionName}/upload-url")
    public ResponseEntity<Map<String, Object>> requestUploadUrl(
            @PathVariable("collectionName") String collectionName,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestBody Map<String, Object> body) {

        String tenantId = TenantContext.get();
        CollectionDefinition definition = tenantCollection(tenantId, collectionName);
        List<MediaFieldConfig> mediaFields = MediaFieldConfig.mediaFields(definition);
        if (mediaFields.isEmpty()) {
            return notFound(collectionName);
        }

        if (!storageService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    JsonApiResponseBuilder.error("503", "Storage unavailable",
                            "Media storage is not enabled on this server"));
        }

        Optional<MediaFieldConfig> target = selectField(mediaFields, body == null ? null : body.get("field"));
        if (target.isEmpty()) {
            return ResponseEntity.badRequest().body(JsonApiResponseBuilder.error("400", "Invalid field",
                    "'field' must name one of this collection's media fields: "
                            + mediaFields.stream().map(MediaFieldConfig::fieldName).toList()));
        }
        MediaFieldConfig media = target.get();

        String fileName = getString(body, "fileName");
        String contentType = getString(body, "contentType");
        Long fileSize = getLong(body, "fileSize");
        if (fileName == null || contentType == null || fileSize == null) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Missing required fields",
                            "Required: fileName, contentType, fileSize"));
        }
        contentType = contentType.trim();

        if (!media.accepts(contentType)) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Invalid content type",
                            "Accepted content types for '" + media.fieldName() + "': " + media.contentTypes()));
        }

        long maxFileSize = media.effectiveMaxFileSize(storageService.getMaxFileSize());
        if (fileSize <= 0 || fileSize > maxFileSize) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Invalid file size",
                            "File size must be between 1 and " + maxFileSize + " bytes"));
        }

        // The tenant segment comes from the gateway-bound TenantContext, never the request body,
        // and the collection segment from the resolved definition.
        String storageKey = tenantId + "/" + definition.name() + "/" + UUID.randomUUID()
                + "/" + sanitizeFileName(fileName);

        String uploadUrl;
        try {
            uploadUrl = storageService.getPresignedUploadUrl(storageKey, contentType);
        } catch (Exception e) {
            log.error("Failed to presign media upload for tenant {} collection {}: {}",
                    tenantId, definition.name(), e.getMessage());
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Failed to generate upload URL",
                            "The upload URL could not be generated"));
        }

        securityLog.info("security_event=MEDIA_UPLOAD_URL_GENERATED user={} tenant={} collection={} "
                + "field={} storageKey={}", userEmail, tenantId, definition.name(), media.fieldName(), storageKey);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("uploadUrl", uploadUrl);
        attributes.put("storageKey", storageKey);
        attributes.put("field", media.fieldName());
        attributes.put("downloadPath", "/api/" + definition.name() + "/download/" + storageKey);
        attributes.put("expiresInSeconds", storageService.getDefaultExpiry().toSeconds());
        attributes.put("method", "PUT");
        attributes.put("headers", Map.of("Content-Type", contentType));

        return ResponseEntity.ok(JsonApiResponseBuilder.single(UPLOAD_URL_TYPE, storageKey, attributes));
    }

    /**
     * Redirects to a short-lived presigned GET for a media object a readable record of this
     * collection references. Cache-Control is {@code private}: the decision is per caller.
     */
    @GetMapping("/{collectionName}/download/{keyTenant}/{keyCollection}/{objectId}/{fileName}")
    public ResponseEntity<Void> redirectToDownloadUrl(
            @PathVariable("collectionName") String collectionName,
            @PathVariable("keyTenant") String keyTenant,
            @PathVariable("keyCollection") String keyCollection,
            @PathVariable("objectId") String objectId,
            @PathVariable("fileName") String fileName,
            HttpServletRequest request) {

        if (!UUID_PATTERN.matcher(objectId).matches() || !SAFE_FILE_NAME.matcher(fileName).matches()
                || ".".equals(fileName) || "..".equals(fileName)) {
            return ResponseEntity.badRequest().build();
        }

        String tenantId = TenantContext.get();
        // Another tenant's key, or a key minted for another collection, is answered exactly like
        // a missing one — never 403, which would confirm it exists.
        if (tenantId == null || !tenantId.equals(keyTenant) || !collectionName.equals(keyCollection)) {
            return ResponseEntity.notFound().build();
        }
        CollectionDefinition definition = tenantCollection(tenantId, collectionName);
        List<MediaFieldConfig> mediaFields = MediaFieldConfig.mediaFields(definition);
        if (mediaFields.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!storageService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        String storageKey = keyTenant + "/" + keyCollection + "/" + objectId + "/" + fileName;
        Optional<Map<String, Object>> record = findReferencingRecord(definition, mediaFields, storageKey);
        if (record.isEmpty() || !recordAuthorization.isRecordReadable(request, collectionName, record.get())) {
            return ResponseEntity.notFound().build();
        }

        String url;
        try {
            url = storageService.getPresignedDownloadUrl(storageKey);
        } catch (Exception e) {
            log.error("Failed to presign media download for key {}: {}", storageKey, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .build();
    }

    /**
     * A tenant collection visible to the current tenant, or null. System collections never carry
     * media fields — their definitions are canonical code, not tenant metadata.
     */
    private CollectionDefinition tenantCollection(String tenantId, String collectionName) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        return definition == null || definition.systemCollection() ? null : definition;
    }

    /**
     * The first record whose media field holds {@code storageKey}. Runs through the QueryEngine
     * under the request's tenant and caller, so RLS and owner-scoped reads narrow it exactly as
     * they narrow a list read of the collection.
     */
    private Optional<Map<String, Object>> findReferencingRecord(CollectionDefinition definition,
                                                                List<MediaFieldConfig> mediaFields,
                                                                String storageKey) {
        for (MediaFieldConfig media : mediaFields) {
            QueryRequest query = new QueryRequest(new Pagination(1, 1), List.of(), List.of(),
                    List.of(new FilterCondition(media.fieldName(), FilterOperator.EQ, storageKey)));
            QueryResult result = queryEngine.executeQuery(definition, query);
            if (result != null && result.data() != null && !result.data().isEmpty()) {
                return Optional.of(result.data().get(0));
            }
        }
        return Optional.empty();
    }

    /** The named media field, or the only one when none is named. */
    private static Optional<MediaFieldConfig> selectField(List<MediaFieldConfig> mediaFields, Object requested) {
        if (requested == null) {
            return mediaFields.size() == 1 ? Optional.of(mediaFields.get(0)) : Optional.empty();
        }
        if (!(requested instanceof String name)) {
            return Optional.empty();
        }
        return mediaFields.stream().filter(m -> m.fieldName().equals(name)).findFirst();
    }

    private ResponseEntity<Map<String, Object>> notFound(String collectionName) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                JsonApiResponseBuilder.error("404", "Not found",
                        "Collection '" + collectionName + "' has no media field"));
    }

    /** Same sanitizer as {@code AttachmentUploadController}: no {@code /}, so no extra key segments. */
    static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload";
        }
        String sanitized = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.length() > 255) {
            sanitized = sanitized.substring(sanitized.length() - 255);
        }
        return ".".equals(sanitized) || "..".equals(sanitized) ? "upload" : sanitized;
    }

    private static String getString(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value instanceof String str && !str.isBlank() ? str : null;
    }

    private static Long getLong(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value instanceof Number num ? num.longValue() : null;
    }
}
