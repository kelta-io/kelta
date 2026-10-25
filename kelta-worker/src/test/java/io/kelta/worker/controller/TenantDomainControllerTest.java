package io.kelta.worker.controller;

import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.events.RecordEventPublisher;
import io.kelta.worker.cache.WorkerCacheManager;
import io.kelta.worker.listener.CustomDomainEventPublisher;
import io.kelta.worker.service.DomainOwnershipVerifier;
import io.kelta.worker.service.LoggingCustomDomainProvisioner;
import io.kelta.worker.util.WithTenant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantDomainController Tests")
@WithTenant("tenant-1")
class TenantDomainControllerTest {

    private final MockHttpServletRequest adminRequest = AdminPermissionGuardFixture.request();

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RecordEventPublisher recordEventPublisher;
    @Mock private PlatformEventPublisher platformEventPublisher;
    @Mock private DomainOwnershipVerifier ownershipVerifier;
    private TenantDomainController controller;

    @BeforeEach
    void setUp() {
        WorkerCacheManager cacheManager = new WorkerCacheManager(new SimpleMeterRegistry());
        CustomDomainEventPublisher domainEventPublisher =
                new CustomDomainEventPublisher(platformEventPublisher);
        org.mockito.Mockito.lenient().when(ownershipVerifier.challengeRecordName(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> "_kelta-verify." + inv.getArgument(0));
        controller = new TenantDomainController(jdbcTemplate, cacheManager, recordEventPublisher,
                domainEventPublisher, new LoggingCustomDomainProvisioner(), ownershipVerifier,
                AdminPermissionGuardFixture.granting("CUSTOMIZE_APPLICATION"));
    }

    @Test
    void shouldRegisterValidDomain() {
        when(jdbcTemplate.queryForList(contains("tenant_custom_domain WHERE domain"), eq("app.acme.com")))
                .thenReturn(List.of());

        var response = controller.registerDomain(adminRequest, Map.of("domain", "app.acme.com"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void shouldRejectDuplicateDomain() {
        when(jdbcTemplate.queryForList(contains("tenant_custom_domain WHERE domain"), eq("taken.com")))
                .thenReturn(List.of(Map.of("id", "existing")));

        var response = controller.registerDomain(adminRequest, Map.of("domain", "taken.com"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void shouldRejectReservedDomain() {
        var response = controller.registerDomain(adminRequest, Map.of("domain", "app.kelta.io"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldRejectInvalidFormat() {
        var response = controller.registerDomain(adminRequest, Map.of("domain", "not a domain!"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldRejectBlankDomain() {
        var response = controller.registerDomain(adminRequest, Map.of("domain", ""));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Nested
    @DisplayName("resolveDomain Tests")
    class ResolveDomain {

        @Test
        void shouldResolveDomainFromDatabase() {
            when(jdbcTemplate.queryForList(contains("tenant_custom_domain tcd"), eq("app.acme.com")))
                    .thenReturn(List.of(Map.of("slug", "acme")));

            var response = controller.resolveDomain("app.acme.com");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("acme");
        }

        @Test
        void shouldReturnNotFoundForUnknownDomain() {
            when(jdbcTemplate.queryForList(contains("tenant_custom_domain tcd"), eq("unknown.com")))
                    .thenReturn(List.of());

            var response = controller.resolveDomain("unknown.com");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void shouldCacheNegativeLookupAndNotQueryDbAgain() {
            when(jdbcTemplate.queryForList(contains("tenant_custom_domain tcd"), eq("unknown.com")))
                    .thenReturn(List.of());

            // First call: cache miss, queries DB
            controller.resolveDomain("unknown.com");

            // Second call: should use cached negative result, not query DB again
            var response = controller.resolveDomain("unknown.com");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            // DB should only have been queried once
            org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.times(1))
                    .queryForList(contains("tenant_custom_domain tcd"), eq("unknown.com"));
        }

        @Test
        void shouldCachePositiveLookupAndNotQueryDbAgain() {
            when(jdbcTemplate.queryForList(contains("tenant_custom_domain tcd"), eq("app.acme.com")))
                    .thenReturn(List.of(Map.of("slug", "acme")));

            // First call: cache miss, queries DB
            controller.resolveDomain("app.acme.com");

            // Second call: should use cached result
            var response = controller.resolveDomain("app.acme.com");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("acme");

            // DB should only have been queried once
            org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.times(1))
                    .queryForList(contains("tenant_custom_domain tcd"), eq("app.acme.com"));
        }
    }

    @Test
    @DisplayName("A profile without CUSTOMIZE_APPLICATION (API_ACCESS only, like Portal User) gets 403 on every handler")
    void deniedWithoutCustomizeApplication() {
        TenantDomainController denied = new TenantDomainController(jdbcTemplate,
                new WorkerCacheManager(new SimpleMeterRegistry()), recordEventPublisher,
                new CustomDomainEventPublisher(platformEventPublisher), new LoggingCustomDomainProvisioner(),
                ownershipVerifier, AdminPermissionGuardFixture.apiAccessOnly());

        AdminPermissionGuardFixture.assertForbidden(() -> denied.listDomains(adminRequest), "CUSTOMIZE_APPLICATION");
        AdminPermissionGuardFixture.assertForbidden(() -> denied.registerDomain(adminRequest, Map.of("domain", "app.acme.com")),
                "CUSTOMIZE_APPLICATION");
        AdminPermissionGuardFixture.assertForbidden(() -> denied.verifyDomain(adminRequest, "d-1"), "CUSTOMIZE_APPLICATION");
        AdminPermissionGuardFixture.assertForbidden(() -> denied.removeDomain(adminRequest, "d-1"), "CUSTOMIZE_APPLICATION");
        verifyNoInteractions(jdbcTemplate, recordEventPublisher, platformEventPublisher);
    }

    @Test
    @DisplayName("A profile holding CUSTOMIZE_APPLICATION can list domains")
    void allowedWithCustomizeApplication() {
        when(jdbcTemplate.queryForList(contains("tenant_custom_domain"), eq("tenant-1"))).thenReturn(List.of());

        assertThat(controller.listDomains(adminRequest).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
