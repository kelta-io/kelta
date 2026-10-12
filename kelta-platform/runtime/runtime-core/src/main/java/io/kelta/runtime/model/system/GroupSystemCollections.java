package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import java.util.List;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Groups &amp; membership system collections.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class GroupSystemCollections {

    private GroupSystemCollections() {
        // Utility class
    }

    public static CollectionDefinition userGroups() {
        return systemBuilder("user-groups", "User Groups", "user_group")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.string("groupType", 20).withColumnName("group_type")
                .withDefault("PUBLIC")
                .withEnumValues(List.of("PUBLIC", "QUEUE", "SYSTEM"))
                .withDescription("Whether the group is managed locally or synced from an identity provider."))
            .addField(FieldDefinition.requiredString("source", 20)
                .withDefault("MANUAL")
                .withDescription("Where membership comes from: managed locally or synced from a provider."))
            .addField(FieldDefinition.string("oidcGroupName", 200)
                .withColumnName("oidc_group_name")
                .withDescription("Identity-provider group name this group is synced from."))
            .build();
    }

    public static CollectionDefinition groupMemberships() {
        return systemBuilder("group-memberships", "Group Memberships", "group_membership")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("groupId", "user-groups", "Group")
                .withColumnName("group_id")
                .withDescription("Group this membership belongs to."))
            .addField(FieldDefinition.requiredString("memberType", 10)
                .withColumnName("member_type")
                .withEnumValues(List.of("USER", "GROUP"))
                .withDescription("Whether the member is a USER or a nested GROUP."))
            .addField(FieldDefinition.requiredString("memberId", 36)
                .withColumnName("member_id")
                .withDescription("Id of the user or nested group that is a member."))
            .build();
    }
}
