package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import java.util.List;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Permission system collections: profile-based system/object/field permissions and delegated-
 * administration scopes.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class PermissionSystemCollections {

    private PermissionSystemCollections() {
        // Utility class
    }

    public static CollectionDefinition profileSystemPermissions() {
        return systemBuilder("profile-system-permissions", "Profile System Permissions",
                "profile_system_permission")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("profileId", "profiles", "Profile")
                .withColumnName("profile_id")
                .withDescription("Profile this row applies to."))
            .addField(FieldDefinition.requiredString("permissionName", 100)
                .withColumnName("permission_name")
                .withDescription("System permission being granted."))
            .addField(FieldDefinition.bool("granted").withDefault(false)
                .withNullable(false)
                .withDescription("Whether the permission is granted."))
            .build();
    }

    public static CollectionDefinition profileObjectPermissions() {
        return systemBuilder("profile-object-permissions", "Profile Object Permissions",
                "profile_object_permission")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("profileId", "profiles", "Profile")
                .withColumnName("profile_id")
                .withDescription("Profile this row applies to."))
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.bool("canCreate").withColumnName("can_create")
                .withDefault(false).withNullable(false)
                .withDescription("Whether the profile may create records in the collection."))
            .addField(FieldDefinition.bool("canRead").withColumnName("can_read")
                .withDefault(false).withNullable(false)
                .withDescription("Whether the profile may read records in the collection."))
            .addField(FieldDefinition.bool("canEdit").withColumnName("can_edit")
                .withDefault(false).withNullable(false)
                .withDescription("Whether the profile may edit records in the collection."))
            .addField(FieldDefinition.bool("canDelete").withColumnName("can_delete")
                .withDefault(false).withNullable(false)
                .withDescription("Whether the profile may delete records in the collection."))
            .build();
    }

    public static CollectionDefinition profileFieldPermissions() {
        return systemBuilder("profile-field-permissions", "Profile Field Permissions",
                "profile_field_permission")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("profileId", "profiles", "Profile")
                .withColumnName("profile_id")
                .withDescription("Profile this row applies to."))
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.masterDetail("fieldId", "fields", "Field")
                .withColumnName("field_id")
                .withDescription("Field this row refers to."))
            .addField(FieldDefinition.requiredString("visibility", 20)
                .withDefault("VISIBLE")
                .withEnumValues(List.of("VISIBLE", "READ_ONLY", "HIDDEN", "MASKED"))
                .withDescription("Field access granted: VISIBLE, READ_ONLY, HIDDEN or MASKED (masked values render redacted; see fieldTypeConfig.masking)."))
            .build();
    }

    /**
     * Delegated administration scopes: a full admin lists delegated users who may manage users
     * whose profile is in {@code manageableProfileIds}, plus the capability booleans. Read fresh
     * per request by the worker's DelegatedAdminService (no cache, no NATS); CRUD via the dedicated
     * MANAGE_DELEGATED_ADMINS-gated controller; DelegatedAdminScopeValidationHook rejects scopes
     * that would delegate administration of privileged profiles.
     */
    public static CollectionDefinition delegatedAdminScopes() {
        return systemBuilder("delegated-admin-scopes", "Delegated Admin Scopes",
                "delegated_admin_scope")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.json("delegatedUserIds").withColumnName("delegated_user_ids")
                .withDescription("JSON array of users granted this delegated-admin scope."))
            .addField(FieldDefinition.json("manageableProfileIds").withColumnName("manageable_profile_ids")
                .withDescription("JSON array of profiles the delegated admins may manage users of."))
            .addField(FieldDefinition.bool("canCreateUsers").withColumnName("can_create_users")
                .withDefault(false)
                .withDescription("Whether the delegated admins may create users."))
            .addField(FieldDefinition.bool("canDeactivateUsers").withColumnName("can_deactivate_users")
                .withDefault(false)
                .withDescription("Whether the delegated admins may deactivate users."))
            .addField(FieldDefinition.bool("canResetPasswords").withColumnName("can_reset_passwords")
                .withDefault(false)
                .withDescription("Whether the delegated admins may reset passwords."))
            .build();
    }
}
