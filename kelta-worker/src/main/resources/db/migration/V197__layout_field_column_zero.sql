-- ---------------------------------------------------------------------------
-- The end-user detail renderer (LayoutFieldSections.tsx) treats columnNumber
-- as 0-based: column 0 is the first column. The layout_field default of 1
-- disagreed, so any placement created without an explicit columnNumber
-- landed in the second column, leaving column 0 empty.
--
-- Default only — no rewrite of existing rows. Existing explicit values are
-- intentional placements, not accidental defaults, so they are left as-is.
-- ---------------------------------------------------------------------------

ALTER TABLE layout_field
    ALTER COLUMN column_number SET DEFAULT 0;
