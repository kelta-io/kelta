package io.kelta.worker.controller;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldDefinitionBuilder;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.filter.TenantContextFilter;
import io.kelta.worker.interceptor.CerbosRecordAuthorizationAdvice;
import io.kelta.worker.repository.GovernorLimitsRepository;
import io.kelta.worker.service.MediaStorageService;
import io.kelta.worker.service.S3StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MediaUploadController} maps {@code /api/{collection}/upload-url} and
 * {@code /api/{collection}/download/<4-segment key>} beside {@code DynamicCollectionRouter}'s
 * all-variable mappings and {@code AttachmentUploadController}'s literal
 * {@code /api/attachments/upload-url}. A controller tested alone cannot see a competing mapping
 * win (concerns.md → Fragile Areas), so the competitors are registered here, shape for shape.
 */
@DisplayName("Media upload route mappings vs the generic collection router")
class MediaUploadRouteMappingTest {

    private static final String TENANT = "tenant-1";
    private static final String KEY = TENANT + "/listings/0f8fad5b-d9cb-469f-a165-70867728950e/photo.jpg";

    /** Stands in for {@code DynamicCollectionRouter}'s mappings, shape for shape. */
    @RestController
    @RequestMapping("/api")
    static class RouterStub {
        @GetMapping("/{collectionName}")
        String list() {
            return "router";
        }

        @GetMapping("/{collectionName}/{id}")
        String get() {
            return "router";
        }

        @PostMapping("/{collectionName}")
        String create() {
            return "router";
        }

        @PatchMapping("/{collectionName}/{id}")
        String patch() {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}")
        String listChildren() {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}/{childId}")
        String getChild() {
            return "router";
        }

        @PostMapping("/{parentName}/{parentId}/{childName}")
        String createChild() {
            return "router";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CollectionRegistry registry = mock(CollectionRegistry.class);
        QueryEngine queryEngine = mock(QueryEngine.class);
        MediaStorageService storage = mock(MediaStorageService.class);
        CerbosRecordAuthorizationAdvice recordAuthorization = mock(CerbosRecordAuthorizationAdvice.class);

        FieldDefinition photo = new FieldDefinitionBuilder().name("photoKey").type(FieldType.STRING)
                .nullable(true).fieldTypeConfig(Map.of("media", Map.of("enabled", true))).build();
        CollectionDefinition listings = CollectionDefinition.builder().name("listings").addField(photo).build();
        when(registry.get("listings")).thenReturn(listings);
        when(storage.isEnabled()).thenReturn(true);
        when(storage.getMaxFileSize()).thenReturn(15_728_640L);
        when(storage.getDefaultExpiry()).thenReturn(Duration.ofMinutes(15));
        when(storage.getPresignedUploadUrl(anyString(), anyString())).thenReturn("https://media.example.com/put");
        when(storage.getPresignedDownloadUrl(KEY)).thenReturn("https://media.example.com/get");
        Map<String, Object> record = Map.of("id", "rec-1", "photoKey", KEY);
        when(queryEngine.executeQuery(eq(listings), any()))
                .thenReturn(QueryResult.of(List.of(record), 1, new Pagination(1, 1)));
        when(recordAuthorization.isRecordReadable(any(), eq("listings"), eq(record))).thenReturn(true);

        // Attachments with storage off answers its own distinctive 503.
        S3StorageService attachmentsStorage = mock(S3StorageService.class);
        AttachmentUploadController attachments = new AttachmentUploadController(
                attachmentsStorage, mock(GovernorLimitsRepository.class), mock(JdbcTemplate.class));

        mvc = MockMvcBuilders
                .standaloneSetup(new MediaUploadController(registry, queryEngine, storage, recordAuthorization),
                        attachments, new RouterStub())
                .addFilters(new TenantContextFilter())
                .build();
    }

    @Test
    @DisplayName("POST /api/{collection}/upload-url reaches the media controller")
    void uploadUrlReachesMediaController() throws Exception {
        mvc.perform(post("/api/listings/upload-url")
                        .header("X-Tenant-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"photo.jpg\",\"contentType\":\"image/jpeg\",\"fileSize\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value(MediaUploadController.UPLOAD_URL_TYPE))
                .andExpect(jsonPath("$.data.attributes.storageKey", startsWith(TENANT + "/listings/")));
    }

    @Test
    @DisplayName("POST /api/attachments/upload-url still reaches AttachmentUploadController")
    void attachmentsUploadUrlUnchanged() throws Exception {
        mvc.perform(post("/api/attachments/upload-url")
                        .header("X-Tenant-ID", TENANT)
                        .header("X-User-Email", "user@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errors[0].detail", containsString("S3 storage")));
    }

    @Test
    @DisplayName("GET /api/{collection}/download/<key> reaches the media controller and redirects")
    void downloadReachesMediaController() throws Exception {
        mvc.perform(get("/api/listings/download/" + KEY).header("X-Tenant-ID", TENANT))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://media.example.com/get"));
    }
}
