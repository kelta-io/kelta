package io.kelta.worker.controller;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.worker.module.RuntimeModuleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The controller's own request mapping, which nothing covered.
 *
 * <p>On prod, POST routes dispatched while GET routes returned 404 with the identical mapping,
 * identical registry contents and the module ACTIVE. Dispatch itself resolves both correctly in
 * isolation, so the gap was always the mapping — and it had no test.
 */
@DisplayName("ModuleHttpController mapping")
class ModuleHttpControllerMvcTest {

    private RuntimeModuleManager manager;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        manager = mock(RuntimeModuleManager.class);
        when(manager.dispatchRoute(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(ActionResult.success(Map.of("ok", true))));

        @SuppressWarnings("unchecked")
        ObjectProvider<RuntimeModuleManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(manager);

        mvc = MockMvcBuilders.standaloneSetup(new ModuleHttpController(provider)).build();
    }

    @Test
    @DisplayName("GET reaches dispatch")
    void getIsMapped() throws Exception {
        mvc.perform(get("/api/modules/kelta-billing/x/plans")
                        .header("X-Tenant-ID", "t1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST reaches dispatch")
    void postIsMapped() throws Exception {
        mvc.perform(post("/api/modules/kelta-billing/x/checkout-sessions")
                        .header("X-Tenant-ID", "t1")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the path handed to the module excludes the platform prefix")
    void dispatchReceivesModuleRelativePath() throws Exception {
        mvc.perform(get("/api/modules/kelta-billing/x/plans").header("X-Tenant-ID", "t1"));

        org.mockito.Mockito.verify(manager).dispatchRoute(
                eq("t1"), eq("kelta-billing"), any(), eq("GET"), eq("/plans"), any(), any());
    }

    /**
     * The regression that cost a night. {@code DynamicCollectionRouter} maps
     * {@code /api/{parentName}/{parentId}/{childName}/{childId}} for GET, which matches a module
     * route exactly, and Spring ranks any pattern containing {@code **} last -- so the old bare
     * {@code /**} mapping lost the GET and the request was dispatched as a record read of a
     * collection called "modules". POST was unaffected because that router has no equally deep POST
     * mapping, which is precisely why testing this controller alone could never catch it.
     *
     * <p>So the competitor is registered alongside it here. Testing the mapping in isolation is
     * what let this ship.
     */
    @Test
    @DisplayName("GET beats the generic nested-collection route that shadowed it on prod")
    void getWinsAgainstNestedCollectionRoute() throws Exception {
        MockMvc withCompetitor = MockMvcBuilders
                .standaloneSetup(new ModuleHttpController(providerOf(manager)), new NestedRouteStub())
                .build();

        withCompetitor.perform(get("/api/modules/kelta-billing/x/plans")
                        .header("X-Tenant-ID", "t1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not("nested-collection-router")));
    }

    /**
     * The gateway puts the caller's email in {@code X-User-Id}; the rest of the platform keys
     * members by the UUID {@code CallerContextFilter} resolves. Handing a module the header made
     * billing stamp the email on Stripe, so a paying member's subscription row was invisible to
     * entitlement resolution.
     */
    @Test
    @DisplayName("the module receives the resolved user UUID, not the X-User-Id email")
    void dispatchReceivesResolvedUserUuid() throws Exception {
        CallerContext caller = new CallerContext("u-uuid", CallerContext.UserType.PORTAL,
                false, false);
        MockMvc withCaller = MockMvcBuilders
                .standaloneSetup(new ModuleHttpController(providerOf(manager)))
                .addFilter((request, response, chain) -> CallerContext.runAs(caller, () -> {
                    try {
                        chain.doFilter(request, response);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }))
                .build();

        withCaller.perform(post("/api/modules/kelta-billing/x/checkout-sessions")
                        .header("X-Tenant-ID", "t1")
                        .header("X-User-Id", "someone@example.com")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(manager).dispatchRoute(eq("t1"), eq("kelta-billing"),
                eq("u-uuid"), eq("POST"), eq("/checkout-sessions"), any(), any());
    }

    @Test
    @DisplayName("with no resolved caller the module receives a null user, never the raw header")
    void dispatchReceivesNullUserWithoutCallerContext() throws Exception {
        mvc.perform(get("/api/modules/kelta-billing/x/plans")
                        .header("X-Tenant-ID", "t1")
                        .header("X-User-Id", "someone@example.com"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(manager).dispatchRoute(eq("t1"), eq("kelta-billing"),
                isNull(), eq("GET"), eq("/plans"), any(), any());
    }

    private static ObjectProvider<RuntimeModuleManager> providerOf(RuntimeModuleManager m) {
        @SuppressWarnings("unchecked")
        ObjectProvider<RuntimeModuleManager> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(m);
        return p;
    }

    /** Stands in for {@code DynamicCollectionRouter}'s nested GET mapping, shape for shape. */
    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/api")
    static class NestedRouteStub {
        @org.springframework.web.bind.annotation.GetMapping(
                "/{parentName}/{parentId}/{childName}/{childId}")
        String nested() {
            return "nested-collection-router";
        }
    }
}
