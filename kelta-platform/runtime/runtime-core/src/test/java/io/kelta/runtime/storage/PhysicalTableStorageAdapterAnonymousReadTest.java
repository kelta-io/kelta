package io.kelta.runtime.storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kelta.runtime.context.RequestAuthentication;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PLT-354: an anonymous request that resolved no tenant must not read a tenant-scoped system
 * collection across tenants. Before this, a public bootstrap read with no tenant
 * ({@code ui-pages}, {@code ui-menus}, {@code oidc-providers}) returned every tenant's rows; the
 * gateway now 404s those (kelta#1617) and the storage layer fails closed behind it.
 *
 * <p>The JdbcTemplate here stands in for the shared {@code public} tables: it holds rows for two
 * tenants and applies the {@code tenant_id} / {@code id} / {@code FALSE} predicate the adapter
 * renders, and records every SELECT so a test can assert no unscoped one ran.
 */
class PhysicalTableStorageAdapterAnonymousReadTest {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

    private final Map<String, List<Map<String, Object>>> rowsByTable = new HashMap<>();
    private final List<String> executedSql = new ArrayList<>();

    private PhysicalTableStorageAdapter adapter;
    private ch.qos.logback.classic.Logger adapterLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SchemaMigrationEngine migrationEngine = mock(SchemaMigrationEngine.class);
        when(migrationEngine.getExistingColumns(anyString(), anyString()))
                .thenReturn(Set.of("id", "tenant_id", "name", "created_at"));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenAnswer(inv -> select(inv.getArgument(0), (Object[]) inv.getRawArguments()[1]));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenAnswer(inv -> (long) select(inv.getArgument(0), (Object[]) inv.getRawArguments()[2]).size());
        adapter = new PhysicalTableStorageAdapter(
                jdbcTemplate, migrationEngine, new tools.jackson.databind.ObjectMapper());

        for (String table : List.of("ui_page", "ui_menu", "oidc_provider")) {
            rowsByTable.put(table, List.of(
                    Map.of("id", table + "-a", "tenant_id", TENANT_A, "name", "a"),
                    Map.of("id", table + "-b", "tenant_id", TENANT_B, "name", "b")));
        }
        rowsByTable.put("tenant", List.of(
                Map.of("id", TENANT_A, "name", "a"),
                Map.of("id", TENANT_B, "name", "b")));

        adapterLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(PhysicalTableStorageAdapter.class);
        previousLevel = adapterLogger.getLevel();
        adapterLogger.setLevel(Level.DEBUG);
        logAppender = new ListAppender<>();
        logAppender.start();
        adapterLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        adapterLogger.detachAppender(logAppender);
        adapterLogger.setLevel(previousLevel);
    }

    /** Applies the single tenant predicate the adapter renders to the seeded rows. */
    private List<Map<String, Object>> select(String sql, Object[] params) {
        executedSql.add(sql);
        String table = sql.replaceAll("(?s).* FROM (\\w+).*", "$1");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rowsByTable.getOrDefault(table, List.of())) {
            boolean visible;
            if (sql.contains("FALSE")) {
                visible = false;
            } else if (sql.contains("tenant_id = ?")) {
                visible = params[0].equals(row.get("tenant_id"));
            } else if (sql.contains("id = ?")) {
                visible = params[0].equals(row.get("id"));
            } else {
                visible = true;
            }
            if (visible) {
                result.add(new HashMap<>(row));
            }
        }
        return result;
    }

    private static List<CollectionDefinition> publicBootstrapCollections() {
        return List.of(
                SystemCollectionDefinitions.uiPages(),
                SystemCollectionDefinitions.uiMenus(),
                SystemCollectionDefinitions.oidcProviders());
    }

    private static QueryRequest query() {
        return new QueryRequest(Pagination.defaults(), List.of(), List.of(), List.of());
    }

    private static <T> T as(RequestAuthentication auth, ScopedValue.CallableOp<T, RuntimeException> op) {
        return ScopedValue.where(RequestAuthentication.CURRENT, auth).call(op);
    }

    private boolean logged(Level level) {
        return logAppender.list.stream().anyMatch(e -> e.getLevel() == level);
    }

    @Test
    @DisplayName("anonymous, no tenant: ui-pages / ui-menus / oidc-providers list nothing and no unscoped SELECT runs")
    void anonymousWithoutTenantListsNothing() {
        for (CollectionDefinition definition : publicBootstrapCollections()) {
            var result = as(RequestAuthentication.ANONYMOUS, () -> adapter.query(definition, query()));

            assertEquals(0, result.data().size(), definition.name() + ": " + result.data());
            assertEquals(0, result.metadata().totalCount(), definition.name());
        }
        assertTrue(executedSql.stream().allMatch(sql -> sql.contains("WHERE FALSE")),
                "every SELECT must carry the fail-closed predicate, got: " + executedSql);
        assertTrue(logAppender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN),
                "a refused read is not a leak, got: " + logAppender.list);
    }

    @Test
    @DisplayName("anonymous, no tenant: get-by-id is empty without touching the database")
    void anonymousWithoutTenantGetsNothing() {
        for (CollectionDefinition definition : publicBootstrapCollections()) {
            String id = definition.storageConfig().tableName() + "-a";
            assertTrue(as(RequestAuthentication.ANONYMOUS, () -> adapter.getById(definition, id)).isEmpty(),
                    definition.name());
        }
        assertTrue(as(RequestAuthentication.ANONYMOUS,
                () -> adapter.getById(SystemCollectionDefinitions.tenants(), TENANT_A)).isEmpty());
        assertTrue(executedSql.isEmpty(), "got: " + executedSql);
    }

    @Test
    @DisplayName("anonymous, no tenant: the self-scoped tenants collection lists nothing either")
    void anonymousWithoutTenantListsNoTenants() {
        var result = as(RequestAuthentication.ANONYMOUS,
                () -> adapter.query(SystemCollectionDefinitions.tenants(), query()));

        assertEquals(0, result.data().size());
    }

    @Test
    @DisplayName("tenant bound: anonymous and authenticated requests see only that tenant's rows")
    void tenantBoundSeesOnlyItsRows() {
        for (RequestAuthentication auth : List.of(RequestAuthentication.ANONYMOUS, RequestAuthentication.AUTHENTICATED)) {
            for (CollectionDefinition definition : publicBootstrapCollections()) {
                var result = as(auth, () -> TenantContext.callWithTenant(TENANT_A,
                        () -> adapter.query(definition, query())));

                assertEquals(1, result.data().size(), auth + " " + definition.name());
                assertEquals(TENANT_A, result.data().get(0).get("tenantId"), auth + " " + definition.name());
            }
        }
    }

    @Test
    @DisplayName("scheduler thread, no request: still reads across tenants, at DEBUG")
    void schedulerThreadStillReadsUnscoped() throws Exception {
        AtomicReference<Integer> rows = new AtomicReference<>();
        Thread scheduler = new Thread(
                () -> rows.set(adapter.query(SystemCollectionDefinitions.uiPages(), query()).data().size()),
                "scheduler-1");
        scheduler.start();
        scheduler.join();

        assertEquals(2, rows.get());
        assertTrue(logged(Level.DEBUG), "got: " + logAppender.list);
        assertTrue(!logged(Level.WARN), "got: " + logAppender.list);
    }

    @Test
    @DisplayName("platform-scoped (MANAGE_TENANTS) read of tenants with no tenant: every row, DEBUG not WARN")
    void platformScopedTenantsReadLogsAtDebug() {
        var result = as(RequestAuthentication.PLATFORM_SCOPED,
                () -> adapter.query(SystemCollectionDefinitions.tenants(), query()));

        assertEquals(2, result.data().size());
        assertTrue(logAppender.list.stream().anyMatch(e -> e.getLevel() == Level.DEBUG
                        && e.getFormattedMessage().contains("tenants")),
                "got: " + logAppender.list);
        assertTrue(!logged(Level.WARN), "WARN must keep meaning 'possible leak', got: " + logAppender.list);
    }

    @Test
    @DisplayName("authenticated but not platform-scoped, no tenant: unchanged — unscoped, and WARNs")
    void authenticatedWithoutTenantStillWarns() {
        var result = as(RequestAuthentication.AUTHENTICATED,
                () -> adapter.query(SystemCollectionDefinitions.uiPages(), query()));

        assertEquals(2, result.data().size());
        assertTrue(logged(Level.WARN), "got: " + logAppender.list);
    }

    @Test
    @DisplayName("anonymous, no tenant: a non-tenant-scoped system collection is not refused")
    void anonymousNonTenantScopedIsUntouched() {
        var packageItems = new io.kelta.runtime.model.CollectionDefinitionBuilder()
                .name("package-items")
                .displayName("Package Items")
                .storageConfig(new io.kelta.runtime.model.StorageConfig("ui_page", null))
                .systemCollection(true)
                .tenantScoped(false)
                .addField(io.kelta.runtime.model.FieldDefinition.requiredString("name", 100))
                .build();

        var result = as(RequestAuthentication.ANONYMOUS, () -> adapter.query(packageItems, query()));

        assertEquals(2, result.data().size());
    }
}
