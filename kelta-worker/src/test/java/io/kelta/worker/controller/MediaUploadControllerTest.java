package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldDefinitionBuilder;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.interceptor.CerbosRecordAuthorizationAdvice;
import io.kelta.worker.service.MediaStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MediaUploadController")
class MediaUploadControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String COLLECTION = "listings";
    private static final String OBJECT_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String KEY = TENANT + "/" + COLLECTION + "/" + OBJECT_ID + "/photo.jpg";

    @Mock private CollectionRegistry registry;
    @Mock private QueryEngine queryEngine;
    @Mock private MediaStorageService storage;
    @Mock private CerbosRecordAuthorizationAdvice recordAuthorization;

    private MediaUploadController controller;

    @BeforeEach
    void setUp() {
        controller = new MediaUploadController(registry, queryEngine, storage, recordAuthorization);
    }

    private static FieldDefinition mediaField(String name, Map<String, Object> extra) {
        Map<String, Object> media = new LinkedHashMap<>(extra);
        media.put("enabled", true);
        return new FieldDefinitionBuilder().name(name).type(FieldType.STRING).nullable(true)
                .fieldTypeConfig(Map.of("media", media)).build();
    }

    private static CollectionDefinition collection(FieldDefinition... fields) {
        var builder = CollectionDefinition.builder().name(COLLECTION).addField(FieldDefinition.string("title"));
        for (FieldDefinition f : fields) {
            builder.addField(f);
        }
        return builder.build();
    }

    private static <T> T asTenant(Supplier<T> call) {
        return TenantContext.callWithTenant(TENANT, call::get);
    }

    private static Map<String, Object> body(String fileName, String contentType, long size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileName", fileName);
        body.put("contentType", contentType);
        body.put("fileSize", size);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) ((Map<String, Object>) response.getBody().get("data")).get("attributes");
    }

    @Nested
    @DisplayName("POST /api/{collection}/upload-url")
    class Upload {

        private ResponseEntity<Map<String, Object>> upload(Map<String, Object> body) {
            return asTenant(() -> controller.requestUploadUrl(COLLECTION, "user@example.com", body));
        }

        private void enabledStorage() {
            lenient().when(storage.isEnabled()).thenReturn(true);
            lenient().when(storage.getMaxFileSize()).thenReturn(15_728_640L);
            lenient().when(storage.getDefaultExpiry()).thenReturn(Duration.ofMinutes(15));
            lenient().when(storage.getPresignedUploadUrl(anyString(), anyString()))
                    .thenReturn("https://media.example.com/signed-put");
        }

        @Test
        @DisplayName("mints a tenant- and collection-prefixed key and a presigned PUT")
        void success() {
            when(registry.get(COLLECTION)).thenReturn(collection(mediaField("photoKey", Map.of())));
            enabledStorage();

            ResponseEntity<Map<String, Object>> response = upload(body("cabin view.jpg", "image/jpeg", 1024));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> attrs = attributes(response);
            String key = (String) attrs.get("storageKey");
            assertThat(key).matches(TENANT + "/" + COLLECTION + "/[0-9a-f-]{36}/cabin_view\\.jpg");
            assertThat(attrs).containsEntry("uploadUrl", "https://media.example.com/signed-put")
                    .containsEntry("field", "photoKey")
                    .containsEntry("method", "PUT")
                    .containsEntry("downloadPath", "/api/" + COLLECTION + "/download/" + key)
                    .containsEntry("headers", Map.of("Content-Type", "image/jpeg"))
                    .containsEntry("expiresInSeconds", 900L);
            verify(storage).getPresignedUploadUrl(key, "image/jpeg");
        }

        @Test
        @DisplayName("a crafted file name cannot add key segments")
        void sanitizesFileName() {
            when(registry.get(COLLECTION)).thenReturn(collection(mediaField("photoKey", Map.of())));
            enabledStorage();

            String key = (String) attributes(upload(body("../../etc/passwd.jpg", "image/jpeg", 10)))
                    .get("storageKey");

            String[] segments = key.split("/");
            assertThat(segments).hasSize(4);
            assertThat(segments[0]).isEqualTo(TENANT);
            assertThat(segments[1]).isEqualTo(COLLECTION);
            assertThat(segments[3]).doesNotContain("/").isNotEqualTo("..");
        }

        @Test
        @DisplayName("unknown collection, collection without a media field, or a system collection: 404")
        void notAMediaCollection() {
            assertThat(upload(body("a.jpg", "image/jpeg", 10)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            when(registry.get(COLLECTION)).thenReturn(collection());
            assertThat(upload(body("a.jpg", "image/jpeg", 10)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            CollectionDefinition system = CollectionDefinition.builder().name(COLLECTION)
                    .addField(mediaField("photoKey", Map.of())).systemCollection(true).build();
            when(registry.get(COLLECTION)).thenReturn(system);
            assertThat(upload(body("a.jpg", "image/jpeg", 10)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            verifyNoInteractions(storage);
        }

        @Test
        @DisplayName("no tenant bound: 404 without touching the registry")
        void noTenant() {
            ResponseEntity<Map<String, Object>> response =
                    controller.requestUploadUrl(COLLECTION, "user@example.com", body("a.jpg", "image/jpeg", 10));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verifyNoInteractions(registry, storage);
        }

        @Test
        @DisplayName("media storage disabled: 503")
        void storageDisabled() {
            when(registry.get(COLLECTION)).thenReturn(collection(mediaField("photoKey", Map.of())));
            when(storage.isEnabled()).thenReturn(false);

            assertThat(upload(body("a.jpg", "image/jpeg", 10)).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("several media fields: 'field' is required and must name one of them")
        void fieldSelection() {
            when(registry.get(COLLECTION)).thenReturn(collection(
                    mediaField("cover", Map.of()), mediaField("floorPlan", Map.of())));
            enabledStorage();

            assertThat(upload(body("a.jpg", "image/jpeg", 10)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            Map<String, Object> unknown = body("a.jpg", "image/jpeg", 10);
            unknown.put("field", "title");
            assertThat(upload(unknown).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            Map<String, Object> named = body("a.jpg", "image/jpeg", 10);
            named.put("field", "floorPlan");
            ResponseEntity<Map<String, Object>> response = upload(named);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(attributes(response)).containsEntry("field", "floorPlan");
        }

        @Test
        @DisplayName("missing metadata, a rejected content type, or an out-of-range size: 400")
        void validation() {
            when(registry.get(COLLECTION)).thenReturn(collection(mediaField("photoKey", Map.of("maxFileSize", 1000))));
            enabledStorage();

            assertThat(upload(Map.of("fileName", "a.jpg")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(upload(body("a.pdf", "application/pdf", 10)).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(upload(body("a.svg", "image/svg+xml", 10)).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(upload(body("a.exe", "application/x-executable", 10)).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(upload(body("a.jpg", "image/jpeg", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            // The field's own 1000-byte limit, below the platform's 15 MB.
            assertThat(upload(body("a.jpg", "image/jpeg", 1001)).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(upload(body("a.jpg", "image/jpeg", 1000)).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("a presigner failure is a 500 that does not echo the exception")
        void presignFailure() {
            when(registry.get(COLLECTION)).thenReturn(collection(mediaField("photoKey", Map.of())));
            enabledStorage();
            when(storage.getPresignedUploadUrl(anyString(), anyString()))
                    .thenThrow(new RuntimeException("secret-internal-detail"));

            ResponseEntity<Map<String, Object>> response = upload(body("a.jpg", "image/jpeg", 10));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().toString()).doesNotContain("secret-internal-detail");
        }
    }

    @Nested
    @DisplayName("GET /api/{collection}/download/{storageKey}")
    class Download {

        private final HttpServletRequest request = new MockHttpServletRequest();

        private ResponseEntity<Void> download(String collection, String keyTenant, String keyCollection,
                                              String objectId, String fileName) {
            return asTenant(() -> controller.redirectToDownloadUrl(
                    collection, keyTenant, keyCollection, objectId, fileName, request));
        }

        private ResponseEntity<Void> download() {
            return download(COLLECTION, TENANT, COLLECTION, OBJECT_ID, "photo.jpg");
        }

        @SafeVarargs
        private static QueryResult rows(Map<String, Object>... rows) {
            return QueryResult.of(List.of(rows), rows.length, new Pagination(1, 1));
        }

        @Test
        @DisplayName("a readable record references the key: 302 to a presigned GET, privately cached")
        void success() {
            CollectionDefinition definition = collection(mediaField("photoKey", Map.of()));
            when(registry.get(COLLECTION)).thenReturn(definition);
            when(storage.isEnabled()).thenReturn(true);
            Map<String, Object> record = Map.of("id", "rec-1", "photoKey", KEY);
            when(queryEngine.executeQuery(eq(definition), any())).thenReturn(rows(record));
            when(recordAuthorization.isRecordReadable(request, COLLECTION, record)).thenReturn(true);
            when(storage.getPresignedDownloadUrl(KEY)).thenReturn("https://media.example.com/signed-get");

            ResponseEntity<Void> response = download();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                    .isEqualTo("https://media.example.com/signed-get");
            assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, max-age=60");

            ArgumentCaptor<QueryRequest> query = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryEngine).executeQuery(eq(definition), query.capture());
            assertThat(query.getValue().filters())
                    .containsExactly(new FilterCondition("photoKey", FilterOperator.EQ, KEY));
        }

        @Test
        @DisplayName("checks every media field until one references the key")
        void secondField() {
            CollectionDefinition definition = collection(mediaField("cover", Map.of()), mediaField("plan", Map.of()));
            when(registry.get(COLLECTION)).thenReturn(definition);
            when(storage.isEnabled()).thenReturn(true);
            Map<String, Object> record = Map.of("id", "rec-1", "plan", KEY);
            when(queryEngine.executeQuery(eq(definition), any())).thenReturn(rows(), rows(record));
            when(recordAuthorization.isRecordReadable(request, COLLECTION, record)).thenReturn(true);
            when(storage.getPresignedDownloadUrl(KEY)).thenReturn("https://media.example.com/signed-get");

            assertThat(download().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        }

        @Test
        @DisplayName("another tenant's key, or one minted for another collection: 404 without a lookup")
        void foreignKey() {
            assertThat(download(COLLECTION, "tenant-2", COLLECTION, OBJECT_ID, "photo.jpg").getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(download(COLLECTION, TENANT, "private-docs", OBJECT_ID, "photo.jpg").getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            verifyNoInteractions(registry, queryEngine, storage, recordAuthorization);
        }

        @Test
        @DisplayName("a malformed object id or file name: 400")
        void malformed() {
            assertThat(download(COLLECTION, TENANT, COLLECTION, "not-a-uuid", "photo.jpg").getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(download(COLLECTION, TENANT, COLLECTION, OBJECT_ID, "..").getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(download(COLLECTION, TENANT, COLLECTION, OBJECT_ID, "a%2Fb.jpg").getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(registry, storage);
        }

        @Test
        @DisplayName("no record references the key (an orphan upload, or a guessed key): 404")
        void unreferenced() {
            CollectionDefinition definition = collection(mediaField("photoKey", Map.of()));
            when(registry.get(COLLECTION)).thenReturn(definition);
            when(storage.isEnabled()).thenReturn(true);
            when(queryEngine.executeQuery(eq(definition), any())).thenReturn(rows());

            assertThat(download().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(storage, never()).getPresignedDownloadUrl(anyString());
        }

        @Test
        @DisplayName("the referencing record is not readable by the caller: 404")
        void unreadable() {
            CollectionDefinition definition = collection(mediaField("photoKey", Map.of()));
            when(registry.get(COLLECTION)).thenReturn(definition);
            when(storage.isEnabled()).thenReturn(true);
            Map<String, Object> record = Map.of("id", "rec-1", "photoKey", KEY);
            when(queryEngine.executeQuery(eq(definition), any())).thenReturn(rows(record));
            when(recordAuthorization.isRecordReadable(request, COLLECTION, record)).thenReturn(false);

            assertThat(download().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(storage, never()).getPresignedDownloadUrl(anyString());
        }

        @Test
        @DisplayName("a collection without a media field: 404; storage disabled: 503")
        void notMediaOrDisabled() {
            when(registry.get(COLLECTION)).thenReturn(collection());
            assertThat(download().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            when(registry.get(COLLECTION)).thenReturn(collection(mediaField("photoKey", Map.of())));
            when(storage.isEnabled()).thenReturn(false);
            assertThat(download().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            verifyNoInteractions(queryEngine);
        }

        @Test
        @DisplayName("a presigner failure: 500")
        void presignFailure() {
            CollectionDefinition definition = collection(mediaField("photoKey", Map.of()));
            when(registry.get(COLLECTION)).thenReturn(definition);
            when(storage.isEnabled()).thenReturn(true);
            Map<String, Object> record = Map.of("id", "rec-1", "photoKey", KEY);
            when(queryEngine.executeQuery(eq(definition), any())).thenReturn(rows(record));
            when(recordAuthorization.isRecordReadable(request, COLLECTION, record)).thenReturn(true);
            when(storage.getPresignedDownloadUrl(KEY)).thenThrow(new RuntimeException("boom"));

            assertThat(download().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Test
    @DisplayName("sanitizeFileName keeps safe characters and never yields a dot segment")
    void sanitize() {
        assertThat(MediaUploadController.sanitizeFileName("my photo (1).JPG")).isEqualTo("my_photo__1_.JPG");
        assertThat(MediaUploadController.sanitizeFileName("..")).isEqualTo("upload");
        assertThat(MediaUploadController.sanitizeFileName(" ")).isEqualTo("upload");
        assertThat(MediaUploadController.sanitizeFileName("a".repeat(300) + ".jpg")).hasSize(255).endsWith(".jpg");
    }
}
