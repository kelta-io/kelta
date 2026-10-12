package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import java.util.List;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Picklist system collections: global picklists, their values and dependencies.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class PicklistSystemCollections {

    private PicklistSystemCollections() {
        // Utility class
    }

    public static CollectionDefinition globalPicklists() {
        return systemBuilder("global-picklists", "Global Picklists", "global_picklist")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.bool("sorted").withDefault(false)
                .withDescription("Whether values are presented in alphabetical order."))
            .addField(FieldDefinition.bool("restricted").withDefault(true)
                .withDescription("Whether values outside the picklist are rejected."))
            .build();
    }

    public static CollectionDefinition picklistValues() {
        return systemBuilder("picklist-values", "Picklist Values", "picklist_value")
            .displayFieldName("label")
            .tenantScoped(true)
            .addField(FieldDefinition.requiredString("picklistSourceType", 20)
                .withColumnName("picklist_source_type")
                .withEnumValues(List.of("FIELD", "GLOBAL"))
                .withDescription("Whether the value belongs to a global picklist or a single field."))
            .addField(FieldDefinition.requiredString("picklistSourceId", 36)
                .withColumnName("picklist_source_id")
                .withDescription("Id of the global picklist or field the value belongs to."))
            .addField(FieldDefinition.requiredString("value", 255)
                .withDescription("Value written to the record when this entry is chosen."))
            .addField(FieldDefinition.requiredString("label", 255)
                .withDescription("Display label shown in the UI."))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default")
                .withDescription("Whether this is the default row for its scope (convention, not enforced as unique)."))
            .addField(FieldDefinition.bool("isActive").withColumnName("is_active")
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.integer("sortOrder").withColumnName("sort_order")
                .withDefault(0)
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .addField(FieldDefinition.string("color", 20)
                .withDescription("Hex color used when the value is rendered as a badge."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .build();
    }

    public static CollectionDefinition picklistDependencies() {
        return systemBuilder("picklist-dependencies", "Picklist Dependencies",
                "picklist_dependency")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("controllingFieldId", "fields",
                "Controlling Field").withColumnName("controlling_field_id")
                .withDescription("Field whose value filters the dependent picklist."))
            .addField(FieldDefinition.masterDetail("dependentFieldId", "fields",
                "Dependent Field").withColumnName("dependent_field_id")
                .withDescription("Field whose available values are filtered."))
            .addField(FieldDefinition.requiredJson("mapping")
                .withDescription("JSON map from controlling value to the dependent values it allows."))
            .build();
    }
}
