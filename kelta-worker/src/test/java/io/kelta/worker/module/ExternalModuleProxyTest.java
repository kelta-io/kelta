package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.service.TenantSlugResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.kelta.worker.module.ExternalModuleFixtures.json;
import static io.kelta.worker.module.ExternalModuleFixtures.manifest;
import static io.kelta.worker.module.ExternalModuleFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ExternalModuleProxy")
class ExternalModuleProxyTest {

    private final ModuleHttpClient http = mock(ModuleHttpClient.class);
    private final ExternalModuleRegistry registry = mock(ExternalModuleRegistry.class);
    private ExternalModuleProxy proxy;

    @BeforeEach
    void setUp() {
        ModuleAccessTokenService tokens = mock(ModuleAccessTokenService.class);
        when(tokens.accessToken(any())).thenReturn("token");
        ExternalModuleInvoker invoker = new ExternalModuleInvoker(mock(ModuleStore.class), http, tokens,
                mock(TenantSlugResolver.class), null, new ExternalModuleFixtures.MutableClock(), Runnable::run);
        proxy = new ExternalModuleProxy(registry, invoker);
        installed(TenantModuleData.STATUS_ACTIVE, true);
    }

    private void installed(String status, boolean webhookEnabled) {
        ExternalModuleManifest manifest = manifest(List.of(), List.of(), List.of(
                new ExternalModuleManifest.Route("reports/summary", List.of("GET", "POST"), null),
                new ExternalModuleManifest.Route("admin/reset", List.of("POST"), "CUSTOMIZE_APPLICATION")),
                webhookEnabled);
        when(registry.endpoint(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE)).thenReturn(Optional.of(
                new ExternalModuleRegistry.Endpoint(row(status), manifest, ModulePrincipalAccess.NONE)));
    }

    private ExternalModuleProxy.RouteOutcome route(String method, String path, boolean permitted) {
        return proxy.route(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE, method, path, "page=2",
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8), "application/json", p -> permitted, "profile-1");
    }

    @Test
    @DisplayName("a declared route is forwarded with its query and body, and the module's status comes back")
    void forwardsADeclaredRoute() {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(201, "{\"id\":\"r-1\"}"));

        ExternalModuleProxy.RouteOutcome outcome = route("post", "reports/summary", true);

        assertThat(outcome.refusal()).isNull();
        assertThat(outcome.forwarded().status()).isEqualTo(201);
        assertThat(new String(outcome.forwarded().body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"r-1\"}");
        ArgumentCaptor<ModuleHttpClient.Call> sent = ArgumentCaptor.captor();
        verify(http).invoke(any(), any(), sent.capture(), any());
        assertThat(sent.getValue().method()).isEqualTo("post");
        assertThat(sent.getValue().path()).isEqualTo("/kelta/v1/routes/reports/summary");
        assertThat(sent.getValue().rawQuery()).isEqualTo("page=2");
        assertThat(sent.getValue().contentType()).isEqualTo("application/json");
    }

    @Test
    @DisplayName("an undeclared path or method, or a module that is not callable, is NOT_FOUND without a call")
    void undeclaredIsNotFound() {
        assertThat(route("GET", "reports/other", true).refusal()).isEqualTo(ExternalModuleProxy.Refusal.NOT_FOUND);
        assertThat(route("DELETE", "reports/summary", true).refusal()).isEqualTo(ExternalModuleProxy.Refusal.NOT_FOUND);
        installed(TenantModuleData.STATUS_DISABLED, true);
        assertThat(route("GET", "reports/summary", true).refusal()).isEqualTo(ExternalModuleProxy.Refusal.NOT_FOUND);
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("a route's permission is required of the caller")
    void permissionIsChecked() {
        ExternalModuleProxy.RouteOutcome outcome = route("POST", "admin/reset", false);

        assertThat(outcome.refusal()).isEqualTo(ExternalModuleProxy.Refusal.FORBIDDEN);
        assertThat(outcome.permission()).isEqualTo("CUSTOMIZE_APPLICATION");
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("a webhook forwards the raw bytes and only the allowlisted headers, lower-cased, under the install's tenant")
    void webhookForwardsRawBytesAndAllowlistedHeaders() {
        AtomicReference<String> boundTenant = new AtomicReference<>();
        when(http.invoke(any(), any(), any(), any())).thenAnswer(invocation -> {
            boundTenant.set(TenantContext.get());
            return json(202, "ignored");
        });
        byte[] raw = {0x7b, (byte) 0xe9, 0x7d};
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Stripe-Signature", "t=1,v1=abc");
        headers.put("X-Hub-Signature-256", "sha256=x");
        headers.put("Content-Type", "application/json; charset=ISO-8859-1");
        headers.put("Authorization", "Bearer internal");
        headers.put("Cookie", "session=1");
        headers.put("Host", "worker");

        Optional<ExternalModuleProxy.Forwarded> forwarded = proxy.webhook(ExternalModuleFixtures.TENANT,
                ExternalModuleFixtures.MODULE, raw, headers);

        assertThat(forwarded).hasValueSatisfying(f -> {
            assertThat(f.status()).isEqualTo(202);
            assertThat(f.body()).as("never the module's body").isNull();
        });
        ArgumentCaptor<ModuleHttpClient.Call> sent = ArgumentCaptor.captor();
        verify(http).invoke(any(), any(), sent.capture(), any());
        assertThat(sent.getValue().path()).isEqualTo("/kelta/v1/webhooks");
        assertThat(sent.getValue().body()).isEqualTo(raw);
        assertThat(sent.getValue().contentType()).isEqualTo("application/json; charset=ISO-8859-1");
        assertThat(sent.getValue().headers()).containsOnlyKeys("stripe-signature", "x-hub-signature-256");
        assertThat(boundTenant.get()).as("the unauthenticated path binds no tenant itself")
                .isEqualTo(ExternalModuleFixtures.TENANT);
    }

    @Test
    @DisplayName("a module that did not enable webhooks, or is not callable, has nothing to forward to")
    void webhookNotEnabled() {
        installed(TenantModuleData.STATUS_ACTIVE, false);
        assertThat(proxy.webhook(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE, new byte[0], Map.of()))
                .isEmpty();
        installed(TenantModuleData.STATUS_DISABLED, true);
        assertThat(proxy.webhook(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE, new byte[0], Map.of()))
                .isEmpty();
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("the header allowlist matches lower-cased prefixes")
    void headerAllowlist() {
        assertThat(ExternalModuleProxy.forwardableHeaders(Map.of("User-Agent", "Stripe/1.0", "Digest", "SHA=1",
                "Signature-Input", "sig1", "Accept", "*/*", "Forwarded", "for=1")))
                .containsOnlyKeys("user-agent", "digest", "signature-input");
    }
}
