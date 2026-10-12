package io.kelta.worker.controller;

import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.interceptor.CerbosRecordAuthorizationAdvice;
import io.kelta.worker.service.MediaStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The media controller and its storage service must come up from configuration alone: the
 * service is {@code @Value}-bound with defaults for every key (a native image freezes conditions
 * at build time, so nothing here is conditional), and the controller's dependencies are all
 * beans the worker already has. A missing bean only surfaces at worker boot, which no unit test
 * starts.
 */
@DisplayName("MediaUploadController wiring")
class MediaUploadControllerWiringTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withBean(MediaStorageService.class)
            .withBean(MediaUploadController.class)
            .withBean(CollectionRegistry.class, () -> mock(CollectionRegistry.class))
            .withBean(QueryEngine.class, () -> mock(QueryEngine.class))
            .withBean(CerbosRecordAuthorizationAdvice.class, () -> mock(CerbosRecordAuthorizationAdvice.class));

    @Test
    @DisplayName("registers with no media configuration at all, storage disabled")
    void registersUnconfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MediaUploadController.class);
            assertThat(context.getBean(MediaStorageService.class).isEnabled()).isFalse();
        });
    }

    @Test
    @DisplayName("enables storage from kelta.storage.media.* properties")
    void enabledFromProperties() {
        runner.withPropertyValues(
                        "kelta.storage.media.enabled=true",
                        "kelta.storage.media.endpoint=http://object-store.internal:3900",
                        "kelta.storage.media.bucket=media-bucket",
                        "kelta.storage.media.access-key=test",
                        "kelta.storage.media.secret-key=test",
                        "kelta.storage.media.max-file-size=1000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MediaStorageService storage = context.getBean(MediaStorageService.class);
                    assertThat(storage.isEnabled()).isTrue();
                    assertThat(storage.getMaxFileSize()).isEqualTo(1000L);
                    assertThat(storage.getPresignedDownloadUrl("t/c/u/a.jpg"))
                            .startsWith("http://object-store.internal:3900/media-bucket/");
                });
    }
}
