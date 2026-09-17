package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;

import java.util.Set;

/**
 * Single source of truth for how a tenant-scoped system collection is filtered.
 *
 * <p>Both the JSON:API router ({@code DynamicCollectionRouter}) and the storage layer
 * ({@code PhysicalTableStorageAdapter}) narrow reads of a tenant-scoped system collection to
 * the caller's tenant. They used to carry their own copy of the "which collections also share
 * the platform's own rows" list, which is exactly the kind of duplication that lets the two
 * drift apart — so the predicate lives here and both ask this class.
 *
 * <p>Tenant scoping itself is enforced <b>once</b>, in
 * {@code PhysicalTableStorageAdapter.query()}, not per caller: every direct
 * {@code queryEngine.executeQuery} caller (dashboards, reports, page render, exports, bulk
 * operations, campaigns) inherits it without knowing this class exists. The router keeps its
 * own injection as a second layer.
 */
public final class SystemCollectionTenancy {

    /**
     * Tenant-scoped system collections whose {@link SystemCollectionDefinitions#SYSTEM_TENANT_ID}
     * rows are readable by every tenant alongside its own: a system collection's definition and
     * its built-in fields must be visible to the tenants that use it.
     *
     * <p>Reads only. A tenant may never write the platform's rows — the Postgres
     * {@code system_rows_read} policy (V200) is likewise {@code FOR SELECT}.
     */
    private static final Set<String> SHARES_SYSTEM_ROWS = Set.of("collections", "fields");

    private SystemCollectionTenancy() {
    }

    /**
     * True when reads of {@code definition} must be narrowed to the current tenant, i.e. it is a
     * system collection whose Flyway-managed table is shared by every tenant and discriminated by
     * a {@code tenant_id} column. User collections are isolated by schema instead and have no such
     * column.
     */
    public static boolean isTenantScoped(CollectionDefinition definition) {
        return definition != null && definition.systemCollection() && definition.tenantScoped();
    }

    /**
     * True when a read of {@code definition} must also admit the platform's own rows
     * ({@code tenant_id IN (caller, SYSTEM_TENANT_ID)}) rather than the caller's alone.
     */
    public static boolean sharesSystemRows(CollectionDefinition definition) {
        return definition != null && SHARES_SYSTEM_ROWS.contains(definition.name());
    }
}
