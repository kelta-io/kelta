package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import java.util.List;
import java.util.Map;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Reports &amp; dashboards system collections.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class ReportSystemCollections {

    private ReportSystemCollections() {
        // Utility class
    }

    public static CollectionDefinition reports() {
        return systemBuilder("reports", "Reports", "report")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 1000)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("reportType", 20)
                .withColumnName("report_type")
                .withEnumValues(List.of("TABULAR", "SUMMARY", "MATRIX"))
                .withDescription("Report shape: TABULAR, SUMMARY or MATRIX."))
            .addField(FieldDefinition.masterDetail("primaryCollectionId", "collections",
                "Primary Collection").withColumnName("primary_collection_id")
                .withDescription("Collection the report's rows come from."))
            .addField(FieldDefinition.json("relatedJoins").withColumnName("related_joins")
                .withDescription("JSON joins pulling fields from related collections."))
            .addField(FieldDefinition.requiredJson("columns")
                .withDescription("JSON array of column definitions rendered by the report."))
            .addField(FieldDefinition.json("filters")
                .withDescription("JSON filter conditions narrowing the report's rows."))
            .addField(FieldDefinition.string("filterLogic", 500)
                .withColumnName("filter_logic")
                .withDescription("Boolean expression combining the numbered filters, e.g. '1 AND (2 OR 3)'."))
            .addField(FieldDefinition.json("rowGroupings").withColumnName("row_groupings")
                .withDescription("JSON row grouping levels."))
            .addField(FieldDefinition.json("columnGroupings")
                .withColumnName("column_groupings")
                .withDescription("JSON column grouping levels, used by matrix reports."))
            .addField(FieldDefinition.json("sortOrder").withColumnName("sort_order")
                .withDescription("JSON sort specification applied to the report's rows."))
            .addField(FieldDefinition.string("chartType", 20).withColumnName("chart_type")
                .withDescription("Chart rendered alongside the report's rows."))
            .addField(FieldDefinition.json("chartConfig").withColumnName("chart_config")
                .withDescription("JSON chart rendering options."))
            .addField(FieldDefinition.string("groupBy", 200).withColumnName("group_by")
                .withDescription("Field results are grouped by."))
            .addField(FieldDefinition.string("sortBy", 200).withColumnName("sort_by")
                .withDescription("Field results are sorted by."))
            .addField(FieldDefinition.string("sortDirection", 4)
                .withColumnName("sort_direction").withDefault("ASC")
                .withEnumValues(List.of("ASC", "DESC"))
                .withDescription("Sort direction: ASC or DESC."))
            .addField(FieldDefinition.string("scope", 20).withDefault("MY_RECORDS")
                .withDescription("Which rows the report reads: MY_RECORDS, MY_TEAM_RECORDS or ALL_RECORDS."))
            .addField(FieldDefinition.lookup("folderId", "report-folders", "Folder")
                .withColumnName("folder_id")
                .withDescription("Folder this record is filed under."))
            .addField(FieldDefinition.string("accessLevel", 20)
                .withColumnName("access_level").withDefault("PRIVATE")
                .withEnumValues(List.of("PRIVATE", "PUBLIC", "HIDDEN"))
                .withDescription("Who may open the report: PRIVATE, PUBLIC or HIDDEN."))
            .build();
    }

    public static CollectionDefinition reportFolders() {
        return systemBuilder("report-folders", "Report Folders", "report_folder")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("accessLevel", 20)
                .withColumnName("access_level").withDefault("PRIVATE")
                .withEnumValues(List.of("PRIVATE", "PUBLIC", "HIDDEN"))
                .withDescription("Who may open the folder: PRIVATE, PUBLIC or HIDDEN."))
            .build();
    }

    public static CollectionDefinition dashboards() {
        return systemBuilder("dashboards", "Dashboards", "dashboard")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 1000)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.lookup("folderId", "report-folders", "Folder")
                .withColumnName("folder_id")
                .withDescription("Folder this record is filed under."))
            .addField(FieldDefinition.string("accessLevel", 20)
                .withColumnName("access_level")
                .withEnumValues(List.of("PRIVATE", "PUBLIC", "HIDDEN"))
                .withDescription("Who may open the dashboard: PRIVATE, PUBLIC or HIDDEN."))
            .addField(FieldDefinition.bool("dynamic").withColumnName("is_dynamic")
                .withDescription("Whether widgets execute as runningUserId rather than the viewer."))
            .addField(FieldDefinition.string("runningUserId", 36)
                .withColumnName("running_user_id")
                .withDescription("User a dynamic dashboard's widgets execute as."))
            .addField(FieldDefinition.integer("columnCount").withColumnName("column_count")
                .withDescription("Width of the dashboard grid in columns."))
            .build();
    }

    public static CollectionDefinition dashboardComponents() {
        return systemBuilder("dashboard-components", "Dashboard Components",
                "dashboard_component")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("dashboardId", "dashboards",
                "Dashboard").withColumnName("dashboard_id")
                .withDescription("Dashboard this component belongs to."))
            .addField(FieldDefinition.lookup("reportId", "reports", "Report")
                .withColumnName("report_id")
                .withDescription("Saved report backing the widget; optional when config names a collection."))
            .addField(FieldDefinition.requiredString("componentType", 20)
                .withColumnName("component_type")
                .withEnumValues(List.of("metric", "chart", "table", "recent"))
                .withDescription("Widget renderer: metric, chart, table or recent."))
            .addField(FieldDefinition.string("title", 200)
                .withDescription("Title rendered on the widget frame."))
            .addField(FieldDefinition.requiredInteger("columnPosition")
                .withColumnName("column_position")
                .withDescription("1-based grid column the widget starts at."))
            .addField(FieldDefinition.requiredInteger("rowPosition")
                .withColumnName("row_position")
                .withDescription("1-based grid row the widget starts at."))
            .addField(FieldDefinition.integer("columnSpan")
                .withColumnName("column_span").withDefault(1)
                .withDescription("How many grid columns the widget spans."))
            .addField(FieldDefinition.integer("rowSpan")
                .withColumnName("row_span").withDefault(1)
                .withDescription("How many grid rows the widget spans."))
            .addField(FieldDefinition.json("config").withDefault(Map.of())
                .withDescription("JSON widget settings: target collection, aggregate, filters, chart style."))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order")
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .build();
    }
}
