-- Module-platform slice 8 (billing removal), final step. Billing runs only as the
-- kelta-billing runtime module, which keeps its data in its own collections
-- (billing_plans, billing_customers, billing_subscriptions, billing_passes,
-- billing_entitlement_rules — plural, underscore names). The compiled-in copy was removed
-- with V207; nothing reads or writes the V178 tables or the five legacy billing-* system
-- collections that sat on them, and their definitions are removed from
-- SystemCollectionDefinitions in the same change.
--
-- Checked against production before writing: billing_customer, billing_subscription,
-- billing_pass and billing_entitlement_rule were empty, and every billing_plan row already
-- existed (by code) in the billing module's own plans collection. Nothing outside these five
-- collections referenced them (lookups, display fields, email templates, layouts, list views,
-- object permissions), and no foreign key points at the five tables.
--
-- SystemCollectionSeeder only inserts, so the platform-tenant collection/field rows must be
-- removed here or they would outlive their tables. Every other dependent of collection/field
-- is ON DELETE CASCADE; the three NO ACTION references are handled explicitly below.

-- collection.display_field_id -> field (NO ACTION): detach before deleting the fields.
UPDATE collection
   SET display_field_id = NULL
 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
   AND system_collection
   AND name IN ('billing-plans', 'billing-customers', 'billing-subscriptions',
                'billing-passes', 'billing-entitlement-rules');

-- email_template.related_collection_id -> collection (NO ACTION).
UPDATE email_template
   SET related_collection_id = NULL
 WHERE related_collection_id IN (
       SELECT id FROM collection
        WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
          AND system_collection
          AND name IN ('billing-plans', 'billing-customers', 'billing-subscriptions',
                       'billing-passes', 'billing-entitlement-rules'));

-- field.reference_collection_id -> collection (NO ACTION): any lookup pointing at these
-- collections lives on the collections themselves, so deleting their fields first covers it.
DELETE FROM field
 WHERE collection_id IN (
       SELECT id FROM collection
        WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
          AND system_collection
          AND name IN ('billing-plans', 'billing-customers', 'billing-subscriptions',
                       'billing-passes', 'billing-entitlement-rules'));

DELETE FROM collection
 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
   AND system_collection
   AND name IN ('billing-plans', 'billing-customers', 'billing-subscriptions',
                'billing-passes', 'billing-entitlement-rules');

-- The RLS policies V178/V200 attached to these tables are dropped with them.
DROP TABLE IF EXISTS billing_pass;
DROP TABLE IF EXISTS billing_subscription;
DROP TABLE IF EXISTS billing_customer;
DROP TABLE IF EXISTS billing_entitlement_rule;
DROP TABLE IF EXISTS billing_plan;
