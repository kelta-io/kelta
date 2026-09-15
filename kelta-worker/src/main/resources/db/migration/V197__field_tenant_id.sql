-- KLT-206: GET /api/fields leaked every tenant's field metadata because the
-- `field` table carried no tenant_id — tenancy was only transitive through
-- collection_id, so injectTenantFilter (a DynamicCollectionRouter concern)
-- had no column to filter on. Add a denormalised tenant_id, backfilled from
-- the owning collection, and lock it down with the same RLS shape as
-- `collection` plus a third policy so system-collection fields (owned by the
-- platform sentinel tenant) stay visible to every tenant, matching how
-- `collections` itself is meant to behave.

ALTER TABLE field ADD COLUMN tenant_id character varying(36);

UPDATE field f
SET tenant_id = c.tenant_id
FROM collection c
WHERE f.collection_id = c.id;

ALTER TABLE field ALTER COLUMN tenant_id SET NOT NULL;

CREATE INDEX idx_field_tenant_id ON field USING btree (tenant_id);

ALTER TABLE field ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY field FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON field USING (((tenant_id)::text = current_setting('app.current_tenant_id'::text, true)));

CREATE POLICY admin_bypass ON field USING ((current_setting('app.current_tenant_id'::text, true) = ''::text));

-- System-collection fields (tenant_id = the platform sentinel tenant) are
-- shared metadata that every tenant must be able to read, the same way
-- `collections` rows for system collections are meant to stay visible
-- alongside a tenant's own. SELECT-only: a tenant may not write these rows.
CREATE POLICY system_fields_visible ON field FOR SELECT USING (((tenant_id)::text = '00000000-0000-0000-0000-000000000001'::text));
