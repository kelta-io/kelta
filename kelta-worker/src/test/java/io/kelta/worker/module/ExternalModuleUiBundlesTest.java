package io.kelta.worker.module;

import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.service.S3StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ExternalModuleUiBundles")
class ExternalModuleUiBundlesTest {

    private static final byte[] BUNDLE = "console.log('ui')".getBytes(StandardCharsets.UTF_8);
    private static final String SHA = ModuleJarService.sha256(BUNDLE);
    private static final String KEY = "modules/tenant-1/stub/1.0.0/ui-bundle." + SHA + ".js";

    private final ModuleHttpClient http = mock(ModuleHttpClient.class);
    private final S3StorageService storage = mock(S3StorageService.class);
    private ExternalModuleUiBundles bundles;

    @BeforeEach
    void setUp() {
        when(storage.isEnabled()).thenReturn(true);
        bundles = new ExternalModuleUiBundles(http, storage, new ObjectMapper());
    }

    private static TenantModuleData pinned(String sha256, String key) {
        TenantModuleData base = ExternalModuleFixtures.row(TenantModuleData.STATUS_ACTIVE);
        return new TenantModuleData(base.id(), base.tenantId(), base.moduleId(), base.name(), base.version(), null,
                base.sourceUrl(), null, null, null,
                "{\"manifestVersion\":2,\"protocolVersion\":1,\"id\":\"stub\",\"name\":\"Stub\",\"version\":\"1.0.0\","
                        + "\"ui\":{\"bundlePath\":\"/ui/bundle.js\",\"sha256\":\"" + SHA + "\"}}",
                base.status(), "admin", Instant.EPOCH, Instant.EPOCH, null, List.of(), base.baseUrl(), 1, "app-1",
                "svc-1", sha256, key);
    }

    private void stored(byte[] bytes) {
        when(storage.streamObject(KEY)).thenAnswer(inv -> new S3StorageService.StorageObject(
                new ByteArrayInputStream(bytes), "application/javascript", bytes.length, "bundle.js"));
    }

    @Test
    @DisplayName("fetch hashes exactly the bytes the module served")
    void fetchHashes() {
        when(http.fetchUiBundle("https://stub.example.test", "/ui/bundle.js")).thenReturn(BUNDLE);

        ExternalModuleUiBundles.Fetched fetched = bundles.fetch("https://stub.example.test",
                new ExternalModuleManifest.Ui("/ui/bundle.js", SHA));

        assertThat(fetched.sha256()).isEqualTo(SHA);
        assertThat(fetched.bytes()).isEqualTo(BUNDLE);
    }

    @Test
    @DisplayName("read serves the stored bundle when its hash is the pinned one")
    void readsThePinnedBundle() {
        stored(BUNDLE);

        assertThat(bundles.read(pinned(SHA, KEY))).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(BUNDLE));
        assertThat(bundles.bundlePath(pinned(SHA, KEY))).isEqualTo("/ui/bundle.js");
    }

    @Test
    @DisplayName("a stored object whose hash is not the pinned one is never served")
    void refusesATamperedObject() {
        stored("alert('swapped')".getBytes(StandardCharsets.UTF_8));

        assertThat(bundles.read(pinned(SHA, KEY))).isEmpty();
    }

    @Test
    @DisplayName("nothing pinned, storage off, or storage failing: nothing served")
    void nothingToServe() {
        assertThat(bundles.read(pinned(null, null))).isEmpty();
        when(storage.streamObject(anyString())).thenThrow(new IllegalStateException("bucket gone"));
        assertThat(bundles.read(pinned(SHA, KEY))).isEmpty();

        when(storage.isEnabled()).thenReturn(false);
        assertThat(bundles.storageAvailable()).isFalse();
        assertThat(bundles.read(pinned(SHA, KEY))).isEmpty();
    }

    @Test
    @DisplayName("store refuses when storage is off; deleteQuietly swallows a failure")
    void storeAndDelete() {
        bundles.store(KEY, new ExternalModuleUiBundles.Fetched(BUNDLE, SHA));
        verify(storage).uploadObject(KEY, BUNDLE, "application/javascript");

        doThrow(new IllegalStateException("bucket gone")).when(storage).deleteObject(KEY);
        bundles.deleteQuietly(KEY);
        bundles.deleteQuietly(null);

        when(storage.isEnabled()).thenReturn(false);
        assertThatThrownBy(() -> bundles.store(KEY, new ExternalModuleUiBundles.Fetched(BUNDLE, SHA)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("KELTA_S3_ENABLED");
        verify(storage, org.mockito.Mockito.times(1)).uploadObject(anyString(), any(), anyString());
    }
}
