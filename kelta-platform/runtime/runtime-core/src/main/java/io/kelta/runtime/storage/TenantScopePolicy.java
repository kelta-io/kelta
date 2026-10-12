package io.kelta.runtime.storage;

import static io.kelta.runtime.storage.SqlIdentifiers.quoteIdentifier;
import static io.kelta.runtime.storage.SqlIdentifiers.resolveColumnName;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.RequestAuthentication;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.OwnerScope;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.model.system.SystemCollectionTenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Row-scoping predicates for {@link PhysicalTableStorageAdapter} reads: the {@code tenant_id}
 * (or self-scoped {@code id}) predicate for tenant-scoped system collections, the owner
 * predicate for owner-scoped collections, and the fail-closed rule for anonymous reads with no
 * tenant bound.
 *
 * <p>Security-critical. Extracted from {@code PhysicalTableStorageAdapter} verbatim; every read
 * path in the adapter still routes through here.
 */
final class TenantScopePolicy {

    /**
     * Logs under {@code PhysicalTableStorageAdapter}'s name, not this class's: the unscoped-read
     * WARN and anonymous-refusal lines predate this extraction, and tests (and anyone filtering
     * logs by logger) key on that name.
     */
    private static final Logger log = LoggerFactory.getLogger(PhysicalTableStorageAdapter.class);

    /**
     * Thread-name prefixes that mark a background scheduler. A tenant-scoped system collection
     * read with no bound tenant is expected there (cross-tenant sweeps bind each tenant in turn,
     * and the outer pass runs unbound); anywhere else it is the leak {@link #tenantScope} exists
     * to stop, so it is logged at WARN. {@code scheduler-} is kelta-worker's {@code SchedulerConfig}
     * prefix, {@code scheduling-} Spring's default.
     */
    private static final List<String> SCHEDULER_THREAD_PREFIXES = List.of("scheduler-", "scheduling-");

    private final SchemaMigrationEngine migrationEngine;

    /**
     * Whether a physical table carries a {@code tenant_id} column, keyed by its SQL identifier.
     * Introspected once per table and cached: eleven tenant-scoped system collections
     * ({@code approval-steps}, {@code field-versions}, {@code migration-steps}, …) are isolated
     * through their parent's foreign key and have no such column, and appending the predicate to
     * those would turn a working query into a SQL error.
     */
    private final Map<String, Boolean> tenantColumnByTable = new ConcurrentHashMap<>();

    TenantScopePolicy(SchemaMigrationEngine migrationEngine) {
        this.migrationEngine = migrationEngine;
    }

    /**
     * A rendered {@code tenant_id} predicate and its bind values, ANDed into a read's WHERE clause.
     *
     * @param sql the predicate SQL, e.g. {@code tenant_id = ?} (or {@code id = ?} when self-scoped)
     * @param params its bind values, in placeholder order
     */
    record TenantScope(String sql, List<Object> params) {}

    /** Matches no row: an anonymous request with no tenant reads nothing, rather than everything. */
    private static final TenantScope NO_ROWS = new TenantScope("FALSE", List.of());

    /**
     * The tenant predicate for a read of {@code definition}, or {@code null} when none applies.
     *
     * <p>Tenant-scoped system collections all live in Flyway-managed tables in {@code public},
     * shared by every tenant and discriminated by {@code tenant_id} — unlike user collections,
     * which are isolated by schema. Scoping them here rather than in each caller is the whole
     * point: {@code DynamicCollectionRouter} injected the filter only on the JSON:API path, so
     * {@code DashboardDataService}, {@code ReportExecutionService}, {@code PageRenderService},
     * {@code DataExportService}, {@code BulkOperationService} and {@code CampaignRunnerService}
     * all read across tenants (a {@code users} metric widget counted every tenant's portal users;
     * {@code /api/pages/home/render} served another tenant's page). Same pattern as
     * {@link PhysicalTableStorageAdapter#isUnique}, which has scoped uniqueness checks this way all along.
     *
     * <p>Self-scoped collections ({@code tenants}, {@link SystemCollectionTenancy#isSelfScoped})
     * have no {@code tenant_id} column — each row is a tenant — so they are narrowed by primary
     * key, {@code id = caller}, without the column introspection.
     *
     * <p>With no tenant bound, an {@link RequestAuthentication#ANONYMOUS anonymous} request gets
     * {@link #NO_ROWS} — see {@link #refusesAnonymousRead}.
     *
     * @param definition the collection being read
     * @param tableRef its resolved table
     * @return the predicate to AND in, or {@code null} when the read needs no tenant narrowing
     */
    TenantScope tenantScope(CollectionDefinition definition, TableRef tableRef) {
        boolean selfScoped = SystemCollectionTenancy.isSelfScoped(definition);
        if (!selfScoped && !SystemCollectionTenancy.isTenantScoped(definition)) {
            return null;
        }
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            if (refusesAnonymousRead(definition)) {
                return NO_ROWS;
            }
            warnUnscopedRead(definition);
            return null;
        }
        if (selfScoped) {
            return new TenantScope("id = ?", List.of(tenantId));
        }
        if (!hasTenantColumn(tableRef)) {
            return null;
        }
        if (SystemCollectionTenancy.sharesSystemRows(definition)) {
            return new TenantScope("tenant_id IN (?, ?)",
                    List.of(tenantId, SystemCollectionDefinitions.SYSTEM_TENANT_ID));
        }
        return new TenantScope("tenant_id = ?", List.of(tenantId));
    }

    /**
     * The owner predicate for an owner-scoped collection read, or {@code null} when the caller
     * is not limited to their own rows.
     *
     * <p>Applies only when the collection declares an owner field and scope, reads are scoped
     * ({@link CollectionDefinition#ownerScopeReads()}), and the bound {@link CallerContext} is
     * {@linkplain CallerContext#ownerScoped owner-scoped} for reads under that scope. No bound
     * caller is the internal tier (flows, NATS, schedulers): never narrowed. Sits beside
     * {@link #tenantScope} for the same reason — every QueryEngine read (lists, counts,
     * aggregates, {@code /latest}, includes, exports, reports, dashboards) passes through here,
     * so the filter is in SQL and paging totals stay correct.
     */
    TenantScope ownerScope(CollectionDefinition definition) {
        if (!definition.isOwnerScoped() || !definition.ownerScopeReads()) {
            return null;
        }
        Optional<CallerContext> caller = CallerContext.current();
        if (caller.isEmpty() || !caller.get().ownerScoped(definition.ownerScope(), CallerContext.Access.READ)) {
            return null;
        }
        String userId = caller.get().userId();
        if (userId == null || userId.isBlank()) {
            return NO_ROWS;
        }
        String column = quoteIdentifier(resolveColumnName(definition, definition.ownerField()));
        if (definition.ownerScope() != OwnerScope.ALL) {
            return new TenantScope(column + " = ?", List.of(userId));
        }
        // Under ALL a record share still widens reads: a row shared with the caller (directly or
        // through a group) stays visible. RecordShareAccessService.widen only re-admits rows the
        // query already returned, so the share has to be part of this predicate.
        return new TenantScope("(" + column + " = ? OR id IN (" + SHARED_WITH_CALLER + "))",
                List.of(userId, definition.name(), userId, userId));
    }

    /**
     * Record ids of the collection named by the 1st bind that are shared with the user in the
     * 2nd bind directly, or with a group whose member is the user in the 3rd bind. RLS on
     * {@code record_share} keeps it to the calling tenant.
     */
    private static final String SHARED_WITH_CALLER = """
            SELECT rs.record_id FROM record_share rs \
            JOIN collection c ON c.id = rs.collection_id \
            WHERE c.name = ? AND ((rs.shared_with_type = 'USER' AND rs.shared_with_id = ?) \
            OR (rs.shared_with_type = 'GROUP' AND rs.shared_with_id IN (\
            SELECT gm.group_id FROM group_membership gm \
            WHERE gm.member_type = 'USER' AND gm.member_id = ?)))""";

    /** {@link #tenantScope} and {@link #ownerScope} ANDed — the full row predicate for a read. */
    TenantScope readScope(CollectionDefinition definition, TableRef tableRef) {
        TenantScope tenant = tenantScope(definition, tableRef);
        TenantScope owner = ownerScope(definition);
        if (owner == null) {
            return tenant;
        }
        if (tenant == null) {
            return owner;
        }
        List<Object> params = new ArrayList<>(tenant.params());
        params.addAll(owner.params());
        return new TenantScope(tenant.sql() + " AND " + owner.sql(), params);
    }

    /**
     * Whether {@code tableRef} has a {@code tenant_id} column, introspected once and cached.
     *
     * <p>Not every tenant-scoped system collection has one: {@code approval-steps},
     * {@code field-versions}, {@code migration-steps} and friends hang off a parent row and are
     * isolated through its foreign key. Appending {@code tenant_id = ?} to those would turn a
     * working query into {@code column "tenant_id" does not exist}.
     *
     * <p>When the introspection tells us nothing — unknown table, unreadable catalog — the answer
     * is {@code true}. Guessing "no column" there would silently drop the tenant predicate, which
     * is the failure mode this whole change exists to remove; guessing "column" at worst surfaces
     * a loud SQL error.
     */
    private boolean hasTenantColumn(TableRef tableRef) {
        return tenantColumnByTable.computeIfAbsent(tableRef.toSql(), key -> {
            try {
                Set<String> columns = migrationEngine.getExistingColumns(
                        tableRef.schema(), tableRef.tableName());
                return columns == null || columns.isEmpty() || columns.contains("tenant_id");
            } catch (DataAccessException e) {
                log.warn("Could not introspect {} for a tenant_id column ({}) — scoping the read anyway",
                        key, e.getMessage());
                return true;
            }
        });
    }

    /**
     * A tenant-scoped (or self-scoped) system collection read with no bound tenant returns every
     * tenant's rows.
     * Genuine platform paths do that deliberately (Flyway, bootstrap, and the scheduler threads
     * that sweep tenant by tenant), so those are logged at DEBUG; anywhere else it is a leak
     * waiting to be reported and gets a WARN naming the collection and the thread.
     */
    private void warnUnscopedRead(CollectionDefinition definition) {
        String threadName = Thread.currentThread().getName();
        if (RequestAuthentication.current() == RequestAuthentication.PLATFORM_SCOPED) {
            log.debug("Tenant-scoped system collection '{}' read with no tenant context by a "
                    + "platform-scoped request — not tenant-filtered", definition.name());
            return;
        }
        if (isSchedulerThread(threadName)) {
            log.debug("Tenant-scoped system collection '{}' read with no tenant context on scheduler "
                    + "thread '{}' — not tenant-filtered", definition.name(), threadName);
            return;
        }
        log.warn("Tenant-scoped system collection '{}' read with no tenant context on thread '{}' — "
                        + "the query is NOT tenant-filtered and returns rows from every tenant",
                definition.name(), threadName);
    }

    /**
     * Whether a read of {@code definition} must fail closed: the collection is tenant- or
     * self-scoped, no tenant is bound, and the request carries no authenticated principal.
     * Defence in depth behind the gateway's 404 for public reads that resolve no tenant
     * (kelta#1617): such a read used to return every tenant's rows. Only a bound
     * {@link RequestAuthentication#ANONYMOUS} opts in — unbound contexts (scheduler, bootstrap,
     * Flyway) keep reading across tenants.
     */
    static boolean refusesAnonymousRead(CollectionDefinition definition) {
        if (RequestAuthentication.current() != RequestAuthentication.ANONYMOUS) {
            return false;
        }
        if (!SystemCollectionTenancy.isSelfScoped(definition)
                && !SystemCollectionTenancy.isTenantScoped(definition)) {
            return false;
        }
        String tenantId = TenantContext.get();
        if (tenantId != null && !tenantId.isBlank()) {
            return false;
        }
        log.info("Anonymous request with no tenant read tenant-scoped system collection '{}' — "
                + "refused, returning no rows", definition.name());
        return true;
    }

    private static boolean isSchedulerThread(String threadName) {
        return threadName != null
                && SCHEDULER_THREAD_PREFIXES.stream().anyMatch(threadName::startsWith);
    }
}
