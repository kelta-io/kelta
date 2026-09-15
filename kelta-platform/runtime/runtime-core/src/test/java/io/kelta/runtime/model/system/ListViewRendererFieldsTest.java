package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared-view renderer fields (V196). A shared {@code list-views} row publishes
 * how it opens, so an admin can hand out a kanban board instead of a column set every
 * user has to re-configure.
 *
 * <p>Both column names are pinned here because the physical columns come from the
 * Flyway migration, not from this definition: a rename on either side would leave the
 * collection registered against a column that does not exist, and the failure would
 * surface as a query error on someone's list rather than at startup.
 */
class ListViewRendererFieldsTest {

    private static FieldDefinition field(String name) {
        return SystemCollectionDefinitions.listViews().fields().stream()
                .filter(f -> name.equals(f.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("list-views has no field \"" + name + "\""));
    }

    @Test
    void viewTypeIsAnEnumFieldDefaultingToTable() {
        FieldDefinition viewType = field("viewType");

        assertThat(viewType.type()).isEqualTo(FieldType.STRING);
        assertThat(viewType.effectiveColumnName()).isEqualTo("view_type");
        assertThat(viewType.enumValues())
                .containsExactly("TABLE", "KANBAN", "CALENDAR", "GALLERY");
        // NOT NULL with a default: every row written before V196 keeps rendering as a
        // table, and a create that omits the attribute does not fail validation.
        assertThat(viewType.defaultValue()).isEqualTo("TABLE");
        assertThat(viewType.nullable()).isFalse();
    }

    @Test
    void typeConfigIsNullableJsonWithNoDeclaredDefault() {
        FieldDefinition typeConfig = field("typeConfig");

        assertThat(typeConfig.type()).isEqualTo(FieldType.JSON);
        assertThat(typeConfig.effectiveColumnName()).isEqualTo("type_config");
        // A declared default here would be injected verbatim on create — see
        // SystemCollectionJsonDefaultsTest. "No renderer settings" is null, not {}.
        assertThat(typeConfig.defaultValue()).isNull();
        assertThat(typeConfig.nullable()).isTrue();
    }

    @Test
    void rendererFieldsDoNotDisturbTheExistingColumnSet() {
        CollectionDefinition listViews = SystemCollectionDefinitions.listViews();
        List<String> names = listViews.fields().stream().map(FieldDefinition::name).toList();

        assertThat(names).contains("collectionId", "name", "visibility", "columns", "filters",
                "sortField", "sortDirection", "rowLimit", "chartConfig", "viewType", "typeConfig");
    }
}
