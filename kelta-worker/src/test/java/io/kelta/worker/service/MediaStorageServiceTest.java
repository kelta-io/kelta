package io.kelta.worker.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MediaStorageService")
class MediaStorageServiceTest {

    private static final String INTERNAL = "http://object-store.internal:3900";
    private static final String PUBLIC = "https://media.example.com";

    private static MediaStorageService service(boolean enabled, String endpoint, String publicEndpoint) {
        return new MediaStorageService(enabled, endpoint, publicEndpoint, "garage", "media-bucket",
                "test-access-key", "test-secret-key", 15_728_640L, 15);
    }

    @Test
    @DisplayName("disabled by default: reports not enabled and refuses to presign")
    void disabled() {
        MediaStorageService service = service(false, "", "");

        assertThat(service.isEnabled()).isFalse();
        assertThatThrownBy(() -> service.getPresignedUploadUrl("key", "image/jpeg"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.getPresignedDownloadUrl("key"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("enabled with no endpoint at all stays disabled rather than failing boot")
    void enabledWithoutEndpoint() {
        assertThat(service(true, "", "").isEnabled()).isFalse();
    }

    @Test
    @DisplayName("presigns an upload locally against the public endpoint, no network call")
    void presignsUpload() {
        // Presigning is a local HMAC computation, so a well-formed fake endpoint is enough.
        MediaStorageService service = service(true, INTERNAL, PUBLIC);

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.getMaxFileSize()).isEqualTo(15_728_640L);
        assertThat(service.getDefaultExpiry()).isEqualTo(Duration.ofMinutes(15));

        String url = service.getPresignedUploadUrl("tenant-1/listings/abc/photo.jpg", "image/jpeg");
        assertThat(url).startsWith(PUBLIC + "/media-bucket/");
        assertThat(url).containsAnyOf("tenant-1/listings/abc/photo.jpg", "tenant-1%2Flistings%2Fabc%2Fphoto.jpg");
        assertThat(url).contains("X-Amz-Signature");
    }

    @Test
    @DisplayName("presigns a download locally")
    void presignsDownload() {
        String url = service(true, INTERNAL, PUBLIC).getPresignedDownloadUrl("tenant-1/listings/abc/photo.jpg");

        assertThat(url).startsWith(PUBLIC + "/media-bucket/");
        assertThat(url).contains("X-Amz-Signature");
    }

    @Test
    @DisplayName("falls back to the internal endpoint when no public endpoint is set")
    void internalFallback() {
        assertThat(service(true, INTERNAL, "").getPresignedUploadUrl("k", "image/png")).startsWith(INTERNAL + "/");
    }
}
