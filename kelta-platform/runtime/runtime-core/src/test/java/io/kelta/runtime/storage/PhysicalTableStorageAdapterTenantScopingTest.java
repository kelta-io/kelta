package io.kelta.runtime.storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.StorageConfig;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.AggregationSpec;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PLT-260: reads of a tenant-scoped system collection are narrowed to the calling tenant in the
 * storage layer, so every direct {@code queryEngine.executeQuery} caller inherits the scoping —
 * not only the JSON:API router. Before this, {@code DashboardDataService},
 * {@code ReportExecutionService} and {@code PageRenderService} queried across tenants: a
 * {@code users} metric widget counted every tenant's portal users, and
 * {@code /api/pages/home/render} served whichever tenant's {@code home} page happened to sort first.
 */
class PhysicalTableStorageAdapterTenantScopingTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private JdbcTemplate jdbcTemplate;
    private SchemaMigrationEngine migrationEngine;
    private PhysicalTableStorageAdapter adapter;
    private ch.qos.logback.classic.Logger adapterLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        migrationEngine = mock(SchemaMigrationEngine.class);
        adapter = new PhysicalTableStorageAdapter(
                jdbcTemplate, migrationEngine, new tools.jackson.databind.ObjectMapper());

        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        withColumns("id", "tenant_id", "email", "user_type", "created_at");

        adapterLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(PhysicalTableStorageAdapter.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        adapterLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        adapterLogger.detachAppender(logAppender);
    }

    private void withColumns(String... columns) {
        when(migrationEngine.getExistingColumns(anyString(), anyString())).thenReturn(Set.of(columns));
    }

    private static CollectionDefinition users() {
        return new CollectionDefinitionBuilder()
                .name("users")
                .displayName("Users")
                .storageConfig(new StorageConfig("platform_user", null))
                .systemCollection(true)
                .tenantScoped(true)
                .addField(FieldDefinition.requiredString("email", 320))
                .addField(FieldDefinition.requiredString("userType", 20).withColumnName("user_type"))
                .build();
    }

    private static CollectionDefinition collections() {
        return new CollectionDefinitionBuilder()
                .name("collections")
                .displayName("Collections")
                .storageConfig(new StorageConfig("collection", null))
                .systemCollection(true)
                .tenantScoped(true)
                .addField(FieldDefinition.requiredString("name", 100))
                .build();
    }

    /** A tenant-scoped system collection isolated through its parent FK, with no tenant_id column. */
    private static CollectionDefinition approvalSteps() {
        return new CollectionDefinitionBuilder()
                .name("approval-steps")
                .displayName("Approval Steps")
                .storageConfig(new StorageConfig("approval_step", null))
                .systemCollection(true)
                .tenantScoped(true)
                .addField(FieldDefinition.requiredString("name", 100))
                .build();
    }

    private static QueryRequest query(FilterCondition... filters) {
        return new QueryRequest(Pagination.defaults(), List.of(), List.of(), List.of(filters));
    }

    private String capturedSelect() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        return sql.getValue();
    }

    private Object[] capturedSelectParams() {
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(anyString(), params.capture());
        return params.getValue();
    }

    private String capturedCount() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        return sql.getValue();
    }

    @Test
    @DisplayName("query on a tenant-scoped system collection filters by the bound tenant")
    void query_scopesToBoundTenant() {
        TenantContext.runWithTenant(TENANT, "acme", () -> adapter.query(users(), query()));

        assertTrue(capturedSelect().contains("WHERE tenant_id = ?"),
                "row query must be tenant-filtered, got: " + capturedSelect());
        assertTrue(List.of(capturedSelectParams()).contains(TENANT),
                "tenant id must be bound as a parameter");
    }

    @Test
    @DisplayName("the total count is scoped too — a metric widget counts only the caller's rows")
    void query_scopesTheTotalCount() {
        // The COUNT(*) is what a `metric` widget reports. Scoping only the row query is what made
        // a spotopened portal-user metric read 14 against an actual 6.
        TenantContext.runWithTenant(TENANT, "acme", () -> adapter.query(users(), query()));

        assertTrue(capturedCount().contains("WHERE tenant_id = ?"),
                "count query must be tenant-filtered, got: " + capturedCount());
    }

    @Test
    @DisplayName("a caller's own filters are ANDed with the tenant predicate, never replaced by it")
    void query_andsTenantPredicateWithCallerFilters() {
        TenantContext.runWithTenant(TENANT, "acme",
                () -> adapter.query(users(), query(FilterCondition.eq("userType", "PORTAL"))));

        String sql = capturedSelect();
        assertTrue(sql.contains("\"user_type\" = ?") && sql.contains("tenant_id = ?") && sql.contains(" AND "),
                "both predicates must survive, got: " + sql);
    }

    @Test
    @DisplayName("a caller-supplied tenantId filter cannot widen the scope")
    void query_cannotWidenScopeWithItsOwnTenantFilter() {
        // Two tenant_id predicates ANDed: naming someone else's tenant yields no rows, not theirs.
        TenantContext.runWithTenant(TENANT, "acme",
                () -> adapter.query(users(), query(FilterCondition.eq("tenantId", "other-tenant"))));

        List<Object> params = List.of(capturedSelectParams());
        assertTrue(params.contains(TENANT), "the bound tenant is still applied");
        assertTrue(params.contains("other-tenant"), "the caller's filter is kept, not dropped");
    }

    @Test
    @DisplayName("'collections' still sees the platform's own rows alongside its tenant's")
    void query_sharesSystemRowsForCollections() {
        TenantContext.runWithTenant(TENANT, "acme", () -> adapter.query(collections(), query()));

        assertTrue(capturedSelect().contains("tenant_id IN (?, ?)"),
                "collections must admit the system tenant's rows, got: " + capturedSelect());
        assertTrue(List.of(capturedSelectParams()).contains(SystemCollectionDefinitions.SYSTEM_TENANT_ID),
                "the system tenant id must be bound");
    }

    @Test
    @DisplayName("a tenant-scoped system collection whose table has no tenant_id column is left alone")
    void query_skipsTablesWithoutATenantColumn() {
        // approval_step and ten siblings hang off a parent row; appending tenant_id = ? to them
        // would turn a working query into `column "tenant_id" does not exist`.
        withColumns("id", "approval_process_id", "created_at");

        TenantContext.runWithTenant(TENANT, "acme", () -> adapter.query(approvalSteps(), query()));

        assertFalse(capturedSelect().contains("tenant_id"),
                "no tenant predicate is possible here, got: " + capturedSelect());
    }

    @Test
    @DisplayName("a non-system collection is untouched — schema-per-tenant already isolates it")
    void query_leavesUserCollectionsAlone() {
        CollectionDefinition products = new CollectionDefinitionBuilder()
                .name("products")
                .displayName("Products")
                .addField(FieldDefinition.requiredString("name"))
                .build();

        TenantContext.runWithTenant(TENANT, "acme", () -> adapter.query(products, query()));

        assertFalse(capturedSelect().contains("tenant_id"),
                "user collections have no tenant_id column, got: " + capturedSelect());
    }

    @Test
    @DisplayName("aggregate is scoped as well, so a COUNT cannot span tenants")
    void aggregate_scopesToBoundTenant() {
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of("total", 3L));

        TenantContext.runWithTenant(TENANT, "acme", () -> adapter.aggregate(
                users(), List.of(), List.of(new AggregationSpec("COUNT", null, "total"))));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForMap(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("WHERE tenant_id = ?"),
                "aggregate must be tenant-filtered, got: " + sql.getValue());
    }

    @Test
    @DisplayName("no tenant context: the read is unfiltered and says so at WARN")
    void query_warnsWhenReadUnscoped() {
        adapter.query(users(), query());

        assertFalse(capturedSelect().contains("tenant_id"), "nothing to filter on without a tenant");
        assertTrue(logAppender.list.stream()
                        .anyMatch(e -> e.getLevel() == Level.WARN
                                && e.getFormattedMessage().contains("users")
                                && e.getFormattedMessage().contains("no tenant context")),
                "an unscoped read of a tenant-scoped system collection must WARN, got: "
                        + logAppender.list);
    }

    @Test
    @DisplayName("no tenant context on a scheduler thread is expected — DEBUG, not WARN")
    void query_doesNotWarnOnSchedulerThreads() throws Exception {
        // Cross-tenant sweeps bind each tenant in turn; the outer pass runs unbound by design.
        Thread scheduler = new Thread(() -> adapter.query(users(), query()), "scheduler-1");
        scheduler.start();
        scheduler.join();

        assertTrue(logAppender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN),
                "scheduler threads must not be noisy, got: " + logAppender.list);
    }

    @Test
    @DisplayName("a non-tenant-scoped system collection never warns and never filters")
    void query_ignoresNonTenantScopedSystemCollections() {
        CollectionDefinition packageItems = new CollectionDefinitionBuilder()
                .name("package-items")
                .displayName("Package Items")
                .storageConfig(new StorageConfig("package_item", null))
                .systemCollection(true)
                .tenantScoped(false)
                .addField(FieldDefinition.requiredString("itemType", 50))
                .build();

        adapter.query(packageItems, query());

        assertFalse(capturedSelect().contains("WHERE"), "got: " + capturedSelect());
        assertTrue(logAppender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN),
                "got: " + logAppender.list);
    }

    // ---- PLT-330: tenants is self-scoped (id = bound tenant) -------------------------------

    private static final String OTHER_TENANT = "22222222-2222-2222-2222-222222222222";

    private static CollectionDefinition tenants() {
        return SystemCollectionDefinitions.tenants();
    }

    @Test
    @DisplayName("tenants: a query is narrowed to the bound tenant's own row by primary key")
    void tenants_queryScopesToOwnRow() {
        withColumns("id", "slug", "name", "created_at");

        TenantContext.runWithTenant(TENANT, "acme", () -> adapter.query(tenants(), query()));

        String sql = capturedSelect();
        assertTrue(sql.contains("WHERE id = ?"), "tenants must be self-scoped, got: " + sql);
        assertFalse(sql.contains("tenant_id"), "tenant has no tenant_id column, got: " + sql);
        assertEquals(TENANT, capturedSelectParams()[0], "the bound tenant is the first bind value");
    }

    @Test
    @DisplayName("tenants: the self-scope is ANDed with caller filters, never replacing them")
    void tenants_queryAndsSelfScopeWithCallerFilters() {
        TenantContext.runWithTenant(TENANT, "acme",
                () -> adapter.query(tenants(), query(FilterCondition.eq("id", OTHER_TENANT))));

        String sql = capturedSelect();
        assertTrue(sql.contains("\"id\" = ?") && sql.contains("id = ?") && sql.contains(" AND "),
                "both predicates must survive, got: " + sql);
        List<Object> params = List.of(capturedSelectParams());
        assertTrue(params.contains(TENANT), "the bound tenant is still applied");
        assertTrue(params.contains(OTHER_TENANT), "the caller's filter is kept — and can only narrow");
    }

    @Test
    @DisplayName("tenants: the total count carries the self-scope — a metric over tenants reads 1")
    void tenants_totalCountIsSelfScoped() {
        TenantContext.runWithTenant(TENANT, "acme", () -> adapter.query(tenants(), query()));

        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(anyString(), eq(Long.class), params.capture());
        assertTrue(capturedCount().contains("WHERE id = ?"), "got: " + capturedCount());
        assertEquals(List.of(TENANT), List.of(params.getValue()));
    }

    @Test
    @DisplayName("tenants: aggregate is self-scoped too")
    void tenants_aggregateIsSelfScoped() {
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of("total", 1L));

        TenantContext.runWithTenant(TENANT, "acme", () -> adapter.aggregate(
                tenants(), List.of(), List.of(new AggregationSpec("COUNT", null, "total"))));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForMap(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("WHERE id = ?"), "got: " + sql.getValue());
    }

    @Test
    @DisplayName("tenants: get-by-id of another tenant's id is ANDed with id = caller and reads as absent")
    void tenants_getByIdOfForeignTenantIsEmpty() {
        Optional<Map<String, Object>> result = TenantContext.callWithTenant(TENANT,
                () -> adapter.getById(tenants(), OTHER_TENANT));

        assertTrue(result.isEmpty(), "another tenant's row must not be readable");
        assertEquals("SELECT * FROM tenant WHERE id = ? AND id = ?", capturedSelect());
        assertArrayEquals(new Object[]{OTHER_TENANT, TENANT}, capturedSelectParams());
    }

    @Test
    @DisplayName("tenants: get-by-id of the caller's own tenant still reads it")
    void tenants_getByIdOfOwnTenant() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(new ArrayList<>(List.of(new java.util.HashMap<>(Map.of("id", TENANT, "slug", "acme")))));

        Optional<Map<String, Object>> result = TenantContext.callWithTenant(TENANT,
                () -> adapter.getById(tenants(), TENANT));

        assertTrue(result.isPresent());
        assertArrayEquals(new Object[]{TENANT, TENANT}, capturedSelectParams());
    }

    @Test
    @DisplayName("tenants: with no tenant bound the read is unfiltered (platform paths), and WARNs off-scheduler")
    void tenants_unboundReadIsUnfiltered() {
        adapter.query(tenants(), query());
        adapter.getById(tenants(), OTHER_TENANT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).queryForList(sql.capture(), any(Object[].class));
        assertFalse(sql.getAllValues().get(0).contains("WHERE"), "got: " + sql.getAllValues().get(0));
        assertEquals("SELECT * FROM tenant WHERE id = ?", sql.getAllValues().get(1));
        assertTrue(logAppender.list.stream()
                        .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("tenants")),
                "consistent with tenant-scoped collections, got: " + logAppender.list);
    }

    @Test
    @DisplayName("get-by-id of a tenant_id-scoped collection is unchanged — that stays router-side")
    void getById_leavesTenantIdScopedCollectionsToTheRouter() {
        TenantContext.callWithTenant(TENANT, () -> adapter.getById(users(), OTHER_TENANT));

        assertEquals("SELECT * FROM platform_user WHERE id = ?", capturedSelect());
    }
}
