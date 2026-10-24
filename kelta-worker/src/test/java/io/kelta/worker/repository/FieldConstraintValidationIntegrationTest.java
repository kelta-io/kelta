package io.kelta.worker.repository;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.query.DefaultQueryEngine;
import io.kelta.runtime.registry.ConcurrentCollectionRegistry;
import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.runtime.router.GlobalExceptionHandler;
import io.kelta.runtime.storage.PhysicalTableStorageAdapter;
import io.kelta.runtime.storage.SchemaMigrationEngine;
import io.kelta.runtime.validation.DefaultValidationEngine;
import io.kelta.worker.service.CollectionLifecycleManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * PLT-711 against real migrations: a user collection's stored field constraints and active
 * picklist values are enforced on write through {@link DynamicCollectionRouter}.
 *
 * <p>The collection definition is built by {@link CollectionLifecycleManager} from real
 * {@code field} / {@code picklist_value} rows — the {@code code} field's constraints are stored the
 * way the field editor writes them (a JSON string holding {@code {"max":3}}), and the
 * {@code status} picklist has the active values {@code a}, {@code b} and the inactive value
 * {@code c}. Before the fix the definition carried neither, and every write below returned 201.
 *
 * <p>Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Field constraints and picklist values are enforced on user-collection writes (PLT-711)")
class FieldConstraintValidationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT = "fcfcfcfc-0000-0000-0000-000000000001";
    static final String SLUG = "fieldconstraints";
    static final String COLLECTION = "fc_items";

    static final ObjectMapper JSON = new ObjectMapper();
    static JdbcTemplate jdbc;
    static MockMvc mockMvc;

    @BeforeAll
    static void migrateSeedAndLoad() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholderReplacement(false)
                .load()
                .migrate();

        String collectionId = UUID.randomUUID().toString();
        String statusFieldId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", TENANT, SLUG, "Field Constraints Co");
        jdbc.update("INSERT INTO collection (id, name, tenant_id, system_collection) VALUES (?, ?, ?, false)",
                collectionId, COLLECTION, TENANT);
        insertField(statusFieldId, collectionId, "status", "PICKLIST", null, 1);
        insertField(UUID.randomUUID().toString(), collectionId, "code", "STRING",
                JSON.writeValueAsString("{\"max\":3}"), 2);
        insertField(UUID.randomUUID().toString(), collectionId, "note", "STRING", null, 3);
        insertValue(statusFieldId, "a", true, 0);
        insertValue(statusFieldId, "b", true, 1);
        insertValue(statusFieldId, "c", false, 2);

        ConcurrentCollectionRegistry registry = new ConcurrentCollectionRegistry();
        PhysicalTableStorageAdapter storageAdapter = new PhysicalTableStorageAdapter(jdbc,
                new SchemaMigrationEngine(jdbc), JSON);
        CollectionLifecycleManager lifecycleManager =
                new CollectionLifecycleManager(registry, storageAdapter, jdbc, JSON);
        lifecycleManager.setPicklistValueRepository(new PicklistValueRepository(jdbc));
        lifecycleManager.initializeCollectionOrThrow(collectionId, true);

        DefaultQueryEngine queryEngine = new DefaultQueryEngine(
                storageAdapter, new DefaultValidationEngine(storageAdapter, registry));
        mockMvc = MockMvcBuilders.standaloneSetup(new DynamicCollectionRouter(registry, queryEngine))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static void insertField(String id, String collectionId, String name, String type,
                                    String constraintsJson, int order) {
        jdbc.update("""
                INSERT INTO field (id, collection_id, tenant_id, name, type, constraints, field_order)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, id, collectionId, TENANT, name, type, constraintsJson, order);
    }

    private static void insertValue(String fieldId, String value, boolean active, int sortOrder) {
        jdbc.update("""
                INSERT INTO picklist_value (id, picklist_source_type, picklist_source_id, value, label,
                                            is_active, sort_order, tenant_id)
                VALUES (?, 'FIELD', ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), fieldId, value, value.toUpperCase(), active, sortOrder, TENANT);
    }

    private static MvcResult perform(MockHttpServletRequestBuilder request, Map<String, Object> attributes)
            throws Exception {
        Map<String, Object> body = Map.of("data", Map.of("type", COLLECTION, "attributes", attributes));
        return mockMvc.perform(request
                        .header("X-Tenant-ID", TENANT)
                        .header("X-Tenant-Slug", SLUG)
                        .contentType("application/json")
                        .content(JSON.writeValueAsString(body)))
                .andReturn();
    }

    private static MvcResult create(Map<String, Object> attributes) throws Exception {
        return perform(post("/api/" + COLLECTION), attributes);
    }

    private static JsonNode firstError(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).path("errors").get(0);
    }

    private static String createdId(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return JSON.readTree(result.getResponse().getContentAsString()).path("data").path("id").asString();
    }

    @Test
    @DisplayName("picklist value outside the active values → 400 naming the field (inactive value too)")
    void picklistValueOutsideActiveValuesIsRejected() throws Exception {
        for (String invalid : new String[] {"zzz", "c"}) {
            MvcResult result = create(Map.of("status", invalid, "note", "picklist-" + invalid));

            assertThat(result.getResponse().getStatus()).as(invalid).isEqualTo(400);
            JsonNode error = firstError(result);
            assertThat(error.path("title").asString()).isEqualTo("Validation Error");
            assertThat(error.path("code").asString()).isEqualTo("enum");
            assertThat(error.path("source").path("pointer").asString()).isEqualTo("/data/attributes/status");
            assertThat(error.path("detail").asString()).isEqualTo("Value must be one of: a, b");
        }
    }

    @Test
    @DisplayName("string over the stored max length 3 → 400 maxLength on the field")
    void stringOverMaxLengthIsRejected() throws Exception {
        MvcResult result = create(Map.of("code", "toolong"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode error = firstError(result);
        assertThat(error.path("code").asString()).isEqualTo("maxLength");
        assertThat(error.path("source").path("pointer").asString()).isEqualTo("/data/attributes/code");
    }

    @Test
    @DisplayName("valid picklist value and string within max length → 201")
    void validValuesAreCreated() throws Exception {
        createdId(create(Map.of("status", "a", "code", "abc", "note", "valid")));
        createdId(create(Map.of("status", "b")));
    }

    @Test
    @DisplayName("PATCH of an unrelated field on a row already holding an invalid picklist value succeeds")
    void patchOfUnrelatedFieldIsNotRetroactivelyRejected() throws Exception {
        String id = createdId(create(Map.of("status", "a", "note", "before")));
        jdbc.update("UPDATE " + SLUG + "." + COLLECTION + " SET status = 'zzz' WHERE id = ?", id);

        MvcResult result = perform(patch("/api/" + COLLECTION + "/" + id), Map.of("note", "after"));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT note FROM " + SLUG + "." + COLLECTION + " WHERE id = ?",
                String.class, id)).isEqualTo("after");
    }
}
