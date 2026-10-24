package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.DefaultQueryEngine;
import io.kelta.runtime.registry.ConcurrentCollectionRegistry;
import io.kelta.runtime.storage.CompositeUniqueConstraintService;
import io.kelta.runtime.storage.PhysicalTableStorageAdapter;
import io.kelta.runtime.storage.SchemaMigrationEngine;
import io.kelta.worker.repository.PackageRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * UNIQUE_CONSTRAINT, REPORT and APPROVAL_PROCESS items against real migrations: each package is
 * applied, then applied again, and the second report must leave the item SKIPPED with nothing
 * created, updated or failed — i.e. the target-tenant seed queries find what the first apply
 * wrote under the same natural key.
 *
 * <p>The target tenant already holds the {@code orders} collection (with its physical table), a
 * user and a group, so each package carries only the item under test. Every item is named
 * uniquely per test, since all tests share the one tenant. Runs only where Docker is available
 * (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Package types UNIQUE_CONSTRAINT / REPORT / APPROVAL_PROCESS — apply twice on Postgres")
class PackageTypesIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT = "eeeeeeee-0000-0000-0000-000000000001";
    static final String SLUG = "pkgtypes";
    static final String USER_ID = UUID.randomUUID().toString();
    static final String GROUP_ID = UUID.randomUUID().toString();

    static JdbcTemplate jdbc;
    static PackageImportService importService;

    @BeforeAll
    static void migrateAndSeed() {
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
        jdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", TENANT, SLUG, "Package Types Co");
        jdbc.update("INSERT INTO platform_user (id, tenant_id, email, username, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                USER_ID, TENANT, "ann@pkgtypes.test", "ann");
        jdbc.update("INSERT INTO user_group (id, tenant_id, name) VALUES (?, ?, ?)", GROUP_ID, TENANT, "Finance");
        jdbc.update("INSERT INTO collection (id, name, tenant_id, system_collection) VALUES (?, ?, ?, false)",
                collectionId, "orders", TENANT);
        for (String field : List.of("region", "code", "owner")) {
            jdbc.update("INSERT INTO field (id, collection_id, tenant_id, name, type) VALUES (?, ?, ?, ?, 'STRING')",
                    UUID.randomUUID().toString(), collectionId, TENANT, field);
        }

        ConcurrentCollectionRegistry registry = new ConcurrentCollectionRegistry();
        SystemCollectionDefinitions.all().forEach(registry::register);
        CollectionDefinition orders = new CollectionDefinitionBuilder().name("orders").tenantId(TENANT)
                .addField(FieldDefinition.string("region"))
                .addField(FieldDefinition.string("code"))
                .addField(FieldDefinition.string("owner"))
                .build();
        registry.register(orders);

        ObjectMapper objectMapper = new ObjectMapper();
        PhysicalTableStorageAdapter storageAdapter = new PhysicalTableStorageAdapter(jdbc,
                new SchemaMigrationEngine(jdbc), objectMapper);
        TenantContext.runWithTenant(TENANT, SLUG, () -> storageAdapter.initializeCollection(orders));

        DefaultQueryEngine queryEngine = new DefaultQueryEngine(storageAdapter);
        importService = new PackageImportService(queryEngine, registry, new PackageRepository(jdbc),
                objectMapper, new JdbcUserIdResolver(jdbc), mock(DashboardTreeService.class),
                new DashboardComponentValidator(registry, mock(CollectionLifecycleManager.class), queryEngine),
                new DataSourceTransactionManager(dataSource),
                new CompositeUniqueConstraintService(jdbc, storageAdapter));
    }

    private static PackageImportService.ImportReport apply(Map<String, Object> item) {
        return TenantContext.callWithTenant(TENANT, SLUG, () -> importService.importPackage(TENANT,
                Map.of("items", List.of(item)), PackageImportService.ImportOptions.defaults()));
    }

    private static Map<String, Object> item(String type, Map<String, Object> data) {
        return Map.of("type", type, "data", data);
    }

    private static void assertSecondApplyUnchanged(Map<String, Object> item, String naturalKey) {
        var second = apply(item);
        assertThat(second.items()).as("%s", second.items()).singleElement().satisfies(result -> {
            assertThat(result.naturalKey()).isEqualTo(naturalKey);
            assertThat(result.action()).isEqualTo("SKIPPED");
        });
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(second.failed()).isZero();
    }

    @Test
    @DisplayName("UNIQUE_CONSTRAINT: the second apply finds the index the first created")
    void uniqueConstraintAppliesOnce() {
        var constraint = item("UNIQUE_CONSTRAINT",
                Map.of("collection_name", "orders", "field_names", List.of("region", "code")));

        var first = apply(constraint);
        assertThat(first.created()).as("%s", first.items()).isEqualTo(1);
        String indexName = first.items().get(0).resourceId();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
                Integer.class, SLUG, indexName)).isEqualTo(1);

        assertSecondApplyUnchanged(constraint, "orders:code,region");
    }

    @Test
    @DisplayName("REPORT: the second apply finds the report the first created, by name")
    void reportAppliesOnce() {
        String name = "it-report-" + UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("report_type", "TABULAR");
        data.put("primary_collection_name", "orders");
        data.put("columns", List.of(Map.of("fieldName", "region"), Map.of("fieldName", "code")));
        data.put("group_by", "region");
        var report = item("REPORT", data);

        var first = apply(report);
        assertThat(first.created()).as("%s", first.items()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT c.name FROM report r JOIN collection c ON c.id = r.primary_collection_id "
                + "WHERE r.tenant_id = ? AND r.name = ?", String.class, TENANT, name)).isEqualTo("orders");

        assertSecondApplyUnchanged(report, name);
    }

    @Test
    @DisplayName("APPROVAL_PROCESS: approvers resolve by email and group name; the second apply finds the process")
    void approvalProcessAppliesOnce() {
        String name = "it-approval-" + UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("collection_name", "orders");
        data.put("name", name);
        data.put("active", true);
        data.put("initial_submitter_field", "owner");
        data.put("steps", List.of(
                Map.of("step_number", 1, "name", "Manager", "approver_type", "USER",
                        "approver_email", "ANN@pkgtypes.test"),
                Map.of("step_number", 2, "name", "Finance", "approver_type", "GROUP",
                        "approver_group_name", "Finance")));
        var process = item("APPROVAL_PROCESS", data);

        var first = apply(process);
        assertThat(first.created()).as("%s", first.items()).isEqualTo(1);
        List<Map<String, Object>> steps = jdbc.queryForList(
                "SELECT s.step_number, s.approver_id FROM approval_step s "
                        + "JOIN approval_process ap ON ap.id = s.approval_process_id "
                        + "WHERE ap.tenant_id = ? AND ap.name = ? ORDER BY s.step_number", TENANT, name);
        assertThat(steps).extracting(s -> s.get("approver_id")).containsExactly(USER_ID, GROUP_ID);

        assertSecondApplyUnchanged(process, "orders:" + name);
    }
}
