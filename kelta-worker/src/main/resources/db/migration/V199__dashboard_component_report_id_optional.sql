-- KLT-213 (K-9 item 11): dashboard_component.report_id was NOT NULL although
-- resolveTargetCollection (DashboardDataService) already accepts config.collectionName
-- as an alternative way to target a widget's data. Every collection-targeting widget
-- was forced to carry a dummy report row just to satisfy this column.
--
-- reportId moves from MASTER_DETAIL to LOOKUP (SystemCollectionDefinitions), matching
-- the DB-level change here: nullable, and ON DELETE SET NULL instead of CASCADE — a
-- widget targeting a collection directly should not be destroyed when an unrelated
-- report is deleted, nor should deleting the linked report cascade-delete the widget.

ALTER TABLE dashboard_component
    ALTER COLUMN report_id DROP NOT NULL;

ALTER TABLE dashboard_component
    DROP CONSTRAINT dashboard_component_report_id_fkey,
    ADD CONSTRAINT dashboard_component_report_id_fkey
        FOREIGN KEY (report_id) REFERENCES report(id) ON DELETE SET NULL;
