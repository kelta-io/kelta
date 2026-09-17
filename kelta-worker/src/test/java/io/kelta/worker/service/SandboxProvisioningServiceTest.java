package io.kelta.worker.service;

import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.EnvironmentRepository;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tenant-backed sandbox provisioning: slug derivation + limits, nested-sandbox
 * rejection, parent config inheritance (limits/settings/IP allowlist), admin
 * credential hardening, and remote-environment registration.
 */
@DisplayName("SandboxProvisioningService")
class SandboxProvisioningServiceTest {

    private static final String PARENT = "t1";

    /** BCrypt hash of "password" — the well-known default seeded by TenantProvisioningHook/V102. */
    private static final String WELL_KNOWN_DEFAULT_HASH =
            "$2a$10$zAQaSHX1XSR1bwUL3pz9EOzecplsxInVizZc9HwLf7xPluSiE1EP6";

    private EnvironmentRepository environmentRepository;
    private SandboxEnvironmentService environmentService;
    private PackageService packageService;
    private PackageImportService packageImportService;
    private QueryEngine queryEngine;
    private CollectionRegistry collectionRegistry;
    private PlatformEventPublisher eventPublisher;
    private JdbcTemplate jdbcTemplate;
    private SandboxProvisioningService service;

    @BeforeEach
    void setUp() {
        environmentRepository = mock(EnvironmentRepository.class);
        environmentService = mock(SandboxEnvironmentService.class);
        packageService = mock(PackageService.class);
        packageImportService = mock(PackageImportService.class);
        queryEngine = mock(QueryEngine.class);
        collectionRegistry = mock(CollectionRegistry.class);
        eventPublisher = mock(PlatformEventPublisher.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        when(environmentRepository.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(collectionRegistry.get("tenants")).thenReturn(SystemCollectionDefinitions.tenants());

        service = new SandboxProvisioningService(environmentRepository, environmentService,
                packageService, packageImportService, queryEngine, collectionRegistry, eventPublisher,
                new tools.jackson.databind.ObjectMapper());
    }

    private Map<String, Object> parentRow(String slug) {
        Map<String, Object> parent = new LinkedHashMap<>();
        parent.put("id", PARENT);
        parent.put("slug", slug);
        parent.put("name", "Acme");
        parent.put("edition", "ENTERPRISE");
        parent.put("settings", "{\"theme\":\"dark\"}");
        parent.put("limits", "{\"apiCalls\":1000}");
        parent.put("ip_allowlist_enabled", Boolean.TRUE);
        parent.put("ip_allowlist_cidrs", "[\"10.0.0.0/8\"]");
        parent.put("parent_tenant_id", null);
        return parent;
    }

    private void stubParent(Map<String, Object> parent) {
        when(jdbcTemplate.queryForList(contains("FROM tenant WHERE id"), eq(PARENT)))
                .thenReturn(List.of(parent));
    }

    // ------------------------------------------------------------------
    // createSandbox
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("createSandbox")
    class CreateSandbox {

        @SuppressWarnings("unchecked")
        private Map<String, Object> happyPathSetup() {
            stubParent(parentRow("acme"));
            when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM tenant WHERE slug"),
                    eq(Integer.class), eq("acme--dev"))).thenReturn(0);
            when(environmentRepository.existsByTenantAndName(PARENT, "dev")).thenReturn(false);

            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "sbx-tenant"));
            when(jdbcTemplate.update(contains("UPDATE user_credential"), any(), any(), any()))
                    .thenReturn(1);

            when(environmentService.ensureProductionEnvironment(PARENT, "admin"))
                    .thenReturn(Map.of("id", "prod-env-1"));
            when(environmentRepository.createWithSandboxTenant(eq(PARENT), eq("dev"), any(),
                    eq("SANDBOX"), eq("prod-env-1"), eq("sbx-tenant"), eq("admin")))
                    .thenReturn("env-1");

            Map<String, Object> envRow = new LinkedHashMap<>();
            envRow.put("id", "env-1");
            envRow.put("status", "CREATING");
            when(environmentRepository.findByIdAndTenant("env-1", PARENT))
                    .thenReturn(Optional.of(envRow));

            // Clone phase (invoked synchronously here — no async proxy in unit tests)
            // The clone resolves both tenants' slugs to bind schema-per-tenant context.
            when(jdbcTemplate.queryForList(contains("slug FROM tenant WHERE id"), eq("sbx-tenant")))
                    .thenReturn(List.of(Map.of("slug", "acme--dev")));
            when(jdbcTemplate.queryForList(contains("slug FROM tenant WHERE id"), eq(PARENT)))
                    .thenReturn(List.of(Map.of("slug", "acme")));
            Map<String, Object> options = Map.of("name", "sandbox-clone");
            Map<String, Object> pkg = Map.of("items", List.of());
            when(packageService.exportAllOptions(PARENT, "sandbox-clone", "1.0.0")).thenReturn(options);
            when(packageService.exportPackage(PARENT, options)).thenReturn(pkg);
            when(packageImportService.importPackage(eq("sbx-tenant"), eq(pkg), any()))
                    .thenReturn(new PackageImportService.ImportReport(1, 0, 0, 0, List.of()));
            return envRow;
        }

        @Test
        @DisplayName("derives the slug as {parent}--{name} and copies parent limits/settings/IP allowlist")
        void createsTenantWithInheritedConfig() {
            happyPathSetup();

            var result = service.createSandbox(PARENT, "dev", "desc", "SANDBOX", "admin");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> tenantCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(any(), tenantCaptor.capture());
            Map<String, Object> tenantData = tenantCaptor.getValue();
            assertThat(tenantData)
                    .containsEntry("slug", "acme--dev")
                    .containsEntry("name", "Acme (dev)")
                    .containsEntry("edition", "ENTERPRISE")
                    // JSONB values are parsed to structures — the QueryEngine's
                    // JSON-field validation rejects raw strings (harness-caught)
                    .containsEntry("settings", Map.of("theme", "dark"))
                    .containsEntry("limits", Map.of("apiCalls", 1000))
                    .containsEntry("ipAllowlistEnabled", Boolean.TRUE)
                    .containsEntry("ipAllowlistCidrs", java.util.List.of("10.0.0.0/8"))
                    .containsEntry("parentTenantId", PARENT);

            assertThat(result.get("sandboxSlug")).isEqualTo("acme--dev");
            assertThat(result.get("adminUsername")).isEqualTo("acme--dev-admin");
            assertThat(result.get("adminInitialPassword")).isNotNull();
        }

        @Test
        @DisplayName("hardens the seeded admin credential with a {bcrypt}-prefixed hash and clears force_change_on_login")
        void hardensAdminCredential() {
            happyPathSetup();

            var result = service.createSandbox(PARENT, "dev", null, "SANDBOX", "admin");

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object> hashCaptor = ArgumentCaptor.forClass(Object.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(),
                    hashCaptor.capture(), eq("sbx-tenant"), eq("acme--dev-admin"));
            assertThat(sqlCaptor.getValue()).contains("UPDATE user_credential")
                    .as("printed credential is a one-time secret — nothing left to force-change")
                    .contains("force_change_on_login = false");

            String hash = (String) hashCaptor.getValue();
            String password = (String) result.get("adminInitialPassword");
            assertThat(hash).as("DelegatingPasswordEncoder-compatible — kelta-auth rejects a bare bcrypt hash")
                    .startsWith("{bcrypt}");
            assertThat(hash).as("never the well-known seeded default")
                    .isNotEqualTo(WELL_KNOWN_DEFAULT_HASH);
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            String bareHash = hash.substring("{bcrypt}".length());
            assertThat(encoder.matches(password, bareHash))
                    .as("stored hash matches the one-time password returned to the caller")
                    .isTrue();
            assertThat(encoder.matches("password", bareHash))
                    .as("default password no longer works")
                    .isFalse();
        }

        @Test
        @DisplayName("marks the environment ACTIVE after a clean clone")
        void marksActiveAfterClone() {
            happyPathSetup();

            service.createSandbox(PARENT, "dev", null, "SANDBOX", "admin");

            verify(environmentRepository).updateStatus("env-1", PARENT, "ACTIVE");
        }

        @Test
        @DisplayName("rejects a combined slug longer than 63 characters with a helpful message")
        void rejectsOverlongSlug() {
            String longParentSlug = "a" + "b".repeat(57); // 58 chars, itself valid
            stubParent(parentRow(longParentSlug));

            assertThatThrownBy(() -> service.createSandbox(PARENT, "toolong", null, "SANDBOX", "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at most 63 characters")
                    .hasMessageContaining("Use a shorter name");
            verify(queryEngine, never()).create(any(), anyMap());
        }

        @Test
        @DisplayName("rejects creating a sandbox from a sandbox tenant")
        void rejectsNestedSandbox() {
            Map<String, Object> parent = parentRow("acme");
            parent.put("parent_tenant_id", "root-tenant");
            stubParent(parent);

            assertThatThrownBy(() -> service.createSandbox(PARENT, "dev", null, "SANDBOX", "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be created from a sandbox");
            verify(queryEngine, never()).create(any(), anyMap());
        }

        @Test
        @DisplayName("rejects a duplicate tenant slug")
        void rejectsDuplicateSlug() {
            stubParent(parentRow("acme"));
            when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM tenant WHERE slug"),
                    eq(Integer.class), eq("acme--dev"))).thenReturn(1);

            assertThatThrownBy(() -> service.createSandbox(PARENT, "dev", null, "SANDBOX", "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
            verify(queryEngine, never()).create(any(), anyMap());
        }

        @Test
        @DisplayName("rejects a duplicate environment name")
        void rejectsDuplicateEnvironmentName() {
            stubParent(parentRow("acme"));
            when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM tenant WHERE slug"),
                    eq(Integer.class), eq("acme--dev"))).thenReturn(0);
            when(environmentRepository.existsByTenantAndName(PARENT, "dev")).thenReturn(true);

            assertThatThrownBy(() -> service.createSandbox(PARENT, "dev", null, "SANDBOX", "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
            verify(queryEngine, never()).create(any(), anyMap());
        }

        @Test
        @DisplayName("rejects a blank sandbox name")
        void rejectsBlankName() {
            stubParent(parentRow("acme"));

            assertThatThrownBy(() -> service.createSandbox(PARENT, "  ", null, "SANDBOX", "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name is required");
        }
    }

    // ------------------------------------------------------------------
    // cloneIntoSandbox failure path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cloneIntoSandbox marks the environment FAILED when the import reports failures")
    void cloneMarksFailedOnImportFailures() {
        when(jdbcTemplate.queryForList(contains("slug FROM tenant WHERE id"), eq(PARENT)))
                .thenReturn(List.of(Map.of("slug", "acme")));
        when(jdbcTemplate.queryForList(contains("slug FROM tenant WHERE id"), eq("sbx-tenant")))
                .thenReturn(List.of(Map.of("slug", "acme--dev")));
        Map<String, Object> options = Map.of("name", "sandbox-clone");
        Map<String, Object> pkg = Map.of("items", List.of());
        when(packageService.exportAllOptions(PARENT, "sandbox-clone", "1.0.0")).thenReturn(options);
        when(packageService.exportPackage(PARENT, options)).thenReturn(pkg);
        when(packageImportService.importPackage(eq("sbx-tenant"), eq(pkg), any()))
                .thenReturn(new PackageImportService.ImportReport(0, 0, 0, 2, List.of()));

        service.cloneIntoSandbox(PARENT, "sbx-tenant", "env-1");

        verify(environmentRepository).updateStatus("env-1", PARENT, "FAILED");
    }

    @Test
    @DisplayName("cloneIntoSandbox imports with OVERWRITE so refreshes converge")
    void cloneUsesOverwrite() {
        when(jdbcTemplate.queryForList(contains("slug FROM tenant WHERE id"), eq(PARENT)))
                .thenReturn(List.of(Map.of("slug", "acme")));
        when(jdbcTemplate.queryForList(contains("slug FROM tenant WHERE id"), eq("sbx-tenant")))
                .thenReturn(List.of(Map.of("slug", "acme--dev")));
        Map<String, Object> options = Map.of("name", "sandbox-clone");
        Map<String, Object> pkg = Map.of("items", List.of());
        when(packageService.exportAllOptions(PARENT, "sandbox-clone", "1.0.0")).thenReturn(options);
        when(packageService.exportPackage(PARENT, options)).thenReturn(pkg);
        when(packageImportService.importPackage(eq("sbx-tenant"), eq(pkg), any()))
                .thenReturn(new PackageImportService.ImportReport(0, 0, 0, 0, List.of()));

        service.cloneIntoSandbox(PARENT, "sbx-tenant", "env-1");

        verify(packageImportService).importPackage(eq("sbx-tenant"), eq(pkg), argThat(opts ->
                opts.conflictMode() == PackageImportService.ConflictMode.OVERWRITE && !opts.dryRun()));
        verify(environmentRepository).updateStatus("env-1", PARENT, "ACTIVE");
    }

    // ------------------------------------------------------------------
    // createRemoteEnvironment
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("createRemoteEnvironment")
    class CreateRemoteEnvironment {

        @Test
        @DisplayName("rejects a non-http(s) base URL")
        void rejectsBadScheme() {
            assertThatThrownBy(() -> service.createRemoteEnvironment(PARENT, "remote", null,
                    "PRODUCTION", "ftp://remote.example.com", "acme", "vault-1", "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("http or https");
        }

        @Test
        @DisplayName("rejects a base URL with embedded credentials")
        void rejectsUserinfo() {
            assertThatThrownBy(() -> service.createRemoteEnvironment(PARENT, "remote", null,
                    "PRODUCTION", "https://user:pass@remote.example.com", "acme", "vault-1", "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not embed credentials");
        }

        @Test
        @DisplayName("requires a remote tenant slug")
        void requiresRemoteTenantSlug() {
            assertThatThrownBy(() -> service.createRemoteEnvironment(PARENT, "remote", null,
                    "PRODUCTION", "https://remote.example.com", " ", "vault-1", "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("remoteTenantSlug is required");
        }

        @Test
        @DisplayName("requires a vault credential reference")
        void requiresCredentialRef() {
            assertThatThrownBy(() -> service.createRemoteEnvironment(PARENT, "remote", null,
                    "PRODUCTION", "https://remote.example.com", "acme", null, "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("credentialRef is required");
        }

        @Test
        @DisplayName("registers a remote target without creating a tenant")
        void registersRemote() {
            when(environmentRepository.existsByTenantAndName(PARENT, "remote")).thenReturn(false);
            when(environmentRepository.createRemote(PARENT, "remote", null, "PRODUCTION",
                    "https://remote.example.com", "acme", "vault-1", "admin")).thenReturn("env-9");
            when(environmentRepository.findByIdAndTenant("env-9", PARENT))
                    .thenReturn(Optional.of(Map.of("id", "env-9")));

            var result = service.createRemoteEnvironment(PARENT, "remote", null, null,
                    "https://remote.example.com", "acme", "vault-1", "admin");

            assertThat(result.get("id")).isEqualTo("env-9");
            verify(environmentRepository).createRemote(PARENT, "remote", null, "PRODUCTION",
                    "https://remote.example.com", "acme", "vault-1", "admin");
            verifyNoInteractions(queryEngine);
        }
    }

    // ------------------------------------------------------------------
    // requireLocalSandbox guard
    // ------------------------------------------------------------------

    @Test
    @DisplayName("refresh/delete reject environments that are not tenant-backed sandboxes")
    void requiresLocalSandbox() {
        Map<String, Object> remoteEnv = new LinkedHashMap<>();
        remoteEnv.put("id", "env-r");
        remoteEnv.put("remote_base_url", "https://remote.example.com");
        remoteEnv.put("sandbox_tenant_id", null);
        when(environmentRepository.findByIdAndTenant("env-r", PARENT))
                .thenReturn(Optional.of(remoteEnv));

        assertThatThrownBy(() -> service.refreshSandbox("env-r", PARENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a tenant-backed local sandbox");
        assertThatThrownBy(() -> service.deleteSandbox("env-r", PARENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a tenant-backed local sandbox");
    }

    @Test
    @DisplayName("deleteSandbox archives the env row and decommissions the backing tenant")
    void deleteDecommissionsTenant() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("id", "env-1");
        env.put("sandbox_tenant_id", "sbx-tenant");
        when(environmentRepository.findByIdAndTenant("env-1", PARENT)).thenReturn(Optional.of(env));

        service.deleteSandbox("env-1", PARENT);

        verify(jdbcTemplate).update(contains("SET status = 'DECOMMISSIONED'"), eq("sbx-tenant"));
        verify(environmentRepository).updateStatus("env-1", PARENT, "ARCHIVED");
    }
}
