package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Declared defaults on JSON-typed system fields must be JSON containers (maps
 * or lists), never strings. {@code DefaultQueryEngine} injects the declared
 * default verbatim when the attribute is missing on create. JSON-typed fields
 * accept scalar values too (needed for {@code fields.defaultValue}), so a
 * String default like {@code "[]"} would validate — but it would be stored as
 * the JSON <em>string</em> {@code "[]"}, not an empty array, silently breaking
 * every consumer that expects a container (this is exactly what broke creating
 * a list view without explicit {@code filters}, back when it failed loudly
 * with "Invalid type, expected JSON").
 */
class SystemCollectionJsonDefaultsTest {

    @Test
    void jsonTypedDefaultsAreJsonValues() {
        for (CollectionDefinition definition : SystemCollectionDefinitions.all()) {
            for (FieldDefinition field : definition.fields()) {
                if (field.defaultValue() == null) {
                    continue;
                }
                if (field.type() == FieldType.JSON || field.type() == FieldType.ARRAY) {
                    assertThat(field.defaultValue())
                            .as("%s.%s declares a %s default — JSON-typed defaults must be a Map or List, not %s",
                                    definition.name(), field.name(), field.type(),
                                    field.defaultValue().getClass().getSimpleName())
                            .matches(v -> v instanceof Map || v instanceof List);
                }
            }
        }
    }

    @Test
    void listViewFiltersDefaultsToEmptyJsonArray() {
        CollectionDefinition listViews = UiSystemCollections.listViews();
        FieldDefinition filters = listViews.fields().stream()
                .filter(f -> "filters".equals(f.name()))
                .findFirst()
                .orElseThrow();
        assertThat(filters.defaultValue()).isEqualTo(List.of());
    }
}
