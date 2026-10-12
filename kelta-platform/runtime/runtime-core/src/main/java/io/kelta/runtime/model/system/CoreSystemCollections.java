package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.ValidationRules;

import java.util.List;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Core entity system collections: tenants, users, profiles and the collection/field metadata
 * tables.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class CoreSystemCollections {

    private CoreSystemCollections() {
        // Utility class
    }

    public static CollectionDefinition tenants() {
        return systemBuilder("tenants", "Tenants", "tenant")
            .tenantScoped(false)
            .displayFieldName("slug")
            .addField(FieldDefinition.requiredString("slug", 63)
                .withUnique(true)
                .withValidation(ValidationRules.forString(null, 63, "^[a-z][a-z0-9-]{1,61}[a-z0-9]$"))
                .withDescription("URL slug the tenant is reached at."))
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Display name of the tenant organization."))
            .addField(FieldDefinition.requiredString("edition", 20)
                .withDefault("PROFESSIONAL")
                .withEnumValues(List.of("FREE", "PROFESSIONAL", "ENTERPRISE", "UNLIMITED"))
                .withDescription("Licensed platform edition, which sets the tenant's feature ceiling."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("PROVISIONING")
                .withEnumValues(List.of("PROVISIONING", "ACTIVE", "SUSPENDED", "DECOMMISSIONED"))
                .withDescription("Provisioning state of the tenant."))
            .addField(FieldDefinition.json("settings")
                .withDescription("Free-form JSON tenant settings."))
            .addField(FieldDefinition.json("limits")
                .withDescription("JSON governor-limit overrides for this tenant."))
            .addField(FieldDefinition.bool("ipAllowlistEnabled", false)
                .withColumnName("ip_allowlist_enabled")
                .withDescription("Whether API access is restricted to ipAllowlistCidrs."))
            .addField(FieldDefinition.json("ipAllowlistCidrs")
                .withColumnName("ip_allowlist_cidrs")
                .withDescription("JSON array of CIDR ranges allowed to reach the API."))
            .addField(FieldDefinition.lookup("parentTenantId", "tenants", "Parent Tenant")
                .withColumnName("parent_tenant_id")
                .withDescription("Parent tenant, set on sandboxes cloned from a production tenant."))
            .build();
    }

    public static CollectionDefinition users() {
        return systemBuilder("users", "Users", "platform_user")
            .displayFieldName("email")
            .addImmutableField("tenantId")
            .addImmutableField("userType")
            .addField(FieldDefinition.lookup("tenantId", "tenants", "Tenant")
                .withColumnName("tenant_id")
                .withDescription("Tenant that owns this record."))
            .addField(FieldDefinition.requiredString("email", 320)
                .withDescription("Email address."))
            .addField(FieldDefinition.requiredString("userType", 20)
                .withColumnName("user_type")
                .withDefault("INTERNAL")
                .withEnumValues(List.of("INTERNAL", "PORTAL"))
                .withDescription("INTERNAL for staff users, PORTAL for external/portal users."))
            .addField(FieldDefinition.string("username", 100)
                .withDescription("Login name, when distinct from the email address."))
            .addField(FieldDefinition.string("firstName", 100).withColumnName("first_name")
                .withDescription("Given name."))
            .addField(FieldDefinition.string("lastName", 100).withColumnName("last_name")
                .withDescription("Family name."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("ACTIVE")
                .withEnumValues(List.of("ACTIVE", "INACTIVE", "LOCKED", "PENDING_ACTIVATION"))
                .withDescription("Account state: ACTIVE, INACTIVE, LOCKED or PENDING_ACTIVATION."))
            .addField(FieldDefinition.string("locale", 10).withDefault("en_US")
                .withDescription("Locale code (e.g. en_US)."))
            .addField(FieldDefinition.string("timezone", 50).withDefault("UTC")
                .withDescription("IANA timezone name (e.g. America/New_York)."))
            .addField(FieldDefinition.lookup("profileId", "profiles", "Profile")
                .withColumnName("profile_id")
                .withDescription("Profile this row applies to."))
            .addField(FieldDefinition.lookup("managerId", "users", "Manager")
                .withColumnName("manager_id")
                .withDescription("This user's manager, used by role-hierarchy sharing and approvals."))
            .addField(FieldDefinition.datetime("lastLoginAt").withColumnName("last_login_at")
                .withDescription("When the user last signed in."))
            .addField(FieldDefinition.integer("loginCount").withColumnName("login_count").withDefault(0)
                .withDescription("Number of successful sign-ins."))
            .addField(FieldDefinition.bool("mfaEnabled").withColumnName("mfa_enabled").withDefault(false)
                .withDescription("Whether multi-factor authentication is enrolled for this user."))
            .addField(FieldDefinition.json("settings")
                .withDescription("Free-form JSON per-user settings."))
            .build();
    }

    public static CollectionDefinition profiles() {
        return systemBuilder("profiles", "Profiles", "profile")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 255)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.text("description")
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.bool("isSystem").withColumnName("is_system").withDefault(false)
                .withDescription("Whether the row ships with the platform and cannot be deleted."))
            .build();
    }

    public static CollectionDefinition collections() {
        return systemBuilder("collections", "Collections", "collection")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100).withUnique(true)
                .withDescription("API name of the collection; it forms the path /api/<name>."))
            .addField(FieldDefinition.string("displayName", 100).withColumnName("display_name")
                .withDescription("Label shown in the UI instead of the raw name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.string("path", 255)
                .withDescription("API path the collection's records are served at."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.bool("systemCollection").withColumnName("system_collection")
                .withDefault(false)
                .withDescription("Whether the collection is platform metadata rather than tenant data."))
            .addField(FieldDefinition.requiredInteger("currentVersion").withColumnName("current_version")
                .withDefault(1)
                .withDescription("Version number of the collection's current schema."))
            .addField(FieldDefinition.lookup("displayFieldId", "fields", "Display Field")
                .withColumnName("display_field_id")
                .withDescription("Field used as the record's display label."))
            .addField(FieldDefinition.json("adapterConfig").withColumnName("adapter_config")
                .withDescription("JSON storage-adapter configuration."))
            .addField(FieldDefinition.bool("trackHistory").withColumnName("track_history")
                .withDefault(false)
                .withDescription("Whether record versions are captured for this collection."))
            .addField(FieldDefinition.bool("captureGeo").withColumnName("capture_geo")
                .withDefault(false)
                .withDescription("Whether request-origin geo is stamped onto records."))
            .addField(FieldDefinition.string("ownerField", 100).withColumnName("owner_field")
                .withDescription("Field holding the owning user's id (a lookup to users, or createdBy); "
                        + "empty when records are not owned."))
            .addField(FieldDefinition.string("ownerScope", 10).withColumnName("owner_scope")
                .withDefault("NONE")
                .withDescription("Who sees and changes only their own records: NONE, PORTAL (portal "
                        + "members) or ALL (everyone without View/Modify All Data)."))
            .addField(FieldDefinition.bool("ownerScopeReads").withColumnName("owner_scope_reads")
                .withDefault(true)
                .withDescription("Whether owner scoping also hides other people's records from reads; "
                        + "false keeps reads open and limits only writes."))
            .build();
    }

    public static CollectionDefinition fields() {
        return systemBuilder("fields", "Fields", "field")
            .displayFieldName("name")
            .addImmutableField("collectionId")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("API name of the field, as it appears in JSON:API attributes."))
            .addField(FieldDefinition.string("displayName", 100).withColumnName("display_name")
                .withDescription("Label shown in the UI instead of the raw name."))
            .addField(FieldDefinition.requiredString("type", 50)
                .withDescription("Field type, e.g. STRING, INTEGER, DATETIME, LOOKUP, FORMULA."))
            .addField(FieldDefinition.bool("required").withDefault(false)
                .withDescription("Whether a value must be supplied."))
            .addField(FieldDefinition.bool("uniqueConstraint").withColumnName("unique_constraint")
                .withDefault(false)
                .withDescription("Whether values must be unique across the collection."))
            .addField(FieldDefinition.bool("indexed").withDefault(false)
                .withDescription("Whether a database index is maintained on the column."))
            .addField(FieldDefinition.json("defaultValue").withColumnName("default_value")
                .withDescription("Value applied when none is supplied."))
            .addField(FieldDefinition.string("referenceTarget", 100).withColumnName("reference_target")
                .withDescription("Name of the collection a relationship field points at."))
            .addField(FieldDefinition.integer("fieldOrder").withColumnName("field_order")
                .withDescription("Ordinal position of the field within the collection."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.json("constraints")
                .withDescription("JSON validation constraints for the field."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("One-line explanation of the field, surfaced by the schema endpoint."))
            .addField(FieldDefinition.json("fieldTypeConfig").withColumnName("field_type_config")
                .withDescription("JSON type-specific settings (currency code, formula, rollup, masking)."))
            .addField(FieldDefinition.string("autoNumberSequenceName", 100)
                .withColumnName("auto_number_sequence_name")
                .withDescription("Database sequence backing an auto-number field."))
            .addField(FieldDefinition.string("relationshipType", 20).withColumnName("relationship_type")
                .withDescription("LOOKUP (nullable, set null on delete) or MASTER_DETAIL (required, cascade)."))
            .addField(FieldDefinition.string("relationshipName", 100).withColumnName("relationship_name")
                .withDescription("Human-readable name of the relationship."))
            .addField(FieldDefinition.bool("cascadeDelete").withColumnName("cascade_delete")
                .withDefault(false)
                .withDescription("Whether deleting the parent deletes referencing records."))
            .addField(FieldDefinition.lookup("referenceCollectionId", "collections", "Reference Collection")
                .withColumnName("reference_collection_id")
                .withDescription("Id of the collection a relationship field points at."))
            .addField(FieldDefinition.bool("trackHistory").withColumnName("track_history")
                .withDefault(false)
                .withDescription("Whether value changes are recorded in field history."))
            .addField(FieldDefinition.bool("searchable").withDefault(false)
                .withDescription("Whether the field's values are indexed for full-text search."))
            .build();
    }
}
