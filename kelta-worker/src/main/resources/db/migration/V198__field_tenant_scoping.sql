-- Security fix (KLT-206): GET /api/fields returned every field on the platform
-- (2,580+ rows across tenants in production) because `fields` was declared
-- .tenantScoped(false) and the `field` table had no tenant_id column, so
-- injectTenantFilter added no predicate and RLS had nothing to key on. A tenant
-- could enumerate other tenants' field names/types via filter[collectionId][eq]
-- guessing (or an unfiltered list call).
--
-- Denormalises tenant_id onto `field` (rather than a router-level join through
-- `collection`) so injectTenantFilter/RLS stay uniform and indexable, matching
-- every other tenant-scoped system collection. Backfilled from the owning
-- collection's tenant_id, which is also the value new rows get going forward
-- (DynamicCollectionRouter.injectTenantId stamps the caller's X-Tenant-ID, and a
-- tenant may only add fields to collections it owns).

ALTER TABLE field
    ADD COLUMN IF NOT EXISTS tenant_id character varying(36);

UPDATE field
    SET tenant_id = collection.tenant_id
    FROM collection
    WHERE field.collection_id = collection.id
      AND field.tenant_id IS NULL;

ALTER TABLE field
    ALTER COLUMN tenant_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_field_tenant_id ON field USING btree (tenant_id);

-- Same shape as the 'collections' system collection (V1__baseline): strict
-- equality plus the platform admin_bypass escape hatch for the empty-setting
-- sentinel. System collections' fields (tenant SYSTEM_TENANT_ID) stay visible
-- to every tenant via the router's IN(tenant, SYSTEM) list filter on reads,
-- same as 'collections' already does — RLS itself stays a strict per-tenant
-- policy, consistent with how 'collection' rows are isolated.
ALTER TABLE field ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY field FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON field
    USING ((tenant_id)::text = current_setting('app.current_tenant_id'::text, true));
CREATE POLICY admin_bypass ON field
    USING (current_setting('app.current_tenant_id'::text, true) = ''::text);
