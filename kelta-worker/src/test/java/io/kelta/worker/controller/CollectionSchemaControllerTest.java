package io.kelta.worker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.registry.CollectionOnDemandLoader;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.PicklistValueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
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
    @DisplayName("Every field carries systemMaintained: true when flagged, false otherwise")
    void systemMaintainedIsEmittedForEveryField() throws Exception {
        CollectionDefinition inspections = CollectionDefinition.builder()
                .name("inspections")
                .displayName("Inspections")
                .addField(FieldDefinition.requiredString("site"))
                .addField(FieldDefinition.integer("failureCount").withSystemMaintained(true))
                .build();
        when(registry.get("inspections")).thenReturn(inspections);

        String body = mvc.perform(get("/api/collections/inspections/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.name=='failureCount')].systemMaintained").value(true))
                .andExpect(jsonPath("$.fields[?(@.name=='site')].systemMaintained").value(false))
                .andReturn().getResponse().getContentAsString();

        for (JsonNode field : new ObjectMapper().readTree(body).get("fields")) {
            assertThat(field.path("systemMaintained").isBoolean())
                    .as("systemMaintained on %s", field.path("name").asText())
                    .isTrue();
        }
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

    // --- picklist values -------------------------------------------------------------------

    private static FieldDefinition picklist(String name, FieldType type, Map<String, Object> fieldTypeConfig) {
        return new FieldDefinition(name, type, true, false, false, null, null, null, null,
                fieldTypeConfig, null, false, null, false, false);
    }

    /** A {@code picklist_value} row joined to its field, as the tenant database holds it. */
    private static Map<String, Object> value(String field, String value, String label,
                                             boolean isDefault, boolean isActive, int sortOrder) {
        Map<String, Object> row = new HashMap<>();
        row.put("field_name", field);
        row.put("value", value);
        row.put("label", label);
        row.put("is_default", isDefault);
        row.put("is_active", isActive);
        row.put("sort_order", sortOrder);
        return row;
    }

    /**
     * Wires a real {@link PicklistValueRepository} over a JdbcTemplate holding {@code rows} for
     * tenant {@code t-1}. The stub applies the query's own active filter and ordering only when
     * the SQL carries them, so dropping either from the repository fails these cases.
     */
    private JdbcTemplate wirePicklistRows(String collection, List<Map<String, Object>> rows) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("t-1"), eq(collection))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            var stream = rows.stream();
            if (sql.contains("pv.is_active = true")) {
                stream = stream.filter(r -> Boolean.TRUE.equals(r.get("is_active")));
            }
            if (sql.contains("pv.sort_order")) {
                stream = stream.sorted(Comparator.comparing(r -> (Integer) r.get("sort_order")));
            }
            return stream.toList();
        });
        controller.setPicklistValueRepository(new PicklistValueRepository(jdbc));
        return jdbc;
    }

    @Test
    @DisplayName("A field-scoped PICKLIST lists only its active values, in sortOrder")
    void fieldScopedPicklistValues() throws Exception {
        when(registry.get("tickets")).thenReturn(CollectionDefinition.builder()
                .name("tickets").displayName("Tickets")
                .addField(picklist("priority", FieldType.PICKLIST, null))
                .build());
        wirePicklistRows("tickets", List.of(
                value("priority", "HIGH", "High", false, true, 2),
                value("priority", "RETIRED", "Retired", false, false, 0),
                value("priority", "LOW", "Low", true, true, 1)));

        String body = mvc.perform(get("/api/collections/tickets/schema").header("X-Tenant-ID", "t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].picklistValues[*].value", contains("LOW", "HIGH")))
                .andReturn().getResponse().getContentAsString();

        JsonNode values = new ObjectMapper().readTree(body).get("fields").get(0).get("picklistValues");
        assertThat(values).hasSize(2);
        assertThat(values.get(0).get("value").asText()).isEqualTo("LOW");
        assertThat(values.get(0).get("label").asText()).isEqualTo("Low");
        assertThat(values.get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(values.get(0).get("sortOrder").asInt()).isEqualTo(1);
        assertThat(values.get(1).get("value").asText()).isEqualTo("HIGH");
        assertThat(values.get(1).get("isDefault").asBoolean()).isFalse();
        assertThat(values.get(1).get("sortOrder").asInt()).isEqualTo(2);
        assertThat(values.get(0).size()).as("keys of a picklist value").isEqualTo(4);
    }

    @Test
    @DisplayName("A field bound to a global picklist lists that picklist's active values in the same shape")
    void globalPicklistValues() throws Exception {
        when(registry.get("tickets")).thenReturn(CollectionDefinition.builder()
                .name("tickets").displayName("Tickets")
                .addField(picklist("region", FieldType.PICKLIST, Map.of("globalPicklistId", "gp-1")))
                .build());
        JdbcTemplate jdbc = wirePicklistRows("tickets", List.of(
                value("region", "EU", "Europe", false, true, 1),
                value("region", "US", "United States", true, true, 0)));

        mvc.perform(get("/api/collections/tickets/schema").header("X-Tenant-ID", "t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].picklistValues[*].value", contains("US", "EU")))
                .andExpect(jsonPath("$.fields[0].picklistValues[0].label").value("United States"))
                .andExpect(jsonPath("$.fields[0].picklistValues[0].isDefault").value(true))
                .andExpect(jsonPath("$.fields[0].picklistValues[0].sortOrder").value(0));

        verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("pv.picklist_source_type = 'GLOBAL'"),
                eq("t-1"), eq("tickets"));
    }

    @Test
    @DisplayName("MULTI_PICKLIST gets picklistValues; other fields carry no picklistValues key")
    void multiPicklistAndNonPicklistFields() throws Exception {
        when(registry.get("tickets")).thenReturn(CollectionDefinition.builder()
                .name("tickets").displayName("Tickets")
                .addField(FieldDefinition.requiredString("subject"))
                .addField(picklist("tags", FieldType.MULTI_PICKLIST, null))
                .addField(picklist("status", FieldType.PICKLIST, null))
                .build());
        wirePicklistRows("tickets", List.of(
                value("tags", "BUG", "Bug", false, true, 0),
                value("tags", "UX", "UX", false, true, 1)));

        String body = mvc.perform(get("/api/collections/tickets/schema").header("X-Tenant-ID", "t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.name=='tags')].picklistValues[*].value", contains("BUG", "UX")))
                .andReturn().getResponse().getContentAsString();

        JsonNode fields = new ObjectMapper().readTree(body).get("fields");
        assertThat(fields.get(0).has("picklistValues")).as("subject").isFalse();
        assertThat(fields.get(2).get("picklistValues")).as("status with no values").isEmpty();
    }

    @Test
    @DisplayName("System collections keep their enum output and never query picklist values")
    void systemCollectionEnumUnchanged() throws Exception {
        register("page-layouts");
        JdbcTemplate jdbc = wirePicklistRows("page-layouts", List.of());

        String body = mvc.perform(get("/api/collections/page-layouts/schema").header("X-Tenant-ID", "t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.name=='layoutType')].enum[*]", hasItem("DETAIL")))
                .andReturn().getResponse().getContentAsString();

        for (JsonNode field : new ObjectMapper().readTree(body).get("fields")) {
            assertThat(field.has("picklistValues")).as(field.path("name").asText()).isFalse();
        }
        verifyNoInteractions(jdbc);
    }
}
