package io.kelta.runtime.model.system;

import io.kelta.runtime.model.ApiConfig;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.StorageConfig;

/**
 * Shared builders for the per-section system-collection classes. Every system collection starts
 * from one of these: they wire the physical table, the {@code /api/<name>} path, the system and
 * tenant-scoped flags, and the four audit fields ({@code createdAt}, {@code createdBy},
 * {@code updatedAt}, {@code updatedBy}).
 */
final class SystemCollectionBuilders {

    private SystemCollectionBuilders() {
        // Utility class
    }

    /**
     * Creates a builder pre-configured for a system collection.
     * Default: tenant-scoped, all CRUD enabled, events enabled, system=true.
     */
    static CollectionDefinitionBuilder systemBuilder(String name, String displayName, String tableName) {
        return CollectionDefinition.builder()
            .name(name)
            .displayName(displayName)
            .storageConfig(StorageConfig.physicalTable(tableName))
            .apiConfig(ApiConfig.allEnabled("/api/" + name))
            .systemCollection(true)
            .tenantScoped(true)
            .readOnly(false)
            .addField(FieldDefinition.datetime("createdAt").withColumnName("created_at")
                .withDescription("When the record was created."))
            .addField(FieldDefinition.lookup("createdBy", "users", "Created By").withColumnName("created_by")
                .withDescription("User who created the record."))
            .addField(FieldDefinition.datetime("updatedAt").withColumnName("updated_at")
                .withDescription("When the record was last modified."))
            .addField(FieldDefinition.lookup("updatedBy", "users", "Updated By").withColumnName("updated_by")
                .withDescription("User who last modified the record."));
    }

    /**
     * Creates a builder pre-configured for a read-only system collection.
     * Default: tenant-scoped, read-only API, system=true.
     */
    static CollectionDefinitionBuilder readOnlySystemBuilder(String name, String displayName, String tableName) {
        return CollectionDefinition.builder()
            .name(name)
            .displayName(displayName)
            .storageConfig(StorageConfig.physicalTable(tableName))
            .apiConfig(ApiConfig.readOnly("/api/" + name))
            .systemCollection(true)
            .tenantScoped(true)
            .readOnly(true)
            .addField(FieldDefinition.datetime("createdAt").withColumnName("created_at")
                .withDescription("When the record was created."))
            .addField(FieldDefinition.lookup("createdBy", "users", "Created By").withColumnName("created_by")
                .withDescription("User who created the record."))
            .addField(FieldDefinition.datetime("updatedAt").withColumnName("updated_at")
                .withDescription("When the record was last modified."))
            .addField(FieldDefinition.lookup("updatedBy", "users", "Updated By").withColumnName("updated_by")
                .withDescription("User who last modified the record."));
    }
}
