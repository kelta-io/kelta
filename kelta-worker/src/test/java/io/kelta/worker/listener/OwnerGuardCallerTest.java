package io.kelta.worker.listener;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.router.UserIdResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OwnerGuardCaller")
class OwnerGuardCallerTest {

    private static final String TENANT = "tenant-1";
    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";

    private final UserIdResolver userIdResolver = mock(UserIdResolver.class);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void headerIs(String identifier) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", identifier);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static CallerContext caller(String userId) {
        return new CallerContext(userId, CallerContext.UserType.PORTAL, false, false);
    }

    @Test
    @DisplayName("reads the bound CallerContext without re-resolving X-User-Id")
    void prefersCallerContext() {
        headerIs("alice@example.com");

        String resolved = CallerContext.callAs(caller(ALICE),
                () -> OwnerGuardCaller.resolve(userIdResolver, TENANT));

        assertThat(resolved).isEqualTo(ALICE);
        verify(userIdResolver, never()).resolve(anyString(), anyString());
    }

    @Test
    @DisplayName("falls back to resolving X-User-Id when no CallerContext is bound")
    void fallsBackToHeader() {
        headerIs("alice@example.com");
        when(userIdResolver.resolve("alice@example.com", TENANT)).thenReturn(ALICE);

        assertThat(OwnerGuardCaller.resolve(userIdResolver, TENANT)).isEqualTo(ALICE);
    }

    @Test
    @DisplayName("an unresolvable header identity is the REJECTED sentinel; no request is internal tier")
    void rejectedAndInternalTier() {
        assertThat(OwnerGuardCaller.resolve(userIdResolver, TENANT)).isNull();

        headerIs("reporting-app-client");
        when(userIdResolver.resolve("reporting-app-client", TENANT)).thenReturn("reporting-app-client");
        assertThat(OwnerGuardCaller.resolve(userIdResolver, TENANT)).isEqualTo(OwnerGuardCaller.REJECTED);
    }

    @Test
    @DisplayName("a guard hook enforces ownership against the bound caller")
    void hookUsesCallerContext() {
        NoteGuardHook hook = new NoteGuardHook(userIdResolver, null, null);
        Map<String, Object> record = new HashMap<>();
        record.put("createdBy", ALICE);

        assertThat(CallerContext.callAs(caller(ALICE), () -> hook.beforeCreate(record, TENANT)).isSuccess())
                .isTrue();
        assertThat(CallerContext.callAs(caller(BOB), () -> hook.beforeCreate(record, TENANT)).isSuccess())
                .isFalse();
    }
}
