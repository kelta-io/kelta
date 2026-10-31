package io.kelta.worker.module;

import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.ModuleManifestParser;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.service.S3StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Pins external modules' admin UI bundles ({@code specs/module-platform/9-external-modules.md}
 * §3.9). The bundle runs same-origin in admin sessions, so it is never proxied live: the planner
 * fetches it from the module and checks it against the manifest's {@code ui.sha256}, install and
 * upgrade store exactly those bytes in object storage, and {@code GET /api/modules/{id}/ui-bundle.js}
 * serves them from the Kelta origin after checking the hash again. A compromised or replaced module
 * service cannot change what an admin approved; a new bundle needs an upgrade, and the upgrade plan
 * shows the hash change.
 *
 * <p>Object storage is required: without it ({@code KELTA_S3_ENABLED=false}) a module that ships a
 * UI cannot be installed (the plan carries a {@code UI_BUNDLE_STORAGE_UNAVAILABLE} blocker), and a
 * module without one is unaffected.
 */
public class ExternalModuleUiBundles {

    private static final Logger log = LoggerFactory.getLogger(ExternalModuleUiBundles.class);

    static final String CONTENT_TYPE = "application/javascript";

    /** A fetched bundle and the SHA-256 (lowercase hex) of its bytes. */
    public record Fetched(byte[] bytes, String sha256) {
        public Fetched {
            Objects.requireNonNull(bytes);
            Objects.requireNonNull(sha256);
        }
    }

    private final ModuleHttpClient http;
    private final S3StorageService storage;
    private final ModuleManifestParser parser;

    /** {@code storage} may be null or disabled; see {@link #storageAvailable()}. */
    public ExternalModuleUiBundles(ModuleHttpClient http, S3StorageService storage, ObjectMapper objectMapper) {
        this.http = Objects.requireNonNull(http);
        this.storage = storage;
        this.parser = new ModuleManifestParser(objectMapper);
    }

    /** True when bundles can be pinned and served. */
    public boolean storageAvailable() {
        return storage != null && storage.isEnabled();
    }

    /**
     * Fetches the bundle the manifest names and hashes it; the caller compares the hash.
     *
     * @throws ModuleHttpClient.ModuleCallException when the module does not serve it
     */
    public Fetched fetch(String baseUrl, ExternalModuleManifest.Ui ui) {
        byte[] bytes = http.fetchUiBundle(baseUrl, ui.bundlePath());
        return new Fetched(bytes, ModuleJarService.sha256(bytes));
    }

    /** {@code modules/{tenantId}/{moduleId}/{version}/ui-bundle.{sha256}.js}. */
    static String key(String tenantId, String moduleId, String version, String sha256) {
        return "modules/" + tenantId + "/" + moduleId + "/" + version + "/ui-bundle." + sha256 + ".js";
    }

    /**
     * Stores {@code bundle} under {@code key}.
     *
     * @throws IllegalStateException when object storage is unavailable
     */
    void store(String key, Fetched bundle) {
        if (!storageAvailable()) {
            throw new IllegalStateException("object storage is not enabled (KELTA_S3_ENABLED), so the UI bundle "
                    + "cannot be pinned");
        }
        storage.uploadObject(key, bundle.bytes(), CONTENT_TYPE);
    }

    /**
     * The pinned bundle of {@code module}, read back from storage and checked against the pinned
     * hash; empty when none is pinned or storage is off. A stored object whose hash no longer
     * matches is refused (logged), never served.
     */
    public Optional<byte[]> read(TenantModuleData module) {
        if (module.uiBundleKey() == null || module.uiBundleSha256() == null || !storageAvailable()) {
            return Optional.empty();
        }
        byte[] bytes;
        try (S3StorageService.StorageObject object = storage.streamObject(module.uiBundleKey())) {
            bytes = object.content().readAllBytes();
        } catch (IOException | RuntimeException e) {
            log.warn("Pinned UI bundle of module '{}' (tenant {}) could not be read from {}: {}", module.moduleId(),
                    module.tenantId(), module.uiBundleKey(), e.getMessage());
            return Optional.empty();
        }
        String sha256 = ModuleJarService.sha256(bytes);
        if (!sha256.equals(module.uiBundleSha256())) {
            log.error("Pinned UI bundle of module '{}' (tenant {}) at {} has SHA-256 {}, but {} was pinned; "
                    + "not serving it", module.moduleId(), module.tenantId(), module.uiBundleKey(), sha256,
                    module.uiBundleSha256());
            return Optional.empty();
        }
        return Optional.of(bytes);
    }

    /** The {@code ui.bundlePath} of {@code module}'s stored manifest, or {@code null}. */
    public String bundlePath(TenantModuleData module) {
        try {
            ExternalModuleManifest.Ui ui = parser.parseExternal(module.manifest()).ui();
            return ui == null ? null : ui.bundlePath();
        } catch (RuntimeException e) {
            log.debug("External module '{}' has an unreadable manifest: {}", module.moduleId(), e.getMessage());
            return null;
        }
    }

    /** Deletes a no-longer-pinned bundle; a failure only leaves an unreferenced object behind. */
    void deleteQuietly(String key) {
        if (key == null || !storageAvailable()) {
            return;
        }
        try {
            storage.deleteObject(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete the unpinned UI bundle {}: {}", key, e.getMessage());
        }
    }
}
