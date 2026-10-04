package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.WinRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/wins} authorizes before it validates. Members hold no {@code canCreate} on
 * wins, so the decision is "a resolvable member identity"; a caller without one gets 403 whatever
 * the body, and only a resolved member is told what is wrong with theirs (9-win-tracking.md).
 */
@DisplayName("WinController — create authorizes before body validation")
class WinControllerCreateAuthzMvcTest {

    private static final String TENANT = "t1";
    private static final String MEMBER = "member-1";

    private QueryEngine queryEngine;
    private UserIdResolver userIdResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queryEngine = mock(QueryEngine.class);
        userIdResolver = mock(UserIdResolver.class);
        when(userIdResolver.resolve(eq(MEMBER), any())).thenReturn(MEMBER);

        WinController controller = new WinController(mock(WinRepository.class), queryEngine,
                mock(CollectionRegistry.class), userIdResolver, mock(CerbosPermissionResolver.class),
                mock(BootstrapRepository.class), mock(DynamicCollectionRouter.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("no member identity + empty body → 403, not a 400 about the body")
    void noIdentityEmptyBodyIsForbidden() throws Exception {
        mvc.perform(post("/api/wins").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        verify(queryEngine, never()).create(any(), any());
    }

    @Test
    @DisplayName("no member identity + invalid body → 403")
    void noIdentityInvalidBodyIsForbidden() throws Exception {
        mvc.perform(post("/api/wins").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("unresolvable identity + invalid body → 403")
    void unresolvableIdentityIsForbidden() throws Exception {
        mvc.perform(post("/api/wins").header("X-User-Id", "stranger@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("resolved member + invalid body → 400")
    void resolvedMemberInvalidBodyIsBadRequest() throws Exception {
        mvc.perform(post("/api/wins").header("X-User-Id", MEMBER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"summary\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verify(queryEngine, never()).create(any(), any());
    }

    @Test
    @DisplayName("resolved member + empty body → 400")
    void resolvedMemberEmptyBodyIsBadRequest() throws Exception {
        mvc.perform(post("/api/wins").header("X-User-Id", MEMBER).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
