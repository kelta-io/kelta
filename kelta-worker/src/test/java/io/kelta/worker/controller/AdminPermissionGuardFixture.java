package io.kelta.worker.controller;

import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * A real {@link AdminPermissionGuard} over a stubbed profile-permission lookup, for admin
 * controller tests: {@link #request()} carries {@link #PROFILE_ID}, which holds exactly the
 * permissions passed to {@link #granting}.
 */
final class AdminPermissionGuardFixture {

    static final String PROFILE_ID = "admin-profile-1";

    private AdminPermissionGuardFixture() {}

    /** A guard under which {@link #PROFILE_ID} holds {@code permissions} (and only those) granted. */
    static AdminPermissionGuard granting(String... permissions) {
        BootstrapRepository bootstrapRepository = mock(BootstrapRepository.class,
                withSettings().strictness(Strictness.LENIENT));
        List<Map<String, Object>> rows = Arrays.stream(permissions)
                .map(p -> Map.<String, Object>of("permission_name", p, "granted", true))
                .toList();
        lenient().when(bootstrapRepository.findProfileSystemPermissions(PROFILE_ID)).thenReturn(rows);
        return new AdminPermissionGuard(new CerbosPermissionResolver(), bootstrapRepository);
    }

    /** A guard under which {@link #PROFILE_ID} holds {@code API_ACCESS} only, as the seeded Portal User profile does. */
    static AdminPermissionGuard apiAccessOnly() {
        return granting("API_ACCESS");
    }

    /** A gateway-forwarded admin request from {@link #PROFILE_ID}. */
    static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Profile-Id", PROFILE_ID);
        return request;
    }

    /** Asserts {@code call} is refused 403 naming {@code permission}. */
    static void assertForbidden(Runnable call, String permission) {
        ResponseStatusException e = catchThrowableOfType(ResponseStatusException.class, call::run);
        assertThat(e).as("expected 403 for missing " + permission).isNotNull();
        assertThat(e.getStatusCode().value()).isEqualTo(403);
        assertThat(e.getReason()).isEqualTo(permission + " permission required");
    }

    /** Asserts {@code call} is refused 403 because the request carries no profile. */
    static void assertForbiddenNoIdentity(Runnable call) {
        ResponseStatusException e = catchThrowableOfType(ResponseStatusException.class, call::run);
        assertThat(e).as("expected 403 for a request with no profile").isNotNull();
        assertThat(e.getStatusCode().value()).isEqualTo(403);
        assertThat(e.getReason()).isEqualTo("No identity");
    }
}
