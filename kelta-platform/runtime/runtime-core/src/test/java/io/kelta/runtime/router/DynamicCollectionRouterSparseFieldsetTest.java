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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for KLT-199: GET /api/epics?fields[epics]=code,taskCount used to 500
 * because PhysicalTableStorageAdapter#buildSelectClause put the ROLLUP_SUMMARY field's name
 * straight into the SQL column list. The fix lives in the storage layer (and is pinned there
 * by PhysicalTableStorageAdapterTest and DefaultQueryEngineTest); this suite confirms the
 * router still serves the computed value through to the JSON:API response on a narrowed read,
 * and that an unrelated sparse fieldset is unaffected.
 *
 * <p>KLT-582: GET by id honours the same {@code fields} / {@code fields[type]} parameter as
 * the list path, projecting a copy so the system-collection cache never holds a narrowed body.
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

    private CollectionDefinition buildProjectsCollection() {
        return new CollectionDefinitionBuilder()
                .name("projects")
                .displayName("Projects")
                .addField(FieldDefinition.requiredString("name"))
                .addField(FieldDefinition.string("status"))
                .addField(FieldDefinition.lookup("owner", "users", "Owner"))
                .systemCollection(false)
                .tenantScoped(false)
                .readOnly(false)
                .build();
    }

    private Map<String, Object> projectRecord() {
        Map<String, Object> record = new HashMap<>();
        record.put("id", "p-1");
        record.put("name", "Apollo");
        record.put("status", "ACTIVE");
        record.put("owner", "u-1");
        record.put("updatedAt", "2026-10-13T10:00:00Z");
        return record;
    }

    private Map<String, Object> readData(MvcResult mvcResult) throws Exception {
        Map<String, Object> response = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(), Map.class);
        return (Map<String, Object>) response.get("data");
    }

    @Test
    @DisplayName("GET by id ?fields[projects]=name returns only name, keeping id and type")
    void getById_typedSparseFieldset_projectsAttributes() throws Exception {
        CollectionDefinition def = buildProjectsCollection();
        when(registry.get("projects")).thenReturn(def);
        when(queryEngine.getById(def, "p-1")).thenReturn(Optional.of(projectRecord()));

        Map<String, Object> data = readData(mockMvc.perform(get("/api/projects/p-1")
                        .param("fields[projects]", "name"))
                .andExpect(status().isOk())
                .andReturn());

        assertEquals("p-1", data.get("id"));
        assertEquals("projects", data.get("type"));
        assertEquals(Map.of("name", "Apollo"), data.get("attributes"));
        assertEquals(Map.of(), data.get("relationships"),
                "a relationship not named in the fieldset is dropped too");
    }

    @Test
    @DisplayName("GET by id ?fields=name,owner (bare form) projects attributes and relationships")
    void getById_bareSparseFieldset_projectsAttributesAndRelationships() throws Exception {
        CollectionDefinition def = buildProjectsCollection();
        when(registry.get("projects")).thenReturn(def);
        when(queryEngine.getById(def, "p-1")).thenReturn(Optional.of(projectRecord()));

        Map<String, Object> data = readData(mockMvc.perform(get("/api/projects/p-1")
                        .param("fields", "name,owner"))
                .andExpect(status().isOk())
                .andReturn());

        assertEquals("p-1", data.get("id"));
        assertEquals("projects", data.get("type"));
        assertEquals(Map.of("name", "Apollo", "owner", "u-1"), data.get("attributes"));
        Map<String, Object> relationships = (Map<String, Object>) data.get("relationships");
        assertEquals(List.of("owner"), List.copyOf(relationships.keySet()));
    }

    @Test
    @DisplayName("GET by id without fields returns the full record as before")
    void getById_withoutFields_returnsFullRecord() throws Exception {
        CollectionDefinition def = buildProjectsCollection();
        when(registry.get("projects")).thenReturn(def);
        when(queryEngine.getById(def, "p-1")).thenReturn(Optional.of(projectRecord()));

        Map<String, Object> data = readData(mockMvc.perform(get("/api/projects/p-1"))
                .andExpect(status().isOk())
                .andReturn());

        Map<String, Object> attributes = (Map<String, Object>) data.get("attributes");
        assertEquals("Apollo", attributes.get("name"));
        assertEquals("ACTIVE", attributes.get("status"));
        assertEquals("u-1", attributes.get("owner"));
        assertEquals("2026-10-13T10:00:00Z", attributes.get("updatedAt"));
        assertTrue(((Map<String, Object>) data.get("relationships")).containsKey("owner"));
    }

    @Test
    @DisplayName("GET by id emits the same ETag with and without fields")
    void getById_etagUnchangedByFields() throws Exception {
        CollectionDefinition def = buildProjectsCollection();
        when(registry.get("projects")).thenReturn(def);
        when(queryEngine.getById(def, "p-1")).thenReturn(Optional.of(projectRecord()));
        String expected = RecordETag.of(projectRecord());
        assertNotNull(expected);

        mockMvc.perform(get("/api/projects/p-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", expected));
        mockMvc.perform(get("/api/projects/p-1").param("fields[projects]", "name"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", expected));
    }

    @Test
    @DisplayName("a fields request on a cacheable system collection caches the full body and serves it unprojected later")
    void getById_fieldsOnCacheableSystemCollection_neverCachesProjectedBody() throws Exception {
        CollectionDefinition def = new CollectionDefinitionBuilder()
                .name("collections")
                .displayName("Collections")
                .addField(FieldDefinition.requiredString("name"))
                .addField(FieldDefinition.string("description"))
                .systemCollection(true)
                .tenantScoped(true)
                .build();
        when(registry.get("collections")).thenReturn(def);
        Map<String, Object> record = new HashMap<>();
        record.put("id", "c-1");
        record.put("name", "orders");
        record.put("description", "Customer orders");
        when(queryEngine.getById(def, "c-1")).thenReturn(Optional.of(record));

        DynamicCollectionRouter router = new DynamicCollectionRouter(registry, queryEngine);
        DynamicCollectionRouterGoldenTest.RecordingCache cache =
                new DynamicCollectionRouterGoldenTest.RecordingCache(new java.util.ArrayList<>());
        router.setSystemCollectionCache(cache);
        MockMvc cachedMvc = MockMvcBuilders.standaloneSetup(router).build();

        Map<String, Object> projected = readData(cachedMvc.perform(get("/api/collections/c-1")
                        .header("X-Tenant-ID", "t-1")
                        .param("fields[collections]", "name"))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(Map.of("name", "orders"), projected.get("attributes"));

        Map<String, Object> stored = cache.getByIdResponse("t-1", "collections", "c-1").orElseThrow();
        Map<String, Object> storedAttributes =
                (Map<String, Object>) ((Map<String, Object>) stored.get("data")).get("attributes");
        assertEquals("Customer orders", storedAttributes.get("description"),
                "the cache must hold the unprojected body");

        Map<String, Object> later = readData(cachedMvc.perform(get("/api/collections/c-1")
                        .header("X-Tenant-ID", "t-1"))
                .andExpect(status().isOk())
                .andReturn());
        Map<String, Object> laterAttributes = (Map<String, Object>) later.get("attributes");
        assertEquals("orders", laterAttributes.get("name"));
        assertEquals("Customer orders", laterAttributes.get("description"),
                "a later unprojected request must get the full record from the cache");

        Map<String, Object> projectedFromCache = readData(cachedMvc.perform(get("/api/collections/c-1")
                        .header("X-Tenant-ID", "t-1")
                        .param("fields", "description"))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(Map.of("description", "Customer orders"), projectedFromCache.get("attributes"),
                "a cache hit is projected too");
        verify(queryEngine, times(1)).getById(def, "c-1");
    }
}
