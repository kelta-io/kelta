package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of all system collection definitions.
 *
 * <p>System collections map to existing JPA tables managed by Flyway migrations.
 * They are served by the worker's DynamicCollectionRouter, just like user-defined collections,
 * but their tables are NOT created by the worker (Flyway manages them).
 *
 * <p>Each definition specifies:
 * <ul>
 *   <li>Collection name (used in API paths: /api/{name})</li>
 *   <li>Physical table name (existing Flyway-managed table)</li>
 *   <li>Field definitions with column mappings (API name -> DB column)</li>
 *   <li>Relationship types (LOOKUP/MASTER_DETAIL) for ?include= resolution</li>
 *   <li>Whether the collection is tenant-scoped</li>
 *   <li>Whether the collection is read-only (audit logs, history)</li>
 * </ul>
 *
 * <p>This class is the registry's single entry point; the definitions themselves live in one
 * class per section in this package ({@link CoreSystemCollections}, {@link UiSystemCollections},
 * {@link AutomationSystemCollections}, {@link AuditLogSystemCollections}, …), built from the
 * package-private {@code SystemCollectionBuilders} helpers. To add a system collection, put its
 * factory method in the matching section class and add it to {@link #all()}. The full content
 * and order of {@link #all()} is pinned by {@code SystemCollectionDefinitionsSplitTest}.
 *
 * @since 1.0.0
 */
public final class SystemCollectionDefinitions {

    /**
     * The system tenant ID used for system collection metadata.
     */
    public static final String SYSTEM_TENANT_ID = "00000000-0000-0000-0000-000000000001";

    private SystemCollectionDefinitions() {
        // Utility class
    }

    /**
     * Returns all system collection definitions.
     *
     * @return unmodifiable list of all system collection definitions
     */
    public static List<CollectionDefinition> all() {
        List<CollectionDefinition> definitions = new ArrayList<>();

        // Core entity collections
        definitions.add(CoreSystemCollections.tenants());
        definitions.add(CoreSystemCollections.users());
        definitions.add(CoreSystemCollections.profiles());
        definitions.add(CoreSystemCollections.collections());
        definitions.add(CoreSystemCollections.fields());

        // Groups & Membership
        definitions.add(GroupSystemCollections.userGroups());
        definitions.add(GroupSystemCollections.groupMemberships());

        // Permission tables (profile-based)
        definitions.add(PermissionSystemCollections.profileSystemPermissions());
        definitions.add(PermissionSystemCollections.profileObjectPermissions());
        definitions.add(PermissionSystemCollections.profileFieldPermissions());

        // Delegated administration
        definitions.add(PermissionSystemCollections.delegatedAdminScopes());

        // UI & Layout
        definitions.add(UiSystemCollections.pageLayouts());
        definitions.add(UiSystemCollections.layoutSections());
        definitions.add(UiSystemCollections.layoutFields());
        definitions.add(UiSystemCollections.layoutRelatedLists());
        definitions.add(UiSystemCollections.layoutAssignments());
        definitions.add(UiSystemCollections.layoutRules());
        definitions.add(UiSystemCollections.listViews());
        definitions.add(UiSystemCollections.userUiPreferences());
        definitions.add(UiSystemCollections.uiPages());
        definitions.add(UiSystemCollections.uiMenus());
        definitions.add(UiSystemCollections.uiMenuItems());
        definitions.add(UiSystemCollections.uiTranslations());

        // Picklists
        definitions.add(PicklistSystemCollections.globalPicklists());
        definitions.add(PicklistSystemCollections.picklistValues());
        definitions.add(PicklistSystemCollections.picklistDependencies());

        // Record types & Validation
        definitions.add(RecordTypeValidationSystemCollections.recordTypes());
        definitions.add(RecordTypeValidationSystemCollections.recordTypePicklists());
        definitions.add(RecordTypeValidationSystemCollections.validationRules());
        definitions.add(RecordTypeValidationSystemCollections.recordScripts());
        definitions.add(RecordTypeValidationSystemCollections.recordShares());
        definitions.add(RecordTypeValidationSystemCollections.quickActions());

        // Workflows & Automation
        definitions.add(AutomationSystemCollections.scripts());
        definitions.add(AutomationSystemCollections.scriptTriggers());
        definitions.add(AutomationSystemCollections.flows());
        definitions.add(AutomationSystemCollections.approvalProcesses());
        definitions.add(AutomationSystemCollections.approvalSteps());
        definitions.add(AutomationSystemCollections.approvalInstances());
        definitions.add(AutomationSystemCollections.approvalStepInstances());
        definitions.add(AutomationSystemCollections.scheduledJobs());

        // Communication
        definitions.add(CommunicationSystemCollections.emailTemplates());
        definitions.add(CommunicationSystemCollections.campaigns());
        definitions.add(CommunicationSystemCollections.campaignRecipients());
        definitions.add(CommunicationSystemCollections.emailSuppressions());

        // Chat (telehealth slice 2)
        definitions.add(ChatSystemCollections.chatQueues());
        definitions.add(ChatSystemCollections.chatConversations());
        definitions.add(ChatSystemCollections.chatMessages());
        definitions.add(ChatSystemCollections.chatParticipants());

        // Support mailbox (support-mailbox slice 1)
        definitions.add(MailboxSystemCollections.mailboxes());
        definitions.add(MailboxSystemCollections.mailboxAccess());
        definitions.add(MailboxSystemCollections.mailboxThreads());
        definitions.add(MailboxSystemCollections.mailboxMessages());
        definitions.add(MailboxSystemCollections.mailboxAttachments());
        definitions.add(MailboxSystemCollections.mailboxInboundEvents());
        definitions.add(MailboxSystemCollections.mailboxTemplates());

        // Scheduling (telehealth slice 4)
        definitions.add(TelehealthSystemCollections.telehealthAvailability());
        definitions.add(TelehealthSystemCollections.telehealthAppointments());

        // Video sessions (telehealth slice 5)
        definitions.add(TelehealthSystemCollections.videoSessions());

        // Archival & retention (telehealth slice 7)
        definitions.add(TelehealthSystemCollections.telehealthArchives());

        // Availability alerting (consumer-alerting slice 3)
        definitions.add(ConsumerAlertingSystemCollections.watchTargets());
        definitions.add(ConsumerAlertingSystemCollections.watches());

        // Analytics capture (consumer-alerting slice 8)
        definitions.add(ConsumerAlertingSystemCollections.analyticsEvents());
        // Win tracking + live ticker (consumer-alerting slice 9)
        definitions.add(ConsumerAlertingSystemCollections.wins());

        // SEO page generation (consumer-alerting slice 11)
        definitions.add(ConsumerAlertingSystemCollections.seoPages());

        // Integration
        definitions.add(IntegrationSystemCollections.connectedApps());
        definitions.add(IntegrationSystemCollections.connectedAppTokens());
        definitions.add(IntegrationSystemCollections.oidcProviders());
        definitions.add(IntegrationSystemCollections.samlProviders());
        definitions.add(IntegrationSystemCollections.credentials());
        definitions.add(IntegrationSystemCollections.credentialOauthTokens());
        definitions.add(IntegrationSystemCollections.apiSpecs());
        definitions.add(IntegrationSystemCollections.apiOperations());

        // Reports & Dashboards
        definitions.add(ReportSystemCollections.reports());
        definitions.add(ReportSystemCollections.reportFolders());
        definitions.add(ReportSystemCollections.dashboards());
        definitions.add(ReportSystemCollections.dashboardComponents());

        // Collaboration
        definitions.add(CollaborationSystemCollections.notes());
        definitions.add(CollaborationSystemCollections.attachments());

        // Platform management
        definitions.add(PlatformManagementSystemCollections.bulkJobs());
        definitions.add(PlatformManagementSystemCollections.packages());
        definitions.add(PlatformManagementSystemCollections.packageItems());
        definitions.add(PlatformManagementSystemCollections.migrationRuns());

        // Read-only audit/log collections
        definitions.add(AuditLogSystemCollections.securityAuditLogs());
        definitions.add(AuditLogSystemCollections.setupAuditEntries());
        definitions.add(AuditLogSystemCollections.fieldHistory());
        definitions.add(AuditLogSystemCollections.recordVersions());
        definitions.add(AuditLogSystemCollections.scriptExecutionLogs());
        definitions.add(AuditLogSystemCollections.flowExecutions());
        definitions.add(AuditLogSystemCollections.jobExecutionLogs());
        definitions.add(AuditLogSystemCollections.bulkJobResults());
        definitions.add(AuditLogSystemCollections.emailLogs());
        definitions.add(AuditLogSystemCollections.loginHistory());
        definitions.add(AuditLogSystemCollections.collectionVersions());
        definitions.add(AuditLogSystemCollections.fieldVersions());
        definitions.add(AuditLogSystemCollections.migrationSteps());

        return Collections.unmodifiableList(definitions);
    }

    /**
     * Returns a map of collection name to definition for quick lookup.
     */
    public static Map<String, CollectionDefinition> byName() {
        Map<String, CollectionDefinition> map = new LinkedHashMap<>();
        for (CollectionDefinition def : all()) {
            map.put(def.name(), def);
        }
        return Collections.unmodifiableMap(map);
    }
}
