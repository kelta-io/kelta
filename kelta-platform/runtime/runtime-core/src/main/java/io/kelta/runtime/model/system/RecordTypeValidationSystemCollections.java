package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import java.util.List;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Record types &amp; validation system collections: record types (and their picklist overrides),
 * validation rules, record scripts, record shares and quick actions.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class RecordTypeValidationSystemCollections {

    private RecordTypeValidationSystemCollections() {
        // Utility class
    }

    public static CollectionDefinition recordTypes() {
        return systemBuilder("record-types", "Record Types", "record_type")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.bool("isActive").withColumnName("is_active")
                .withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default")
                .withDefault(false)
                .withDescription("Whether this is the default row for its scope (convention, not enforced as unique)."))
            .build();
    }

    public static CollectionDefinition validationRules() {
        return systemBuilder("validation-rules", "Validation Rules", "validation_rule")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.requiredString("errorConditionFormula")
                .withColumnName("error_condition_formula")
                .withDescription("Formula that makes the record invalid when it evaluates true."))
            .addField(FieldDefinition.requiredString("errorMessage", 1000)
                .withColumnName("error_message")
                .withDescription("Failure detail recorded when the run did not succeed."))
            .addField(FieldDefinition.string("errorField", 100).withColumnName("error_field")
                .withDescription("Field the error message is attached to."))
            .addField(FieldDefinition.requiredString("evaluateOn", 20)
                .withColumnName("evaluate_on")
                .withDefault("CREATE_AND_UPDATE")
                .withEnumValues(List.of("CREATE", "UPDATE", "CREATE_AND_UPDATE"))
                .withDescription("Which operations the rule is evaluated on."))
            .addField(FieldDefinition.bool("enforceOnClient")
                .withColumnName("enforce_on_client")
                .withDefault(false)
                .withDescription("Whether the UI evaluates the rule before submitting."))
            .addField(FieldDefinition.requiredString("severity", 10)
                .withDefault("ERROR")
                .withEnumValues(List.of("ERROR", "WARNING"))
                .withDescription("How hard the rule fails: blocking or warning."))
            .build();
    }

    /**
     * Record-event scripts (unified record experience, slice 7): tenant-defined JavaScript bound to
     * a collection's record lifecycle events, executed server-side by the sandboxed GraalVM
     * {@code ScriptExecutor} from a {@code BeforeSaveHook}. A {@code BEFORE_*} script may block a
     * write (validation) or return field updates to merge; {@code AFTER_*} scripts run side effects.
     */
    public static CollectionDefinition recordScripts() {
        return systemBuilder("record-scripts", "Record Scripts", "record_script")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("triggerType", 20)
                .withColumnName("trigger_type")
                .withEnumValues(List.of(
                    "BEFORE_CREATE", "BEFORE_UPDATE", "AFTER_CREATE", "AFTER_UPDATE", "AFTER_DELETE"))
                .withDescription("What causes this to run."))
            .addField(FieldDefinition.text("scriptSource").withColumnName("script_source")
                .withDescription("Script body executed by the trigger."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.integer("orderSequence")
                .withColumnName("order_sequence").withDefault(0)
                .withDescription("Execution order among scripts on the same trigger."))
            .addField(FieldDefinition.integer("timeoutSeconds")
                .withColumnName("timeout_seconds").withDefault(5)
                .withDescription("Seconds the script may run before it is killed."))
            .build();
    }

    /**
     * Manual per-record shares (Salesforce-style). A row grants a user or group an access
     * level on a specific record. CRUD is served by the generic dynamic path
     * ({@code /api/record-shares}); the record-detail Sharing panel manages these rows.
     *
     * <p>NOTE: this ships the share <em>store</em> + CRUD. Runtime <em>enforcement</em>
     * (Cerbos/record-authz consulting shares to widen access) is a documented follow-up.
     */
    public static CollectionDefinition recordShares() {
        return systemBuilder("record-shares", "Record Shares", "record_share")
            .displayFieldName("recordId")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("recordId", 36).withColumnName("record_id")
                .withDescription("Id of the record this row refers to."))
            .addField(FieldDefinition.requiredString("sharedWithId", 36).withColumnName("shared_with_id")
                .withDescription("Id of the user or group the record is shared with."))
            .addField(FieldDefinition.requiredString("sharedWithType", 20)
                .withColumnName("shared_with_type")
                .withEnumValues(List.of("USER", "GROUP"))
                .withDescription("Whether the record is shared with a USER or a GROUP."))
            .addField(FieldDefinition.requiredString("accessLevel", 20)
                .withColumnName("access_level")
                .withDefault("READ")
                .withEnumValues(List.of("READ", "EDIT"))
                .withDescription("Access granted by the share: READ or EDIT."))
            .addField(FieldDefinition.string("reason", 500)
                .withDescription("Free-text reason recorded with the row."))
            .build();
    }

    /**
     * Per-collection record/list quick actions surfaced by the `QuickActionsMenu`. Each row is a
     * button definition (label + icon + action type + type-specific `config` JSON). CRUD via the
     * generic dynamic path (`/api/quick-actions`); `useQuickActions` fetches the active actions for
     * a collection. `actionType` (not `type`) avoids clashing with the JSON:API resource `type`.
     */
    public static CollectionDefinition quickActions() {
        return systemBuilder("quick-actions", "Quick Actions", "quick_action")
            .displayFieldName("label")
            .addField(FieldDefinition.requiredString("collectionName", 200).withColumnName("collection_name")
                .withDescription("Name of the collection this row applies to."))
            .addField(FieldDefinition.requiredString("label", 200)
                .withDescription("Display label shown in the UI."))
            .addField(FieldDefinition.string("icon", 50)
                .withDescription("Icon name rendered beside the label."))
            .addField(FieldDefinition.requiredString("actionType", 30).withColumnName("action_type")
                .withEnumValues(List.of("create_related", "update_field", "run_script",
                    "log_activity", "send_email", "custom"))
                .withDescription("What the action does when invoked."))
            .addField(FieldDefinition.requiredString("context", 10)
                .withDefault("record")
                .withEnumValues(List.of("record", "list", "both"))
                .withDescription("Where the action is offered (list, record, related list)."))
            .addField(FieldDefinition.integer("sortOrder").withColumnName("sort_order").withDefault(0)
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .addField(FieldDefinition.bool("requiresConfirmation")
                .withColumnName("requires_confirmation").withDefault(false)
                .withDescription("Whether the user must confirm before the action runs."))
            .addField(FieldDefinition.string("confirmationMessage", 500).withColumnName("confirmation_message")
                .withDescription("Prompt shown in the confirmation dialog."))
            .addField(FieldDefinition.json("config")
                .withDescription("JSON action settings: target flow, prefilled values, navigation."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    public static CollectionDefinition recordTypePicklists() {
        return systemBuilder("record-type-picklists", "Record Type Picklists",
                "record_type_picklist")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("recordTypeId", "record-types",
                "Record Type").withColumnName("record_type_id")
                .withDescription("Record type this row applies to."))
            .addField(FieldDefinition.masterDetail("fieldId", "fields", "Field")
                .withColumnName("field_id")
                .withDescription("Field this row refers to."))
            .addField(FieldDefinition.requiredJson("availableValues")
                .withColumnName("available_values")
                .withDescription("JSON subset of picklist values available for this record type."))
            .addField(FieldDefinition.string("defaultValue", 255)
                .withColumnName("default_value")
                .withDescription("Picklist value pre-selected for this record type."))
            .build();
    }
}
