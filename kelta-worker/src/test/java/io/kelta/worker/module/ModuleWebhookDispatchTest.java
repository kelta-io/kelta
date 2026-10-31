package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.worker.service.TenantSlugResolver;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.worker.module.testmodule.WebhookTestModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Webhook dispatch and UI-bundle serving for runtime-installed modules — the two pieces that let a
 * module reach the outside world without a `@RestController` of its own.
 *
 * <p>Loads a real JAR through the real sandboxed classloader, because dispatch resolves the
 * handler out of the tenant-scoped registry that only a genuine load populates.
 */
@DisplayName("RuntimeModuleManager — webhook dispatch and UI bundles")
class ModuleWebhookDispatchTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String TENANT_SLUG = "tenant-one";
    private static final String MODULE_ID = "webhook-module";
    private static final String MODULE_CLASS =
            "io.kelta.worker.module.testmodule.WebhookTestModule";
    private static final String BUNDLE_PATH = "static/ui-bundle.js";
    private static final String BUNDLE_SOURCE =
            "export const registered = true; // module UI bundle\n";

    private static final String MANIFEST_JSON = """
        {
          "id": "webhook-module",
          "name": "Webhook Test Module",
          "version": "1.0.0",
          "moduleClass": "io.kelta.worker.module.testmodule.WebhookTestModule",
          "webhookHandlerKey": "test:webhook",
          "uiBundlePath": "static/ui-bundle.js"
        }
        """;

    @TempDir Path tempDir;

    private ModuleStore moduleStore;
    private RuntimeModuleManager manager;
    private byte[] jarBytes;

    @BeforeEach
    void setUp() throws Exception {
        moduleStore = mock(ModuleStore.class);
        Path jarFile = buildModuleJar();
        jarBytes = Files.readAllBytes(jarFile);

        ModuleJarService jarService = mock(ModuleJarService.class);
        when(jarService.downloadJarToCache("s3/key.jar")).thenReturn(jarFile.toUri().toURL());

        manager = new RuntimeModuleManager(moduleStore, new ActionHandlerRegistry(),
                new ObjectMapper(), jarService, null, null, new BeforeSaveHookRegistry(), null);
    }

    // ------------------------------------------------------------ Webhook dispatch

    @Test
    @DisplayName("Dispatches the raw body and headers to the manifest's webhook handler")
    void dispatchesRawBodyAndHeaders() {
        installed(TenantModuleData.STATUS_ACTIVE);
        manager.loadModule(TENANT_ID, moduleData(TenantModuleData.STATUS_ACTIVE));

        String body = "{\"id\":\"evt_1\"}";
        Optional<ActionResult> result = manager.dispatchWebhook(TENANT_ID, MODULE_ID, body,
                Map.of("x-test-signature", "sig"));

        assertThat(result).isPresent();
        assertThat(result.get().successful()).isTrue();
        // Byte-for-byte: a module verifying an HMAC over the body depends on this.
        assertThat(result.get().outputData())
                .containsEntry("echoBody", body)
                .containsEntry("echoTenant", TENANT_ID)
                .containsEntry("echoModule", MODULE_ID);
    }

    @Test
    @DisplayName("Binds the tenant before invoking the handler")
    void bindsTenantForTheHandler() {
        // Observed from a PLATFORM-side handler, because a JAR module cannot read TenantContext at
        // all — io.kelta.runtime.context is outside the classloader allowlist. That is exactly why
        // the platform has to bind it: the webhook route is unauthenticated, so nothing upstream
        // binds a tenant, and unbound every read and write the handler makes would run under the
        // admin_bypass RLS policy, across tenants.
        ActionHandlerRegistry registry = new ActionHandlerRegistry();
        AtomicReference<String> boundId = new AtomicReference<>();
        AtomicReference<String> boundSlug = new AtomicReference<>();
        registry.registerTenantHandler(TENANT_ID, new ActionHandler() {
            @Override
            public String getActionTypeKey() {
                return WebhookTestModule.HANDLER_KEY;
            }

            @Override
            public ActionResult execute(ActionContext context) {
                boundId.set(TenantContext.get());
                boundSlug.set(TenantContext.getSlug());
                return ActionResult.success();
            }
        });
        TenantSlugResolver slugResolver = mock(TenantSlugResolver.class);
        when(slugResolver.resolveSlug(TENANT_ID)).thenReturn(Optional.of(TENANT_SLUG));
        RuntimeModuleManager platformSide = new RuntimeModuleManager(
                moduleStore, registry, new ObjectMapper(), null, null, null, null, null,
                slugResolver);
        installed(TenantModuleData.STATUS_ACTIVE);

        assertThat(platformSide.dispatchWebhook(TENANT_ID, MODULE_ID, "{}", Map.of())).isPresent();

        assertThat(boundId.get()).isEqualTo(TENANT_ID);
        // The SLUG is what PhysicalTableStorageAdapter#getTableRef reads to pick the tenant
        // schema. Binding only the id left it null, the adapter fell back to the PUBLIC schema,
        // and every module query hit an unqualified table that does not exist there —
        // "relation \"billing_customers\" does not exist" against a live Stripe webhook.
        assertThat(boundSlug.get()).isEqualTo(TENANT_SLUG);
    }

    @Test
    @DisplayName("Leaves no tenant bound after dispatch returns")
    void unbindsTenantAfterDispatch() {
        installed(TenantModuleData.STATUS_ACTIVE);
        manager.loadModule(TENANT_ID, moduleData(TenantModuleData.STATUS_ACTIVE));

        manager.dispatchWebhook(TENANT_ID, MODULE_ID, "{}", Map.of("x-test-signature", "sig"));

        // A leaked binding would silently scope whatever the calling thread does next.
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("Surfaces the handler's own rejection rather than treating it as success")
    void surfacesHandlerRejection() {
        installed(TenantModuleData.STATUS_ACTIVE);
        manager.loadModule(TENANT_ID, moduleData(TenantModuleData.STATUS_ACTIVE));

        Optional<ActionResult> result =
                manager.dispatchWebhook(TENANT_ID, MODULE_ID, "{}", Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().successful()).isFalse();
    }

    @Test
    @DisplayName("Dispatches nothing for an unknown tenant or module")
    void dispatchesNothingForUnknownModule() {
        when(moduleStore.findByTenantAndModuleId(TENANT_ID, MODULE_ID))
                .thenReturn(Optional.empty());

        assertThat(manager.dispatchWebhook(TENANT_ID, MODULE_ID, "{}", Map.of())).isEmpty();
    }

    @Test
    @DisplayName("Dispatches nothing for a module that is installed but not active")
    void dispatchesNothingForInactiveModule() {
        installed(TenantModuleData.STATUS_DISABLED);

        // Same empty outcome as an unknown module, so a caller cannot tell them apart.
        assertThat(manager.dispatchWebhook(TENANT_ID, MODULE_ID, "{}",
                Map.of("x-test-signature", "sig"))).isEmpty();
    }

    @Test
    @DisplayName("Dispatches nothing for an external module: its webhook is not an in-process handler")
    void dispatchesNothingForAnExternalModule() {
        TenantModuleData external = new TenantModuleData("mod-2", TENANT_ID, MODULE_ID, "External", "1.0.0",
                null, "https://module.example.test", null, null, null, "{\"manifestVersion\":2}",
                TenantModuleData.STATUS_ACTIVE, "user-1", Instant.now(), Instant.now(), null, List.of(),
                "https://module.example.test", 1, "app-1", "svc-1");
        when(moduleStore.findByTenantAndModuleId(TENANT_ID, MODULE_ID)).thenReturn(Optional.of(external));

        assertThat(manager.dispatchWebhook(TENANT_ID, MODULE_ID, "{}", Map.of())).isEmpty();
    }

    @Test
    @DisplayName("Dispatches nothing when the manifest declares no webhook handler")
    void dispatchesNothingWithoutADeclaredHandler() {
        String noWebhook = MANIFEST_JSON.replace("\"webhookHandlerKey\": \"test:webhook\",", "");
        when(moduleStore.findByTenantAndModuleId(TENANT_ID, MODULE_ID))
                .thenReturn(Optional.of(moduleData(TenantModuleData.STATUS_ACTIVE, noWebhook)));

        assertThat(manager.dispatchWebhook(TENANT_ID, MODULE_ID, "{}", Map.of())).isEmpty();
    }

    // ------------------------------------------------------------ UI bundle

    @Test
    @DisplayName("Serves the UI bundle from the module's JAR")
    void servesUiBundleFromJar() {
        installed(TenantModuleData.STATUS_ACTIVE);

        Optional<byte[]> bundle = manager.readUiBundle(TENANT_ID, MODULE_ID);

        assertThat(bundle).isPresent();
        assertThat(new String(bundle.get(), StandardCharsets.UTF_8)).isEqualTo(BUNDLE_SOURCE);
    }

    @Test
    @DisplayName("Serves no bundle for a module the tenant has not activated")
    void servesNoBundleForInactiveModule() {
        installed(TenantModuleData.STATUS_DISABLED);

        assertThat(manager.readUiBundle(TENANT_ID, MODULE_ID)).isEmpty();
    }

    @Test
    @DisplayName("Serves no bundle when the JAR checksum no longer verifies")
    void servesNoBundleWhenChecksumFails() {
        // Same gate as the classloader path: never serve bytes out of a tampered JAR.
        TenantModuleData tampered = new TenantModuleData(
                "mod-1", TENANT_ID, MODULE_ID, "Webhook Test Module", "1.0.0", "Test",
                "https://example.com/module.jar", "sha256:not-the-real-checksum",
                (long) jarBytes.length, MODULE_CLASS, MANIFEST_JSON,
                TenantModuleData.STATUS_ACTIVE, "user-1",
                Instant.now(), Instant.now(), "s3/key.jar", List.of());
        when(moduleStore.findByTenantAndModuleId(TENANT_ID, MODULE_ID))
                .thenReturn(Optional.of(tampered));

        assertThat(manager.readUiBundle(TENANT_ID, MODULE_ID)).isEmpty();
    }

    @Test
    @DisplayName("Serves no bundle when the manifest declares none")
    void servesNoBundleWhenNoneDeclared() {
        String noBundle = MANIFEST_JSON.replace(",\n  \"uiBundlePath\": \"static/ui-bundle.js\"", "");
        when(moduleStore.findByTenantAndModuleId(TENANT_ID, MODULE_ID))
                .thenReturn(Optional.of(moduleData(TenantModuleData.STATUS_ACTIVE, noBundle)));

        assertThat(manager.readUiBundle(TENANT_ID, MODULE_ID)).isEmpty();
    }

    // ------------------------------------------------------------ Helpers

    private void installed(String status) {
        when(moduleStore.findByTenantAndModuleId(TENANT_ID, MODULE_ID))
                .thenReturn(Optional.of(moduleData(status)));
    }

    private TenantModuleData moduleData(String status) {
        return moduleData(status, MANIFEST_JSON);
    }

    private TenantModuleData moduleData(String status, String manifestJson) {
        return new TenantModuleData(
                "mod-1", TENANT_ID, MODULE_ID, "Webhook Test Module", "1.0.0", "Test",
                "https://example.com/module.jar", ModuleJarService.sha256(jarBytes),
                (long) jarBytes.length, MODULE_CLASS, manifestJson, status, "user-1",
                Instant.now(), Instant.now(), "s3/key.jar", List.of());
    }

    /** Packages the test module's class plus a UI bundle resource into one JAR. */
    private Path buildModuleJar() throws IOException {
        Path jarFile = tempDir.resolve("webhook-module.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(jarFile.toFile()),
                manifest)) {
            addClass(jar, MODULE_CLASS.replace('.', '/') + ".class");
            addClass(jar, MODULE_CLASS.replace('.', '/') + "$1.class");
            jar.putNextEntry(new JarEntry(BUNDLE_PATH));
            jar.write(BUNDLE_SOURCE.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
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

    static {
        // Referencing the constant keeps the handler key in the test and the module in sync.
        assert WebhookTestModule.HANDLER_KEY.equals("test:webhook");
    }
}
