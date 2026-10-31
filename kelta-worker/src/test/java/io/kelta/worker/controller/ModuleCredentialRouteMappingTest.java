package io.kelta.worker.controller;

import io.kelta.runtime.context.CallerContext;
import io.kelta.worker.module.ModuleCredentialService;
import io.kelta.worker.module.ModuleCredentialService.ModuleCredential;
import io.kelta.worker.module.ModuleCredentialService.Refusal;
import io.kelta.worker.module.ModuleCredentialService.RefusedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/modules/self/credentials/{name}} is four segments under {@code /api}, the depth of
 * {@code DynamicCollectionRouter}'s {@code /{parentName}/{parentId}/{childName}/{childId}} GET. The
 * literal mapping must win; the router stand-in is registered because the controller alone would
 * pass against a broken mapping (CLAUDE.md pitfall). The other cases pin how each refusal of
 * {@link ModuleCredentialService} reaches the module, and that every answer is {@code no-store}.
 */
@DisplayName("Module credential endpoint vs the generic collection router")
class ModuleCredentialRouteMappingTest {

    private static final String TENANT = "tenant-1";
    private static final String PATH = "/api/modules/self/credentials/stripe";

    /** Stands in for {@code DynamicCollectionRouter}'s GET mappings, shape for shape. */
    @RestController
    @RequestMapping("/api")
    static class RouterStub {
        @GetMapping("/{collectionName}")
        String one(@PathVariable String collectionName) {
            return "router";
        }

        @GetMapping("/{collectionName}/{id}")
        String two(@PathVariable String collectionName, @PathVariable String id) {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}")
        String three(@PathVariable String parentName, @PathVariable String parentId,
                     @PathVariable String childName) {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}/{childId}")
        String four(@PathVariable String parentName, @PathVariable String parentId,
                    @PathVariable String childName, @PathVariable String childId) {
            return "router";
        }
    }

    private ModuleCredentialService service;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(ModuleCredentialService.class);
        ObjectProvider<ModuleCredentialService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        mvc = MockMvcBuilders.standaloneSetup(new ModuleCredentialController(provider), new RouterStub()).build();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String callerUserId) throws Exception {
        if (callerUserId == null) {
            return mvc.perform(request.header("X-Tenant-ID", TENANT));
        }
        CallerContext caller = new CallerContext(callerUserId, CallerContext.UserType.INTERNAL, false, false);
        try {
            return CallerContext.callAs(caller, () -> {
                try {
                    return mvc.perform(request.header("X-Tenant-ID", TENANT));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IllegalStateException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    @DisplayName("the credential reaches ModuleCredentialController: secrets + metadata, no-store")
    void credentialWinsOverTheRouter() throws Exception {
        when(service.read(TENANT, "app-1", "svc-1", "stripe", null)).thenReturn(new ModuleCredential("stub",
                "stripe", "stripe", Map.of("secretKey", "sk_test_placeholder"),
                Map.of("allowedReturnOrigins", List.of("https://app.example.test"))));

        perform(get(PATH).header("X-Connected-App-Id", "app-1"), "svc-1")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.name").value("stripe"))
                .andExpect(jsonPath("$.type").value("stripe"))
                .andExpect(jsonPath("$.secrets.secretKey").value("sk_test_placeholder"))
                .andExpect(jsonPath("$.metadata.allowedReturnOrigins[0]").value("https://app.example.test"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("the module's purpose label is passed through; a malformed one is 400 before any check")
    void purpose() throws Exception {
        when(service.read(TENANT, "app-1", "svc-1", "stripe", "STRIPE_API:checkout")).thenReturn(
                new ModuleCredential("stub", "stripe", "stripe", Map.of(), Map.of()));

        perform(get(PATH).param("purpose", "STRIPE_API:checkout").header("X-Connected-App-Id", "app-1"), "svc-1")
                .andExpect(status().isOk());
        perform(get(PATH).param("purpose", "has space").header("X-Connected-App-Id", "app-1"), "svc-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.errors[0].source.parameter").value("purpose"));
        verify(service, never()).read(any(), any(), any(), any(), eq("has space"));
    }

    @Test
    @DisplayName("a caller with no app id and no resolved user is handed to the service as such, and refused")
    void noIdentity() throws Exception {
        when(service.read(eq(TENANT), isNull(), isNull(), eq("stripe"), isNull()))
                .thenThrow(new RefusedException(Refusal.NOT_MODULE_PRINCIPAL, "refused"));

        perform(get(PATH), null)
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_MODULE_PRINCIPAL"));
    }

    @Test
    @DisplayName("each refusal maps to its status and code")
    void refusals() throws Exception {
        Map<Refusal, Object[]> expected = Map.of(
                Refusal.NOT_MODULE_PRINCIPAL, new Object[] {403, "NOT_MODULE_PRINCIPAL"},
                Refusal.MODULE_NOT_ACTIVE, new Object[] {403, "MODULE_NOT_ACTIVE"},
                Refusal.CREDENTIAL_NOT_DECLARED, new Object[] {403, "CREDENTIAL_NOT_DECLARED"},
                Refusal.CREDENTIAL_NOT_FOUND, new Object[] {404, "CREDENTIAL_NOT_FOUND"},
                Refusal.CREDENTIALS_UNAVAILABLE, new Object[] {503, "CREDENTIALS_UNAVAILABLE"});
        for (Map.Entry<Refusal, Object[]> entry : expected.entrySet()) {
            doThrow(new RefusedException(entry.getKey(), "refused"))
                    .when(service).read(TENANT, "app-1", "svc-1", "stripe", null);

            perform(get(PATH).header("X-Connected-App-Id", "app-1"), "svc-1")
                    .andExpect(status().is((Integer) entry.getValue()[0]))
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(jsonPath("$.errors[0].code").value(entry.getValue()[1]));
        }
    }

    @Test
    @DisplayName("without module support every caller is refused, never a 404")
    @SuppressWarnings("unchecked")
    void moduleSupportOff() throws Exception {
        ObjectProvider<ModuleCredentialService> absent = mock(ObjectProvider.class);
        MockMvc off = MockMvcBuilders.standaloneSetup(new ModuleCredentialController(absent), new RouterStub())
                .build();

        off.perform(get(PATH).header("X-Tenant-ID", TENANT).header("X-Connected-App-Id", "app-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_MODULE_PRINCIPAL"));
    }
}
