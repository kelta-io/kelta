package io.kelta.worker.controller;

import io.kelta.worker.module.ModuleAssertionSigner;
import io.kelta.worker.module.ModuleAssertionSignerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/modules/.well-known/jwks.json} is three segments under {@code /api}, the depth of
 * {@code DynamicCollectionRouter}'s {@code /{parentName}/{parentId}/{childName}} GET. The literal
 * mapping must win; the router stand-in is registered because the controller alone would pass
 * against a broken mapping (CLAUDE.md pitfall).
 */
@DisplayName("Module JWKS route vs the generic collection router")
class ModuleJwksRouteMappingTest {

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

    @Test
    @DisplayName("the JWKS reaches ModuleJwksController with public keys only and a cache header")
    void jwksWinsOverTheRouter() throws Exception {
        ModuleAssertionSigner signer = new ModuleAssertionSigner(ModuleAssertionSignerTest.KEY_PEM,
                "https://api.example.test/api/modules");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ModuleJwksController(signer), new RouterStub())
                .build();

        mvc.perform(get("/api/modules/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/jwk-set+json")))
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(jsonPath("$.keys", hasSize(1)))
                .andExpect(jsonPath("$.keys[0].kty").value("EC"))
                .andExpect(jsonPath("$.keys[0].crv").value("P-256"))
                .andExpect(jsonPath("$.keys[0].alg").value("ES256"))
                .andExpect(jsonPath("$.keys[0].kid").value(signer.currentKeyId()))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    @DisplayName("unconfigured, the JWKS is an empty key set rather than an error")
    void unconfiguredIsEmpty() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ModuleJwksController(new ModuleAssertionSigner("", "")), new RouterStub()).build();

        mvc.perform(get("/api/modules/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys", hasSize(0)));
    }
}
