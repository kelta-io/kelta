package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import java.util.List;
import java.util.Map;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.readOnlySystemBuilder;

/**
 * Read-only audit/log system collections: security and setup audit, field history, record and
 * metadata versions, execution logs, email logs and login history.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class AuditLogSystemCollections {

    private AuditLogSystemCollections() {
        // Utility class
    }

    public static CollectionDefinition securityAuditLogs() {
        return readOnlySystemBuilder("security-audit-logs", "Security Audit Logs", "security_audit_log")
            .addField(FieldDefinition.requiredString("eventType", 50)
                .withColumnName("event_type")
                .withDescription("Kind of event recorded."))
            .addField(FieldDefinition.requiredString("eventCategory", 30)
                .withColumnName("event_category")
                .withDescription("Category the audited event belongs to."))
            .addField(FieldDefinition.string("actorUserId", 36)
                .withColumnName("actor_user_id")
                .withDescription("User who performed the audited action."))
            .addField(FieldDefinition.string("actorEmail", 320)
                .withColumnName("actor_email")
                .withDescription("Email of the user who performed the audited action, captured at the time."))
            .addField(FieldDefinition.string("targetType", 50)
                .withColumnName("target_type")
                .withDescription("Kind of object the audited action targeted."))
            .addField(FieldDefinition.string("targetId", 36)
                .withColumnName("target_id")
                .withDescription("Id of the object the audited action targeted."))
            .addField(FieldDefinition.string("targetName", 255)
                .withColumnName("target_name")
                .withDescription("Name of the targeted object, captured at the time."))
            .addField(FieldDefinition.json("details")
                .withDescription("JSON detail captured with the audited event."))
            .addField(FieldDefinition.string("ipAddress", 45)
                .withColumnName("ip_address")
                .withDescription("IP address the request originated from."))
            .addField(FieldDefinition.text("userAgent").withColumnName("user_agent")
                .withDescription("User-agent string of the originating request."))
            .addField(FieldDefinition.string("correlationId", 36)
                .withColumnName("correlation_id")
                .withDescription("Correlation id tying this entry to the originating request."))
            .build();
    }

    public static CollectionDefinition setupAuditEntries() {
        return readOnlySystemBuilder("setup-audit-entries", "Setup Audit Entries", "setup_audit_trail")
            .addField(FieldDefinition.requiredString("userId", 36)
                .withColumnName("user_id")
                .withDescription("User this row belongs to."))
            .addField(FieldDefinition.requiredString("action", 50)
                .withDescription("Setup action performed."))
            .addField(FieldDefinition.requiredString("section", 100)
                .withDescription("Setup area the change was made in."))
            .addField(FieldDefinition.requiredString("entityType", 50)
                .withColumnName("entity_type")
                .withDescription("Kind of metadata object that changed."))
            .addField(FieldDefinition.string("entityId", 36)
                .withColumnName("entity_id")
                .withDescription("Id of the metadata object that changed."))
            .addField(FieldDefinition.string("entityName", 200)
                .withColumnName("entity_name")
                .withDescription("Name of the metadata object that changed, captured at the time."))
            .addField(FieldDefinition.json("oldValue").withColumnName("old_value")
                .withDescription("Value before the change."))
            .addField(FieldDefinition.json("newValue").withColumnName("new_value")
                .withDescription("Value after the change."))
            .addField(FieldDefinition.datetime("timestamp")
                .withDescription("When the entry was recorded."))
            .build();
    }

    public static CollectionDefinition fieldHistory() {
        return readOnlySystemBuilder("field-history", "Field History", "field_history")
            .tenantScoped(true)
            .addField(FieldDefinition.requiredString("collectionId", 36)
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("recordId", 36)
                .withColumnName("record_id")
                .withDescription("Id of the record this row refers to."))
            .addField(FieldDefinition.requiredString("fieldName", 100)
                .withColumnName("field_name")
                .withDescription("Field whose value changed."))
            .addField(FieldDefinition.json("oldValue").withColumnName("old_value")
                .withDescription("Value before the change."))
            .addField(FieldDefinition.json("newValue").withColumnName("new_value")
                .withDescription("Value after the change."))
            .addField(FieldDefinition.requiredString("changedBy", 36)
                .withColumnName("changed_by")
                .withDescription("User who made the change."))
            .addField(FieldDefinition.datetime("changedAt").withColumnName("changed_at")
                .withDescription("When the change was made."))
            .addField(FieldDefinition.requiredString("changeSource", 20)
                .withColumnName("change_source")
                .withDescription("What performed the change (UI, API, flow, script, bulk job)."))
            .build();
    }

    public static CollectionDefinition recordVersions() {
        return readOnlySystemBuilder("record-versions", "Record Versions", "record_version")
            .tenantScoped(true)
            .addField(FieldDefinition.requiredString("collectionId", 36)
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("recordId", 36)
                .withColumnName("record_id")
                .withDescription("Id of the record this row refers to."))
            .addField(FieldDefinition.requiredInteger("versionNumber")
                .withColumnName("version_number")
                .withDescription("Monotonic version number of the record snapshot."))
            .addField(FieldDefinition.requiredString("changeType", 10)
                .withColumnName("change_type")
                .withDescription("Whether the version records a create, update or delete."))
            .addField(FieldDefinition.json("snapshot")
                .withDescription("JSON copy of the record at this version."))
            .addField(FieldDefinition.json("changedFields").withColumnName("changed_fields")
                .withDescription("JSON list of fields that changed in this version."))
            .addField(FieldDefinition.requiredString("changedBy", 36)
                .withColumnName("changed_by")
                .withDescription("User who made the change."))
            .addField(FieldDefinition.datetime("changedAt").withColumnName("changed_at")
                .withDescription("When the change was made."))
            .addField(FieldDefinition.requiredString("changeSource", 20)
                .withColumnName("change_source")
                .withDescription("What performed the change (UI, API, flow, script, bulk job)."))
            .build();
    }

    public static CollectionDefinition emailLogs() {
        return readOnlySystemBuilder("email-logs", "Email Logs", "email_log")
            .addField(FieldDefinition.lookup("templateId", "email-templates", "Email Template")
                .withColumnName("template_id")
                .withDescription("Email template used."))
            .addField(FieldDefinition.requiredString("recipientEmail", 320)
                .withColumnName("recipient_email")
                .withDescription("Address the message was sent to."))
            .addField(FieldDefinition.requiredString("subject", 500)
                .withDescription("Subject line."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.string("source", 30)
                .withDescription("Subsystem that sent the message."))
            .addField(FieldDefinition.string("sourceId", 36).withColumnName("source_id")
                .withDescription("Id of the object in the sending subsystem."))
            .addField(FieldDefinition.text("errorMessage").withColumnName("error_message")
                .withDescription("Failure detail recorded when the run did not succeed."))
            .addField(FieldDefinition.datetime("sentAt").withColumnName("sent_at")
                .withDescription("When the message was sent."))
            .build();
    }

    public static CollectionDefinition loginHistory() {
        return readOnlySystemBuilder("login-history", "Login History", "login_history")
            .addField(FieldDefinition.requiredString("userId", 36)
                .withColumnName("user_id")
                .withDescription("User this row belongs to."))
            .addField(FieldDefinition.datetime("loginTime").withColumnName("login_time")
                .withDescription("When the sign-in attempt happened."))
            .addField(FieldDefinition.string("sourceIp", 45).withColumnName("source_ip")
                .withDescription("IP address the sign-in came from."))
            .addField(FieldDefinition.string("loginType", 20).withColumnName("login_type")
                .withDescription("How the user signed in (password, SSO, token)."))
            .addField(FieldDefinition.string("status", 20)
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.text("userAgent").withColumnName("user_agent")
                .withDescription("User-agent string of the originating request."))
            .addField(FieldDefinition.string("geoCountry", 2).withColumnName("geo_country")
                .withDescription("ISO country code resolved from the request IP."))
            .addField(FieldDefinition.string("geoRegion", 100).withColumnName("geo_region")
                .withDescription("Region/state resolved from the request IP."))
            .addField(FieldDefinition.string("geoCity", 150).withColumnName("geo_city")
                .withDescription("City resolved from the request IP."))
            .addField(FieldDefinition.doubleField("geoLat").withColumnName("geo_lat")
                .withDescription("Latitude resolved from the request IP."))
            .addField(FieldDefinition.doubleField("geoLon").withColumnName("geo_lon")
                .withDescription("Longitude resolved from the request IP."))
            .build();
    }

    public static CollectionDefinition scriptExecutionLogs() {
        return readOnlySystemBuilder("script-execution-logs", "Script Execution Logs",
                "script_execution_log")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("scriptId", "scripts", "Script")
                .withColumnName("script_id")
                .withDescription("Script this row refers to."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withEnumValues(List.of("SUCCESS", "FAILURE", "TIMEOUT",
                    "GOVERNOR_LIMIT"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.string("triggerType", 30)
                .withColumnName("trigger_type")
                .withDescription("What causes this to run."))
            .addField(FieldDefinition.string("recordId", 36)
                .withColumnName("record_id")
                .withDescription("Id of the record this row refers to."))
            .addField(FieldDefinition.integer("durationMs")
                .withColumnName("duration_ms")
                .withDescription("Wall-clock duration in milliseconds."))
            .addField(FieldDefinition.integer("cpuMs")
                .withColumnName("cpu_ms")
                .withDescription("CPU time consumed, in milliseconds."))
            .addField(FieldDefinition.integer("queriesExecuted")
                .withColumnName("queries_executed").withDefault(0)
                .withDescription("Number of queries the execution ran."))
            .addField(FieldDefinition.integer("dmlRows")
                .withColumnName("dml_rows").withDefault(0)
                .withDescription("Number of rows written by the execution."))
            .addField(FieldDefinition.integer("callouts").withDefault(0)
                .withDescription("Number of outbound HTTP callouts the execution made."))
            .addField(FieldDefinition.text("errorMessage")
                .withColumnName("error_message")
                .withDescription("Failure detail recorded when the run did not succeed."))
            .addField(FieldDefinition.text("logOutput")
                .withColumnName("log_output")
                .withDescription("Captured log output of the execution."))
            .addField(FieldDefinition.datetime("executedAt")
                .withColumnName("executed_at")
                .withDescription("When the execution ran."))
            .build();
    }

    public static CollectionDefinition flowExecutions() {
        return readOnlySystemBuilder("flow-executions", "Flow Executions",
                "flow_execution")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("flowId", "flows", "Flow")
                .withColumnName("flow_id")
                .withDescription("Flow this execution belongs to."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("RUNNING")
                .withEnumValues(List.of("RUNNING", "COMPLETED", "FAILED",
                    "WAITING", "CANCELLED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.string("startedBy", 36)
                .withColumnName("started_by")
                .withDescription("User or process that started the execution."))
            .addField(FieldDefinition.string("triggerRecordId", 36)
                .withColumnName("trigger_record_id")
                .withDescription("Record whose change triggered the flow."))
            .addField(FieldDefinition.json("variables").withDefault(Map.of())
                .withDescription("JSON variable state of the flow run."))
            .addField(FieldDefinition.string("currentNodeId", 100)
                .withColumnName("current_node_id")
                .withDescription("Node the run is currently waiting on."))
            .addField(FieldDefinition.text("errorMessage")
                .withColumnName("error_message")
                .withDescription("Failure detail recorded when the run did not succeed."))
            .addField(FieldDefinition.datetime("startedAt")
                .withColumnName("started_at").withNullable(false)
                .withDescription("When execution started."))
            .addField(FieldDefinition.datetime("completedAt")
                .withColumnName("completed_at")
                .withDescription("When execution finished."))
            .build();
    }

    public static CollectionDefinition jobExecutionLogs() {
        return readOnlySystemBuilder("job-execution-logs", "Job Execution Logs",
                "job_execution_log")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("jobId", "scheduled-jobs",
                "Scheduled Job").withColumnName("job_id")
                .withDescription("Scheduled job this run belongs to."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.integer("recordsProcessed")
                .withColumnName("records_processed").withDefault(0)
                .withDescription("Number of records the run processed."))
            .addField(FieldDefinition.text("errorMessage")
                .withColumnName("error_message")
                .withDescription("Failure detail recorded when the run did not succeed."))
            .addField(FieldDefinition.datetime("startedAt")
                .withColumnName("started_at").withNullable(false)
                .withDescription("When execution started."))
            .addField(FieldDefinition.datetime("completedAt")
                .withColumnName("completed_at")
                .withDescription("When execution finished."))
            .addField(FieldDefinition.integer("durationMs")
                .withColumnName("duration_ms")
                .withDescription("Wall-clock duration in milliseconds."))
            .build();
    }

    public static CollectionDefinition bulkJobResults() {
        return readOnlySystemBuilder("bulk-job-results", "Bulk Job Results",
                "bulk_job_result")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("bulkJobId", "bulk-jobs",
                "Bulk Job").withColumnName("bulk_job_id")
                .withDescription("Bulk job this result row belongs to."))
            .addField(FieldDefinition.requiredInteger("recordIndex")
                .withColumnName("record_index")
                .withDescription("0-based position of the record within the submitted batch."))
            .addField(FieldDefinition.string("recordId", 36)
                .withColumnName("record_id")
                .withDescription("Id of the record this row refers to."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withEnumValues(List.of("SUCCESS", "FAILURE"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.text("errorMessage")
                .withColumnName("error_message")
                .withDescription("Failure detail recorded when the run did not succeed."))
            .build();
    }

    public static CollectionDefinition collectionVersions() {
        return readOnlySystemBuilder("collection-versions", "Collection Versions",
                "collection_version")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("collectionId", "collections",
                "Collection").withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredInteger("version")
                .withDescription("Version number of this schema snapshot."))
            .addField(FieldDefinition.json("schema")
                .withDescription("JSON snapshot of the collection schema at this version."))
            .build();
    }

    public static CollectionDefinition fieldVersions() {
        return readOnlySystemBuilder("field-versions", "Field Versions", "field_version")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("collectionVersionId",
                "collection-versions", "Collection Version")
                .withColumnName("collection_version_id")
                .withDescription("Collection version this field snapshot belongs to."))
            .addField(FieldDefinition.masterDetail("fieldId", "fields", "Field")
                .withColumnName("field_id")
                .withDescription("Field this row refers to."))
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Field name captured in this snapshot."))
            .addField(FieldDefinition.requiredString("type", 50)
                .withDescription("Field type captured in this snapshot."))
            .addField(FieldDefinition.bool("required").withDefault(false)
                .withNullable(false)
                .withDescription("Whether a value must be supplied."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withNullable(false)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.json("constraints")
                .withDescription("JSON validation constraints for the field."))
            .build();
    }

    public static CollectionDefinition migrationSteps() {
        return readOnlySystemBuilder("migration-steps", "Migration Steps",
                "migration_step")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("migrationRunId", "migration-runs",
                "Migration Run").withColumnName("migration_run_id")
                .withDescription("Migration run this step belongs to."))
            .addField(FieldDefinition.requiredInteger("stepNumber")
                .withColumnName("step_number")
                .withDescription("1-based position of this step in its sequence."))
            .addField(FieldDefinition.requiredString("operation", 100)
                .withDescription("Schema operation this step performs."))
            .addField(FieldDefinition.requiredString("status", 50)
                .withDefault("PENDING")
                .withEnumValues(List.of("PENDING", "RUNNING", "COMPLETED",
                    "FAILED", "SKIPPED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.json("details")
                .withDescription("JSON detail of what the step did."))
            .addField(FieldDefinition.string("errorMessage", 2000)
                .withColumnName("error_message")
                .withDescription("Failure detail recorded when the run did not succeed."))
            .build();
    }
}
