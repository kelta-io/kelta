package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every system field must carry a one-line description, and the picklist-shaped ones must
 * declare their allowed values.
 *
 * <p>{@code GET /api/collections/{name}/schema} and the generated OpenAPI document are the only
 * way an API client learns what a system field means; a field added without a description leaves
 * that client reading {@link SystemCollectionDefinitions} source, which is the gap this exists to
 * close. So a new {@code addField(...)} without {@code withDescription(...)} fails here rather
 * than shipping an undocumented attribute.
 */
@DisplayName("System collection field documentation")
class SystemCollectionDescriptionsTest {

    @Test
    @DisplayName("Every system field declares a non-blank description")
    void everySystemFieldHasADescription() {
        List<String> undocumented = new ArrayList<>();
        for (CollectionDefinition definition : SystemCollectionDefinitions.all()) {
            for (FieldDefinition field : definition.fields()) {
                if (field.description() == null || field.description().isBlank()) {
                    undocumented.add(definition.name() + "." + field.name());
                }
            }
        }

        assertThat(undocumented)
                .as("system fields missing withDescription(...): %s", undocumented)
                .isEmpty();
    }

    @Test
    @DisplayName("Picklist-shaped system fields declare their allowed values")
    void picklistShapedFieldsDeclareEnumValues() {
        Map<String, List<String>> expected = Map.of(
                "page-layouts.layoutType", List.of("DETAIL", "EDIT", "MINI", "LIST"),
                "list-views.visibility", List.of("PRIVATE", "PUBLIC", "GROUP"),
                "list-views.sortDirection", List.of("ASC", "DESC"),
                "profile-field-permissions.visibility", List.of("VISIBLE", "READ_ONLY", "HIDDEN", "MASKED"),
                "dashboard-components.componentType", List.of("metric", "chart", "table", "recent"),
                "reports.reportType", List.of("TABULAR", "SUMMARY", "MATRIX"),
                "reports.accessLevel", List.of("PRIVATE", "PUBLIC", "HIDDEN"),
                "report-folders.accessLevel", List.of("PRIVATE", "PUBLIC", "HIDDEN"),
                "dashboards.accessLevel", List.of("PRIVATE", "PUBLIC", "HIDDEN"),
                "layout-related-lists.sortDirection", List.of("ASC", "DESC")
        );

        Map<String, CollectionDefinition> byName = SystemCollectionDefinitions.byName();
        expected.forEach((key, values) -> {
            String[] parts = key.split("\\.");
            CollectionDefinition definition = byName.get(parts[0]);
            assertThat(definition).as("collection %s", parts[0]).isNotNull();

            FieldDefinition field = definition.fields().stream()
                    .filter(f -> f.name().equals(parts[1]))
                    .findFirst()
                    .orElse(null);
            assertThat(field).as("field %s", key).isNotNull();
            assertThat(field.enumValues()).as("enum values of %s", key)
                    .containsExactlyElementsOf(values);
        });
    }

    @Test
    @DisplayName("page-layouts exposes its collection reference and layout type to schema readers")
    void pageLayoutsCarriesReferenceTargetAndEnum() {
        CollectionDefinition pageLayouts = SystemCollectionDefinitions.byName().get("page-layouts");

        FieldDefinition collectionId = field(pageLayouts, "collectionId");
        assertThat(collectionId.referenceConfig()).isNotNull();
        assertThat(collectionId.referenceConfig().targetCollection()).isEqualTo("collections");
        assertThat(collectionId.type().isRelationship()).isTrue();

        assertThat(field(pageLayouts, "layoutType").enumValues())
                .containsExactly("DETAIL", "EDIT", "MINI", "LIST");
    }

    @Test
    @DisplayName("layout-fields keeps the 0-based columnNumber default readable from the schema")
    void layoutFieldsCarriesColumnNumberDefault() {
        FieldDefinition columnNumber =
                field(SystemCollectionDefinitions.byName().get("layout-fields"), "columnNumber");

        assertThat(columnNumber.defaultValue()).isEqualTo(0);
        assertThat(columnNumber.description()).contains("0-based");
    }

    private static FieldDefinition field(CollectionDefinition definition, String name) {
        return definition.fields().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no field '" + name + "' on " + definition.name()));
    }
}
