package io.kelta.worker.controller;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.interceptor.CerbosRecordAuthorizationAdvice;
import io.kelta.worker.repository.GovernorLimitsRepository;
import io.kelta.worker.service.S3StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AttachmentUploadControllerTest {

    @Mock
    private S3StorageService storageService;

    @Mock
    private GovernorLimitsRepository governorLimitsRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private CollectionRegistry collectionRegistry;

    @Mock
    private QueryEngine queryEngine;

    @Mock
    private CerbosRecordAuthorizationAdvice recordAuthorization;

    @Mock
    private HttpServletRequest request;

    private AttachmentUploadController controller;

    private final CollectionDefinition accounts = CollectionDefinition.builder().name("accounts")
            .addField(FieldDefinition.string("name")).build();
    private final Map<String, Object> parent = Map.of("id", "rec-1", "name", "Acme");

    private static final String TENANT_ID = "tenant-1";
    private static final String USER_EMAIL = "user@example.com";

    @BeforeEach
    void setUp() {
        controller = new AttachmentUploadController(storageService, governorLimitsRepository, jdbcTemplate,
                collectionRegistry, queryEngine, recordAuthorization);
        lenient().when(storageService.isEnabled()).thenReturn(true);
        // Default: col-1 is the tenant's "accounts" collection and the caller may edit rec-1.
        lenient().when(jdbcTemplate.queryForList(contains("FROM collection"), eq(String.class), eq("col-1"), eq(TENANT_ID)))
                .thenReturn(List.of("accounts"));
        lenient().when(collectionRegistry.get("accounts")).thenReturn(accounts);
        lenient().when(queryEngine.getById(accounts, "rec-1")).thenReturn(Optional.of(parent));
        lenient().when(recordAuthorization.isRecordReadable(request, "accounts", parent)).thenReturn(true);
        lenient().when(recordAuthorization.isRecordEditable(request, "accounts", parent)).thenReturn(true);
    }

    private Map<String, Object> validUploadBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileName", "report.pdf");
        body.put("contentType", "application/pdf");
        body.put("fileSize", 1024L);
        body.put("collectionId", "col-1");
        body.put("recordId", "rec-1");
        return body;
    }

    @Test
    void requestUploadUrl_success() {
        Map<String, Object> body = validUploadBody();

        when(storageService.getMaxFileSize()).thenReturn(52_428_800L);
        when(governorLimitsRepository.sumStorageBytes(TENANT_ID)).thenReturn(0L);
        when(governorLimitsRepository.findTenantLimits(TENANT_ID)).thenReturn(Optional.empty());
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(storageService.getDefaultExpiry()).thenReturn(Duration.ofMinutes(15));
        when(storageService.getPresignedUploadUrl(anyString(), eq("application/pdf"), any(Duration.class)))
                .thenReturn("https://s3.example.com/presigned-upload-url");

        ResponseEntity<Map<String, Object>> response = controller.requestUploadUrl(TENANT_ID, USER_EMAIL, body, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertNotNull(data);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) data.get("attributes");
        assertEquals("https://s3.example.com/presigned-upload-url", attributes.get("uploadUrl"));
        assertEquals("PUT", attributes.get("method"));
        assertEquals(900L, attributes.get("expiresInSeconds"));
    }

    @Test
    void requestUploadUrl_missingFields_returnsBadRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileName", "report.pdf");
        // missing other required fields

        ResponseEntity<Map<String, Object>> response = controller.requestUploadUrl(TENANT_ID, USER_EMAIL, body, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void requestUploadUrl_blockedContentType_returnsBadRequest() {
        Map<String, Object> body = validUploadBody();
        body.put("contentType", "application/x-executable");

        ResponseEntity<Map<String, Object>> response = controller.requestUploadUrl(TENANT_ID, USER_EMAIL, body, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void requestUploadUrl_fileTooLarge_returnsBadRequest() {
        Map<String, Object> body = validUploadBody();
        body.put("fileSize", 100_000_000L);

        when(storageService.getMaxFileSize()).thenReturn(52_428_800L);

        ResponseEntity<Map<String, Object>> response = controller.requestUploadUrl(TENANT_ID, USER_EMAIL, body, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void requestUploadUrl_storageLimitExceeded_returnsForbidden() {
        Map<String, Object> body = validUploadBody();
        body.put("fileSize", 1_000_000L);

        when(storageService.getMaxFileSize()).thenReturn(52_428_800L);
        // Current usage is 9.99 GB, limit is 10 GB, adding 1 MB would exceed
        when(governorLimitsRepository.sumStorageBytes(TENANT_ID)).thenReturn(10_737_000_000L);
        when(governorLimitsRepository.findTenantLimits(TENANT_ID)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.requestUploadUrl(TENANT_ID, USER_EMAIL, body, request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void finalizeUpload_success() {
        String attachmentId = "att-1";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", attachmentId);
        row.put("tenant_id", TENANT_ID);
        row.put("collection_id", "col-1");
        row.put("record_id", "rec-1");
        row.put("file_name", "report.pdf");
        row.put("file_size", 1024L);
        row.put("content_type", "application/pdf");
        row.put("storage_key", "");
        row.put("uploaded_by", USER_EMAIL);

        when(jdbcTemplate.queryForList(anyString(), eq(attachmentId), eq(TENANT_ID)))
                .thenReturn(List.of(row));
        when(storageService.objectExists(anyString())).thenReturn(true);
        when(jdbcTemplate.update(anyString(), anyString(), eq(attachmentId), eq(TENANT_ID)))
                .thenReturn(1);

        ResponseEntity<Map<String, Object>> response = controller.finalizeUpload(attachmentId, TENANT_ID, USER_EMAIL, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void finalizeUpload_notFound() {
        when(jdbcTemplate.queryForList(anyString(), eq("bad-id"), eq(TENANT_ID)))
                .thenReturn(Collections.emptyList());

        ResponseEntity<Map<String, Object>> response = controller.finalizeUpload("bad-id", TENANT_ID, USER_EMAIL, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void finalizeUpload_alreadyFinalized_returnsBadRequest() {
        String attachmentId = "att-1";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("collection_id", "col-1");
        row.put("record_id", "rec-1");
        row.put("storage_key", "tenant-1/col-1/rec-1/att-1/report.pdf");

        when(jdbcTemplate.queryForList(anyString(), eq(attachmentId), eq(TENANT_ID)))
                .thenReturn(List.of(row));

        ResponseEntity<Map<String, Object>> response = controller.finalizeUpload(attachmentId, TENANT_ID, USER_EMAIL, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void finalizeUpload_fileNotInS3_returnsBadRequest() {
        String attachmentId = "att-1";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", attachmentId);
        row.put("tenant_id", TENANT_ID);
        row.put("collection_id", "col-1");
        row.put("record_id", "rec-1");
        row.put("file_name", "report.pdf");
        row.put("storage_key", "");

        when(jdbcTemplate.queryForList(anyString(), eq(attachmentId), eq(TENANT_ID)))
                .thenReturn(List.of(row));
        when(storageService.objectExists(anyString())).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.finalizeUpload(attachmentId, TENANT_ID, USER_EMAIL, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void deleteAttachment_success_deletesS3AndRow() {
        String attachmentId = "att-1";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", attachmentId);
        row.put("storage_key", "tenant-1/col-1/rec-1/att-1/report.pdf");

        when(jdbcTemplate.queryForList(anyString(), eq(attachmentId), eq(TENANT_ID)))
                .thenReturn(List.of(row));

        ResponseEntity<Void> response = controller.deleteAttachment(attachmentId, TENANT_ID, USER_EMAIL);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(storageService).deleteObject("tenant-1/col-1/rec-1/att-1/report.pdf");
        verify(jdbcTemplate).update(contains("DELETE FROM file_attachment"), eq(attachmentId), eq(TENANT_ID));
    }

    @Test
    void deleteAttachment_deniedForNonOwner() {
        String attachmentId = "att-1";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", attachmentId);
        row.put("storage_key", "tenant-1/col-1/rec-1/att-1/report.pdf");
        row.put("uploaded_by", "owner@example.com");

        when(jdbcTemplate.queryForList(anyString(), eq(attachmentId), eq(TENANT_ID)))
                .thenReturn(List.of(row));

        ResponseEntity<Void> response = controller.deleteAttachment(attachmentId, TENANT_ID, "someone-else@example.com");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(storageService, never()).deleteObject(anyString());
        verify(jdbcTemplate, never()).update(contains("DELETE FROM file_attachment"), anyString(), anyString());
    }

    @Test
    void deleteAttachment_allowedForOwner() {
        String attachmentId = "att-1";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", attachmentId);
        row.put("storage_key", "tenant-1/col-1/rec-1/att-1/report.pdf");
        row.put("uploaded_by", USER_EMAIL);

        when(jdbcTemplate.queryForList(anyString(), eq(attachmentId), eq(TENANT_ID)))
                .thenReturn(List.of(row));

        ResponseEntity<Void> response = controller.deleteAttachment(attachmentId, TENANT_ID, USER_EMAIL);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(storageService).deleteObject("tenant-1/col-1/rec-1/att-1/report.pdf");
    }

    @Test
    void deleteAttachment_notFound() {
        when(jdbcTemplate.queryForList(anyString(), eq("bad-id"), eq(TENANT_ID)))
                .thenReturn(Collections.emptyList());

        ResponseEntity<Void> response = controller.deleteAttachment("bad-id", TENANT_ID, USER_EMAIL);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(storageService, never()).deleteObject(anyString());
    }

    @Test
    void deleteAttachment_s3Disabled_skipsS3ButDeletesRow() {
        when(storageService.isEnabled()).thenReturn(false);
        String attachmentId = "att-1";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("storage_key", "tenant-1/col-1/rec-1/att-1/report.pdf");

        when(jdbcTemplate.queryForList(anyString(), eq(attachmentId), eq(TENANT_ID)))
                .thenReturn(List.of(row));

        ResponseEntity<Void> response = controller.deleteAttachment(attachmentId, TENANT_ID, USER_EMAIL);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(storageService, never()).deleteObject(anyString());
        verify(jdbcTemplate).update(contains("DELETE FROM file_attachment"), eq(attachmentId), eq(TENANT_ID));
    }

    @Test
    void deleteAttachment_blankStorageKey_skipsS3() {
        String attachmentId = "att-1";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("storage_key", "");

        when(jdbcTemplate.queryForList(anyString(), eq(attachmentId), eq(TENANT_ID)))
                .thenReturn(List.of(row));

        ResponseEntity<Void> response = controller.deleteAttachment(attachmentId, TENANT_ID, USER_EMAIL);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(storageService, never()).deleteObject(anyString());
        verify(jdbcTemplate).update(contains("DELETE FROM file_attachment"), eq(attachmentId), eq(TENANT_ID));
    }

    // ==================== Parent-record edit gate ====================

    @Test
    void requestUploadUrl_forbiddenWhenParentNotEditable() {
        when(recordAuthorization.isRecordEditable(request, "accounts", parent)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response =
                controller.requestUploadUrl(TENANT_ID, USER_EMAIL, validUploadBody(), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(storageService, never()).getPresignedUploadUrl(anyString(), anyString(), any());
    }

    @Test
    void requestUploadUrl_notFoundWhenParentUnreadable() {
        // 404, not 403: a record the caller cannot read must not be confirmed to exist.
        when(recordAuthorization.isRecordReadable(request, "accounts", parent)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response =
                controller.requestUploadUrl(TENANT_ID, USER_EMAIL, validUploadBody(), request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(recordAuthorization, never()).isRecordEditable(any(), anyString(), any());
        verify(storageService, never()).getPresignedUploadUrl(anyString(), anyString(), any());
    }

    @Test
    void requestUploadUrl_notFoundForAnotherTenantsCollection() {
        Map<String, Object> body = validUploadBody();
        body.put("collectionId", "foreign-col");
        when(jdbcTemplate.queryForList(contains("FROM collection"), eq(String.class), eq("foreign-col"), eq(TENANT_ID)))
                .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response =
                controller.requestUploadUrl(TENANT_ID, USER_EMAIL, body, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verifyNoInteractions(queryEngine);
    }

    @Test
    void requestUploadUrl_notFoundForMissingParent() {
        when(queryEngine.getById(accounts, "rec-1")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response =
                controller.requestUploadUrl(TENANT_ID, USER_EMAIL, validUploadBody(), request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void finalizeUpload_forbiddenWhenParentNotEditable() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "att-1");
        row.put("collection_id", "col-1");
        row.put("record_id", "rec-1");
        row.put("file_name", "report.pdf");
        row.put("storage_key", "");
        when(jdbcTemplate.queryForList(anyString(), eq("att-1"), eq(TENANT_ID))).thenReturn(List.of(row));
        when(recordAuthorization.isRecordEditable(request, "accounts", parent)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response =
                controller.finalizeUpload("att-1", TENANT_ID, USER_EMAIL, request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(storageService, never()).objectExists(anyString());
        verify(jdbcTemplate, never()).update(anyString(), anyString(), anyString(), anyString());
    }
}
