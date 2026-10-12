package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import java.util.List;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Automation system collections: scripts and their triggers, flows, approval processes (steps and
 * instances) and scheduled jobs.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class AutomationSystemCollections {

    private AutomationSystemCollections() {
        // Utility class
    }

    public static CollectionDefinition scripts() {
        return systemBuilder("scripts", "Scripts", "script")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("scriptType", 30).withColumnName("script_type")
                .withEnumValues(List.of("BEFORE_TRIGGER", "AFTER_TRIGGER", "SCHEDULED",
                    "API_ENDPOINT", "VALIDATION", "EVENT_HANDLER", "EMAIL_HANDLER"))
                .withDescription("Kind of script and how the platform invokes it."))
            .addField(FieldDefinition.requiredString("language", 20).withDefault("javascript")
                .withDescription("Language the source is written in."))
            .addField(FieldDefinition.requiredText("sourceCode").withColumnName("source_code")
                .withDescription("Script source executed by the runtime."))
            .addField(FieldDefinition.bool("active")
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.integer("version")
                .withDescription("Version of the script source."))
            .addField(FieldDefinition.string("requiredPermission", 100)
                .withColumnName("required_permission")
                .withDescription("Permission a caller must hold to execute the script."))
            .build();
    }

    public static CollectionDefinition flows() {
        return systemBuilder("flows", "Flows", "flow")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 1000)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("flowType", 30).withColumnName("flow_type")
                .withEnumValues(List.of("RECORD_TRIGGERED", "NATS_TRIGGERED", "SCHEDULED",
                    "AUTOLAUNCHED", "SCREEN"))
                .withDescription("How the flow is started (record trigger, schedule, NATS topic, manual)."))
            .addField(FieldDefinition.bool("active").withDefault(false)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.integer("version").withDefault(1)
                .withDescription("Version of the flow definition."))
            .addField(FieldDefinition.json("triggerConfig").withColumnName("trigger_config")
                .withDescription("JSON trigger settings, e.g. the collection or NATS topic that starts the flow."))
            .addField(FieldDefinition.requiredJson("definition")
                .withDescription("JSON state-machine definition of the flow's nodes and transitions."))
            // Audit identity stamped on records this flow writes when the
            // execution has no initiating user (cron/NATS/webhook starts);
            // falls back to the flow owner (created_by) when unset.
            .addField(FieldDefinition.string("runAsUserId", 36).withColumnName("run_as_user_id")
                .withDescription("User the flow executes as, when it runs with fixed privileges."))
            .build();
    }

    public static CollectionDefinition approvalProcesses() {
        return systemBuilder("approval-processes", "Approval Processes", "approval_process")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 1000)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.bool("active")
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.string("entryCriteria").withColumnName("entry_criteria")
                .withDescription("JSON criteria a record must meet to enter this process."))
            .addField(FieldDefinition.string("recordEditability", 20)
                .withColumnName("record_editability").withDefault("LOCKED")
                .withDescription("Who may edit the record while it is locked for approval."))
            .addField(FieldDefinition.string("initialSubmitterField", 100)
                .withColumnName("initial_submitter_field")
                .withDescription("Field recording who submitted the record for approval."))
            .addField(FieldDefinition.json("onSubmitFieldUpdates")
                .withColumnName("on_submit_field_updates")
                .withDescription("JSON field updates applied when the record is submitted."))
            .addField(FieldDefinition.json("onApprovalFieldUpdates")
                .withColumnName("on_approval_field_updates")
                .withDescription("JSON field updates applied on final approval."))
            .addField(FieldDefinition.json("onRejectionFieldUpdates")
                .withColumnName("on_rejection_field_updates")
                .withDescription("JSON field updates applied on rejection."))
            .addField(FieldDefinition.json("onRecallFieldUpdates")
                .withColumnName("on_recall_field_updates")
                .withDescription("JSON field updates applied when the submission is recalled."))
            .addField(FieldDefinition.bool("allowRecall").withColumnName("allow_recall")
                .withDefault(true)
                .withDescription("Whether the submitter may recall a pending submission."))
            .addField(FieldDefinition.integer("executionOrder").withColumnName("execution_order")
                .withDefault(0)
                .withDescription("Ordinal evaluation order; lower runs first."))
            .build();
    }

    public static CollectionDefinition scheduledJobs() {
        return systemBuilder("scheduled-jobs", "Scheduled Jobs", "scheduled_job")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("jobType", 20).withColumnName("job_type")
                .withEnumValues(List.of("FLOW", "SCRIPT", "REPORT_EXPORT"))
                .withDescription("Kind of work the job runs."))
            .addField(FieldDefinition.string("jobReferenceId", 36)
                .withColumnName("job_reference_id")
                .withDescription("Id of the flow, script or report the job executes."))
            .addField(FieldDefinition.requiredString("cronExpression", 100)
                .withColumnName("cron_expression")
                .withDescription("Cron expression controlling when the job runs."))
            .addField(FieldDefinition.string("timezone", 50).withDefault("UTC")
                .withDescription("IANA timezone name (e.g. America/New_York)."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.json("config")
                .withDescription("JSON parameters passed to the job on each run."))
            .addField(FieldDefinition.datetime("lastRunAt").withColumnName("last_run_at")
                .withDescription("When the job last ran."))
            .addField(FieldDefinition.string("lastStatus", 20).withColumnName("last_status")
                .withDescription("Outcome of the most recent run."))
            .addField(FieldDefinition.datetime("nextRunAt").withColumnName("next_run_at")
                .withDescription("When the job is next due to run."))
            .build();
    }

    public static CollectionDefinition scriptTriggers() {
        return systemBuilder("script-triggers", "Script Triggers", "script_trigger")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("scriptId", "scripts", "Script")
                .withColumnName("script_id")
                .withDescription("Script this row refers to."))
            .addField(FieldDefinition.masterDetail("collectionId", "collections",
                "Collection").withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("triggerEvent", 20)
                .withColumnName("trigger_event")
                .withEnumValues(List.of("INSERT", "UPDATE", "DELETE"))
                .withDescription("Record event that fires the trigger."))
            .addField(FieldDefinition.integer("executionOrder")
                .withColumnName("execution_order").withDefault(0)
                .withDescription("Ordinal evaluation order; lower runs first."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    public static CollectionDefinition approvalSteps() {
        return systemBuilder("approval-steps", "Approval Steps", "approval_step")
            .displayFieldName("name")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("approvalProcessId",
                "approval-processes", "Approval Process")
                .withColumnName("approval_process_id")
                .withDescription("Approval process this row belongs to."))
            .addField(FieldDefinition.requiredInteger("stepNumber")
                .withColumnName("step_number")
                .withDescription("1-based position of this step in its sequence."))
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.text("entryCriteria")
                .withColumnName("entry_criteria")
                .withDescription("JSON criteria a record must meet to enter this process."))
            .addField(FieldDefinition.requiredString("approverType", 30)
                .withColumnName("approver_type")
                .withDescription("How the step's approver is chosen (user, group, field, hierarchy)."))
            .addField(FieldDefinition.string("approverId", 36)
                .withColumnName("approver_id")
                .withDescription("Id of the specific approver, when the type names one."))
            .addField(FieldDefinition.string("approverField", 100)
                .withColumnName("approver_field")
                .withDescription("Field naming the approver, when the type resolves one from the record."))
            .addField(FieldDefinition.bool("unanimityRequired")
                .withColumnName("unanimity_required").withDefault(false)
                .withDescription("Whether every approver must approve rather than just one."))
            .addField(FieldDefinition.integer("escalationTimeoutHours")
                .withColumnName("escalation_timeout_hours")
                .withDescription("Hours a step may sit unactioned before it escalates."))
            .addField(FieldDefinition.string("escalationAction", 20)
                .withColumnName("escalation_action")
                .withDescription("What happens when the step's escalation timeout passes."))
            .addField(FieldDefinition.string("onApproveAction", 20)
                .withColumnName("on_approve_action").withDefault("NEXT_STEP")
                .withDescription("What happens after this step is approved."))
            .addField(FieldDefinition.string("onRejectAction", 20)
                .withColumnName("on_reject_action").withDefault("REJECT_FINAL")
                .withDescription("What happens after this step is rejected."))
            .build();
    }

    public static CollectionDefinition approvalInstances() {
        return systemBuilder("approval-instances", "Approval Instances",
                "approval_instance")
            .addField(FieldDefinition.masterDetail("approvalProcessId",
                "approval-processes", "Approval Process")
                .withColumnName("approval_process_id")
                .withDescription("Approval process this row belongs to."))
            .addField(FieldDefinition.masterDetail("collectionId", "collections",
                "Collection").withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("recordId", 36)
                .withColumnName("record_id")
                .withDescription("Id of the record this row refers to."))
            .addField(FieldDefinition.requiredString("submittedBy", 36)
                .withColumnName("submitted_by")
                .withDescription("User who submitted the record for approval."))
            .addField(FieldDefinition.requiredInteger("currentStepNumber")
                .withColumnName("current_step_number").withDefault(1)
                .withDescription("Step the instance is currently waiting on."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("PENDING")
                .withEnumValues(List.of("PENDING", "APPROVED", "REJECTED", "RECALLED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.datetime("submittedAt")
                .withColumnName("submitted_at").withNullable(false)
                .withDescription("When the record was submitted for approval."))
            .addField(FieldDefinition.datetime("completedAt")
                .withColumnName("completed_at")
                .withDescription("When execution finished."))
            .build();
    }

    public static CollectionDefinition approvalStepInstances() {
        return systemBuilder("approval-step-instances", "Approval Step Instances",
                "approval_step_instance")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("approvalInstanceId",
                "approval-instances", "Approval Instance")
                .withColumnName("approval_instance_id")
                .withDescription("Approval instance this step belongs to."))
            .addField(FieldDefinition.masterDetail("stepId", "approval-steps",
                "Approval Step").withColumnName("step_id")
                .withDescription("Approval process step this instance realizes."))
            .addField(FieldDefinition.requiredString("assignedTo", 36)
                .withColumnName("assigned_to")
                .withDescription("User currently responsible for this record."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("PENDING")
                .withEnumValues(List.of("PENDING", "APPROVED", "REJECTED", "REASSIGNED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.text("comments")
                .withDescription("Comment the approver left with their decision."))
            .addField(FieldDefinition.datetime("actedAt")
                .withColumnName("acted_at")
                .withDescription("When the approver acted."))
            .build();
    }
}
