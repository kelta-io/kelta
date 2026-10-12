package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import java.util.List;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Platform-management system collections: bulk jobs, packages and migration runs.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class PlatformManagementSystemCollections {

    private PlatformManagementSystemCollections() {
        // Utility class
    }

    public static CollectionDefinition bulkJobs() {
        return systemBuilder("bulk-jobs", "Bulk Jobs", "bulk_job")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("operation", 20)
                .withDescription("Bulk operation being run (insert, update, upsert or delete)."))
            .addField(FieldDefinition.requiredString("status", 20).withDefault("QUEUED")
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.integer("totalRecords").withColumnName("total_records")
                .withDescription("Number of records the job was asked to process."))
            .addField(FieldDefinition.integer("processedRecords")
                .withColumnName("processed_records")
                .withDescription("Number of records processed so far."))
            .addField(FieldDefinition.integer("successRecords")
                .withColumnName("success_records")
                .withDescription("Number of records that processed successfully."))
            .addField(FieldDefinition.integer("errorRecords")
                .withColumnName("error_records")
                .withDescription("Number of records that failed."))
            .addField(FieldDefinition.string("externalIdField")
                .withColumnName("external_id_field")
                .withDescription("Field matched on to upsert existing records."))
            .addField(FieldDefinition.string("contentType", 50)
                .withColumnName("content_type").withDefault("application/json")
                .withDescription("MIME type of the stored content."))
            .addField(FieldDefinition.integer("batchSize").withColumnName("batch_size")
                .withDefault(200)
                .withDescription("Records processed per batch."))
            .addField(FieldDefinition.datetime("startedAt").withColumnName("started_at")
                .withDescription("When execution started."))
            .addField(FieldDefinition.datetime("completedAt").withColumnName("completed_at")
                .withDescription("When execution finished."))
            .build();
    }

    public static CollectionDefinition packages() {
        return systemBuilder("packages", "Packages", "package")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.requiredString("version", 50)
                .withDescription("Package version string."))
            .addField(FieldDefinition.string("description")
                .withDescription("Free-text description of what this record is for."))
            .build();
    }

    public static CollectionDefinition packageItems() {
        return systemBuilder("package-items", "Package Items", "package_item")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("packageId", "packages", "Package")
                .withColumnName("package_id")
                .withDescription("Package this item belongs to."))
            .addField(FieldDefinition.requiredString("itemType", 50)
                .withColumnName("item_type")
                .withEnumValues(List.of("COLLECTION", "FIELD", "ROLE", "POLICY",
                    "ROUTE_POLICY", "FIELD_POLICY", "OIDC_PROVIDER",
                    "UI_PAGE", "UI_MENU", "UI_MENU_ITEM"))
                .withDescription("Kind of metadata this item carries."))
            .addField(FieldDefinition.requiredString("itemId", 36)
                .withColumnName("item_id")
                .withDescription("Id of the metadata object this item carries."))
            .addField(FieldDefinition.json("content")
                .withDescription("Serialized JSON of the packaged metadata object."))
            .build();
    }

    public static CollectionDefinition migrationRuns() {
        return systemBuilder("migration-runs", "Migration Runs", "migration_run")
            .addField(FieldDefinition.requiredString("collectionId", 36)
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredInteger("fromVersion")
                .withColumnName("from_version")
                .withDescription("Schema version the migration starts from."))
            .addField(FieldDefinition.requiredInteger("toVersion")
                .withColumnName("to_version")
                .withDescription("Schema version the migration targets."))
            .addField(FieldDefinition.requiredString("status", 50)
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.string("errorMessage", 2000)
                .withColumnName("error_message")
                .withDescription("Failure detail recorded when the run did not succeed."))
            .build();
    }
}
