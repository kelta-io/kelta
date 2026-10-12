package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.OwnerScope;

import java.util.List;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * UI &amp; layout system collections: page layouts and their child sections/fields/rules/related
 * lists, list views, UI preferences, pages, menus and translations.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class UiSystemCollections {

    private UiSystemCollections() {
        // Utility class
    }

    public static CollectionDefinition pageLayouts() {
        return systemBuilder("page-layouts", "Page Layouts", "page_layout")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Name of the layout, shown in the layout picker."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.string("layoutType", 20).withColumnName("layout_type")
                .withDefault("DETAIL")
                .withEnumValues(List.of("DETAIL", "EDIT", "MINI", "LIST"))
                .withDescription("Which record surface the layout drives: DETAIL, EDIT, MINI or LIST."))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default")
                .withDefault(false)
                .withDescription("Whether this is the collection's default layout for its layoutType."))
            .addField(FieldDefinition.json("defaultFilter").withColumnName("default_filter")
                .withDescription("JSON default filter for related lists rendered under this layout."))
            .addField(FieldDefinition.string("defaultSortField", 100).withColumnName("default_sort_field")
                .withDescription("Default sort field for related lists rendered under this layout."))
            .addField(FieldDefinition.string("defaultSortDirection", 4).withColumnName("default_sort_direction")
                .withDefault("ASC")
                .withEnumValues(List.of("ASC", "DESC"))
                .withDescription("Default sort direction for related lists under this layout."))
            .addField(FieldDefinition.integer("defaultRowLimit").withColumnName("default_row_limit")
                .withDefault(50)
                .withDescription("Default row cap for related lists rendered under this layout."))
            .addField(FieldDefinition.json("headerConfig").withColumnName("header_config")
                .withDescription("JSON record header config: titleFields, avatarFrom, metaFields."))
            .addField(FieldDefinition.json("railBlocks").withColumnName("rail_blocks")
                .withDescription("JSON array of side-rail widgets, each with a kind discriminator."))
            .build();
    }

    public static CollectionDefinition layoutAssignments() {
        return systemBuilder("layout-assignments", "Layout Assignments", "layout_assignment")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("profileId", 36).withColumnName("profile_id")
                .withDescription("Profile this row applies to."))
            .addField(FieldDefinition.string("recordTypeId", 36).withColumnName("record_type_id")
                .withDescription("Record type this row applies to."))
            .addField(FieldDefinition.masterDetail("layoutId", "page-layouts", "Layout")
                .withColumnName("layout_id")
                .withDescription("Page layout this row belongs to."))
            .addField(FieldDefinition.json("condition")
                .withDescription("JSON condition that must hold for this assignment to apply."))
            .addField(FieldDefinition.integer("evaluationOrder").withColumnName("evaluation_order")
                .withDefault(100).withNullable(false)
                .withDescription("Ordinal evaluation order; the first matching assignment wins."))
            .build();
    }

    public static CollectionDefinition listViews() {
        return systemBuilder("list-views", "List Views", "list_view")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("visibility", 20).withDefault("PRIVATE")
                .withEnumValues(List.of("PRIVATE", "PUBLIC", "GROUP"))
                .withDescription("Who may use the view: PRIVATE, PUBLIC or GROUP."))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default")
                .withDescription("Whether this is the default row for its scope (convention, not enforced as unique)."))
            .addField(FieldDefinition.requiredJson("columns")
                .withDescription("JSON array of field names rendered as columns."))
            .addField(FieldDefinition.string("filterLogic").withColumnName("filter_logic")
                .withDescription("Boolean expression combining the numbered filters, e.g. '1 AND (2 OR 3)'."))
            // JSON-typed defaults must be JSON containers, not strings — a String
            // default is injected verbatim on create and stored as a JSON string
            // ("[]"), not an array, breaking every consumer of the field
            // (guarded by SystemCollectionJsonDefaultsTest).
            .addField(FieldDefinition.json("filters").withDefault(List.of())
                .withDescription("JSON filter conditions applied to the query."))
            .addField(FieldDefinition.string("sortField").withColumnName("sort_field")
                .withDescription("Field name results are sorted by."))
            .addField(FieldDefinition.string("sortDirection", 4).withColumnName("sort_direction")
                .withDefault("ASC")
                .withEnumValues(List.of("ASC", "DESC"))
                .withDescription("Sort direction: ASC or DESC."))
            .addField(FieldDefinition.json("sort")
                .withDescription("JSON multi-field sort specification."))
            .addField(FieldDefinition.integer("rowLimit").withColumnName("row_limit")
                .withDefault(50)
                .withDescription("Maximum number of rows rendered."))
            .addField(FieldDefinition.json("chartConfig").withColumnName("chart_config")
                .withDescription("JSON chart rendering options."))
            // Renderer for the shared view (V196). Mirrors the per-user SavedView
            // fields so an admin can publish a board, not just a column set. The
            // enum values are also a CHECK constraint on the column.
            .addField(FieldDefinition.enumField("viewType",
                    List.of("TABLE", "KANBAN", "CALENDAR", "GALLERY"))
                .withColumnName("view_type").withDefault("TABLE").withNullable(false)
                .withDescription("Renderer published with the view: TABLE, KANBAN, CALENDAR or GALLERY."))
            .addField(FieldDefinition.json("typeConfig").withColumnName("type_config")
                .withDescription("JSON per-renderer settings, e.g. kanban lane and card fields."))
            .build();
    }

    /**
     * Per-user UI preferences (saved list views, favorites, recents). One row per
     * (userId, prefType, prefKey). Owned by {@code userId} for every caller: reads and writes
     * are both limited to the caller's own rows, on the generic route included.
     */
    public static CollectionDefinition userUiPreferences() {
        return systemBuilder("user-ui-preferences", "User UI Preferences", "user_ui_preference")
            .displayFieldName("prefKey")
            .ownerField("userId")
            .ownerScope(OwnerScope.ALL)
            .ownerScopeReads(true)
            .addField(FieldDefinition.requiredString("userId", 36)
                .withColumnName("user_id")
                .withDescription("User this row belongs to."))
            .addField(FieldDefinition.requiredString("prefType", 30)
                .withColumnName("pref_type")
                .withDescription("Which kind of preference this row stores."))
            .addField(FieldDefinition.requiredString("prefKey", 200)
                .withColumnName("pref_key").withDefault("-")
                .withDescription("Key identifying the preference within its type."))
            .addField(FieldDefinition.requiredJson("value")
                .withDescription("JSON preference payload."))
            .build();
    }

    /**
     * Tenant-authored UI translations (app-intelligence slice 4): a per-locale
     * key/value overlay over the static FE bundles. Unique per
     * (tenant, locale, key) — V165.
     */
    public static CollectionDefinition uiTranslations() {
        return systemBuilder("ui-translations", "UI Translations", "ui_translation")
            .displayFieldName("key")
            .addField(FieldDefinition.requiredString("locale", 10)
                .withDescription("Locale this translation applies to."))
            .addField(FieldDefinition.requiredString("key", 200)
                .withColumnName("translation_key")
                .withDescription("Translation key being overridden."))
            .addField(FieldDefinition.requiredString("value", 2000)
                .withColumnName("translation_value")
                .withDescription("Translated string for the key in this locale."))
            .build();
    }

    public static CollectionDefinition uiPages() {
        return systemBuilder("ui-pages", "UI Pages", "ui_page")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.requiredString("path", 200)
                .withDescription("Route the page is served at."))
            .addField(FieldDefinition.string("slug", 200)
                .withDescription("URL-safe identifier used in paths."))
            .addField(FieldDefinition.string("title", 200)
                .withDescription("Browser and header title of the page."))
            .addField(FieldDefinition.json("config")
                .withDescription("JSON page definition: layout, components and bindings."))
            .addField(FieldDefinition.bool("active").withDefault(true).withNullable(false)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.bool("published").withDefault(false).withNullable(false)
                .withDescription("Whether the page is live for end users; drafts stay hidden."))
            .build();
    }

    public static CollectionDefinition uiMenus() {
        return systemBuilder("ui-menus", "UI Menus", "ui_menu")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.integer("displayOrder").withColumnName("display_order")
                .withDefault(0)
                .withDescription("Ordinal position among sibling rows (ascending)."))
            // Apps (nav v2): a menu is the "app" unit — switcher icon, default app,
            // and an active flag that hides the app from the end-user shell (V164).
            .addField(FieldDefinition.string("icon", 100)
                .withDescription("Icon name rendered beside the label."))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default")
                .withDefault(false).withNullable(false)
                .withDescription("Whether this is the default row for its scope (convention, not enforced as unique)."))
            .addField(FieldDefinition.bool("active").withDefault(true).withNullable(false)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    public static CollectionDefinition layoutSections() {
        return systemBuilder("layout-sections", "Layout Sections", "layout_section")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("layoutId", "page-layouts", "Layout")
                .withColumnName("layout_id")
                .withDescription("Page layout this row belongs to."))
            .addField(FieldDefinition.string("heading", 200)
                .withDescription("Section heading rendered above the fields."))
            .addField(FieldDefinition.integer("columns").withDefault(2)
                .withDescription("Number of columns the section lays fields out in (default 2)."))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order")
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .addField(FieldDefinition.bool("collapsed").withDefault(false)
                .withDescription("Whether the section starts collapsed."))
            .addField(FieldDefinition.string("style", 20).withDefault("DEFAULT")
                .withDescription("Visual style applied to the section."))
            .addField(FieldDefinition.string("sectionType", 30)
                .withColumnName("section_type").withDefault("STANDARD")
                .withDescription("Section kind: STANDARD, FIELDS or HIGHLIGHTS_PANEL."))
            .addField(FieldDefinition.string("tabGroup", 100)
                .withColumnName("tab_group")
                .withDescription("Tab group this section belongs to, for tabbed layouts."))
            .addField(FieldDefinition.string("tabLabel", 200)
                .withColumnName("tab_label")
                .withDescription("Label of the tab this section is rendered under."))
            .addField(FieldDefinition.json("visibilityRule")
                .withColumnName("visibility_rule")
                .withDescription("JSON conditional-visibility expression; hides the element when it evaluates false."))
            .build();
    }

    public static CollectionDefinition layoutFields() {
        return systemBuilder("layout-fields", "Layout Fields", "layout_field")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("sectionId", "layout-sections", "Section")
                .withColumnName("section_id")
                .withDescription("Layout section this field placement belongs to."))
            .addField(FieldDefinition.masterDetail("fieldId", "fields", "Field")
                .withColumnName("field_id")
                .withDescription("Field this row refers to."))
            .addField(FieldDefinition.integer("columnNumber")
                .withColumnName("column_number").withDefault(0)
                .withDescription("0-based column within the section; values at or past the section's column count are clamped."))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order")
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .addField(FieldDefinition.bool("isRequiredOnLayout")
                .withColumnName("is_required_on_layout").withDefault(false)
                .withDescription("Whether the field is required on this layout even if optional in the schema."))
            .addField(FieldDefinition.bool("isReadOnlyOnLayout")
                .withColumnName("is_read_only_on_layout").withDefault(false)
                .withDescription("Whether the field is read-only on this layout."))
            .addField(FieldDefinition.string("labelOverride", 200)
                .withColumnName("label_override")
                .withDescription("Label shown instead of the field's own label on this layout."))
            .addField(FieldDefinition.string("helpTextOverride", 500)
                .withColumnName("help_text_override")
                .withDescription("Help text shown instead of the field's own help text."))
            .addField(FieldDefinition.json("visibilityRule")
                .withColumnName("visibility_rule")
                .withDescription("JSON conditional-visibility expression; hides the element when it evaluates false."))
            .addField(FieldDefinition.integer("columnSpan")
                .withColumnName("column_span").withDefault(1)
                .withDescription("How many of the section's columns the field spans."))
            .build();
    }

    public static CollectionDefinition layoutRules() {
        return systemBuilder("layout-rules", "Layout Rules", "layout_rule")
            .displayFieldName("name")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("layoutId", "page-layouts", "Layout")
                .withColumnName("layout_id")
                .withDescription("Page layout this row belongs to."))
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("kind", 20)
                .withEnumValues(List.of("COMPUTE", "VALIDATE", "DEFAULT", "TRANSFORM", "SCRIPT"))
                .withDescription("Rule kind, which decides how the body is interpreted."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.requiredJson("whenEvents")
                .withColumnName("when_events")
                .withDescription("JSON list of form events the rule reacts to."))
            .addField(FieldDefinition.string("targetField", 100)
                .withColumnName("target_field")
                .withDescription("Field the rule acts on."))
            .addField(FieldDefinition.json("dependsOn")
                .withColumnName("depends_on")
                .withDescription("JSON list of fields whose changes re-evaluate the rule."))
            .addField(FieldDefinition.requiredJson("body")
                .withDescription("JSON rule body describing what the rule does when it fires."))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order")
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .build();
    }

    public static CollectionDefinition layoutRelatedLists() {
        return systemBuilder("layout-related-lists", "Layout Related Lists",
                "layout_related_list")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("layoutId", "page-layouts", "Layout")
                .withColumnName("layout_id")
                .withDescription("Page layout this row belongs to."))
            .addField(FieldDefinition.masterDetail("relatedCollectionId", "collections",
                "Related Collection").withColumnName("related_collection_id")
                .withDescription("Related collection this row points at."))
            .addField(FieldDefinition.masterDetail("relationshipFieldId", "fields",
                "Relationship Field").withColumnName("relationship_field_id")
                .withDescription("Field on the related collection that points back at this record."))
            .addField(FieldDefinition.requiredJson("displayColumns")
                .withColumnName("display_columns")
                .withDescription("JSON array of field names shown as columns in the related list."))
            .addField(FieldDefinition.string("sortField", 100)
                .withColumnName("sort_field")
                .withDescription("Field name results are sorted by."))
            .addField(FieldDefinition.string("sortDirection", 4)
                .withColumnName("sort_direction").withDefault("DESC")
                .withEnumValues(List.of("ASC", "DESC"))
                .withDescription("Sort direction: ASC or DESC."))
            .addField(FieldDefinition.integer("rowLimit")
                .withColumnName("row_limit").withDefault(10)
                .withDescription("Maximum number of rows rendered."))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order")
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .build();
    }

    public static CollectionDefinition uiMenuItems() {
        return systemBuilder("ui-menu-items", "UI Menu Items", "ui_menu_item")
            .displayFieldName("label")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("menuId", "ui-menus", "Menu")
                .withColumnName("menu_id")
                .withDescription("Menu this item belongs to."))
            // Submenus (V166): a group header is an item with children and no path;
            // deleting a parent floats its children to the top level (FK SET NULL).
            .addField(FieldDefinition.lookup("parentId", "ui-menu-items", "Parent Item")
                .withColumnName("parent_id")
                .withDescription("Parent menu item, for submenus."))
            .addField(FieldDefinition.requiredString("label", 100)
                .withDescription("Display label shown in the UI."))
            // Optional since V166: group headers navigate nowhere themselves.
            .addField(FieldDefinition.string("path", 200)
                .withDescription("Route the menu item navigates to."))
            .addField(FieldDefinition.string("icon", 100)
                .withDescription("Icon name rendered beside the label."))
            .addField(FieldDefinition.integer("displayOrder")
                .withColumnName("display_order").withDefault(0)
                .withNullable(false)
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withNullable(false)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }
}
