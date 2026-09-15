-- KLT-206: GET /api/fields leaked every tenant's field metadata (2,580 rows in
-- production) because the `fields` system collection was `.tenantScoped(false)`
-- and `field` had no tenant_id column, so injectTenantFilter added no predicate.
-- Denormalise tenant_id onto `field` (mirrors `collection.tenant_id`) so the
-- generic tenant-scoped-system-collection path (and Postgres RLS) can enforce
-- isolation the same way it already does for `collection`.

ALTER TABLE field ADD COLUMN tenant_id character varying(36);

-- Backfill from the owning collection — every existing field's tenant is its
-- parent collection's tenant, including system collections (SYSTEM_TENANT_ID).
UPDATE field
SET tenant_id = c.tenant_id
FROM collection c
WHERE c.id = field.collection_id
  AND field.tenant_id IS NULL;

ALTER TABLE field ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE ONLY field
    ADD CONSTRAINT fk_field_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);

CREATE INDEX idx_field_tenant_id ON field USING btree (tenant_id);

CREATE INDEX idx_field_tenant_collection ON field USING btree (tenant_id, collection_id);

-- Same RLS shape as `collection`: admin/bypass paths (Flyway, internal, cross-tenant
-- work) leave app.current_tenant_id unset (''), everything else is scoped to the
-- request-bound tenant.
ALTER TABLE field ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY field FORCE ROW LEVEL SECURITY;

CREATE POLICY admin_bypass ON field
    USING ((current_setting('app.current_tenant_id'::text, true) = ''::text));

CREATE POLICY tenant_isolation ON field
    USING (((tenant_id)::text = current_setting('app.current_tenant_id'::text, true)));
