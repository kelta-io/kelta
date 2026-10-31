package io.kelta.worker.service;

import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.worker.cache.WorkerCacheManager;
import io.kelta.worker.repository.BootstrapRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Cerbos Admin API password has no built-in default. Without one the service still
 * constructs (the worker starts, and authorization reads over gRPC are a different client), but
 * pushes nothing: every sync and seed returns before touching the database or the Admin API.
 */
@DisplayName("CerbosPolicySyncService without an Admin API password")
class CerbosPolicySyncAdminPasswordTest {

    private JdbcTemplate jdbcTemplate;
    private BootstrapRepository bootstrapRepository;
    private CerbosPolicyGenerator policyGenerator;
    private PlatformEventPublisher eventPublisher;
    private WorkerCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        policyGenerator = mock(CerbosPolicyGenerator.class);
        eventPublisher = mock(PlatformEventPublisher.class);
        cacheManager = mock(WorkerCacheManager.class);
    }

    private CerbosPolicySyncService service(String password) {
        // Port 9 (discard) on loopback: nothing in these tests may reach it.
        return new CerbosPolicySyncService(jdbcTemplate, bootstrapRepository, policyGenerator,
                new ObjectMapper(), eventPublisher, cacheManager, "127.0.0.1", 9, "cerbos", password);
    }

    @ParameterizedTest(name = "password [{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("constructs, reports the Admin API as not configured and pushes nothing")
    void blankPasswordPushesNothing(String password) {
        CerbosPolicySyncService service = service(password);

        assertThat(service.isAdminApiConfigured()).isFalse();
        assertThat(service.basePoliciesPresent()).isFalse();

        service.syncTenant("00000000-0000-0000-0000-000000000001");
        service.seedBasePolicies();
        service.syncAllTenants();

        verifyNoInteractions(jdbcTemplate, bootstrapRepository, policyGenerator, eventPublisher, cacheManager);
    }

    @Test
    @DisplayName("with a password the Admin API is configured and a tenant sync reads the tenant's profiles")
    void passwordConfigured() {
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());
        CerbosPolicySyncService service = service("test-only-password");

        assertThat(service.isAdminApiConfigured()).isTrue();

        // No profiles: the sync stops before generating or pushing anything.
        service.syncTenant("00000000-0000-0000-0000-000000000001");
        verify(jdbcTemplate).queryForList(anyString(), anyString());
        verifyNoInteractions(policyGenerator, eventPublisher, cacheManager);
    }
}
