package io.kelta.worker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.registry.CollectionOnDemandLoader;
import io.kelta.runtime.registry.CollectionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/collections/{name}/schema} — the endpoint an API client reads to learn a
 * collection's attribute names, types, defaults, enum values and reference targets.
 *
 * <p>The router stand-in is registered deliberately: {@code DynamicCollectionRouter} maps
 * all-variable GETs four segments deep under {@code /api}, so a controller tested alone can pass
 * while losing every request in production (see {@code concerns.md} → Fragile Areas).
 */
@DisplayName("Collection schema endpoint")
class CollectionSchemaControllerTest {

    /** Stands in for {@code DynamicCollectionRouter}'s nested GET mappings, shape for shape. */
    @RestController
    @RequestMapping("/api")
    static class NestedRouteStub {
        @GetMapping("/{collectionName}")
        String one() {
            return "router";
        }

        @GetMapping("/{collectionName}/{id}")
        String two() {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}")
        String three() {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}/{childId}")
        String four() {
            return "router";
        }
    }

    private CollectionRegistry registry;
    private CollectionSchemaController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        registry = mock(CollectionRegistry.class);
        controller = new CollectionSchemaController(registry);
        mvc = MockMvcBuilders.standaloneSetup(controller, new NestedRouteStub()).build();
    }

    private void register(String name) {
        CollectionDefinition definition = SystemCollectionDefinitions.byName().get(name);
        when(registry.get(name)).thenReturn(definition);
    }

    @Test
    @DisplayName("page-layouts schema carries enum values, reference targets and descriptions")
    void pageLayoutsSchema() throws Exception {
        register("page-layouts");

        mvc.perform(get("/api/collections/page-layouts/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("page-layouts"))
                .andExpect(jsonPath("$.displayName").value("Page Layouts"))
                .andExpect(jsonPath("$.systemCollection").value(true))
                .andExpect(jsonPath("$.fields[?(@.name=='layoutType')].enum[*]",
                        hasItem("DETAIL")))
                .andExpect(jsonPath("$.fields[?(@.name=='layoutType')].default")
                        .value("DETAIL"))
                .andExpect(jsonPath("$.fields[?(@.name=='collectionId')].reference.target")
                        .value("collections"))
                .andExpect(jsonPath("$.fields[?(@.name=='collectionId')].isRelationship")
                        .value(true))
                .andExpect(jsonPath("$.fields[*].description", not(hasItem(""))))
                .andExpect(jsonPath("$.fields[*].description").isNotEmpty());
    }

    @Test
    @DisplayName("Every page-layouts and layout-fields attribute is described")
    void everyFieldIsDescribed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        for (String name : new String[]{"page-layouts", "layout-fields"}) {
            register(name);
            String body = mvc.perform(get("/api/collections/" + name + "/schema"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode fields = mapper.readTree(body).get("fields");
            assertThat(fields).as("%s exposes fields", name).isNotEmpty();
            for (JsonNode field : fields) {
                assertThat(field.path("description").asText(""))
                        .as("description of %s.%s", name, field.path("name").asText())
                        .isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("layout-fields schema exposes the 0-based columnNumber default")
    void layoutFieldsSchemaCarriesDefault() throws Exception {
        register("layout-fields");

        mvc.perform(get("/api/collections/layout-fields/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.name=='columnNumber')].default").value(0))
                .andExpect(jsonPath("$.fields[?(@.name=='columnNumber')].required").value(false));
    }

    @Test
    @DisplayName("An unknown collection name is a 404, not a router record read")
    void unknownCollectionIsNotFound() throws Exception {
        when(registry.get("not-a-collection")).thenReturn(null);

        mvc.perform(get("/api/collections/not-a-collection/schema"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("COLLECTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("A tenant collection this pod has not loaded is fetched on demand")
    void unloadedTenantCollectionIsLoadedOnDemand() throws Exception {
        CollectionDefinition orders = CollectionDefinition.builder()
                .name("orders")
                .displayName("Orders")
                .addField(FieldDefinition.requiredString("reference")
                        .withDescription("Customer-facing order reference."))
                .build();

        CollectionOnDemandLoader loader = mock(CollectionOnDemandLoader.class);
        when(registry.get("orders")).thenReturn(null);
        when(loader.load(eq("orders"), any())).thenReturn(orders);
        controller.setOnDemandLoader(loader);

        mvc.perform(get("/api/collections/orders/schema").header("X-Tenant-ID", "t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemCollection").value(false))
                .andExpect(jsonPath("$.fields[?(@.name=='reference')].required").value(true))
                .andExpect(jsonPath("$.fields[?(@.name=='reference')].description")
                        .value("Customer-facing order reference."));
    }
}
