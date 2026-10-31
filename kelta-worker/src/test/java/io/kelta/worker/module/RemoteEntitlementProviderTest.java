package io.kelta.worker.module;

import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.module.service.MemberEntitlements;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.service.TenantSlugResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static io.kelta.worker.module.ExternalModuleFixtures.json;
import static io.kelta.worker.module.ExternalModuleFixtures.manifest;
import static io.kelta.worker.module.ExternalModuleFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RemoteEntitlementProvider")
class RemoteEntitlementProviderTest {

    private static final String ANSWER =
            "{\"planCode\":\"pro\",\"subscriptionStatus\":\"active\",\"values\":{\"maxActiveWatches\":3,\"gone\":null}}";

    private final ModuleHttpClient http = mock(ModuleHttpClient.class);
    private final ExternalModuleRegistry registry = mock(ExternalModuleRegistry.class);
    private RemoteEntitlementProvider provider;

    @BeforeEach
    void setUp() {
        ModuleAccessTokenService tokens = mock(ModuleAccessTokenService.class);
        when(tokens.accessToken(any())).thenReturn("token");
        ExternalModuleInvoker invoker = new ExternalModuleInvoker(mock(ModuleStore.class), http, tokens,
                mock(TenantSlugResolver.class), null, new ExternalModuleFixtures.MutableClock(), Runnable::run);
        provider = new RemoteEntitlementProvider(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE,
                registry, invoker, new ObjectMapper());
    }

    private void ttl(int seconds) {
        ExternalModuleManifest manifest = manifest(List.of(),
                List.of(new ExternalModuleManifest.Service("entitlements", seconds)), List.of(), false);
        when(registry.endpoint(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE)).thenReturn(Optional.of(
                new ExternalModuleRegistry.Endpoint(row(TenantModuleData.STATUS_ACTIVE), manifest,
                        ModulePrincipalAccess.NONE)));
    }

    @Test
    @DisplayName("reads MemberEntitlements, dropping null values, and caches it per member for cacheTtlSeconds")
    void cachesAnswers() {
        ttl(60);
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, ANSWER));

        MemberEntitlements first = provider.resolve(ExternalModuleFixtures.TENANT, "user-1");
        MemberEntitlements second = provider.resolve(ExternalModuleFixtures.TENANT, "user-1");

        assertThat(first.planCode()).isEqualTo("pro");
        assertThat(first.subscriptionStatus()).isEqualTo("active");
        assertThat(first.intValue("maxActiveWatches", -1)).isEqualTo(3);
        assertThat(first.values()).doesNotContainKey("gone");
        assertThat(second).isSameAs(first);
        verify(http, times(1)).invoke(any(), any(), any(), any());

        provider.resolve(ExternalModuleFixtures.TENANT, "user-2");
        verify(http, times(2)).invoke(any(), any(), any(), any());
    }

    @Test
    @DisplayName("cacheTtlSeconds 0 calls the module every time")
    void zeroTtlNeverCaches() {
        ttl(0);
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, ANSWER));

        provider.resolve(ExternalModuleFixtures.TENANT, "user-1");
        provider.resolve(ExternalModuleFixtures.TENANT, "user-1");

        verify(http, times(2)).invoke(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a failure throws (ModuleEntitlementService answers EMPTY) and is never cached")
    void failuresAreNotCached() {
        ttl(60);
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(500, "")).thenReturn(json(200, ANSWER));

        assertThatThrownBy(() -> provider.resolve(ExternalModuleFixtures.TENANT, "user-1"))
                .isInstanceOf(ModuleUnavailableException.class);
        assertThat(provider.resolve(ExternalModuleFixtures.TENANT, "user-1").planCode()).isEqualTo("pro");
    }

    @Test
    @DisplayName("an answer without a values object is a module failure")
    void valuesRequired() {
        ttl(60);
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, "{\"planCode\":\"pro\"}"));

        assertThatThrownBy(() -> provider.resolve(ExternalModuleFixtures.TENANT, "user-1"))
                .isInstanceOf(ModuleUnavailableException.class).hasMessageContaining("values");
    }

    @Test
    @DisplayName("invalidate drops cached answers (the install was re-registered)")
    void invalidateDropsTheCache() {
        ttl(60);
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, ANSWER));

        provider.resolve(ExternalModuleFixtures.TENANT, "user-1");
        provider.invalidate();
        provider.resolve(ExternalModuleFixtures.TENANT, "user-1");

        verify(http, times(2)).invoke(any(), any(), any(), any());
    }

    @Test
    @DisplayName("never answers for another tenant")
    void otherTenantIsEmpty() {
        assertThat(provider.resolve("tenant-2", "user-1")).isSameAs(MemberEntitlements.EMPTY);
    }
}
