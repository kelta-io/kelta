package io.kelta.worker.filter;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.CallerContext.UserType;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.repository.BootstrapRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CallerContextFilter")
class CallerContextFilterTest {

    private static final String TENANT = "tenant-1";
    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String PROFILE = "22222222-2222-2222-2222-222222222222";

    private UserIdResolver userIdResolver;
    private BootstrapRepository bootstrapRepository;
    private CallerContextFilter filter;

    @BeforeEach
    void setUp() {
        userIdResolver = mock(UserIdResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        filter = new CallerContextFilter(userIdResolver, bootstrapRepository);
    }

    private record Outcome(boolean chained, Optional<CallerContext> caller, MockHttpServletResponse response) {}

    private Outcome run(MockHttpServletRequest request, String tenantId) {
        AtomicBoolean chained = new AtomicBoolean();
        AtomicReference<Optional<CallerContext>> seen = new AtomicReference<>(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();
        Runnable call = () -> {
            try {
                filter.doFilter(request, response, (req, res) -> {
                    chained.set(true);
                    seen.set(CallerContext.current());
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        if (tenantId == null) {
            call.run();
        } else {
            TenantContext.runWithTenant(tenantId, call);
        }
        return new Outcome(chained.get(), seen.get(), response);
    }

    private static MockHttpServletRequest request(String userId, String userType, String profileId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/watches");
        if (userId != null) request.addHeader("X-User-Id", userId);
        if (userType != null) request.addHeader("X-User-Type", userType);
        if (profileId != null) request.addHeader("X-User-Profile-Id", profileId);
        return request;
    }

    private void profileGrants(Map<String, Boolean> grants) {
        when(bootstrapRepository.findProfileSystemPermissions(PROFILE)).thenReturn(
                grants.entrySet().stream()
                        .map(e -> Map.<String, Object>of("permission_name", e.getKey(), "granted", e.getValue()))
                        .toList());
    }

    @Test
    @DisplayName("an email resolves to the caller's UUID")
    void emailResolvesToUuid() {
        when(userIdResolver.resolve("alice@example.com", TENANT)).thenReturn(ALICE);

        Outcome outcome = run(request("alice@example.com", "INTERNAL", null), TENANT);

        assertThat(outcome.chained()).isTrue();
        CallerContext caller = outcome.caller().orElseThrow();
        assertThat(caller.userId()).isEqualTo(ALICE);
        assertThat(caller.userType()).isEqualTo(UserType.INTERNAL);
        assertThat(caller.viewAll()).isFalse();
        assertThat(caller.modifyAll()).isFalse();
    }

    @Test
    @DisplayName("a UUID passes through and X-User-Type PORTAL is carried")
    void uuidPassesThrough() {
        when(userIdResolver.resolve(ALICE, TENANT)).thenReturn(ALICE);

        Outcome outcome = run(request(ALICE, "PORTAL", null), TENANT);

        CallerContext caller = outcome.caller().orElseThrow();
        assertThat(caller.userId()).isEqualTo(ALICE);
        assertThat(caller.userType()).isEqualTo(UserType.PORTAL);
    }

    @Test
    @DisplayName("an unknown email is rejected 401 CALLER_UNRESOLVED without reaching the chain")
    void unknownEmailIsRejected() throws Exception {
        // JdbcUserIdResolver returns the raw identifier when it cannot resolve it.
        when(userIdResolver.resolve("ghost@example.com", TENANT)).thenReturn("ghost@example.com");

        Outcome outcome = run(request("ghost@example.com", "INTERNAL", null), TENANT);

        assertThat(outcome.chained()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(401);
        assertThat(outcome.response().getContentAsString())
                .contains("\"code\":\"CALLER_UNRESOLVED\"")
                .contains("\"status\":\"401\"");
    }

    @Test
    @DisplayName("no identity headers bind no context (internal tier)")
    void noHeadersNoContext() {
        Outcome outcome = run(request(null, null, null), TENANT);

        assertThat(outcome.chained()).isTrue();
        assertThat(outcome.caller()).isEmpty();
        verify(userIdResolver, never()).resolve(anyString(), anyString());
    }

    @Test
    @DisplayName("no tenant binds no context")
    void noTenantNoContext() {
        Outcome outcome = run(request("alice@example.com", "INTERNAL", null), null);

        assertThat(outcome.chained()).isTrue();
        assertThat(outcome.caller()).isEmpty();
    }

    @Test
    @DisplayName("a machine identity (connected-app client id) binds no context and is not rejected")
    void machineIdentityPassesUnbound() {
        Outcome outcome = run(request("reporting-app-client", "INTERNAL", null), TENANT);

        assertThat(outcome.chained()).isTrue();
        assertThat(outcome.caller()).isEmpty();
        verify(userIdResolver, never()).resolve(anyString(), anyString());
    }

    @Test
    @DisplayName("a profile holding VIEW_ALL_DATA sets viewAll")
    void viewAllDataSetsViewAll() {
        when(userIdResolver.resolve("alice@example.com", TENANT)).thenReturn(ALICE);
        profileGrants(Map.of("VIEW_ALL_DATA", true, "MODIFY_ALL_DATA", false));

        CallerContext caller = run(request("alice@example.com", "INTERNAL", PROFILE), TENANT)
                .caller().orElseThrow();

        assertThat(caller.viewAll()).isTrue();
        assertThat(caller.modifyAll()).isFalse();
    }

    @Test
    @DisplayName("a profile holding MODIFY_ALL_DATA sets modifyAll")
    void modifyAllDataSetsModifyAll() {
        when(userIdResolver.resolve("alice@example.com", TENANT)).thenReturn(ALICE);
        profileGrants(Map.of("MODIFY_ALL_DATA", true));

        CallerContext caller = run(request("alice@example.com", "INTERNAL", PROFILE), TENANT)
                .caller().orElseThrow();

        assertThat(caller.modifyAll()).isTrue();
        assertThat(caller.viewAll()).isFalse();
    }

    @Test
    @DisplayName("a PORTAL caller never gets the bypass flags, whatever the profile grants")
    void portalNeverBypasses() {
        when(userIdResolver.resolve("member@example.com", TENANT)).thenReturn(ALICE);
        profileGrants(Map.of("VIEW_ALL_DATA", true, "MODIFY_ALL_DATA", true));

        CallerContext caller = run(request("member@example.com", "PORTAL", PROFILE), TENANT)
                .caller().orElseThrow();

        assertThat(caller.viewAll()).isFalse();
        assertThat(caller.modifyAll()).isFalse();
        verify(bootstrapRepository, never()).findProfileSystemPermissions(anyString());
    }

    @Test
    @DisplayName("internal endpoints are not filtered")
    void skipsInternalEndpoints() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/user-identity");
        request.setRequestURI("/internal/user-identity");
        request.addHeader("X-User-Id", "ghost@example.com");

        Outcome outcome = run(request, TENANT);

        assertThat(outcome.chained()).isTrue();
        assertThat(outcome.caller()).isEmpty();
        assertThat(outcome.response().getStatus()).isEqualTo(200);
    }
}
