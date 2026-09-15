-- ---------------------------------------------------------------------------
-- Shared views carry their renderer (K-8 item 11).
--
-- The kanban/calendar/gallery renderers already work over any list, but the
-- only place the choice could be stored was the per-user `user_ui_preference`
-- row. An admin could therefore build a board and publish nothing: every user
-- had to flip the toolbar switch and re-pick the lane field for themselves.
-- These two columns give the shared `list_view` row the same two facts the
-- personal SavedView already carries, so "the board" becomes publishable.
--
-- view_type is NOT NULL DEFAULT 'TABLE' so every pre-existing row keeps its
-- current behaviour without a backfill — a shared view that predates this
-- migration renders exactly as it does today.
--
-- type_config is the renderer's own settings, shaped per view_type:
--   KANBAN   {"kanban":   {"laneField": "...", "cardFields": ["...", ...]}}
--   CALENDAR {"calendar": {"dateField": "...", "endDateField": "..."}}
--   GALLERY  {"gallery":  {"imageField": "...", "titleField": "...",
--                          "cardFields": ["...", ...]}}
-- JSONB rather than columns: each renderer needs a different set of field
-- references, and a column per renderer setting would make adding the next
-- renderer a migration. The FE validates the shape on read and falls back to
-- the table renderer when it does not hold, so a malformed config degrades to
-- today's behaviour instead of breaking the list.
--
-- No RLS or permission changes: list_view already carries tenant_isolation +
-- admin_bypass from the baseline, and adding columns alters neither.
-- ---------------------------------------------------------------------------

ALTER TABLE list_view
    ADD COLUMN IF NOT EXISTS view_type character varying(20) DEFAULT 'TABLE' NOT NULL;

ALTER TABLE list_view
    ADD COLUMN IF NOT EXISTS type_config jsonb;

-- The CHECK is the one guarantee the FE fallback cannot give: it keeps an
-- unknown renderer out of the table in the first place, so "unrecognized
-- view_type" only ever arises from rows written before this migration.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'list_view_view_type_check') THEN
        ALTER TABLE list_view
            ADD CONSTRAINT list_view_view_type_check
            CHECK (view_type IN ('TABLE', 'KANBAN', 'CALENDAR', 'GALLERY'));
    END IF;
END $$;

COMMENT ON COLUMN list_view.view_type IS
    'Renderer for this shared view: TABLE (default) | KANBAN | CALENDAR | GALLERY. '
    'Mirrors SavedView.viewType on the per-user preference row.';

COMMENT ON COLUMN list_view.type_config IS
    'Per-renderer settings, keyed by lowercased view type — e.g. '
    '{"kanban": {"laneField": "status", "cardFields": ["title"]}}. Mirrors '
    'SavedView.typeConfig.';
