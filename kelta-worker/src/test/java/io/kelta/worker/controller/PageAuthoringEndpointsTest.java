package io.kelta.worker.controller;

import io.kelta.worker.service.PageWidgetCatalog;
import io.kelta.worker.service.UiPageConfigValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Routing + response shapes for the page-authoring endpoints.
 *
 * <p>The router stand-in is registered deliberately: {@code DynamicCollectionRouter} maps
 * all-variable routes under {@code /api}, so {@code /api/pages/widgets} would be answered as a
 * record read and {@code /api/ui-pages/validate} as a record write if these literal patterns ever
 * stopped out-ranking them. A controller tested alone cannot see that (see {@code concerns.md}
 * → Fragile Areas).
 */
@DisplayName("Page authoring endpoints")
class PageAuthoringEndpointsTest {

    /** Stands in for {@code DynamicCollectionRouter}'s all-variable mappings, shape for shape. */
    @RestController
    @RequestMapping("/api")
    static class NestedRouteStub {
        @GetMapping("/{collectionName}")
        String list() {
            return "router";
        }

        @GetMapping("/{collectionName}/{id}")
        String get() {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}")
        String listChildren() {
            return "router";
        }

        @PostMapping("/{collectionName}")
        String create() {
            return "router";
        }

        @PostMapping("/{collectionName}/{id}")
        String createChild() {
            return "router";
        }
    }

    private MockMvc mvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        PageWidgetCatalog catalog = new PageWidgetCatalog(objectMapper);
        mvc = MockMvcBuilders.standaloneSetup(
                new PageWidgetsController(catalog),
                new UiPageValidateController(new UiPageConfigValidator(catalog)),
                new NestedRouteStub()).build();
    }

    private String json(Object body) {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    @DisplayName("GET /api/pages/widgets lists every built-in with its prop schema")
    void widgetsCatalogueIsServed() throws Exception {
        mvc.perform(get("/api/pages/widgets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.widgets").isArray())
                .andExpect(jsonPath("$.widgets[?(@.type == 'heading')]").isNotEmpty())
                .andExpect(jsonPath("$.widgets[?(@.type == 'container')].acceptsChildren")
                        .value(true))
                .andExpect(jsonPath("$.widgets[?(@.type == 'heading')].source").value("builtin"))
                .andExpect(jsonPath("$.widgets[?(@.type == 'heading')].propSchema[0].key")
                        .value("text"))
                .andExpect(jsonPath("$.widgets[?(@.type == 'heading')].propSchema[0].bindable")
                        .value(true));
    }

    @Test
    @DisplayName("GET /api/pages/config-schema serves the JSON Schema")
    void configSchemaIsServed() throws Exception {
        mvc.perform(get("/api/pages/config-schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.$id").value("https://kelta.io/schema/ui-page-config.schema.json"))
                .andExpect(jsonPath("$.properties.components").exists())
                .andExpect(jsonPath("$.properties.dataSources.maxItems")
                        .value(UiPageConfigValidator.MAX_PAGE_DATA_SOURCES));
    }

    @Test
    @DisplayName("POST /api/ui-pages/validate reports an unknown widget type by pointer")
    void validateReportsUnknownWidgetType() throws Exception {
        Map<String, Object> config = Map.of(
                "components", List.of(Map.of("id", "c1", "type", "nope", "props", Map.of())));

        mvc.perform(post("/api/ui-pages/validate")
                        .contentType(MediaType.APPLICATION_JSON).content(json(config)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].path").value("/components/0/type"))
                .andExpect(jsonPath("$.errors[0].severity").value("error"));
    }

    @Test
    @DisplayName("POST /api/ui-pages/validate names an undeclared data source and still saves")
    void validateNamesUndeclaredDataSource() throws Exception {
        Map<String, Object> config = Map.of("components", List.of(Map.of(
                "id", "t1", "type", "text",
                "props", Map.of("content", "{{data.missing.length}}"))));

        mvc.perform(post("/api/ui-pages/validate")
                        .contentType(MediaType.APPLICATION_JSON).content(json(config)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors[0].severity").value("warning"))
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("missing")));
    }

    @Test
    @DisplayName("POST /api/ui-pages/validate rejects 13 sources, an over-cap limit and a non-EQ filter")
    void validateEnforcesDataSourceCaps() throws Exception {
        List<Map<String, Object>> sources = IntStream.range(0, 13)
                .mapToObj(i -> Map.<String, Object>of("name", "s" + i, "collection", "tickets"))
                .toList();

        mvc.perform(post("/api/ui-pages/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dataSources", sources))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].path").value("/dataSources"));

        mvc.perform(post("/api/ui-pages/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dataSources", List.of(
                                Map.of("name", "t", "collection", "tickets", "limit", 500))))))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].path").value("/dataSources/0/limit"));

        mvc.perform(post("/api/ui-pages/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dataSources", List.of(Map.of(
                                "name", "t", "collection", "tickets",
                                "filter", Map.of("amount", Map.of("GT", 100))))))))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].path").value("/dataSources/0/filter/amount"));
    }

    @Test
    @DisplayName("a body wrapping the config under `config` is accepted, and a valid page is valid")
    void validateAcceptsWrappedBodyAndCleanPage() throws Exception {
        Map<String, Object> body = Map.of("config", Map.of(
                "schemaVersion", 2,
                "dataSources", List.of(Map.of("name", "tickets", "collection", "tickets")),
                "components", List.of(Map.of("id", "h1", "type", "heading",
                        "props", Map.of("text", "{{data.tickets.length}} open")))));

        mvc.perform(post("/api/ui-pages/validate")
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isEmpty());
    }
}
