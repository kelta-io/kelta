package io.kelta.worker.service;

import io.kelta.worker.config.S3ConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private PresignedGetObjectRequest presignedGetObjectRequest;

    @Mock
    private PresignedPutObjectRequest presignedPutObjectRequest;

    private S3ConfigProperties config;
    private S3StorageService service;

    @BeforeEach
    void setUp() {
        config = new S3ConfigProperties();
        config.setEnabled(true);
        config.setEndpoint("https://s3.example.com");
        config.setPublicEndpoint("https://s3-public.example.com");
        config.setRegion("us-east-1");
        config.setBucket("test-bucket");
        config.setAccessKey("test-access-key");
        config.setSecretKey("test-secret-key");
        config.setPresignedUrlExpiryMinutes(15);

        // Create service with test config, then inject mock presigner
        service = new S3StorageService(config);
        service.setS3Presigner(s3Presigner);
    }

    @Test
    void getPresignedDownloadUrl_generatesUrlWithCorrectParameters() throws Exception {
        String storageKey = "tenant-1/col-1/rec-1/uuid/report.pdf";
        Duration expiry = Duration.ofMinutes(15);
        URL expectedUrl = URI.create("https://s3-public.example.com/test-bucket/tenant-1/col-1/rec-1/uuid/report.pdf?presigned=true").toURL();

        when(presignedGetObjectRequest.url()).thenReturn(expectedUrl);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenReturn(presignedGetObjectRequest);

        String result = service.getPresignedDownloadUrl(storageKey, expiry);

        assertNotNull(result);
        assertEquals(expectedUrl.toString(), result);

        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner).presignGetObject(captor.capture());

        GetObjectPresignRequest capturedRequest = captor.getValue();
        assertEquals(expiry, capturedRequest.signatureDuration());
        assertEquals("test-bucket", capturedRequest.getObjectRequest().bucket());
        assertEquals(storageKey, capturedRequest.getObjectRequest().key());
    }

    @Test
    void getDefaultExpiry_returnsConfiguredDuration() {
        Duration expiry = service.getDefaultExpiry();
        assertEquals(Duration.ofMinutes(15), expiry);
    }

    @Test
    void getDefaultExpiry_reflectsConfigChange() {
        config.setPresignedUrlExpiryMinutes(30);
        assertEquals(Duration.ofMinutes(30), service.getDefaultExpiry());
    }

    @Test
    void getPresignedUploadUrl_generatesUrlWithCorrectParameters() throws Exception {
        String storageKey = "tenant-1/col-1/rec-1/uuid/photo.jpg";
        String contentType = "image/jpeg";
        Duration expiry = Duration.ofMinutes(15);
        URL expectedUrl = URI.create("https://s3-public.example.com/test-bucket/tenant-1/col-1/rec-1/uuid/photo.jpg?presigned=true").toURL();

        when(presignedPutObjectRequest.url()).thenReturn(expectedUrl);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(presignedPutObjectRequest);

        String result = service.getPresignedUploadUrl(storageKey, contentType, expiry);

        assertNotNull(result);
        assertEquals(expectedUrl.toString(), result);

        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(s3Presigner).presignPutObject(captor.capture());

        PutObjectPresignRequest capturedRequest = captor.getValue();
        assertEquals(expiry, capturedRequest.signatureDuration());
        assertEquals("test-bucket", capturedRequest.putObjectRequest().bucket());
        assertEquals(storageKey, capturedRequest.putObjectRequest().key());
        assertEquals(contentType, capturedRequest.putObjectRequest().contentType());
    }

    @Test
    void streamObjectRange_carriesTheStoresContentRange() throws Exception {
        S3Client s3Client = org.mockito.Mockito.mock(S3Client.class);
        service.setS3Client(s3Client);
        GetObjectResponse metadata = GetObjectResponse.builder()
                .contentType("text/plain")
                .contentLength(10L)
                .contentRange("bytes 0-9/1234")
                .build();
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                metadata, AbortableInputStream.create(new ByteArrayInputStream(new byte[10]))));

        try (S3StorageService.StorageObject object = service.streamObjectRange("t/c/r/file.txt", "bytes=0-9")) {
            assertEquals("bytes 0-9/1234", object.contentRange());
            assertEquals(10L, object.contentLength());
        }

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(captor.capture());
        assertEquals("bytes=0-9", captor.getValue().range());
    }

    @Test
    void getMaxFileSize_returnsConfiguredValue() {
        config.setMaxFileSize(100_000_000L);
        assertEquals(100_000_000L, service.getMaxFileSize());
    }
}
