package io.kelta.runtime.router;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ReferenceConfig;
import io.kelta.runtime.query.PaginationMetadata;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A GET response echoes a LOOKUP/MASTER_DETAIL field's raw id in both
 * {@code attributes} and {@code relationships}, so a client that diffs what it
 * PATCHed against what it reads back never sees the reference field "missing".
 * Covers single-resource, list, and {@code included[]} responses.
 */
@DisplayName("DynamicCollectionRouter attributes/relationships id echo on read")
class DynamicCollectionRouterAttributeIdEchoTest {

    private CollectionRegistry registry;
    private QueryEngine queryEngine;
    private DynamicCollectionRouter router;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = mock(CollectionRegistry.class);
        queryEngine = mock(QueryEngine.class);
        router = new DynamicCollectionRouter(registry, queryEngine);
        mockMvc = MockMvcBuilders.standaloneSetup(router).build();
        objectMapper = new ObjectMapper();
    }

    private CollectionDefinition pageLayoutsCollection() {
        return new CollectionDefinitionBuilder()
                .name("page-layouts")
                .displayName("Page Layouts")
                .addField(new FieldDefinition("collectionId", FieldType.MASTER_DETAIL, false, false, false,
                        null, null, null,
                        ReferenceConfig.masterDetail("collections", "Collection"), null))
                .addField(FieldDefinition.requiredString("name", 100))
                .systemCollection(true)
                .tenantScoped(false)
                .readOnly(false)
                .build();
    }

    private CollectionDefinition layoutSectionsCollection() {
        return new CollectionDefinitionBuilder()
                .name("layout-sections")
                .displayName("Layout Sections")
                .addField(new FieldDefinition("layoutId", FieldType.MASTER_DETAIL, false, false, false,
                        null, null, null,
                        ReferenceConfig.masterDetail("page-layouts", "Layout"), null))
                .addField(FieldDefinition.string("heading", 200))
                .systemCollection(true)
                .tenantScoped(false)
                .readOnly(false)
                .build();
    }

    private Map<String, Object> layoutRecord(String id, String name, String collectionId) {
        Map<String, Object> record = new HashMap<>();
        record.put("id", id);
        record.put("name", name);
        record.put("collectionId", collectionId);
        return record;
    }

    private Map<String, Object> sectionRecord(String id, String layoutId, String heading) {
        Map<String, Object> record = new HashMap<>();
        record.put("id", id);
        record.put("layoutId", layoutId);
        record.put("heading", heading);
        return record;
    }

    @SuppressWarnings("unchecked")
    private void assertAttributeMatchesRelationshipId(Map<String, Object> resource, String field) {
        Map<String, Object> attributes = (Map<String, Object>) resource.get("attributes");
        Map<String, Object> relationships = (Map<String, Object>) resource.get("relationships");
        assertNotNull(attributes, "attributes must be present");
        assertNotNull(relationships, "relationships must be present");
        assertTrue(attributes.containsKey(field), "attributes must carry the raw id for " + field);

        Map<String, Object> relField = (Map<String, Object>) relationships.get(field);
        assertNotNull(relField, "relationships must carry " + field);
        Map<String, Object> relData = (Map<String, Object>) relField.get("data");
        assertNotNull(relData, "relationships." + field + ".data must be present");

        assertEquals(relData.get("id"), attributes.get(field),
                "attributes." + field + " must equal relationships." + field + ".data.id");
    }

    @Test
    @DisplayName("GET single resource: attributes.collectionId == relationships.collectionId.data.id")
    void getSingle_echoesReferenceIdInAttributesAndRelationships() throws Exception {
        CollectionDefinition layoutDef = pageLayoutsCollection();
        when(registry.get("page-layouts")).thenReturn(layoutDef);

        Map<String, Object> layout = layoutRecord("layout-1", "Detail Layout", "coll-42");
        when(queryEngine.getById(layoutDef, "layout-1")).thenReturn(Optional.of(layout));

        MvcResult result = mockMvc.perform(get("/api/page-layouts/layout-1"))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> response = objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> data = (Map<String, Object>) response.get("data");

        assertAttributeMatchesRelationshipId(data, "collectionId");
    }

    @Test
    @DisplayName("GET list: every resource has attributes.collectionId == relationships.collectionId.data.id")
    void getList_echoesReferenceIdInAttributesAndRelationships() throws Exception {
        CollectionDefinition layoutDef = pageLayoutsCollection();
        when(registry.get("page-layouts")).thenReturn(layoutDef);

        Map<String, Object> layout1 = layoutRecord("layout-1", "Detail Layout", "coll-42");
        Map<String, Object> layout2 = layoutRecord("layout-2", "Edit Layout", "coll-43");
        QueryResult queryResult = new QueryResult(
                List.of(layout1, layout2),
                new PaginationMetadata(2, 1, 20, 1));
        when(queryEngine.executeQuery(eq(layoutDef), any(QueryRequest.class)))
                .thenReturn(queryResult);

        MvcResult result = mockMvc.perform(get("/api/page-layouts"))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> response = objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        assertEquals(2, data.size());
        for (Map<String, Object> resource : data) {
            assertAttributeMatchesRelationshipId(resource, "collectionId");
        }
    }

    @Test
    @DisplayName("included[]: resources also carry the raw id in attributes")
    void included_echoesReferenceIdInAttributesAndRelationships() throws Exception {
        CollectionDefinition layoutDef = pageLayoutsCollection();
        CollectionDefinition sectionDef = layoutSectionsCollection();
        when(registry.get("page-layouts")).thenReturn(layoutDef);
        when(registry.get("layout-sections")).thenReturn(sectionDef);

        Map<String, Object> layout = layoutRecord("layout-1", "Detail Layout", "coll-42");
        when(queryEngine.getById(layoutDef, "layout-1")).thenReturn(Optional.of(layout));

        Map<String, Object> section = sectionRecord("sec-1", "layout-1", "General");
        QueryResult sectionResult = new QueryResult(
                List.of(section),
                new PaginationMetadata(1, 1, 1000, 1));
        when(queryEngine.executeQuery(eq(sectionDef), any(QueryRequest.class)))
                .thenReturn(sectionResult);

        MvcResult result = mockMvc.perform(get("/api/page-layouts/layout-1")
                        .param("include", "layout-sections"))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> response = objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class);

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertAttributeMatchesRelationshipId(data, "collectionId");

        List<Map<String, Object>> included = (List<Map<String, Object>>) response.get("included");
        assertNotNull(included);
        assertEquals(1, included.size());
        assertAttributeMatchesRelationshipId(included.get(0), "layoutId");
    }

    @Test
    @DisplayName("PATCH with a read-back body (id in both attributes and relationships) sends a single unchanged value")
    void patch_readBackBodyRoundTrips_toSingleUnchangedValue() throws Exception {
        CollectionDefinition layoutDef = pageLayoutsCollection();
        when(registry.get("page-layouts")).thenReturn(layoutDef);

        Map<String, Object> unchanged = layoutRecord("layout-1", "Detail Layout", "coll-42");
        when(queryEngine.update(eq(layoutDef), eq("layout-1"), any())).thenReturn(Optional.of(unchanged));

        // Body shape a client gets back from GET and diffs/PATCHes verbatim: the
        // reference id present in both attributes and relationships.
        String body = "{\"data\":{\"type\":\"page-layouts\",\"id\":\"layout-1\","
                + "\"attributes\":{\"name\":\"Detail Layout\",\"collectionId\":\"coll-42\"},"
                + "\"relationships\":{\"collectionId\":{\"data\":{\"type\":\"collections\",\"id\":\"coll-42\"}}}}}";

        mockMvc.perform(patch("/api/page-layouts/layout-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(queryEngine).update(eq(layoutDef), eq("layout-1"), dataCaptor.capture());

        Map<String, Object> data = dataCaptor.getValue();
        assertEquals("coll-42", data.get("collectionId"),
                "attributes and relationships must resolve to the same unchanged value");
    }
}
