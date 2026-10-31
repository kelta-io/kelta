package io.kelta.worker.controller;

import io.kelta.runtime.module.ModuleManifestParser;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.worker.module.ModuleRouteRegistry;
import io.kelta.worker.module.RuntimeModuleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end route dispatch, with the billing module's real manifest.
 *
 * <p>On prod, POST routes dispatched to their handlers while GET routes returned 404, with all four
 * routes reported registered and the module ACTIVE on every pod. This exercises the same path —
 * manifest → registry → dispatch — so the asymmetry has somewhere to reproduce.
 */
@DisplayName("Module route dispatch")
class ModuleHttpDispatchTest {

    private static final String TENANT = "t1";

    private ActionHandlerRegistry handlers;
    private ModuleRouteRegistry routes;
    private RuntimeModuleManager manager;

    /** The context the last dispatched handler saw, so inputs can be asserted directly. */
    private final java.util.concurrent.atomic.AtomicReference<io.kelta.runtime.workflow.ActionContext>
            lastContext = new java.util.concurrent.atomic.AtomicReference<>();

    private ActionHandler handler(String key) {
        return new ActionHandler() {
            @Override public String getActionTypeKey() { return key; }
            @Override public ActionResult execute(io.kelta.runtime.workflow.ActionContext c) {
                lastContext.set(c);
                return ActionResult.success(Map.of("handled", key));
            }
        };
    }

    @BeforeEach
    void setUp() throws Exception {
        handlers = new ActionHandlerRegistry();
        routes = new ModuleRouteRegistry();

        String json = Files.readString(
                Path.of("../kelta-modules/billing-jar/src/main/resources/kelta-module.json"));
        var manifest = new ModuleManifestParser(new ObjectMapper()).parse(json);
        routes.register(TENANT, "kelta-billing", manifest.routes());
        for (var r : manifest.routes()) {
            handlers.registerTenantHandler(TENANT, handler(r.handlerKey()));
        }

        ModuleStore store = mock(ModuleStore.class);
        when(store.findByTenantAndModuleId(any(), any())).thenReturn(java.util.Optional.of(
            new TenantModuleData("m1", TENANT, "kelta-billing", "Kelta Billing", "1.0.0", "d",
                "u", "ck", 1L, "C", json, TenantModuleData.STATUS_ACTIVE, "me",
                Instant.now(), Instant.now(), "s3/k.jar", List.of())));

        manager = new RuntimeModuleManager(store, handlers, new ObjectMapper(),
                null, null, null, null, null);
        manager.setModuleRouteRegistry(routes);
    }

    @Test
    @DisplayName("every declared route dispatches — GET as well as POST")
    void everyRouteDispatches() {
        assertThat(manager.dispatchRoute(TENANT, "kelta-billing", "u1", "GET", "/plans",
                Map.of(), null)).isPresent();
        assertThat(manager.dispatchRoute(TENANT, "kelta-billing", "u1", "GET", "/me",
                Map.of(), null)).isPresent();
        assertThat(manager.dispatchRoute(TENANT, "kelta-billing", "u1", "POST", "/checkout-sessions",
                Map.of(), "{}")).isPresent();
        assertThat(manager.dispatchRoute(TENANT, "kelta-billing", "u1", "POST", "/portal-sessions",
                Map.of(), "{}")).isPresent();
    }

    @Test
    @DisplayName("a method the route does not declare does not dispatch")
    void wrongMethodDoesNotDispatch() {
        assertThat(manager.dispatchRoute(TENANT, "kelta-billing", "u1", "POST", "/plans",
                Map.of(), "{}")).isEmpty();
    }

    @Test
    @DisplayName("a JSON body becomes handler inputs, not just rawBody")
    void jsonBodyBecomesInputs() {
        // dispatchRoute built "input" from query parameters alone and passed the body only as
        // rawBody, so a POST handler reading its own fields found nothing and answered
        // "planCode ... is required" against a request that supplied them. That is indistinguishable
        // from real validation from outside, which is how it reached production and broke checkout
        // and the billing portal for members.
        manager.dispatchRoute(TENANT, "kelta-billing", "u1", "POST", "/checkout-sessions",
                Map.of(), "{\"planCode\":\"PRO\",\"successUrl\":\"https://a.test/ok\"}");

        // Handlers read through the module-side ActionInputs, which unwraps "input" -- so that is
        // the map the fields must land in.
        Map<?, ?> inputs = (Map<?, ?>) lastContext.get().resolvedData().get("input");
        assertThat(inputs.get("planCode")).isEqualTo("PRO");
        assertThat(inputs.get("successUrl")).isEqualTo("https://a.test/ok");
    }

    @Test
    @DisplayName("the exact body bytes still reach the handler for signature verification")
    void rawBodyIsPreserved() {
        String body = "{\"id\":\"evt_1\"}";
        manager.dispatchRoute(TENANT, "kelta-billing", "u1", "POST", "/checkout-sessions",
                Map.of(), body);

        // A webhook verifies a signature over the bytes it was sent; a re-serialised map will not do.
        assertThat(lastContext.get().resolvedData().get("rawBody")).isEqualTo(body);
    }

    @Test
    @DisplayName("the body wins over a query parameter of the same name")
    void bodyOverridesQuery() {
        manager.dispatchRoute(TENANT, "kelta-billing", "u1", "POST", "/checkout-sessions",
                Map.of("planCode", "FROM_QUERY"), "{\"planCode\":\"FROM_BODY\"}");

        assertThat(((Map<?, ?>) lastContext.get().resolvedData().get("input")).get("planCode"))
                .isEqualTo("FROM_BODY");
    }

    @Test
    @DisplayName("a non-object or malformed body dispatches with no inputs rather than failing")
    void malformedBodyIsTolerated() {
        for (String body : new String[]{"[1,2]", "\"scalar\"", "not json at all", ""}) {
            var result = manager.dispatchRoute(TENANT, "kelta-billing", "u1", "POST",
                    "/checkout-sessions", Map.of(), body);
            assertThat(result).as("body=%s", body).isPresent();
        }
    }
}
