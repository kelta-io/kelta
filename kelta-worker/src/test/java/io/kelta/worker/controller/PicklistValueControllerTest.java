package io.kelta.worker.controller;

import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.runtime.storage.UniqueConstraintViolationException;
import io.kelta.worker.filter.TenantContextFilter;
import io.kelta.worker.repository.PicklistValueRepository;
import io.kelta.worker.service.PicklistValueCreationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/picklist-values} answers a duplicate value in a global picklist with a 409 naming
 * the picklist and the value. The router stand-in inserts into a real (H2) {@code picklist_value}
 * table carrying {@code uq_picklist_value} and, like the physical storage adapter, reports the
 * composite clash as field {@code id} with the new row's UUID.
 */
@DisplayName("Picklist value create: duplicate value in a global picklist")
class PicklistValueControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String PICKLIST = "gp-stage";

    /** Stands in for {@code DynamicCollectionRouter}'s create mapping, shape for shape. */
    @RestController
    @RequestMapping("/api")
    static class RouterStub {
        @PostMapping("/{collectionName}")
        String create() {
            return "router";
        }
    }

    private JdbcTemplate jdbc;
    private DynamicCollectionRouter router;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // VALUE is reserved in H2, not in Postgres, where the repository's SQL runs.
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE"));
        jdbc.execute("""
                CREATE TABLE global_picklist (
                    id VARCHAR(36) PRIMARY KEY,
                    tenant_id VARCHAR(36) NOT NULL,
                    name VARCHAR(100) NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE picklist_value (
                    id VARCHAR(36) PRIMARY KEY,
                    tenant_id VARCHAR(36) NOT NULL,
                    picklist_source_type VARCHAR(20) NOT NULL,
                    picklist_source_id VARCHAR(36) NOT NULL,
                    value VARCHAR(255) NOT NULL,
                    CONSTRAINT uq_picklist_value UNIQUE (picklist_source_type, picklist_source_id, value)
                )""");
        jdbc.update("INSERT INTO global_picklist (id, tenant_id, name) VALUES (?, ?, ?)",
                PICKLIST, TENANT, "crm-stage");

        router = mock(DynamicCollectionRouter.class);
        when(router.create(eq("picklist-values"), anyMap(), any())).thenAnswer(inv -> {
            Map<String, Object> body = inv.getArgument(1);
            Map<String, Object> attributes = body.containsKey("data")
                    ? (Map<String, Object>) ((Map<String, Object>) body.get("data")).get("attributes")
                    : body;
            String id = UUID.randomUUID().toString();
            try {
                jdbc.update("INSERT INTO picklist_value (id, tenant_id, picklist_source_type, "
                                + "picklist_source_id, value) VALUES (?, ?, ?, ?, ?)",
                        id, TENANT, attributes.get("picklistSourceType"),
                        attributes.get("picklistSourceId"), attributes.get("value"));
            } catch (DuplicateKeyException e) {
                throw new UniqueConstraintViolationException("picklist-values", "id", id, e);
            }
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("data", Map.of("id", id, "type", "picklist-values")));
        });

        PicklistValueCreationService service =
                new PicklistValueCreationService(new PicklistValueRepository(jdbc));
        mvc = MockMvcBuilders
                .standaloneSetup(new PicklistValueController(service, router), new RouterStub())
                .addFilters(new TenantContextFilter())
                .build();
    }

    private ResultActions addValue(String sourceType, String value) throws Exception {
        return mvc.perform(post("/api/picklist-values")
                .header("X-Tenant-ID", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"data":{"type":"picklist-values","attributes":{
                          "value":"%s","label":"%s",
                          "picklistSourceType":"%s","picklistSourceId":"%s"}}}
                        """.formatted(value, value, sourceType, PICKLIST)));
    }

    @Test
    @DisplayName("Adding a value the picklist already has returns 409 naming the picklist and the value")
    void duplicateValueReturnsConflictNamingPicklistAndValue() throws Exception {
        addValue("GLOBAL", "OPEN").andExpect(status().isCreated());

        addValue("GLOBAL", "OPEN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].status").value("409"))
                .andExpect(jsonPath("$.errors[0].code").value("UNIQUE_VIOLATION"))
                .andExpect(jsonPath("$.errors[0].detail").value("Picklist 'crm-stage' already has the value 'OPEN'"))
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/value"))
                .andExpect(jsonPath("$.errors[0].meta.picklist").value("crm-stage"))
                .andExpect(jsonPath("$.errors[0].meta.value").value("OPEN"))
                .andExpect(content().string(not(containsString("field 'id'"))))
                .andExpect(content().string(not(containsString(PICKLIST))));
    }

    @Test
    @DisplayName("A new, distinct value in the same picklist is still created")
    void distinctValueStillCreated() throws Exception {
        addValue("GLOBAL", "OPEN").andExpect(status().isCreated());
        addValue("GLOBAL", "OPEN").andExpect(status().isConflict());

        addValue("GLOBAL", "WON")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("picklist-values"));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM picklist_value WHERE picklist_source_id = ?", Integer.class, PICKLIST);
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("A flat (non-JSON:API) body gets the pointer /value")
    void flatBodyPointsAtValue() throws Exception {
        String body = """
                {"value":"OPEN","label":"OPEN","picklistSourceType":"GLOBAL","picklistSourceId":"%s"}
                """.formatted(PICKLIST);
        mvc.perform(post("/api/picklist-values").header("X-Tenant-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/picklist-values").header("X-Tenant-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/value"));
    }

    @Test
    @DisplayName("A unique violation on a field-local value keeps the generic handling")
    void fieldValueViolationIsNotRewritten() throws Exception {
        addValue("FIELD", "OPEN").andExpect(status().isCreated());

        assertThatThrownBy(() -> addValue("FIELD", "OPEN"))
                .hasCauseInstanceOf(UniqueConstraintViolationException.class);
    }

    @Test
    @DisplayName("POST to another collection still reaches the router")
    void otherCollectionReachesRouter() throws Exception {
        mvc.perform(post("/api/global-picklists").header("X-Tenant-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string("router"));
        verify(router, never()).create(any(), any(), any());
    }
}
