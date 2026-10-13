package io.kelta.worker.module;

import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.module.ModuleLoadFailureClassifier.Kind;
import io.kelta.worker.module.testmodule.WebhookTestModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import software.amazon.awssdk.core.exception.SdkClientException;
import tools.jackson.databind.ObjectMapper;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A module load that fails because infrastructure is down must not be persisted as terminal.
 *
 * <p>Reproduces the 2026-10-06 incident: object storage refused connections while pods loaded
 * modules, every failure was treated as the module's fault, every module on every tenant was
 * written {@code QUARANTINED}, and — because startup loads only {@code ACTIVE} rows — they stayed
 * down until the rows were flipped back by hand. The fix keeps the fail-closed behaviour on the pod
 * (refusing handlers) but leaves the status alone and retries with backoff.
 */
@DisplayName("RuntimeModuleManager — transient load failures retry instead of quarantining")
class RuntimeModuleManagerLoadRetryTest {

    private static final String TENANT = "tenant-1";
    private static final String ROW_ID = "mod-row-1";
    private static final String MODULE_ID = "webhook-module";
    private static final String MODULE_CLASS =
            "io.kelta.worker.module.testmodule.WebhookTestModule";
    private static final String MANIFEST_JSON = """
        {
          "id": "webhook-module",
          "name": "Webhook Test Module",
          "version": "1.0.0",
          "moduleClass": "io.kelta.worker.module.testmodule.WebhookTestModule"
        }
        """;
    private static final String S3_KEY = "modules/tenant-1/webhook-module/1.0.0/x.jar";

    @TempDir Path tempDir;

    private ModuleStore moduleStore;
    private ActionHandlerRegistry handlerRegistry;
    private ModuleJarService jarService;
    private SimpleMeterRegistry meterRegistry;
    private MutableClock clock;
    private RuntimeModuleManager manager;
    private URL jarUrl;
    private String checksum;

    @BeforeEach
    void setUp() throws Exception {
        moduleStore = mock(ModuleStore.class);
        handlerRegistry = new ActionHandlerRegistry();
        jarService = mock(ModuleJarService.class);
        meterRegistry = new SimpleMeterRegistry();
        clock = new MutableClock(Instant.parse("2026-10-06T10:00:00Z"));

        Path jar = buildModuleJar();
        jarUrl = jar.toUri().toURL();
        checksum = ModuleJarService.sha256(Files.readAllBytes(jar));

        manager = new RuntimeModuleManager(moduleStore, handlerRegistry, new ObjectMapper(),
                jarService, null, null, null, null);
        manager.setClock(clock);
        manager.setMeterRegistry(meterRegistry);
        manager.setLoadRetryPolicy(new ModuleLoadRetryPolicy(
                Duration.ofSeconds(30), Duration.ofMinutes(10), 4));
    }

    @Test
    @DisplayName("object storage refusing connections fails closed but does not quarantine")
    void storageOutageDoesNotQuarantine() throws Exception {
        when(jarService.downloadJarToCache(S3_KEY)).thenThrow(storageDown());

        manager.loadModule(TENANT, module(checksum, TenantModuleData.STATUS_ACTIVE));

        // Fail closed exactly as quarantine does: the action refuses rather than succeeding or
        // vanishing.
        ActionHandler handler = handlerRegistry.getHandler(TENANT, WebhookTestModule.HANDLER_KEY)
                .orElseThrow();
        assertThatThrownBy(() -> handler.execute(ActionContext.builder().tenantId(TENANT).build()))
                .isInstanceOf(ModuleUnavailableException.class)
                .hasMessageContaining("temporarily unavailable");

        // ...but nothing terminal is written: the error is recorded, the status is untouched.
        verify(moduleStore).recordLoadError(eq(ROW_ID), anyString());
        verify(moduleStore, never()).recordLoadOutcome(anyString(), anyString(), any());
        verify(moduleStore, never()).updateStatus(anyString(), anyString());

        assertThat(manager.podLoadState(TENANT, MODULE_ID))
                .containsEntry("loadStateOnThisPod", "RETRYING")
                .containsEntry("loadFailurePhase", "FETCH")
                .containsEntry("loadRetryAttempts", 1)
                .containsEntry("nextLoadRetryAt", "2026-10-06T10:00:30Z");
        assertThat(manager.pendingLoadRetries()).isEqualTo(1);
        assertThat(meterRegistry.get(RuntimeModuleManager.METRIC_LOAD_FAILURES)
                .tag("kind", "transient").tag("phase", "fetch").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get(RuntimeModuleManager.METRIC_LOAD_RETRY_PENDING)
                .gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("once storage is back the retry loads the module, ACTIVE with the error cleared")
    void transientFailureThenRecovery() throws Exception {
        TenantModuleData row = module(checksum, TenantModuleData.STATUS_ACTIVE);
        when(jarService.downloadJarToCache(S3_KEY)).thenThrow(storageDown()).thenReturn(jarUrl);
        when(moduleStore.findById(ROW_ID)).thenReturn(Optional.of(row));

        manager.loadModule(TENANT, row);

        // Not due yet: nothing is re-attempted before the backoff elapses.
        clock.advance(Duration.ofSeconds(29));
        manager.retryPendingLoads();
        verify(jarService, times(1)).downloadJarToCache(S3_KEY);

        clock.advance(Duration.ofSeconds(1));
        manager.retryPendingLoads();
        verify(jarService, times(2)).downloadJarToCache(S3_KEY);

        // Real handler now, not the refusing one.
        ActionHandler handler = handlerRegistry.getHandler(TENANT, WebhookTestModule.HANDLER_KEY)
                .orElseThrow();
        ActionResult result = handler.execute(ActionContext.builder().tenantId(TENANT)
                .resolvedData(Map.of("headers", Map.of("x-test-signature", "s"))).build());
        assertThat(result.successful()).isTrue();

        // ACTIVE with a null error is what clears last_error.
        verify(moduleStore).recordLoadOutcome(ROW_ID, TenantModuleData.STATUS_ACTIVE, null);
        verify(moduleStore, never()).recordLoadOutcome(eq(ROW_ID),
                eq(TenantModuleData.STATUS_QUARANTINED), any());
        assertThat(manager.isLoaded(TENANT, MODULE_ID)).isTrue();
        assertThat(manager.podLoadState(TENANT, MODULE_ID))
                .containsExactlyEntriesOf(Map.of("loadStateOnThisPod", "LOADED"));
        assertThat(manager.pendingLoadRetries()).isZero();
        assertThat(meterRegistry.get(RuntimeModuleManager.METRIC_LOAD_RECOVERED).counter().count())
                .isEqualTo(1.0);

        // And nothing further is retried.
        clock.advance(Duration.ofHours(1));
        manager.retryPendingLoads();
        verify(jarService, times(2)).downloadJarToCache(S3_KEY);
    }

    @Test
    @DisplayName("repeated failures back off exponentially up to the cap and keep the first-failure time")
    void backoffGrowsAndCaps() throws Exception {
        TenantModuleData row = module(checksum, TenantModuleData.STATUS_ACTIVE);
        when(jarService.downloadJarToCache(S3_KEY)).thenThrow(storageDown());
        when(moduleStore.findById(ROW_ID)).thenReturn(Optional.of(row));
        Instant firstFailure = clock.instant();

        manager.loadModule(TENANT, row);

        List<Duration> expectedWaits = List.of(Duration.ofSeconds(30), Duration.ofSeconds(60),
                Duration.ofSeconds(120), Duration.ofSeconds(240), Duration.ofSeconds(480),
                Duration.ofMinutes(10), Duration.ofMinutes(10));
        for (int i = 0; i < expectedWaits.size(); i++) {
            var failure = manager.loadFailure(TENANT, MODULE_ID);
            assertThat(failure.attempts()).isEqualTo(i + 1);
            assertThat(failure.kind()).isEqualTo(Kind.TRANSIENT);
            assertThat(failure.firstFailedAt()).isEqualTo(firstFailure);
            assertThat(Duration.between(clock.instant(), failure.nextAttemptAt()))
                    .isEqualTo(expectedWaits.get(i));
            clock.advance(expectedWaits.get(i));
            manager.retryPendingLoads();
        }

        // Escalated once the policy's threshold (4) was crossed; still retrying.
        assertThat(manager.loadFailure(TENANT, MODULE_ID).escalated()).isTrue();
        assertThat(manager.podLoadState(TENANT, MODULE_ID))
                .containsEntry("loadStateOnThisPod", "RETRYING");
        verify(moduleStore, never()).recordLoadOutcome(eq(ROW_ID),
                eq(TenantModuleData.STATUS_QUARANTINED), any());
        // The refusing handler is still in place after every retry.
        assertThat(handlerRegistry.getHandler(TENANT, WebhookTestModule.HANDLER_KEY)).isPresent();
    }

    @Test
    @DisplayName("a checksum mismatch is still QUARANTINED and is never retried")
    void permanentFailureStillQuarantines() throws Exception {
        when(jarService.downloadJarToCache(S3_KEY)).thenReturn(jarUrl);

        manager.loadModule(TENANT, module("not-the-checksum", TenantModuleData.STATUS_ACTIVE));

        verify(moduleStore).recordLoadOutcome(eq(ROW_ID),
                eq(TenantModuleData.STATUS_QUARANTINED), any());
        verify(moduleStore, never()).recordLoadError(anyString(), anyString());
        assertThat(manager.podLoadState(TENANT, MODULE_ID))
                .containsEntry("loadStateOnThisPod", "FAILED")
                .containsEntry("loadFailureKind", "PERMANENT")
                .containsEntry("loadFailurePhase", "VERIFY");
        assertThat(manager.pendingLoadRetries()).isZero();
        assertThat(meterRegistry.get(RuntimeModuleManager.METRIC_LOAD_FAILURES)
                .tag("kind", "permanent").tag("phase", "verify").counter().count()).isEqualTo(1.0);

        clock.advance(Duration.ofHours(1));
        manager.retryPendingLoads();
        verify(jarService, times(1)).downloadJarToCache(S3_KEY);
        verify(moduleStore, never()).findById(anyString());
    }

    @Test
    @DisplayName("a JAR whose object is gone from storage quarantines — retrying cannot bring it back")
    void missingObjectQuarantines() throws Exception {
        when(jarService.downloadJarToCache(S3_KEY)).thenThrow(
                software.amazon.awssdk.services.s3.model.NoSuchKeyException.builder()
                        .message("The specified key does not exist").build());

        manager.loadModule(TENANT, module(checksum, TenantModuleData.STATUS_ACTIVE));

        verify(moduleStore).recordLoadOutcome(eq(ROW_ID),
                eq(TenantModuleData.STATUS_QUARANTINED), any());
    }

    @Test
    @DisplayName("a retry that finds a permanent problem once storage is back quarantines then")
    void retryThatFindsPermanentFailureQuarantines() throws Exception {
        TenantModuleData row = module("not-the-checksum", TenantModuleData.STATUS_ACTIVE);
        when(jarService.downloadJarToCache(S3_KEY)).thenThrow(storageDown()).thenReturn(jarUrl);
        when(moduleStore.findById(ROW_ID)).thenReturn(Optional.of(row));

        manager.loadModule(TENANT, row);
        clock.advance(Duration.ofSeconds(30));
        manager.retryPendingLoads();

        verify(moduleStore).recordLoadOutcome(eq(ROW_ID),
                eq(TenantModuleData.STATUS_QUARANTINED), any());
        assertThat(manager.podLoadState(TENANT, MODULE_ID))
                .containsEntry("loadStateOnThisPod", "FAILED");
        assertThat(manager.pendingLoadRetries()).isZero();
    }

    @Test
    @DisplayName("a module disabled while retrying stops being retried")
    void retryStopsWhenRowIsNoLongerActive() throws Exception {
        when(jarService.downloadJarToCache(S3_KEY)).thenThrow(storageDown());
        when(moduleStore.findById(ROW_ID)).thenReturn(
                Optional.of(module(checksum, TenantModuleData.STATUS_DISABLED)));

        manager.loadModule(TENANT, module(checksum, TenantModuleData.STATUS_ACTIVE));
        clock.advance(Duration.ofSeconds(30));
        manager.retryPendingLoads();

        verify(jarService, times(1)).downloadJarToCache(S3_KEY);
        assertThat(manager.pendingLoadRetries()).isZero();
        // Still fail-closed until the DISABLED event unloads it.
        assertThat(handlerRegistry.getHandler(TENANT, WebhookTestModule.HANDLER_KEY)).isPresent();

        manager.unloadModule(TENANT, MODULE_ID);
        assertThat(handlerRegistry.getHandler(TENANT, WebhookTestModule.HANDLER_KEY)).isEmpty();
        assertThat(manager.podLoadState(TENANT, MODULE_ID))
                .containsExactlyEntriesOf(Map.of("loadStateOnThisPod", "NOT_LOADED"));
    }

    @Test
    @DisplayName("a module uninstalled while retrying is unloaded")
    void retryUnloadsWhenRowIsGone() throws Exception {
        when(jarService.downloadJarToCache(S3_KEY)).thenThrow(storageDown());
        when(moduleStore.findById(ROW_ID)).thenReturn(Optional.empty());

        manager.loadModule(TENANT, module(checksum, TenantModuleData.STATUS_ACTIVE));
        clock.advance(Duration.ofSeconds(30));
        manager.retryPendingLoads();

        assertThat(manager.isLoaded(TENANT, MODULE_ID)).isFalse();
        assertThat(handlerRegistry.getHandler(TENANT, WebhookTestModule.HANDLER_KEY)).isEmpty();
        assertThat(manager.pendingLoadRetries()).isZero();
    }

    @Test
    @DisplayName("the database being down when a retry reads the row just pushes the retry back")
    void databaseDownDuringRetryReschedules() throws Exception {
        when(jarService.downloadJarToCache(S3_KEY)).thenThrow(storageDown());
        when(moduleStore.findById(ROW_ID)).thenThrow(
                new CannotGetJdbcConnectionException("Connection refused"));

        manager.loadModule(TENANT, module(checksum, TenantModuleData.STATUS_ACTIVE));
        clock.advance(Duration.ofSeconds(30));
        manager.retryPendingLoads();

        var failure = manager.loadFailure(TENANT, MODULE_ID);
        assertThat(failure.attempts()).isEqualTo(2);
        assertThat(failure.nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(60));
        assertThat(handlerRegistry.getHandler(TENANT, WebhookTestModule.HANDLER_KEY)).isPresent();
    }

    @Test
    @DisplayName("a pod with no JAR storage fails closed without writing QUARANTINED for every pod")
    void podWithoutJarStorageDoesNotQuarantine() {
        RuntimeModuleManager noStorage = new RuntimeModuleManager(moduleStore, handlerRegistry,
                new ObjectMapper(), null, null, null, null, null);
        noStorage.setClock(clock);

        noStorage.loadModule(TENANT, module(checksum, TenantModuleData.STATUS_ACTIVE));

        assertThat(handlerRegistry.getHandler(TENANT, WebhookTestModule.HANDLER_KEY)).isPresent();
        verify(moduleStore, never()).recordLoadOutcome(anyString(), anyString(), any());
        verify(moduleStore).recordLoadError(eq(ROW_ID), anyString());
        assertThat(noStorage.podLoadState(TENANT, MODULE_ID))
                .containsEntry("loadStateOnThisPod", "RETRYING")
                .containsEntry("loadFailurePhase", "SOURCE");
    }

    @Test
    @DisplayName("startup listing failing (database down) is retried instead of loading nothing forever")
    void startupListFailureIsRetried() throws Exception {
        TenantModuleData row = module(checksum, TenantModuleData.STATUS_ACTIVE);
        when(moduleStore.findAllActive())
                .thenThrow(new CannotGetJdbcConnectionException("Connection refused"))
                .thenReturn(List.of(row));
        when(jarService.downloadJarToCache(S3_KEY)).thenReturn(jarUrl);

        manager.loadAllActiveModules();
        assertThat(manager.isLoaded(TENANT, MODULE_ID)).isFalse();
        assertThat(manager.pendingLoadRetries()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(30));
        manager.retryPendingLoads();

        assertThat(manager.isLoaded(TENANT, MODULE_ID)).isTrue();
        assertThat(manager.podLoadState(TENANT, MODULE_ID))
                .containsEntry("loadStateOnThisPod", "LOADED");
        assertThat(manager.pendingLoadRetries()).isZero();
    }

    @Test
    @DisplayName("re-enabling a quarantined module re-runs the load instead of the already-loaded no-op")
    void enableRetriesAQuarantinedModule() throws Exception {
        when(jarService.downloadJarToCache(S3_KEY)).thenReturn(jarUrl);
        manager.loadModule(TENANT, module("not-the-checksum", TenantModuleData.STATUS_ACTIVE));
        assertThat(manager.podLoadState(TENANT, MODULE_ID))
                .containsEntry("loadStateOnThisPod", "FAILED");

        // The admin fixes the cause (here: the checksum on the row) and re-enables.
        TenantModuleData fixed = module(checksum, TenantModuleData.STATUS_QUARANTINED);
        when(moduleStore.findByTenantAndModuleId(TENANT, MODULE_ID)).thenReturn(Optional.of(fixed));
        manager.enableModule(TENANT, MODULE_ID);

        verify(moduleStore).updateStatus(ROW_ID, TenantModuleData.STATUS_ACTIVE);
        verify(moduleStore).recordLoadOutcome(ROW_ID, TenantModuleData.STATUS_ACTIVE, null);
        assertThat(manager.podLoadState(TENANT, MODULE_ID))
                .containsEntry("loadStateOnThisPod", "LOADED");
        verify(jarService, times(2)).downloadJarToCache(S3_KEY);
    }

    @Test
    @DisplayName("enabling an ACTIVE module that is retrying on this pod retries now, not after the backoff")
    void enableOfAnActiveModuleRetriesATransientFailureNow() throws Exception {
        TenantModuleData row = module(checksum, TenantModuleData.STATUS_ACTIVE);
        when(jarService.downloadJarToCache(S3_KEY)).thenThrow(storageDown()).thenReturn(jarUrl);
        when(moduleStore.findByTenantAndModuleId(TENANT, MODULE_ID)).thenReturn(Optional.of(row));

        manager.loadModule(TENANT, row);
        assertThat(manager.podLoadState(TENANT, MODULE_ID))
                .containsEntry("loadStateOnThisPod", "RETRYING");

        // Storage is back; the admin presses "retry now" well inside the 30 s backoff.
        clock.advance(Duration.ofSeconds(5));
        manager.enableModule(TENANT, MODULE_ID);

        verify(jarService, times(2)).downloadJarToCache(S3_KEY);
        // No status write: the row was ACTIVE all along, and only the load outcome is recorded.
        verify(moduleStore, never()).updateStatus(anyString(), anyString());
        verify(moduleStore).recordLoadOutcome(ROW_ID, TenantModuleData.STATUS_ACTIVE, null);
        assertThat(manager.podLoadState(TENANT, MODULE_ID))
                .containsExactlyEntriesOf(Map.of("loadStateOnThisPod", "LOADED"));
        assertThat(manager.pendingLoadRetries()).isZero();
    }

    @Test
    @DisplayName("re-enabling a module quarantined for an integrity failure re-verifies and stays QUARANTINED")
    void reEnableOfAnIntegrityFailureStaysQuarantined() throws Exception {
        when(jarService.downloadJarToCache(S3_KEY)).thenReturn(jarUrl);
        TenantModuleData tampered = module("not-the-checksum", TenantModuleData.STATUS_ACTIVE);
        manager.loadModule(TENANT, tampered);
        verify(moduleStore).recordLoadOutcome(eq(ROW_ID),
                eq(TenantModuleData.STATUS_QUARANTINED), anyString());

        // Nothing about the JAR changed; the admin re-enables anyway.
        TenantModuleData stillTampered =
                module("not-the-checksum", TenantModuleData.STATUS_QUARANTINED);
        when(moduleStore.findByTenantAndModuleId(TENANT, MODULE_ID))
                .thenReturn(Optional.of(stillTampered));
        manager.enableModule(TENANT, MODULE_ID);

        // The load re-ran and failed the checksum again: quarantined again, never a real handler.
        verify(jarService, times(2)).downloadJarToCache(S3_KEY);
        verify(moduleStore, times(2)).recordLoadOutcome(eq(ROW_ID),
                eq(TenantModuleData.STATUS_QUARANTINED), anyString());
        verify(moduleStore, never()).recordLoadOutcome(ROW_ID, TenantModuleData.STATUS_ACTIVE, null);
        ActionHandler handler = handlerRegistry.getHandler(TENANT, WebhookTestModule.HANDLER_KEY)
                .orElseThrow();
        assertThatThrownBy(() -> handler.execute(ActionContext.builder().tenantId(TENANT).build()))
                .isInstanceOf(ModuleUnavailableException.class);
        assertThat(manager.podLoadState(TENANT, MODULE_ID))
                .containsEntry("loadStateOnThisPod", "FAILED");

        // And the retry scheduler never picks a permanent failure up on its own.
        clock.advance(Duration.ofHours(2));
        manager.retryPendingLoads();
        verify(jarService, times(2)).downloadJarToCache(S3_KEY);
    }

    @Test
    @DisplayName("ensureLoaded leaves a healthy load alone")
    void ensureLoadedDoesNotChurnAHealthyModule() throws Exception {
        TenantModuleData row = module(checksum, TenantModuleData.STATUS_ACTIVE);
        when(jarService.downloadJarToCache(S3_KEY)).thenReturn(jarUrl);

        manager.loadModule(TENANT, row);
        manager.ensureLoaded(TENANT, row);

        verify(jarService, times(1)).downloadJarToCache(S3_KEY);
    }

    private static SdkClientException storageDown() {
        return SdkClientException.builder()
                .message("Unable to execute HTTP request: Connection refused")
                .cause(new ConnectException("Connection refused"))
                .build();
    }

    private TenantModuleData module(String jarChecksum, String status) {
        return new TenantModuleData(
                ROW_ID, TENANT, MODULE_ID, "Webhook Test Module", "1.0.0", "desc",
                S3_KEY, jarChecksum, 10L, MODULE_CLASS, MANIFEST_JSON, status, "user-1",
                Instant.now(), Instant.now(), S3_KEY,
                List.of(new TenantModuleData.TenantModuleActionData(
                        "act-1", ROW_ID, WebhookTestModule.HANDLER_KEY, "Webhook", "Test",
                        "handles a webhook", null, null, null)));
    }

    private Path buildModuleJar() throws IOException {
        Path jarFile = tempDir.resolve("webhook-module.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(jarFile.toFile()),
                manifest)) {
            addClass(jar, MODULE_CLASS.replace('.', '/') + ".class");
            addClass(jar, MODULE_CLASS.replace('.', '/') + "$1.class");
        }
        return jarFile;
    }

    private void addClass(JarOutputStream jar, String resource) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Test module class not on the test classpath: " + resource);
            }
            jar.putNextEntry(new JarEntry(resource));
            in.transferTo(jar);
            jar.closeEntry();
        }
    }

    /** A clock the test moves by hand, so backoff is asserted without sleeping. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
