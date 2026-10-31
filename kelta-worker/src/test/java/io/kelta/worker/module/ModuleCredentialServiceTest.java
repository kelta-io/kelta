package io.kelta.worker.module;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.worker.module.ModuleCredentialService.ModuleCredential;
import io.kelta.worker.module.ModuleCredentialService.Refusal;
import io.kelta.worker.module.ModuleCredentialService.RefusedException;
import io.kelta.worker.service.ModuleServicePrincipalService;
import io.kelta.worker.service.credential.CredentialDisabledException;
import io.kelta.worker.service.credential.CredentialNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.kelta.worker.module.ExternalModuleFixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ModuleCredentialService")
class ModuleCredentialServiceTest {

    private static final String APP = "app-1";
    private static final String SERVICE_USER = "svc-1";
    private static final String MANIFEST = """
            {"manifestVersion": 2, "protocolVersion": 1, "id": "stub", "name": "Stub", "version": "1.0.0",
             "permissions": {"credentials": ["stripe"]}}
            """;

    private ModuleStore moduleStore;
    private ModuleServicePrincipalService principals;
    private CredentialResolverPort resolver;
    private ObjectProvider<CredentialResolverPort> resolverProvider;
    private ModuleCredentialService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        moduleStore = mock(ModuleStore.class);
        principals = mock(ModuleServicePrincipalService.class);
        resolver = mock(CredentialResolverPort.class);
        resolverProvider = mock(ObjectProvider.class);
        when(resolverProvider.getIfAvailable()).thenReturn(resolver);
        when(moduleStore.findByConnectedAppId(TENANT, APP)).thenReturn(Optional.of(install("ACTIVE", MANIFEST)));
        when(principals.isActivePrincipal(TENANT, APP, SERVICE_USER)).thenReturn(true);
        service = new ModuleCredentialService(moduleStore, principals, resolverProvider, new ObjectMapper());
    }

    private static TenantModuleData install(String status, String manifest) {
        Instant at = Instant.parse("2026-10-01T00:00:00Z");
        return new TenantModuleData("install-1", TENANT, "stub", "Stub", "1.0.0", null, "https://stub.example.test",
                null, null, null, manifest, status, "admin", at, at, null, List.of(),
                "https://stub.example.test", 1, APP, SERVICE_USER);
    }

    private static ResolvedCredential stripe() {
        return new ResolvedCredential("cred-1", "stripe", "stripe",
                Map.of("secretKey", "sk_test_placeholder", "webhookSecret", "whsec_placeholder"),
                Map.of("allowedReturnOrigins", List.of("https://app.example.test")), Instant.now());
    }

    private Refusal refusalOf(Runnable call) {
        try {
            call.run();
        } catch (RefusedException e) {
            return e.refusal();
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    @DisplayName("the install's principal reads a declared credential: secrets + metadata, audited as the module")
    void readsADeclaredCredential() {
        when(resolver.resolve(TENANT, "stripe", "MODULE:stub", SERVICE_USER)).thenReturn(stripe());

        ModuleCredential credential = service.read(TENANT, APP, SERVICE_USER, "stripe", null);

        assertThat(credential.moduleId()).isEqualTo("stub");
        assertThat(credential.type()).isEqualTo("stripe");
        assertThat(credential.secrets()).containsEntry("secretKey", "sk_test_placeholder")
                .containsEntry("webhookSecret", "whsec_placeholder");
        assertThat(credential.metadata()).containsEntry("allowedReturnOrigins", List.of("https://app.example.test"));
        assertThat(credential.toString()).doesNotContain("sk_test_placeholder", "whsec_placeholder", "example.test");
    }

    @Test
    @DisplayName("a module's label is appended to the platform's MODULE:<id> purpose, never replacing it")
    void purposeLabel() {
        when(resolver.resolve(TENANT, "stripe", "MODULE:stub:STRIPE_API:checkout", SERVICE_USER))
                .thenReturn(stripe());

        assertThat(service.read(TENANT, APP, SERVICE_USER, "stripe", "STRIPE_API:checkout").name())
                .isEqualTo("stripe");
        assertThat(ModuleCredentialService.PURPOSE_LABEL.matcher("STRIPE_WEBHOOK_VERIFY").matches()).isTrue();
        assertThat(ModuleCredentialService.PURPOSE_LABEL.matcher("bad label\nforged").matches()).isFalse();
        assertThat(ModuleCredentialService.PURPOSE_LABEL.matcher("x".repeat(65)).matches()).isFalse();
    }

    @Test
    @DisplayName("no connected app (a user token or a PAT) is not a module principal")
    void noConnectedApp() {
        assertThat(refusalOf(() -> service.read(TENANT, null, "user-1", "stripe", null)))
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        assertThat(refusalOf(() -> service.read(TENANT, " ", "user-1", "stripe", null)))
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        verify(moduleStore, never()).findByConnectedAppId(any(), any());
        verifyNothingResolved();
    }

    @Test
    @DisplayName("a connected app that is no module install's principal is refused")
    void unknownConnectedApp() {
        when(moduleStore.findByConnectedAppId(TENANT, "app-other")).thenReturn(Optional.empty());

        assertThat(refusalOf(() -> service.read(TENANT, "app-other", SERVICE_USER, "stripe", null)))
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        verifyNothingResolved();
    }

    @Test
    @DisplayName("a caller other than the install's SERVICE user is refused, even carrying its app id")
    void wrongUser() {
        assertThat(refusalOf(() -> service.read(TENANT, APP, "user-1", "stripe", null)))
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        assertThat(refusalOf(() -> service.read(TENANT, APP, null, "stripe", null)))
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        verifyNothingResolved();
    }

    @Test
    @DisplayName("another install's principal names its own install, which does not declare this credential")
    void anotherInstallsPrincipal() {
        TenantModuleData other = new TenantModuleData("install-2", TENANT, "other", "Other", "1.0.0", null,
                "https://other.example.test", null, null, null, """
                {"manifestVersion": 2, "protocolVersion": 1, "id": "other", "name": "Other", "version": "1.0.0"}
                """, "ACTIVE", "admin", Instant.now(), Instant.now(), null, List.of(),
                "https://other.example.test", 1, "app-2", "svc-2");
        when(moduleStore.findByConnectedAppId(TENANT, "app-2")).thenReturn(Optional.of(other));
        when(principals.isActivePrincipal(TENANT, "app-2", "svc-2")).thenReturn(true);

        assertThat(refusalOf(() -> service.read(TENANT, "app-2", "svc-2", "stripe", null)))
                .isEqualTo(Refusal.CREDENTIAL_NOT_DECLARED);
        // ...and it cannot borrow the first install's app id: the user would not match.
        assertThat(refusalOf(() -> service.read(TENANT, APP, "svc-2", "stripe", null)))
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        verifyNothingResolved();
    }

    @Test
    @DisplayName("a deactivated app or SERVICE user is refused while its token is still valid")
    void inactivePrincipal() {
        when(principals.isActivePrincipal(TENANT, APP, SERVICE_USER)).thenReturn(false);

        assertThat(refusalOf(() -> service.read(TENANT, APP, SERVICE_USER, "stripe", null)))
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        verifyNothingResolved();
    }

    @Test
    @DisplayName("a JAR module row is not an external install")
    void notExternal() {
        Instant at = Instant.now();
        TenantModuleData jar = new TenantModuleData("install-1", TENANT, "stub", "Stub", "1.0.0", null, null,
                "abc", 1L, "com.example.Module", "{}", "ACTIVE", "admin", at, at, "s3-key", List.of(),
                null, null, APP, SERVICE_USER);
        when(moduleStore.findByConnectedAppId(TENANT, APP)).thenReturn(Optional.of(jar));

        assertThat(refusalOf(() -> service.read(TENANT, APP, SERVICE_USER, "stripe", null)))
                .isEqualTo(Refusal.NOT_MODULE_PRINCIPAL);
        verifyNothingResolved();
    }

    @Test
    @DisplayName("a disabled, failed or still-installing install reads nothing; a DEGRADED one still may")
    void installStatus() {
        for (String status : List.of("DISABLED", "FAILED", "INSTALLING")) {
            when(moduleStore.findByConnectedAppId(TENANT, APP)).thenReturn(Optional.of(install(status, MANIFEST)));
            assertThat(refusalOf(() -> service.read(TENANT, APP, SERVICE_USER, "stripe", null)))
                    .as(status).isEqualTo(Refusal.MODULE_NOT_ACTIVE);
        }
        verifyNothingResolved();

        when(moduleStore.findByConnectedAppId(TENANT, APP)).thenReturn(Optional.of(install("DEGRADED", MANIFEST)));
        when(resolver.resolve(TENANT, "stripe", "MODULE:stub", SERVICE_USER)).thenReturn(stripe());
        assertThat(service.read(TENANT, APP, SERVICE_USER, "stripe", null).name()).isEqualTo("stripe");
    }

    @Test
    @DisplayName("a name the installed manifest does not declare is refused before anything is decrypted")
    void undeclared() {
        assertThat(refusalOf(() -> service.read(TENANT, APP, SERVICE_USER, "smtp", null)))
                .isEqualTo(Refusal.CREDENTIAL_NOT_DECLARED);
        assertThat(refusalOf(() -> service.read(TENANT, APP, SERVICE_USER, "Stripe", null)))
                .as("exact match only").isEqualTo(Refusal.CREDENTIAL_NOT_DECLARED);
        verifyNothingResolved();
    }

    @Test
    @DisplayName("a UUID-shaped name is refused even when declared: the resolver would read it as an id")
    void uuidShapedName() {
        String id = "3f2b9c1e-0000-4000-8000-000000000001";
        when(moduleStore.findByConnectedAppId(TENANT, APP)).thenReturn(Optional.of(install("ACTIVE", """
                {"manifestVersion": 2, "protocolVersion": 1, "id": "stub", "name": "Stub", "version": "1.0.0",
                 "permissions": {"credentials": ["%s"]}}
                """.formatted(id))));

        assertThat(refusalOf(() -> service.read(TENANT, APP, SERVICE_USER, id, null)))
                .isEqualTo(Refusal.CREDENTIAL_NOT_DECLARED);
        verifyNothingResolved();
    }

    @Test
    @DisplayName("an installed manifest that no longer parses declares nothing")
    void unreadableManifest() {
        when(moduleStore.findByConnectedAppId(TENANT, APP)).thenReturn(Optional.of(install("ACTIVE", "{oops")));

        assertThat(refusalOf(() -> service.read(TENANT, APP, SERVICE_USER, "stripe", null)))
                .isEqualTo(Refusal.CREDENTIAL_NOT_DECLARED);
        verifyNothingResolved();
    }

    @Test
    @DisplayName("a declared credential the tenant lacks, or has disabled, is not found")
    void missingOrDisabled() {
        when(resolver.resolve(TENANT, "stripe", "MODULE:stub", SERVICE_USER))
                .thenThrow(new CredentialNotFoundException(TENANT, "stripe"))
                .thenThrow(new CredentialDisabledException("cred-1", "stripe"));

        assertThat(refusalOf(() -> service.read(TENANT, APP, SERVICE_USER, "stripe", null)))
                .isEqualTo(Refusal.CREDENTIAL_NOT_FOUND);
        assertThat(refusalOf(() -> service.read(TENANT, APP, SERVICE_USER, "stripe", null)))
                .isEqualTo(Refusal.CREDENTIAL_NOT_FOUND);
    }

    @Test
    @DisplayName("without a credential vault every allowed read is unavailable")
    void noVault() {
        when(resolverProvider.getIfAvailable()).thenReturn(null);

        assertThat(refusalOf(() -> service.read(TENANT, APP, SERVICE_USER, "stripe", null)))
                .isEqualTo(Refusal.CREDENTIALS_UNAVAILABLE);
    }

    @Test
    @DisplayName("a refusal's message names the reason, never a secret")
    void refusalMessage() {
        assertThatThrownBy(() -> service.read(TENANT, APP, SERVICE_USER, "smtp", null))
                .isInstanceOf(RefusedException.class)
                .hasMessageContaining("does not declare the credential 'smtp'");
    }

    private void verifyNothingResolved() {
        verify(resolver, never()).resolve(anyString(), anyString(), anyString(), any());
        verify(resolver, never()).resolve(anyString(), anyString(), anyString());
    }
}
