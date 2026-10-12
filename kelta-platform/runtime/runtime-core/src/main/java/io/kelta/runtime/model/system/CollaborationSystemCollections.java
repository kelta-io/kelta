package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.OwnerScope;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Collaboration system collections: notes and attachments.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class CollaborationSystemCollections {

    private CollaborationSystemCollections() {
        // Utility class
    }

    /**
     * Notes on any record. Only the author ({@code createdBy}) may edit or delete one; reads stay
     * unscoped because a note is read through its parent record's access.
     */
    public static CollectionDefinition notes() {
        return systemBuilder("notes", "Notes", "note")
            .ownerField("createdBy")
            .ownerScope(OwnerScope.ALL)
            .ownerScopeReads(false)
            .addImmutableField("collectionId")
            .addImmutableField("recordId")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("recordId").withColumnName("record_id")
                .withDescription("Id of the record this row refers to."))
            .addField(FieldDefinition.requiredText("content")
                .withDescription("Note body."))
            .build();
    }

    public static CollectionDefinition attachments() {
        return systemBuilder("attachments", "Attachments", "file_attachment")
            .displayFieldName("fileName")
            .addImmutableField("collectionId")
            .addImmutableField("recordId")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("recordId").withColumnName("record_id")
                .withDescription("Id of the record this row refers to."))
            .addField(FieldDefinition.requiredString("fileName", 500)
                .withColumnName("file_name")
                .withDescription("Original file name as uploaded."))
            .addField(FieldDefinition.longField("fileSize").withColumnName("file_size")
                .withDescription("File size in bytes."))
            .addField(FieldDefinition.requiredString("contentType", 200)
                .withColumnName("content_type")
                .withDescription("MIME type of the stored content."))
            .addField(FieldDefinition.string("storageKey", 500)
                .withColumnName("storage_key")
                .withDescription("Object-storage key the binary is stored under."))
            .addField(FieldDefinition.requiredString("uploadedBy", 320)
                .withColumnName("uploaded_by")
                .withDescription("User who uploaded the file."))
            .addField(FieldDefinition.datetime("uploadedAt").withColumnName("uploaded_at")
                .withDescription("When the file was uploaded."))
            .build();
    }
}
