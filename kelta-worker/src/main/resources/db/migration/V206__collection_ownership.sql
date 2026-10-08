-- Owner-scoped collections (member-data-ownership slice 2). A collection may declare the
-- field holding each record's owning users.id and which callers are limited to the rows
-- they own. PhysicalTableStorageAdapter adds the owner predicate to reads and
-- OwnerScopeGuardHook guards writes. Defaults keep every existing collection unscoped.
ALTER TABLE collection
    ADD COLUMN owner_field varchar(100);

ALTER TABLE collection
    ADD COLUMN owner_scope varchar(10) DEFAULT 'NONE' NOT NULL
        CONSTRAINT collection_owner_scope_check CHECK (owner_scope IN ('NONE', 'PORTAL', 'ALL'));

ALTER TABLE collection
    ADD COLUMN owner_scope_reads boolean DEFAULT true NOT NULL;

COMMENT ON COLUMN collection.owner_field IS
    'Field holding the owning users.id (a LOOKUP to users, or createdBy); NULL = records are not owned';
COMMENT ON COLUMN collection.owner_scope IS
    'Which callers see and change only their own rows: NONE, PORTAL (portal members) or ALL (everyone without VIEW/MODIFY_ALL_DATA)';
COMMENT ON COLUMN collection.owner_scope_reads IS
    'When false, owner scoping limits writes only and reads stay unscoped';
