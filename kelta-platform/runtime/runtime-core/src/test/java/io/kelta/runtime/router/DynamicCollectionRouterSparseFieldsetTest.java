package io.kelta.runtime.router;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.query.PaginationMetadata;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for KLT-199: GET /api/epics?fields[epics]=code,taskCount used to 500
 * because PhysicalTableStorageAdapter#buildSelectClause put the ROLLUP_SUMMARY field's name
 * straight into the SQL column list. The fix lives in the storage layer (and is pinned there
 * by PhysicalTableStorageAdapterTest and DefaultQueryEngineTest); this suite confirms the
 * router still serves the computed value through to the JSON:API response on a narrowed read,
 * and that an unrelated sparse fieldset is unaffected.
 */
@SuppressWarnings("unchecked")
class DynamicCollectionRouterSparseFieldsetTest {

    private CollectionRegistry registry;
    private QueryEngine queryEngine;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = mock(CollectionRegistry.class);
        queryEngine = mock(QueryEngine.class);
        DynamicCollectionRouter router = new DynamicCollectionRouter(registry, queryEngine);
        mockMvc = MockMvcBuilders.standaloneSetup(router).build();
        objectMapper = new ObjectMapper();
    }

    private CollectionDefinition buildEpicsCollection() {
        return new CollectionDefinitionBuilder()
                .name("epics")
                .displayName("Epics")
                .addField(FieldDefinition.requiredString("code", 50))
                .addField(new FieldDefinition("taskCount", FieldType.ROLLUP_SUMMARY, true, false, false,
                        null, null, null, null,
                        Map.of("childCollection", "tasks", "foreignKeyField", "epic",
                                "aggregateFunction", "COUNT")))
                .systemCollection(false)
                .tenantScoped(false)
                .readOnly(false)
                .build();
    }

    private Map<String, Object> epicRecord(String id, String code, Object taskCount) {
        Map<String, Object> record = new HashMap<>();
        record.put("id", id);
        record.put("code", code);
        if (taskCount != null) {
            record.put("taskCount", taskCount);
        }
        return record;
    }

    @Test
    @DisplayName("GET ?fields[epics]=code,taskCount returns 200 with the rollup value populated")
    void sparseFieldsetWithRollupField_returnsComputedValue() throws Exception {
        CollectionDefinition def = buildEpicsCollection();
        when(registry.get("epics")).thenReturn(def);

        // Mirrors what DefaultQueryEngine.executeQuery returns after the fix: the storage
        // adapter's SELECT skips the ROLLUP_SUMMARY column, but computeVirtualFields has
        // already filled "taskCount" back in on the row by the time the router sees it.
        QueryResult result = new QueryResult(
                List.of(epicRecord("epic-1", "EPIC-1", 3)),
                new PaginationMetadata(1, 1, 1000, 1));
        when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(result);

        MvcResult mvcResult = mockMvc.perform(get("/api/epics")
                        .param("fields[epics]", "code,taskCount"))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> response = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(), Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        assertEquals(1, data.size());
        Map<String, Object> attributes = (Map<String, Object>) data.get(0).get("attributes");
        assertEquals("EPIC-1", attributes.get("code"));
        assertEquals(3, attributes.get("taskCount"),
                "the rollup value must still reach the response for a sparse fieldset naming it");
    }

    @Test
    @DisplayName("GET ?fields[epics]=code (no rollup requested) is unchanged")
    void sparseFieldsetWithoutRollupField_isUnaffected() throws Exception {
        CollectionDefinition def = buildEpicsCollection();
        when(registry.get("epics")).thenReturn(def);

        QueryResult result = new QueryResult(
                List.of(epicRecord("epic-1", "EPIC-1", null)),
                new PaginationMetadata(1, 1, 1000, 1));
        when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(result);

        MvcResult mvcResult = mockMvc.perform(get("/api/epics")
                        .param("fields[epics]", "code"))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> response = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(), Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        assertEquals(1, data.size());
        Map<String, Object> attributes = (Map<String, Object>) data.get(0).get("attributes");
        assertEquals("EPIC-1", attributes.get("code"));
        assertFalse(attributes.containsKey("taskCount"),
                "a sparse fieldset that didn't ask for the rollup should not gain it");
    }
}
