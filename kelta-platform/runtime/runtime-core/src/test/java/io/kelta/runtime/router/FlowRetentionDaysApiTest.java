package io.kelta.runtime.router;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.AutomationSystemCollections;
import io.kelta.runtime.query.DefaultQueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.validation.DefaultValidationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/flows} accepts, returns and validates {@code retentionDays} (PLT-603), driven
 * through the real router, query engine and validation engine against the real {@code flows}
 * system collection definition; only storage is stubbed.
 */
@DisplayName("/api/flows retentionDays")
class FlowRetentionDaysApiTest {

    private static final String TENANT = "tenant-a";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> rows = new ConcurrentHashMap<>();
    private StorageAdapter storage;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CollectionDefinition flows = AutomationSystemCollections.flows();
        CollectionRegistry registry = mock(CollectionRegistry.class);
        when(registry.get("flows")).thenReturn(flows);

        storage = mock(StorageAdapter.class);
        when(storage.create(any(), anyMap())).thenAnswer(inv -> {
            Map<String, Object> row = new LinkedHashMap<>(inv.<Map<String, Object>>getArgument(1));
            row.putIfAbsent("id", "flow-" + (rows.size() + 1));
            rows.put((String) row.get("id"), row);
            return row;
        });
        when(storage.getById(any(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(rows.get(inv.<String>getArgument(1))).map(HashMap::new));
        when(storage.update(any(), anyString(), anyMap())).thenAnswer(inv -> {
            Map<String, Object> row = rows.get(inv.<String>getArgument(1));
            if (row == null) {
                return Optional.empty();
            }
            row.putAll(inv.getArgument(2));
            return Optional.of(new HashMap<>(row));
        });

        DefaultQueryEngine queryEngine = new DefaultQueryEngine(storage, new DefaultValidationEngine(storage));
        DynamicCollectionRouter router = new DynamicCollectionRouter(registry, queryEngine);
        mockMvc = MockMvcBuilders.standaloneSetup(router)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String body(Map<String, Object> attributes) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "flows");
        data.put("attributes", attributes);
        return mapper.writeValueAsString(Map.of("data", data));
    }

    private static Map<String, Object> flow(Object retentionDays) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Nightly sync");
        attributes.put("flowType", "AUTOLAUNCHED");
        attributes.put("definition", Map.of("StartAt", "Done",
                "States", Map.of("Done", Map.of("Type", "Succeed"))));
        if (retentionDays != null) {
            attributes.put("retentionDays", retentionDays);
        }
        return attributes;
    }

    private static MockHttpServletRequestBuilder asTenant(MockHttpServletRequestBuilder b) {
        return b.header("X-Tenant-ID", TENANT).header("X-Tenant-Slug", "acme")
                .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("POST accepts 7 and returns it; GET returns it")
    void createAndRead() throws Exception {
        mockMvc.perform(asTenant(post("/api/flows")).content(body(flow(7))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.retentionDays").value(7));

        String id = rows.keySet().iterator().next();
        mockMvc.perform(asTenant(get("/api/flows/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.retentionDays").value(7));
    }

    @Test
    @DisplayName("POST without retentionDays leaves it unset (the global window applies)")
    void createWithoutOverride() throws Exception {
        mockMvc.perform(asTenant(post("/api/flows")).content(body(flow(null))))
                .andExpect(status().isCreated());

        assertThat(rows.values().iterator().next().get("retentionDays")).isNull();
    }

    @ParameterizedTest(name = "POST retentionDays={0} is a 400")
    @ValueSource(strings = {"0", "-1", "7.5", "\"7.5\"", "\"-3\"", "\"week\"", "true", "3651"})
    void createRejectsInvalid(String json) throws Exception {
        Object value = mapper.readValue(json, Object.class);
        mockMvc.perform(asTenant(post("/api/flows")).content(body(flow(value))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].source.pointer", containsString("retentionDays")));

        verify(storage, never()).create(any(), anyMap());
    }

    @Test
    @DisplayName("PATCH accepts 120, rejects 0 and -5 with a 400, and accepts null to clear")
    void updateValidates() throws Exception {
        mockMvc.perform(asTenant(post("/api/flows")).content(body(flow(null))))
                .andExpect(status().isCreated());
        String id = rows.keySet().iterator().next();

        for (int invalid : new int[]{0, -5}) {
            mockMvc.perform(asTenant(patch("/api/flows/" + id)).content(body(Map.of("retentionDays", invalid))))
                    .andExpect(status().isBadRequest());
        }
        verify(storage, never()).update(any(), anyString(), anyMap());

        mockMvc.perform(asTenant(patch("/api/flows/" + id)).content(body(Map.of("retentionDays", 120))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.retentionDays").value(120));

        Map<String, Object> clear = new HashMap<>();
        clear.put("retentionDays", null);
        mockMvc.perform(asTenant(patch("/api/flows/" + id)).content(body(clear)))
                .andExpect(status().isOk());
        assertThat(rows.get(id).get("retentionDays")).isNull();
    }
}
