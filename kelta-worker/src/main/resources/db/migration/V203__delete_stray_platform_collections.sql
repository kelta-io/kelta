-- KLT-262: DynamicCollectionRouter.injectTenantFilter's sharesSystemRows branch let
-- any row owned by the platform tenant (SYSTEM_TENANT_ID) through GET /api/collections
-- and GET /api/fields for every tenant, not just genuine system collections. The
-- e2e collection-wizard spec ran against the platform tenant and left three custom
-- collections behind, which then leaked into every other tenant's collection list
-- (verified in production for tenant `spotopened`, 2026-09-17).
--
-- The router fix (DynamicCollectionRouter.filterSharedSystemRows) closes the leak going
-- forward; this deletes the three known stray rows and their fields by exact name+tenant
-- match, scoped to the platform tenant so no other tenant's data is touched.

DELETE FROM field
WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
  AND collection_id IN (
      SELECT id FROM collection
      WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
        AND name IN (
            'e2e_wizard_1789372102967',
            'e2e_wizard_1789470365774',
            'e2e_wizard_1789481624858'
        )
  );

DELETE FROM collection
WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
  AND name IN (
      'e2e_wizard_1789372102967',
      'e2e_wizard_1789470365774',
      'e2e_wizard_1789481624858'
  );
