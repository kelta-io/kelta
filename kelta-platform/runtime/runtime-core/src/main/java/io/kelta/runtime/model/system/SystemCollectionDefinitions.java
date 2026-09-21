package io.kelta.runtime.model.system;

import io.kelta.runtime.model.*;

import java.util.*;

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
        definitions.add(tenants());
        definitions.add(users());
        definitions.add(profiles());
        definitions.add(collections());
        definitions.add(fields());

        // Groups & Membership
        definitions.add(userGroups());
        definitions.add(groupMemberships());

        // Permission tables (profile-based)
        definitions.add(profileSystemPermissions());
        definitions.add(profileObjectPermissions());
        definitions.add(profileFieldPermissions());

        // Delegated administration
        definitions.add(delegatedAdminScopes());

        // UI & Layout
        definitions.add(pageLayouts());
        definitions.add(layoutSections());
        definitions.add(layoutFields());
        definitions.add(layoutRelatedLists());
        definitions.add(layoutAssignments());
        definitions.add(layoutRules());
        definitions.add(listViews());
        definitions.add(userUiPreferences());
        definitions.add(uiPages());
        definitions.add(uiMenus());
        definitions.add(uiMenuItems());
        definitions.add(uiTranslations());

        // Picklists
        definitions.add(globalPicklists());
        definitions.add(picklistValues());
        definitions.add(picklistDependencies());

        // Record types & Validation
        definitions.add(recordTypes());
        definitions.add(recordTypePicklists());
        definitions.add(validationRules());
        definitions.add(recordScripts());
        definitions.add(recordShares());
        definitions.add(quickActions());

        // Workflows & Automation
        definitions.add(scripts());
        definitions.add(scriptTriggers());
        definitions.add(flows());
        definitions.add(approvalProcesses());
        definitions.add(approvalSteps());
        definitions.add(approvalInstances());
        definitions.add(approvalStepInstances());
        definitions.add(scheduledJobs());

        // Communication
        definitions.add(emailTemplates());
        definitions.add(campaigns());
        definitions.add(campaignRecipients());
        definitions.add(emailSuppressions());

        // Chat (telehealth slice 2)
        definitions.add(chatQueues());
        definitions.add(chatConversations());
        definitions.add(chatMessages());
        definitions.add(chatParticipants());

        // Support mailbox (support-mailbox slice 1)
        definitions.add(mailboxes());
        definitions.add(mailboxAccess());
        definitions.add(mailboxThreads());
        definitions.add(mailboxMessages());
        definitions.add(mailboxAttachments());
        definitions.add(mailboxInboundEvents());
        definitions.add(mailboxTemplates());

        // Scheduling (telehealth slice 4)
        definitions.add(telehealthAvailability());
        definitions.add(telehealthAppointments());

        // Video sessions (telehealth slice 5)
        definitions.add(videoSessions());

        // Archival & retention (telehealth slice 7)
        definitions.add(telehealthArchives());

        // Portal billing (consumer-alerting slice 1)
        definitions.add(billingPlans());
        definitions.add(billingEntitlementRules());

        // Availability alerting (consumer-alerting slice 3)
        definitions.add(watchTargets());
        definitions.add(watches());

        // Analytics capture (consumer-alerting slice 8)
        definitions.add(analyticsEvents());
        // Win tracking + live ticker (consumer-alerting slice 9)
        definitions.add(wins());

        // SEO page generation (consumer-alerting slice 11)
        definitions.add(seoPages());

        // Integration
        definitions.add(connectedApps());
        definitions.add(connectedAppTokens());
        definitions.add(oidcProviders());
        definitions.add(samlProviders());
        definitions.add(credentials());
        definitions.add(credentialOauthTokens());
        definitions.add(apiSpecs());
        definitions.add(apiOperations());

        // Reports & Dashboards
        definitions.add(reports());
        definitions.add(reportFolders());
        definitions.add(dashboards());
        definitions.add(dashboardComponents());

        // Collaboration
        definitions.add(notes());
        definitions.add(attachments());

        // Platform management
        definitions.add(bulkJobs());
        definitions.add(packages());
        definitions.add(packageItems());
        definitions.add(migrationRuns());

        // Read-only audit/log collections
        definitions.add(securityAuditLogs());
        definitions.add(setupAuditEntries());
        definitions.add(fieldHistory());
        definitions.add(recordVersions());
        definitions.add(scriptExecutionLogs());
        definitions.add(flowExecutions());
        definitions.add(jobExecutionLogs());
        definitions.add(bulkJobResults());
        definitions.add(emailLogs());
        definitions.add(loginHistory());
        definitions.add(collectionVersions());
        definitions.add(fieldVersions());
        definitions.add(migrationSteps());
        definitions.add(billingCustomers());
        definitions.add(billingSubscriptions());
        definitions.add(billingPasses());

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

    // =========================================================================
    // Core Entity Collections
    // =========================================================================

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

    // ------------------------------------------------------------------
    // Chat (telehealth slice 2, specs/telehealth/2-chat-backend.md).
    // Message bodies are deliberately excluded from search/embedding
    // indexes and from realtime data push — content is served only over
    // the authorized /api/chat/** path (participant-checked in-controller).
    // ------------------------------------------------------------------

    public static CollectionDefinition chatQueues() {
        return systemBuilder("chat-queues", "Chat Queues", "chat_queue")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.text("description")
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    public static CollectionDefinition chatConversations() {
        return systemBuilder("chat-conversations", "Chat Conversations", "chat_conversation")
            .displayFieldName("subject")
            .addImmutableField("origin")
            .addField(FieldDefinition.lookup("queueId", "chat-queues", "Queue")
                .withColumnName("queue_id")
                .withDescription("Chat queue the conversation is waiting in."))
            .addField(FieldDefinition.string("subject", 200)
                .withDescription("Short subject describing what the conversation is about."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("OPEN")
                .withEnumValues(List.of("OPEN", "ASSIGNED", "CLOSED", "ARCHIVED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.requiredString("origin", 20)
                .withDefault("INTERNAL")
                .withEnumValues(List.of("PORTAL", "INTERNAL"))
                .withDescription("Where the conversation was started from: PORTAL or INTERNAL."))
            .addField(FieldDefinition.lookup("assignedTo", "users", "Assigned To")
                .withColumnName("assigned_to")
                .withDescription("User currently responsible for this record."))
            .addField(FieldDefinition.string("contextRecordId", 36)
                .withColumnName("context_record_id")
                .withDescription("Record the conversation is about, when opened from a record page."))
            .addField(FieldDefinition.datetime("lastMessageAt").withColumnName("last_message_at")
                .withDescription("When the most recent message arrived."))
            .addField(FieldDefinition.datetime("closedAt").withColumnName("closed_at")
                .withDescription("When the record was closed."))
            .build();
    }

    public static CollectionDefinition chatMessages() {
        return systemBuilder("chat-messages", "Chat Messages", "chat_message")
            .displayFieldName("id")
            .addImmutableField("conversationId")
            .addImmutableField("senderId")
            .addImmutableField("senderType")
            .addField(FieldDefinition.masterDetail("conversationId", "chat-conversations", "Conversation")
                .withColumnName("conversation_id")
                .withDescription("Chat conversation this row belongs to."))
            .addField(FieldDefinition.lookup("senderId", "users", "Sender")
                .withColumnName("sender_id")
                .withDescription("User who sent the message."))
            .addField(FieldDefinition.requiredString("senderType", 20)
                .withColumnName("sender_type")
                .withEnumValues(List.of("INTERNAL", "PORTAL", "SYSTEM"))
                .withDescription("Whether the sender is an INTERNAL agent, a PORTAL user, or SYSTEM."))
            .addField(FieldDefinition.requiredString("kind", 20)
                .withDefault("TEXT")
                .withEnumValues(List.of("TEXT", "SYSTEM", "ATTACHMENT"))
                .withDescription("Message kind: TEXT, SYSTEM or ATTACHMENT."))
            .addField(FieldDefinition.requiredText("body")
                .withDescription("Message text; never published on the realtime event."))
            .addField(FieldDefinition.datetime("sentAt").withColumnName("sent_at")
                .withDescription("When the message was sent."))
            .build();
    }

    public static CollectionDefinition chatParticipants() {
        return systemBuilder("chat-participants", "Chat Participants", "chat_participant")
            .displayFieldName("id")
            .addImmutableField("conversationId")
            .addImmutableField("userId")
            .addField(FieldDefinition.masterDetail("conversationId", "chat-conversations", "Conversation")
                .withColumnName("conversation_id")
                .withDescription("Chat conversation this row belongs to."))
            .addField(FieldDefinition.lookup("userId", "users", "User")
                .withColumnName("user_id")
                .withDescription("User this row belongs to."))
            .addField(FieldDefinition.requiredString("role", 20)
                .withEnumValues(List.of("AGENT", "PORTAL"))
                .withDescription("Participant role: AGENT or PORTAL."))
            .addField(FieldDefinition.datetime("joinedAt").withColumnName("joined_at")
                .withDescription("When the participant joined the conversation."))
            .addField(FieldDefinition.datetime("lastReadAt").withColumnName("last_read_at")
                .withDescription("How far the participant has read; drives unread counts."))
            .build();
    }

    // ------------------------------------------------------------------
    // Support mailbox (support-mailbox slice 1, specs/support-mailbox/README.md).
    //
    // Every one of these is readOnlySystemBuilder, and that is a decision rather
    // than caution. Each has a write path with side effects the generic route
    // cannot perform:
    //   mailboxes            — creation must mint a webhook key and a vault-stored
    //                          HMAC secret, and echo the secret exactly once.
    //   mailbox-access       — granting access is a permission change; it must be
    //                          audited and validated against user/group existence.
    //   mailbox-threads      — status transitions have consequences. RESOLVED must
    //                          stamp resolved_at and settle the SLA clock;
    //                          WAITING_ON_CUSTOMER must pause it. A generic
    //                          PATCH {status:"RESOLVED"} performs neither and
    //                          leaves a row that is silently wrong in every report.
    //   mailbox-messages     — received mail is immutable; sending is
    //                          POST /api/support/threads/{id}/reply, not an insert.
    //   mailbox-attachments  — written only by ingest, beside an object-store upload.
    //   mailbox-inbound-events — an ops ledger; readable so an engineer can debug a
    //                          redelivery without a bespoke endpoint.
    //
    // Like chat, no object-permission rows are seeded (V191) — /api/support/**
    // (mailbox_access-checked in-controller) is the product path.
    //
    // NOTE: bodyHtml is deliberately NOT declared on mailbox-messages. It is
    // third-party HTML, and declaring it would let a VIEW_ALL_DATA holder pull raw
    // attacker markup into the admin Resource Browser, which renders rows with no
    // sandboxing. The sanitized body is served only by the mailbox controller.
    // ------------------------------------------------------------------

    public static CollectionDefinition mailboxes() {
        return readOnlySystemBuilder("mailboxes", "Mailboxes", "mailbox")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.text("description")
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("address", 320)
                .withDescription("Inbound email address mail is received on."))
            .addField(FieldDefinition.string("replyFromAddress", 320)
                .withColumnName("reply_from_address")
                .withDescription("Address outbound replies are sent from."))
            .addField(FieldDefinition.string("replyFromName", 200)
                .withColumnName("reply_from_name")
                .withDescription("Display name outbound replies are sent as."))
            .addField(FieldDefinition.string("verpDomain", 255)
                .withColumnName("verp_domain")
                .withDescription("Domain used to build VERP return-path addresses for bounce attribution."))
            .addField(FieldDefinition.requiredString("webhookKey", 64)
                .withColumnName("webhook_key")
                .withDescription("Opaque key in the mailbox's inbound webhook URL."))
            .addField(FieldDefinition.requiredString("inboundProvider", 30)
                .withColumnName("inbound_provider")
                .withDefault("SES_SNS")
                .withEnumValues(List.of("SES_SNS", "SES_SNS_INLINE", "GENERIC_HMAC",
                                        "POSTMARK", "MAILGUN", "CLOUDMAILIN"))
                .withDescription("Inbound mail provider whose webhook format and signature are expected."))
            .addField(FieldDefinition.string("providerTopicArn", 500)
                .withColumnName("provider_topic_arn")
                .withDescription("Provider topic ARN inbound notifications must originate from."))
            // The credential ids are deliberately NOT declared: an undeclared column
            // is not selectable through the generic route. A hint and a rotation
            // timestamp are everything the admin UI needs to show.
            .addField(FieldDefinition.string("inboundSecretHint", 12)
                .withColumnName("inbound_secret_hint")
                .withDescription("Last characters of the inbound signing secret, for identification only."))
            .addField(FieldDefinition.datetime("inboundSecretRotatedAt")
                .withColumnName("inbound_secret_rotated_at")
                .withDescription("When the inbound signing secret was last rotated."))
            .addField(FieldDefinition.integer("slaFirstResponseMinutes")
                .withColumnName("sla_first_response_minutes")
                .withDescription("Minutes allowed for a first response before the SLA breaches."))
            .addField(FieldDefinition.integer("slaResolutionMinutes")
                .withColumnName("sla_resolution_minutes")
                .withDescription("Minutes allowed to resolve a thread before the SLA breaches."))
            .addField(FieldDefinition.integer("slaRiskThresholdPct")
                .withColumnName("sla_risk_threshold_pct")
                .withDescription("Percentage of the SLA window after which a thread is flagged at risk."))
            .addField(FieldDefinition.json("businessHours").withColumnName("business_hours")
                .withDescription("JSON business-hours schedule the SLA clock runs against."))
            .addField(FieldDefinition.string("businessTimezone", 64)
                .withColumnName("business_timezone")
                .withDescription("IANA timezone the business hours are expressed in."))
            .addField(FieldDefinition.lookup("escalationUserId", "users", "Escalation Contact")
                .withColumnName("escalation_user_id")
                .withDescription("User notified when a thread breaches its SLA."))
            .addField(FieldDefinition.bool("autoReplyEnabled", false)
                .withColumnName("auto_reply_enabled")
                .withDescription("Whether the mailbox may send AI auto-replies."))
            .addField(FieldDefinition.doubleField("autoReplyMinConfidence")
                .withColumnName("auto_reply_min_confidence")
                .withDescription("Minimum classifier confidence required to auto-send a reply."))
            .addField(FieldDefinition.integer("maxAutoRepliesPerThread")
                .withColumnName("max_auto_replies_per_thread")
                .withDescription("Cap on auto-replies sent to a single thread."))
            .addField(FieldDefinition.bool("aiDraftEnabled", false)
                .withColumnName("ai_draft_enabled")
                .withDescription("Whether AI reply drafts are offered to agents."))
            .addField(FieldDefinition.bool("requireVerifiedSenderForAccountData", true)
                .withColumnName("require_verified_sender_for_account_data")
                .withDescription("Whether account data may only be disclosed to a verified sender."))
            .addField(FieldDefinition.lookup("defaultAssigneeId", "users", "Default Assignee")
                .withColumnName("default_assignee_id")
                .withDescription("User new threads are assigned to when no rule matches."))
            .addField(FieldDefinition.bool("active", true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    public static CollectionDefinition mailboxAccess() {
        return readOnlySystemBuilder("mailbox-access", "Mailbox Access", "mailbox_access")
            .displayFieldName("id")
            .addField(FieldDefinition.masterDetail("mailboxId", "mailboxes", "Mailbox")
                .withColumnName("mailbox_id")
                .withDescription("Mailbox this row belongs to."))
            .addField(FieldDefinition.requiredString("principalType", 10)
                .withColumnName("principal_type")
                .withEnumValues(List.of("USER", "GROUP"))
                .withDescription("Whether the grantee is a USER or a GROUP."))
            .addField(FieldDefinition.requiredString("principalId", 36)
                .withColumnName("principal_id")
                .withDescription("Id of the user or group the grant applies to."))
            // VIEWER reads, AGENT replies, MANAGER approves drafts and configures.
            // Approval authority lives here rather than in a system permission so
            // it cannot leak across mailboxes the holder is not a member of.
            .addField(FieldDefinition.requiredString("role", 10)
                .withEnumValues(List.of("VIEWER", "AGENT", "MANAGER"))
                .withDescription("Access role the principal holds on the mailbox."))
            .build();
    }

    public static CollectionDefinition mailboxThreads() {
        return readOnlySystemBuilder("mailbox-threads", "Mailbox Threads", "mailbox_thread")
            .displayFieldName("subject")
            .addField(FieldDefinition.masterDetail("mailboxId", "mailboxes", "Mailbox")
                .withColumnName("mailbox_id")
                .withDescription("Mailbox this row belongs to."))
            .addField(FieldDefinition.string("subject", 500)
                .withDescription("Thread subject, taken from the first message."))
            .addField(FieldDefinition.requiredString("status", 24)
                .withDefault("OPEN")
                .withEnumValues(List.of("OPEN", "ASSIGNED", "WAITING_ON_CUSTOMER",
                                        "WAITING_ON_APPROVAL", "RESOLVED", "CLOSED",
                                        "SPAM", "ARCHIVED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.requiredString("priority", 10)
                .withDefault("NORMAL")
                .withEnumValues(List.of("LOW", "NORMAL", "HIGH", "URGENT"))
                .withDescription("Thread priority used for queue ordering."))
            .addField(FieldDefinition.lookup("assignedTo", "users", "Assigned To")
                .withColumnName("assigned_to")
                .withDescription("User currently responsible for this record."))
            .addField(FieldDefinition.requiredString("requesterEmail", 320)
                .withColumnName("requester_email")
                .withDescription("Email address of the person who opened the thread."))
            .addField(FieldDefinition.string("requesterName", 200)
                .withColumnName("requester_name")
                .withDescription("Display name of the person who opened the thread."))
            // The disclosure gate. False unless an identity was actually proven.
            .addField(FieldDefinition.bool("requesterVerified", false)
                .withColumnName("requester_verified")
                .withDescription("Whether the requester's identity has been verified."))
            .addField(FieldDefinition.string("verificationMethod", 20)
                .withColumnName("verification_method")
                .withEnumValues(List.of("DMARC_MATCH", "CHALLENGE", "MANUAL"))
                .withDescription("How the requester's identity was verified."))
            .addField(FieldDefinition.string("category", 60)
                .withDescription("Support category the thread was classified into."))
            .addField(FieldDefinition.doubleField("categoryConfidence")
                .withColumnName("category_confidence")
                .withDescription("Classifier confidence in the assigned category, 0-1."))
            .addField(FieldDefinition.integer("autoReplyCount")
                .withColumnName("auto_reply_count")
                .withDescription("Number of auto-replies already sent on this thread."))
            .addField(FieldDefinition.integer("messageCount").withColumnName("message_count")
                .withDescription("Number of messages on this thread."))
            .addField(FieldDefinition.datetime("lastMessageAt").withColumnName("last_message_at")
                .withDescription("When the most recent message arrived."))
            .addField(FieldDefinition.datetime("lastInboundAt").withColumnName("last_inbound_at")
                .withDescription("When the most recent inbound message arrived."))
            .addField(FieldDefinition.datetime("lastOutboundAt").withColumnName("last_outbound_at")
                .withDescription("When the most recent outbound message was sent."))
            .addField(FieldDefinition.datetime("firstResponseAt").withColumnName("first_response_at")
                .withDescription("When an agent first replied; stops the first-response SLA clock."))
            .addField(FieldDefinition.datetime("slaFirstResponseDueAt")
                .withColumnName("sla_first_response_due_at")
                .withDescription("Deadline for the first response."))
            .addField(FieldDefinition.requiredString("slaFirstResponseState", 10)
                .withDefault("NONE")
                .withColumnName("sla_first_response_state")
                .withEnumValues(List.of("NONE", "PENDING", "AT_RISK", "BREACHED", "MET"))
                .withDescription("First-response SLA state (on track, at risk, breached, met)."))
            .addField(FieldDefinition.datetime("slaResolutionDueAt")
                .withColumnName("sla_resolution_due_at")
                .withDescription("Deadline for resolving the thread."))
            .addField(FieldDefinition.requiredString("slaResolutionState", 10)
                .withDefault("NONE")
                .withColumnName("sla_resolution_state")
                .withEnumValues(List.of("NONE", "PENDING", "AT_RISK", "BREACHED", "MET"))
                .withDescription("Resolution SLA state (on track, at risk, breached, met)."))
            .addField(FieldDefinition.datetime("resolvedAt").withColumnName("resolved_at")
                .withDescription("When the thread was resolved; settles the resolution SLA clock."))
            .addField(FieldDefinition.datetime("closedAt").withColumnName("closed_at")
                .withDescription("When the record was closed."))
            .build();
    }

    public static CollectionDefinition mailboxMessages() {
        return readOnlySystemBuilder("mailbox-messages", "Mailbox Messages", "mailbox_message")
            .displayFieldName("subject")
            .addField(FieldDefinition.masterDetail("threadId", "mailbox-threads", "Thread")
                .withColumnName("thread_id")
                .withDescription("Mailbox thread this message belongs to."))
            .addField(FieldDefinition.requiredString("direction", 10)
                .withEnumValues(List.of("INBOUND", "OUTBOUND"))
                .withDescription("Whether the message is INBOUND or OUTBOUND."))
            .addField(FieldDefinition.requiredString("kind", 12)
                .withDefault("EMAIL")
                .withEnumValues(List.of("EMAIL", "NOTE", "SYSTEM"))
                .withDescription("Message kind, e.g. a customer reply or an internal note."))
            .addField(FieldDefinition.string("fromAddress", 320).withColumnName("from_address")
                .withDescription("Sender email address."))
            .addField(FieldDefinition.string("fromName", 200).withColumnName("from_name")
                .withDescription("Sender display name."))
            .addField(FieldDefinition.string("subject", 500)
                .withDescription("Subject line."))
            // Text only. bodyHtml is intentionally absent — see the block comment
            // above; it is third-party markup and must not reach a generic renderer.
            .addField(FieldDefinition.text("bodyText").withColumnName("body_text")
                .withDescription("Plain-text body."))
            .addField(FieldDefinition.string("snippet", 500)
                .withDescription("Short plain-text preview of the message body."))
            .addField(FieldDefinition.string("spfResult", 20).withColumnName("spf_result")
                .withDescription("SPF authentication result reported for the inbound message."))
            .addField(FieldDefinition.string("dkimResult", 20).withColumnName("dkim_result")
                .withDescription("DKIM authentication result reported for the inbound message."))
            .addField(FieldDefinition.string("dmarcResult", 20).withColumnName("dmarc_result")
                .withDescription("DMARC authentication result reported for the inbound message."))
            .addField(FieldDefinition.string("spamVerdict", 20).withColumnName("spam_verdict")
                .withDescription("Spam scanner verdict for the inbound message."))
            .addField(FieldDefinition.string("virusVerdict", 20).withColumnName("virus_verdict")
                .withDescription("Virus scanner verdict for the inbound message."))
            .addField(FieldDefinition.bool("isBulk", false).withColumnName("is_bulk")
                .withDescription("Whether the message was detected as bulk/marketing mail."))
            .addField(FieldDefinition.bool("isBounce", false).withColumnName("is_bounce")
                .withDescription("Whether the message is a bounce notification."))
            .addField(FieldDefinition.string("deliveryStatus", 20)
                .withColumnName("delivery_status")
                .withEnumValues(List.of("QUEUED", "SENT", "FAILED", "SUPPRESSED"))
                .withDescription("Delivery outcome reported for an outbound message."))
            .addField(FieldDefinition.datetime("sentAt").withColumnName("sent_at")
                .withDescription("When the message was sent."))
            .addField(FieldDefinition.datetime("receivedAt").withColumnName("received_at")
                .withDescription("When the message was received."))
            .build();
    }

    public static CollectionDefinition mailboxAttachments() {
        return readOnlySystemBuilder("mailbox-attachments", "Mailbox Attachments", "mailbox_attachment")
            .displayFieldName("filename")
            .addField(FieldDefinition.masterDetail("messageId", "mailbox-messages", "Message")
                .withColumnName("message_id")
                .withDescription("Mailbox message this row belongs to."))
            .addField(FieldDefinition.requiredString("filename", 500)
                .withDescription("Original file name of the attachment."))
            .addField(FieldDefinition.requiredString("contentType", 200)
                .withColumnName("content_type")
                .withDescription("MIME type of the stored content."))
            .addField(FieldDefinition.longField("sizeBytes").withColumnName("size_bytes")
                .withDescription("Attachment size in bytes."))
            .addField(FieldDefinition.string("contentId", 255).withColumnName("content_id")
                .withDescription("MIME Content-ID, set for inline attachments."))
            .addField(FieldDefinition.bool("inline", false)
                .withDescription("Whether the attachment is rendered inline in the message body."))
            .addField(FieldDefinition.string("storageKey", 500).withColumnName("storage_key")
                .withDescription("Object-storage key the binary is stored under."))
            .addField(FieldDefinition.string("checksumSha256", 64).withColumnName("checksum_sha256")
                .withDescription("SHA-256 checksum of the stored bytes."))
            .addField(FieldDefinition.requiredString("scanStatus", 20)
                .withDefault("UNKNOWN")
                .withColumnName("scan_status")
                .withEnumValues(List.of("UNKNOWN", "CLEAN", "INFECTED", "SKIPPED"))
                .withDescription("Malware-scan status of the stored attachment."))
            .build();
    }

    public static CollectionDefinition mailboxInboundEvents() {
        return readOnlySystemBuilder("mailbox-inbound-events", "Mailbox Inbound Events",
                                     "mailbox_inbound_event")
            .displayFieldName("id")
            .addField(FieldDefinition.masterDetail("mailboxId", "mailboxes", "Mailbox")
                .withColumnName("mailbox_id")
                .withDescription("Mailbox this row belongs to."))
            .addField(FieldDefinition.requiredString("provider", 30)
                .withDescription("Provider that delivered the inbound event."))
            .addField(FieldDefinition.string("providerEventId", 255)
                .withColumnName("provider_event_id")
                .withDescription("Provider's own event id, used to detect redeliveries."))
            .addField(FieldDefinition.requiredString("payloadDigest", 64)
                .withColumnName("payload_digest")
                .withDescription("Digest of the raw payload, used to detect duplicates."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("RECEIVED")
                .withEnumValues(List.of("RECEIVED", "PARSED", "ROUTED", "REJECTED",
                                        "DUPLICATE", "FAILED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.string("rejectReason", 200).withColumnName("reject_reason")
                .withDescription("Why the inbound event was rejected, when it was."))
            .addField(FieldDefinition.datetime("receivedAt").withColumnName("received_at")
                .withDescription("When the message was received."))
            .addField(FieldDefinition.datetime("processedAt").withColumnName("processed_at")
                .withDescription("When the event finished processing."))
            .build();
    }

    /**
     * Canned answers (support-mailbox slice 6).
     *
     * <p>The one WRITABLE collection in this feature. It is pure configuration with no side
     * effects on save — unlike a thread, whose status change has to settle an SLA clock — so it
     * follows {@code email-templates} rather than the read-only pattern used by the rest.
     *
     * <p>Writable does not mean unguarded: {@code MailboxTemplateGuardHook} refuses to mark a
     * template auto-sendable when its copy references anything but platform constants. That check
     * has to be a hook precisely because this collection is reachable through the generic JSON:API
     * route, where a controller check would not run.
     *
     * <p>The copy itself lives in {@code email_template}, referenced by {@code templateKey}. This
     * row holds only matching and policy, which is what keeps {@code autoSendEligible} off
     * {@code email_template} — otherwise every invoice notice would be one boolean away from being
     * sent unreviewed to whoever emailed support.
     */
    public static CollectionDefinition mailboxTemplates() {
        return systemBuilder("mailbox-templates", "Mailbox Templates", "mailbox_template")
            .displayFieldName("category")
            .addField(FieldDefinition.masterDetail("mailboxId", "mailboxes", "Mailbox")
                .withColumnName("mailbox_id")
                .withDescription("Mailbox this row belongs to."))
            .addField(FieldDefinition.requiredString("category", 60)
                .withDescription("Support category this template answers."))
            .addField(FieldDefinition.requiredString("templateKey", 200)
                .withColumnName("template_key")
                .withDescription("Stable key used to look the template up from code."))
            .addField(FieldDefinition.text("description")
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.json("matchKeywords").withColumnName("match_keywords")
                .withDescription("JSON keywords that make this template a candidate."))
            .addField(FieldDefinition.json("excludeKeywords").withColumnName("exclude_keywords")
                .withDescription("JSON keywords that disqualify this template."))
            .addField(FieldDefinition.integer("priority")
                .withDescription("Selection priority; the highest-priority matching template wins."))
            // Every automation default is off. A template is not auto-sendable because it matches
            // well; someone has to say so, and the guard hook decides whether they may.
            .addField(FieldDefinition.bool("autoSendEligible", false)
                .withColumnName("auto_send_eligible")
                .withDescription("Whether a reply from this template may be sent without agent review."))
            .addField(FieldDefinition.doubleField("minConfidence").withColumnName("min_confidence")
                .withDescription("Minimum classifier confidence required to use this template."))
            .addField(FieldDefinition.bool("requiresVerifiedSender", false)
                .withColumnName("requires_verified_sender")
                .withDescription("Whether this template may only be used for a verified sender."))
            .addField(FieldDefinition.bool("disclosesAccountData", false)
                .withColumnName("discloses_account_data")
                .withDescription("Whether the template's content includes account data."))
            .addField(FieldDefinition.bool("active", true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    // ------------------------------------------------------------------
    // Scheduling (telehealth slice 4, specs/telehealth/4-scheduling.md).
    // Like chat: no object-permission rows are seeded — /api/telehealth/**
    // (participant/provider-checked in-controller) is the product path.
    // ------------------------------------------------------------------

    public static CollectionDefinition telehealthAvailability() {
        return systemBuilder("telehealth-availability", "Telehealth Availability", "telehealth_availability")
            .displayFieldName("id")
            .addField(FieldDefinition.lookup("providerId", "users", "Provider")
                .withColumnName("provider_id")
                .withDescription("Provider (staff user) this row belongs to."))
            .addField(FieldDefinition.requiredString("kind", 20)
                .withDefault("RULE")
                .withEnumValues(List.of("RULE", "EXCEPTION"))
                .withDescription("Whether the row is a weekly pattern or a single-date exception."))
            .addField(FieldDefinition.integer("weekday")
                .withDescription("Day of week the rule applies to (0=Sunday)."))
            .addField(FieldDefinition.date("exceptionDate").withColumnName("exception_date")
                .withDescription("Specific date this row overrides the weekly pattern for."))
            // Wall-clock "HH:mm" / "HH:mm:ss" strings (varchar(8) columns since
            // V172); SlotService parses them with LocalTime.parse, so keep the
            // pattern in lockstep with what java.time accepts.
            .addField(FieldDefinition.string("startTime", 8).withColumnName("start_time")
                .withValidation(ValidationRules.forString(null, 8, "^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$"))
                .withDescription("Local start time of the availability window."))
            .addField(FieldDefinition.string("endTime", 8).withColumnName("end_time")
                .withValidation(ValidationRules.forString(null, 8, "^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$"))
                .withDescription("Local end time of the availability window."))
            .addField(FieldDefinition.requiredString("timezone", 50).withDefault("UTC")
                .withDescription("IANA timezone name (e.g. America/New_York)."))
            .addField(FieldDefinition.bool("closed").withDefault(false)
                .withDescription("Whether the provider is unavailable for the whole window."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    public static CollectionDefinition telehealthAppointments() {
        return systemBuilder("telehealth-appointments", "Telehealth Appointments", "telehealth_appointment")
            .displayFieldName("id")
            .addImmutableField("providerId")
            .addImmutableField("portalUserId")
            .addField(FieldDefinition.lookup("providerId", "users", "Provider")
                .withColumnName("provider_id")
                .withDescription("Provider (staff user) this row belongs to."))
            .addField(FieldDefinition.lookup("portalUserId", "users", "Portal User")
                .withColumnName("portal_user_id")
                .withDescription("Portal (external) user this row belongs to."))
            .addField(FieldDefinition.datetime("scheduledStart").withColumnName("scheduled_start")
                .withDescription("When the appointment is scheduled to start."))
            .addField(FieldDefinition.datetime("scheduledEnd").withColumnName("scheduled_end")
                .withDescription("When the appointment is scheduled to end."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("CONFIRMED")
                .withEnumValues(List.of("REQUESTED", "CONFIRMED", "CANCELLED", "COMPLETED", "NO_SHOW"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.string("visitType", 100).withColumnName("visit_type")
                .withDescription("Kind of visit being scheduled."))
            .addField(FieldDefinition.string("reason", 500)
                .withDescription("Patient-supplied reason for the visit."))
            .addField(FieldDefinition.lookup("conversationId", "chat-conversations", "Conversation")
                .withColumnName("conversation_id")
                .withDescription("Chat conversation this row belongs to."))
            .addField(FieldDefinition.string("videoSessionId", 36).withColumnName("video_session_id")
                .withDescription("Video session created for this appointment."))
            .addField(FieldDefinition.datetime("reminderSentAt").withColumnName("reminder_sent_at")
                .withDescription("When the appointment reminder was sent."))
            .addField(FieldDefinition.datetime("cancelledAt").withColumnName("cancelled_at")
                .withDescription("When the appointment was cancelled."))
            .build();
    }

    /** Video sessions (telehealth slice 5) — lifecycle owned by the LiveKit webhook. */
    public static CollectionDefinition videoSessions() {
        return systemBuilder("video-sessions", "Video Sessions", "video_session")
            .displayFieldName("id")
            .addImmutableField("roomName")
            .addField(FieldDefinition.lookup("appointmentId", "telehealth-appointments", "Appointment")
                .withColumnName("appointment_id")
                .withDescription("Appointment this row belongs to."))
            .addField(FieldDefinition.lookup("conversationId", "chat-conversations", "Conversation")
                .withColumnName("conversation_id")
                .withDescription("Chat conversation this row belongs to."))
            .addField(FieldDefinition.requiredString("roomName", 100)
                .withColumnName("room_name").withUnique(true)
                .withDescription("LiveKit room name the session runs in."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("CREATED")
                .withEnumValues(List.of("CREATED", "ACTIVE", "ENDED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.datetime("startedAt").withColumnName("started_at")
                .withDescription("When the video session became active."))
            .addField(FieldDefinition.datetime("endedAt").withColumnName("ended_at")
                .withDescription("When the session ended."))
            .addField(FieldDefinition.integer("durationSeconds").withColumnName("duration_seconds")
                .withDescription("Session length in seconds, stamped when it ends."))
            .addField(FieldDefinition.bool("recordingConsent").withColumnName("recording_consent")
                .withDefault(false)
                .withDescription("Whether all parties consented to recording."))
            .addField(FieldDefinition.string("recordingKey", 500).withColumnName("recording_key")
                .withDescription("Object-storage key of the session recording."))
            .build();
    }

    /**
     * Archived encounter records (telehealth slice 7) — one immutable row per
     * archived source (a closed chat conversation or an ended video session).
     * The artifact (canonical JSON transcript + PDF render) lives in S3 as
     * attachments owned by this row; {@code sha256} pins the JSON for tamper
     * evidence; {@code retentionUntil}/{@code legalHold} drive the purge sweep.
     */
    public static CollectionDefinition telehealthArchives() {
        return systemBuilder("telehealth-archives", "Telehealth Archives", "telehealth_archive")
            .displayFieldName("id")
            .addImmutableField("sourceType")
            .addImmutableField("sourceId")
            .addField(FieldDefinition.requiredString("sourceType", 20)
                .withColumnName("source_type")
                .withEnumValues(List.of("CONVERSATION", "VIDEO_SESSION"))
                .withDescription("Kind of object archived: a conversation or a video session."))
            .addField(FieldDefinition.requiredString("sourceId", 36).withColumnName("source_id")
                .withDescription("Id of the object this row was derived from."))
            .addField(FieldDefinition.lookup("appointmentId", "telehealth-appointments", "Appointment")
                .withColumnName("appointment_id")
                .withDescription("Appointment this row belongs to."))
            .addField(FieldDefinition.lookup("portalUserId", "users", "Portal User")
                .withColumnName("portal_user_id")
                .withDescription("Portal (external) user this row belongs to."))
            .addField(FieldDefinition.json("artifactAttachmentIds").withColumnName("artifact_attachment_ids")
                .withDescription("JSON array of attachment ids captured in this archive."))
            .addField(FieldDefinition.string("sha256", 64)
                .withDescription("SHA-256 digest of the archived artifacts."))
            .addField(FieldDefinition.datetime("archivedAt").withColumnName("archived_at")
                .withDescription("When the archive was written."))
            .addField(FieldDefinition.string("archivedBy", 64).withColumnName("archived_by")
                .withDescription("User or process that produced the archive."))
            .addField(FieldDefinition.datetime("retentionUntil").withColumnName("retention_until")
                .withDescription("Date before which the archive must not be purged."))
            .addField(FieldDefinition.bool("legalHold", false).withColumnName("legal_hold")
                .withDescription("Whether the archive is under legal hold and exempt from purge."))
            .addField(FieldDefinition.datetime("purgedAt").withColumnName("purged_at")
                .withDescription("When the archived artifacts were purged; the row survives as a tombstone."))
            .build();
    }

    /**
     * Billing plans a tenant offers its portal members (consumer-alerting slice 1).
     * {@code entitlements} is an OPAQUE tenant-defined map — the platform merges and
     * compares its values but never interprets the keys, so tenants add limits
     * without a schema change. {@code kind=DEFAULT} is the free/lapsed baseline.
     */
    public static CollectionDefinition billingPlans() {
        return systemBuilder("billing-plans", "Billing Plans", "billing_plan")
            .displayFieldName("name")
            .addImmutableField("code")
            .addField(FieldDefinition.requiredString("code", 100)
                .withDescription("Stable machine key for this plan."))
            .addField(FieldDefinition.requiredString("name", 255)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.text("description")
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("kind", 20)
                .withDefault("SUBSCRIPTION")
                .withEnumValues(List.of("SUBSCRIPTION", "ONE_TIME", "DEFAULT"))
                .withDescription("Whether the plan is a recurring subscription or a one-off pass."))
            .addField(FieldDefinition.string("stripeProductId", 100)
                .withColumnName("stripe_product_id")
                .withDescription("Stripe product id backing the plan."))
            .addField(FieldDefinition.string("stripePriceId", 100)
                .withColumnName("stripe_price_id")
                .withDescription("Stripe price id backing the plan."))
            .addField(FieldDefinition.json("entitlements").withDefault(Map.of())
                .withDescription("JSON entitlement map granted by the plan."))
            .addField(FieldDefinition.integer("passDurationDays")
                .withColumnName("pass_duration_days")
                .withDescription("How many days a one-off pass stays valid."))
            .addField(FieldDefinition.bool("active", true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.integer("sortOrder").withColumnName("sort_order")
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .build();
    }

    /**
     * Quota enforcement as configuration (consumer-alerting slice 1): "records in
     * collection X are capped by entitlement key Y". The generic member-quota hook
     * reads these rows, so capping a new collection needs no new code.
     */
    public static CollectionDefinition billingEntitlementRules() {
        return systemBuilder("billing-entitlement-rules", "Billing Entitlement Rules",
                "billing_entitlement_rule")
            .displayFieldName("limitKey")
            .addField(FieldDefinition.requiredString("collectionName", 100)
                .withColumnName("collection_name")
                .withDescription("Name of the collection this row applies to."))
            .addField(FieldDefinition.requiredString("limitKey", 100)
                .withColumnName("limit_key")
                .withDescription("Entitlement key this rule enforces."))
            .addField(FieldDefinition.json("countFilter").withColumnName("count_filter")
                .withDescription("JSON filter selecting the rows counted against the limit."))
            .addField(FieldDefinition.requiredString("appliesTo", 20)
                .withColumnName("applies_to")
                .withDefault("PORTAL")
                .withEnumValues(List.of("PORTAL", "ALL"))
                .withDescription("Which subjects the rule applies to."))
            .addField(FieldDefinition.string("message", 500)
                .withDescription("Message shown when the rule denies the action."))
            .addField(FieldDefinition.bool("active", true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    /**
     * Something watchable — a campsite, an interview slot pool, a race, a permit
     * (consumer-alerting slice 3). Identified by {@code (source, externalId)}, so
     * two sources that happen to reuse the same upstream id cannot collide; that
     * pair is what an external poller's report resolves against.
     *
     * <p>{@code metadata} is opaque per-source extras (upstream ids, grouping,
     * optional geo) — the platform stores and returns it without interpreting it.
     */
    public static CollectionDefinition watchTargets() {
        return systemBuilder("watch-targets", "Watch Targets", "watch_target")
            .displayFieldName("name")
            .addImmutableField("source")
            .addImmutableField("externalId")
            .addField(FieldDefinition.requiredString("source", 50)
                .withDescription("External system the target is polled from."))
            .addField(FieldDefinition.requiredString("externalId", 200)
                .withColumnName("external_id")
                .withDescription("Identifier for this target in the source system."))
            .addField(FieldDefinition.requiredString("name", 255)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("category", 50)
                .withDescription("Category the target is grouped under."))
            .addField(FieldDefinition.json("metadata").withDefault(Map.of())
                .withDescription("Free-form JSON metadata."))
            .addField(FieldDefinition.bool("active", true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    /**
     * A member's standing interest in a target (consumer-alerting slice 3).
     *
     * <p>{@code criteria} holds the match predicate
     * ({@code {dateStart, dateEnd, quantity?, minDuration?}}); the matcher pushes
     * the date-range overlap into SQL and evaluates the rest in Java, so this
     * shape must stay stable — see {@code WatchCriteria}, which versions it.
     * {@code channels} is the subset of the member's entitled alert channels.
     *
     * <p>Written through the slice-5 owner-scoped controller; the generic route is
     * owner-guarded there too.
     */
    public static CollectionDefinition watches() {
        return systemBuilder("watches", "Watches", "watch")
            .displayFieldName("id")
            .addField(FieldDefinition.lookup("memberId", "users", "Member")
                .withColumnName("member_id")
                .withDescription("Portal member this row belongs to."))
            .addField(FieldDefinition.lookup("targetId", "watch-targets", "Target")
                .withColumnName("target_id")
                .withDescription("Id of the object this row refers to."))
            .addField(FieldDefinition.json("criteria").withDefault(Map.of())
                .withDescription("JSON match criteria the watch alerts on."))
            .addField(FieldDefinition.json("channels").withDefault(List.of())
                .withDescription("JSON delivery channels alerts are sent over."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("ACTIVE")
                .withEnumValues(List.of("ACTIVE", "PAUSED", "EXPIRED", "FULFILLED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.datetime("expiresAt").withColumnName("expires_at")
                .withDescription("When the watch stops alerting."))
            .build();
    }

    /**
     * The questions a consumer product's users ask, plus lightweight usage/acquisition
     * events (consumer-alerting slice 8). Read-only over the generic API — rows are written
     * only by the capture paths ({@code SearchController} auto-capture and the authenticated
     * {@code POST /api/analytics/events} ingest), never through generic CRUD.
     *
     * <p>The corpus behind the demand-mining loop (parent §5.2) and the later Q&amp;A/SEO page
     * generation and demand clustering. High-volume by design: age-based retention ships in the
     * same slice ({@code AnalyticsRetentionSweep}).
     *
     * <p>{@code eventType} has no DB CHECK on purpose — a new client event kind must not need a
     * migration. {@code matchedTargetId}/{@code memberId} are plain ids with NO FK: analytics
     * history outlives the target/user it references, and a deletion is honored by the
     * retention/erasure path rather than a cascade. Geo is coarse (country/region) only.
     */
    public static CollectionDefinition analyticsEvents() {
        return readOnlySystemBuilder("analytics-events", "Analytics Events", "analytics_event")
            .tenantScoped(true)
            .displayFieldName("eventType")
            .addField(FieldDefinition.requiredString("eventType", 30)
                .withColumnName("event_type")
                .withDescription("Kind of analytics event, e.g. search, view or click."))
            .addField(FieldDefinition.text("query")
                .withDescription("Search text the member entered."))
            .addField(FieldDefinition.bool("zeroResult")
                .withColumnName("zero_result")
                .withDescription("Whether the search returned no results."))
            .addField(FieldDefinition.string("matchedTargetId", 36)
                .withColumnName("matched_target_id")
                .withDescription("Watch target the event resolved to, when it resolved."))
            .addField(FieldDefinition.string("path", 500)
                .withDescription("Path of the page the event was recorded on."))
            .addField(FieldDefinition.string("referrer", 500)
                .withDescription("Referring URL of the request."))
            .addField(FieldDefinition.json("utm").withDefault(Map.of())
                .withDescription("JSON UTM campaign parameters from the request."))
            .addField(FieldDefinition.string("sessionId", 64)
                .withColumnName("session_id")
                .withDescription("Opaque browser session identifier."))
            .addField(FieldDefinition.string("memberId", 36)
                .withColumnName("member_id")
                .withDescription("Portal member this row belongs to."))
            .addField(FieldDefinition.string("geoCountry", 2)
                .withColumnName("geo_country")
                .withDescription("ISO country code resolved from the request IP."))
            .addField(FieldDefinition.string("geoRegion", 80)
                .withColumnName("geo_region")
                .withDescription("Region/state resolved from the request IP."))
            .addField(FieldDefinition.json("metadata").withDefault(Map.of())
                .withDescription("Free-form JSON metadata."))
            .addField(FieldDefinition.datetime("occurredAt")
                .withColumnName("occurred_at")
                .withDescription("When the event happened on the client."))
            .build();
    }

    /**
     * A member's confirmed win — "I got the spot" (consumer-alerting slice 9). The
     * social-proof + retention engine: claim confirmations feed per-target success stats and
     * the live-wins ticker.
     *
     * <p>Written through the owner-scoped {@code WinController} (generic route owner-guarded by
     * {@code WinGuardHook}). {@code targetId}/{@code watchId}/{@code alertId} are plain ids with
     * NO FK — a win outlives the target/watch/alert it references, and the alert ledger is
     * pruned by retention. {@code isPublic} gates whether a win appears on the ticker;
     * {@code claimantName} is a server-set FIRST NAME only (never more) so the public feed can
     * name a claimant without exposing member PII.
     */
    public static CollectionDefinition wins() {
        return systemBuilder("wins", "Wins", "win")
            .displayFieldName("summary")
            .addField(FieldDefinition.lookup("memberId", "users", "Member")
                .withColumnName("member_id")
                .withDescription("Portal member this row belongs to."))
            .addField(FieldDefinition.string("targetId", 36)
                .withColumnName("target_id")
                .withDescription("Id of the object this row refers to."))
            .addField(FieldDefinition.string("watchId", 36)
                .withColumnName("watch_id")
                .withDescription("Watch that produced this row."))
            .addField(FieldDefinition.string("alertId", 36)
                .withColumnName("alert_id")
                .withDescription("Alert that produced this row."))
            .addField(FieldDefinition.string("category", 50)
                .withDescription("Category the win is grouped under."))
            .addField(FieldDefinition.requiredString("summary", 280)
                .withDescription("One-line public summary of the win."))
            .addField(FieldDefinition.integer("quantity")
                .withDescription("Number of units involved."))
            .addField(FieldDefinition.bool("isPublic", false)
                .withColumnName("is_public")
                .withDescription("Whether the row may be shown on public surfaces."))
            .addField(FieldDefinition.string("claimantName", 80)
                .withColumnName("claimant_name")
                .withDescription("Display name shown publicly for the claimant."))
            .addField(FieldDefinition.datetime("claimedAt")
                .withColumnName("claimed_at")
                .withDescription("When the win was claimed."))
            .build();
    }

    /**
     * Per-target generated stat block for the SEO/content engine (consumer-alerting slice 11).
     * Read-only — rows are written only by the nightly {@code SeoPageGenerationService}, never
     * through generic CRUD.
     *
     * <p><b>Aggregate only — deliberately no member data.</b> A page carries the target's public
     * metadata (name, category) plus AGGREGATE counts ({@code watcherCount}, {@code winCount},
     * {@code lastWinAt}). It is the one collection safe to read with a build-time service token,
     * which is how a static content site renders these pages — there is still no anonymous bulk
     * API (parent Key Decisions). {@code published} gates the §6.3 quality guardrail: a page with
     * too little real data behind it stays unpublished (noindex) rather than becoming thin
     * programmatic spam.
     */
    public static CollectionDefinition seoPages() {
        return readOnlySystemBuilder("seo-pages", "SEO Pages", "seo_page")
            .tenantScoped(true)
            .displayFieldName("title")
            .addField(FieldDefinition.string("targetId", 36)
                .withColumnName("target_id")
                .withDescription("Id of the object this row refers to."))
            .addField(FieldDefinition.requiredString("slug", 200)
                .withDescription("URL-safe identifier used in paths."))
            .addField(FieldDefinition.requiredString("title", 255)
                .withDescription("Page title used in the document head and listings."))
            .addField(FieldDefinition.string("category", 50)
                .withDescription("Category the page is grouped under."))
            .addField(FieldDefinition.integer("watcherCount")
                .withColumnName("watcher_count")
                .withDescription("Number of members watching this target."))
            .addField(FieldDefinition.integer("winCount")
                .withColumnName("win_count")
                .withDescription("Number of wins recorded for this target."))
            .addField(FieldDefinition.datetime("lastWinAt")
                .withColumnName("last_win_at")
                .withDescription("When the most recent win was recorded."))
            .addField(FieldDefinition.json("stats").withDefault(Map.of())
                .withDescription("JSON aggregate statistics rendered on the page."))
            .addField(FieldDefinition.bool("published", false)
                .withDescription("Whether the record is visible to end users."))
            .addField(FieldDefinition.datetime("generatedAt")
                .withColumnName("generated_at")
                .withDescription("When the page content was last generated."))
            .build();
    }

    /**
     * Member-to-processor customer mapping (consumer-alerting slice 1). Read-only:
     * rows are written only by the verified webhook and checkout paths.
     */
    public static CollectionDefinition billingCustomers() {
        return readOnlySystemBuilder("billing-customers", "Billing Customers",
                "billing_customer")
            .tenantScoped(true)
            .displayFieldName("email")
            .addField(FieldDefinition.lookup("userId", "users", "Member")
                .withColumnName("user_id")
                .withDescription("User this row belongs to."))
            .addField(FieldDefinition.requiredString("stripeCustomerId", 100)
                .withColumnName("stripe_customer_id")
                .withDescription("Stripe customer id."))
            .addField(FieldDefinition.string("email", 255)
                .withDescription("Email recorded on the Stripe customer."))
            .build();
    }

    /**
     * Mirrored subscription state (consumer-alerting slice 1). {@code status} holds
     * the processor's own vocabulary verbatim rather than a platform enum, so an
     * unrecognized status degrades to "not entitled" instead of dropping the event.
     */
    public static CollectionDefinition billingSubscriptions() {
        return readOnlySystemBuilder("billing-subscriptions", "Billing Subscriptions",
                "billing_subscription")
            .tenantScoped(true)
            .displayFieldName("stripeSubscriptionId")
            .addField(FieldDefinition.lookup("userId", "users", "Member")
                .withColumnName("user_id")
                .withDescription("User this row belongs to."))
            .addField(FieldDefinition.lookup("planId", "billing-plans", "Plan")
                .withColumnName("plan_id")
                .withDescription("Billing plan this row refers to."))
            .addField(FieldDefinition.requiredString("stripeSubscriptionId", 100)
                .withColumnName("stripe_subscription_id")
                .withDescription("Stripe subscription id."))
            .addField(FieldDefinition.string("stripeCustomerId", 100)
                .withColumnName("stripe_customer_id")
                .withDescription("Stripe customer id."))
            .addField(FieldDefinition.requiredString("status", 40)
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.datetime("currentPeriodEnd")
                .withColumnName("current_period_end")
                .withDescription("End of the current paid period."))
            .addField(FieldDefinition.bool("cancelAtPeriodEnd", false)
                .withColumnName("cancel_at_period_end")
                .withDescription("Whether the subscription ends when the current period does."))
            .addField(FieldDefinition.datetime("canceledAt").withColumnName("canceled_at")
                .withDescription("When the subscription was cancelled."))
            .build();
    }

    /**
     * One-time passes (consumer-alerting slice 1) — a bounded window of elevated
     * entitlements. Expired passes are ignored at resolution time regardless of
     * row status; the expiry sweep only tidies the stored status.
     */
    public static CollectionDefinition billingPasses() {
        return readOnlySystemBuilder("billing-passes", "Billing Passes", "billing_pass")
            .tenantScoped(true)
            .displayFieldName("stripeCheckoutSessionId")
            .addField(FieldDefinition.lookup("userId", "users", "Member")
                .withColumnName("user_id")
                .withDescription("User this row belongs to."))
            .addField(FieldDefinition.lookup("planId", "billing-plans", "Plan")
                .withColumnName("plan_id")
                .withDescription("Billing plan this row refers to."))
            .addField(FieldDefinition.requiredString("stripeCheckoutSessionId", 100)
                .withColumnName("stripe_checkout_session_id")
                .withDescription("Stripe checkout session that created the pass."))
            .addField(FieldDefinition.string("stripePaymentIntentId", 100)
                .withColumnName("stripe_payment_intent_id")
                .withDescription("Stripe payment intent that paid for the pass."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("ACTIVE")
                .withEnumValues(List.of("ACTIVE", "EXPIRED", "REFUNDED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.datetime("startsAt").withColumnName("starts_at")
                .withDescription("When the pass becomes valid."))
            .addField(FieldDefinition.datetime("expiresAt").withColumnName("expires_at")
                .withDescription("When this record stops being valid."))
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

    // =========================================================================
    // UI & Layout Collections
    // =========================================================================

    public static CollectionDefinition pageLayouts() {
        return systemBuilder("page-layouts", "Page Layouts", "page_layout")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Name of the layout, shown in the layout picker."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.string("layoutType", 20).withColumnName("layout_type")
                .withDefault("DETAIL")
                .withEnumValues(List.of("DETAIL", "EDIT", "MINI", "LIST"))
                .withDescription("Which record surface the layout drives: DETAIL, EDIT, MINI or LIST."))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default")
                .withDefault(false)
                .withDescription("Whether this is the collection's default layout for its layoutType."))
            .addField(FieldDefinition.json("defaultFilter").withColumnName("default_filter")
                .withDescription("JSON default filter for related lists rendered under this layout."))
            .addField(FieldDefinition.string("defaultSortField", 100).withColumnName("default_sort_field")
                .withDescription("Default sort field for related lists rendered under this layout."))
            .addField(FieldDefinition.string("defaultSortDirection", 4).withColumnName("default_sort_direction")
                .withDefault("ASC")
                .withEnumValues(List.of("ASC", "DESC"))
                .withDescription("Default sort direction for related lists under this layout."))
            .addField(FieldDefinition.integer("defaultRowLimit").withColumnName("default_row_limit")
                .withDefault(50)
                .withDescription("Default row cap for related lists rendered under this layout."))
            .addField(FieldDefinition.json("headerConfig").withColumnName("header_config")
                .withDescription("JSON record header config: titleFields, avatarFrom, metaFields."))
            .addField(FieldDefinition.json("railBlocks").withColumnName("rail_blocks")
                .withDescription("JSON array of side-rail widgets, each with a kind discriminator."))
            .build();
    }

    public static CollectionDefinition layoutAssignments() {
        return systemBuilder("layout-assignments", "Layout Assignments", "layout_assignment")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("profileId", 36).withColumnName("profile_id")
                .withDescription("Profile this row applies to."))
            .addField(FieldDefinition.string("recordTypeId", 36).withColumnName("record_type_id")
                .withDescription("Record type this row applies to."))
            .addField(FieldDefinition.masterDetail("layoutId", "page-layouts", "Layout")
                .withColumnName("layout_id")
                .withDescription("Page layout this row belongs to."))
            .addField(FieldDefinition.json("condition")
                .withDescription("JSON condition that must hold for this assignment to apply."))
            .addField(FieldDefinition.integer("evaluationOrder").withColumnName("evaluation_order")
                .withDefault(100).withNullable(false)
                .withDescription("Ordinal evaluation order; the first matching assignment wins."))
            .build();
    }

    public static CollectionDefinition listViews() {
        return systemBuilder("list-views", "List Views", "list_view")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id")
                .withDescription("Collection this row belongs to."))
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("visibility", 20).withDefault("PRIVATE")
                .withEnumValues(List.of("PRIVATE", "PUBLIC", "GROUP"))
                .withDescription("Who may use the view: PRIVATE, PUBLIC or GROUP."))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default")
                .withDescription("Whether this is the default row for its scope (convention, not enforced as unique)."))
            .addField(FieldDefinition.requiredJson("columns")
                .withDescription("JSON array of field names rendered as columns."))
            .addField(FieldDefinition.string("filterLogic").withColumnName("filter_logic")
                .withDescription("Boolean expression combining the numbered filters, e.g. '1 AND (2 OR 3)'."))
            // JSON-typed defaults must be JSON containers, not strings — a String
            // default is injected verbatim on create and stored as a JSON string
            // ("[]"), not an array, breaking every consumer of the field
            // (guarded by SystemCollectionJsonDefaultsTest).
            .addField(FieldDefinition.json("filters").withDefault(List.of())
                .withDescription("JSON filter conditions applied to the query."))
            .addField(FieldDefinition.string("sortField").withColumnName("sort_field")
                .withDescription("Field name results are sorted by."))
            .addField(FieldDefinition.string("sortDirection", 4).withColumnName("sort_direction")
                .withDefault("ASC")
                .withEnumValues(List.of("ASC", "DESC"))
                .withDescription("Sort direction: ASC or DESC."))
            .addField(FieldDefinition.json("sort")
                .withDescription("JSON multi-field sort specification."))
            .addField(FieldDefinition.integer("rowLimit").withColumnName("row_limit")
                .withDefault(50)
                .withDescription("Maximum number of rows rendered."))
            .addField(FieldDefinition.json("chartConfig").withColumnName("chart_config")
                .withDescription("JSON chart rendering options."))
            // Renderer for the shared view (V196). Mirrors the per-user SavedView
            // fields so an admin can publish a board, not just a column set. The
            // enum values are also a CHECK constraint on the column.
            .addField(FieldDefinition.enumField("viewType",
                    List.of("TABLE", "KANBAN", "CALENDAR", "GALLERY"))
                .withColumnName("view_type").withDefault("TABLE").withNullable(false)
                .withDescription("Renderer published with the view: TABLE, KANBAN, CALENDAR or GALLERY."))
            .addField(FieldDefinition.json("typeConfig").withColumnName("type_config")
                .withDescription("JSON per-renderer settings, e.g. kanban lane and card fields."))
            .build();
    }

    /**
     * Per-user UI preferences (saved list views, favorites, recents). One row per
     * (userId, prefType, prefKey); writes are owner-guarded by the worker's
     * UserPreferenceGuardHook — any tenant user may otherwise reach this collection
     * through the generic route.
     */
    public static CollectionDefinition userUiPreferences() {
        return systemBuilder("user-ui-preferences", "User UI Preferences", "user_ui_preference")
            .displayFieldName("prefKey")
            .addField(FieldDefinition.requiredString("userId", 36)
                .withColumnName("user_id")
                .withDescription("User this row belongs to."))
            .addField(FieldDefinition.requiredString("prefType", 30)
                .withColumnName("pref_type")
                .withDescription("Which kind of preference this row stores."))
            .addField(FieldDefinition.requiredString("prefKey", 200)
                .withColumnName("pref_key").withDefault("-")
                .withDescription("Key identifying the preference within its type."))
            .addField(FieldDefinition.requiredJson("value")
                .withDescription("JSON preference payload."))
            .build();
    }

    /**
     * Tenant-authored UI translations (app-intelligence slice 4): a per-locale
     * key/value overlay over the static FE bundles. Unique per
     * (tenant, locale, key) — V165.
     */
    public static CollectionDefinition uiTranslations() {
        return systemBuilder("ui-translations", "UI Translations", "ui_translation")
            .displayFieldName("key")
            .addField(FieldDefinition.requiredString("locale", 10)
                .withDescription("Locale this translation applies to."))
            .addField(FieldDefinition.requiredString("key", 200)
                .withColumnName("translation_key")
                .withDescription("Translation key being overridden."))
            .addField(FieldDefinition.requiredString("value", 2000)
                .withColumnName("translation_value")
                .withDescription("Translated string for the key in this locale."))
            .build();
    }

    public static CollectionDefinition uiPages() {
        return systemBuilder("ui-pages", "UI Pages", "ui_page")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.requiredString("path", 200)
                .withDescription("Route the page is served at."))
            .addField(FieldDefinition.string("slug", 200)
                .withDescription("URL-safe identifier used in paths."))
            .addField(FieldDefinition.string("title", 200)
                .withDescription("Browser and header title of the page."))
            .addField(FieldDefinition.json("config")
                .withDescription("JSON page definition: layout, components and bindings."))
            .addField(FieldDefinition.bool("active").withDefault(true).withNullable(false)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.bool("published").withDefault(false).withNullable(false)
                .withDescription("Whether the page is live for end users; drafts stay hidden."))
            .build();
    }

    public static CollectionDefinition uiMenus() {
        return systemBuilder("ui-menus", "UI Menus", "ui_menu")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.integer("displayOrder").withColumnName("display_order")
                .withDefault(0)
                .withDescription("Ordinal position among sibling rows (ascending)."))
            // Apps (nav v2): a menu is the "app" unit — switcher icon, default app,
            // and an active flag that hides the app from the end-user shell (V164).
            .addField(FieldDefinition.string("icon", 100)
                .withDescription("Icon name rendered beside the label."))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default")
                .withDefault(false).withNullable(false)
                .withDescription("Whether this is the default row for its scope (convention, not enforced as unique)."))
            .addField(FieldDefinition.bool("active").withDefault(true).withNullable(false)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    // =========================================================================
    // Picklist Collections
    // =========================================================================

    public static CollectionDefinition globalPicklists() {
        return systemBuilder("global-picklists", "Global Picklists", "global_picklist")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.bool("sorted").withDefault(false)
                .withDescription("Whether values are presented in alphabetical order."))
            .addField(FieldDefinition.bool("restricted").withDefault(true)
                .withDescription("Whether values outside the picklist are rejected."))
            .build();
    }

    public static CollectionDefinition picklistValues() {
        return systemBuilder("picklist-values", "Picklist Values", "picklist_value")
            .displayFieldName("label")
            .tenantScoped(true)
            .addField(FieldDefinition.requiredString("picklistSourceType", 20)
                .withColumnName("picklist_source_type")
                .withEnumValues(List.of("FIELD", "GLOBAL"))
                .withDescription("Whether the value belongs to a global picklist or a single field."))
            .addField(FieldDefinition.requiredString("picklistSourceId", 36)
                .withColumnName("picklist_source_id")
                .withDescription("Id of the global picklist or field the value belongs to."))
            .addField(FieldDefinition.requiredString("value", 255)
                .withDescription("Value written to the record when this entry is chosen."))
            .addField(FieldDefinition.requiredString("label", 255)
                .withDescription("Display label shown in the UI."))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default")
                .withDescription("Whether this is the default row for its scope (convention, not enforced as unique)."))
            .addField(FieldDefinition.bool("isActive").withColumnName("is_active")
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.integer("sortOrder").withColumnName("sort_order")
                .withDefault(0)
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .addField(FieldDefinition.string("color", 20)
                .withDescription("Hex color used when the value is rendered as a badge."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .build();
    }

    // =========================================================================
    // Record Types & Validation
    // =========================================================================

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

    // =========================================================================
    // Automation Collections
    // =========================================================================

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

    // =========================================================================
    // Communication Collections
    // =========================================================================

    public static CollectionDefinition emailTemplates() {
        return systemBuilder("email-templates", "Email Templates", "email_template")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("subject", 500)
                .withDescription("Subject line."))
            .addField(FieldDefinition.requiredText("bodyHtml").withColumnName("body_html")
                .withDescription("HTML body."))
            .addField(FieldDefinition.text("bodyText").withColumnName("body_text")
                .withDescription("Plain-text body."))
            .addField(FieldDefinition.string("relatedCollectionId")
                .withColumnName("related_collection_id")
                .withDescription("Related collection this row points at."))
            .addField(FieldDefinition.string("folder")
                .withDescription("Folder the template is filed under."))
            // The stable key the platform resolves copy by (EmailRepository.findTemplateByKey,
            // with tenant -> 'system' fallback). Support-mailbox templates reference their copy
            // through it, so without this field their copy could only be authored by SQL.
            .addField(FieldDefinition.string("templateKey").withColumnName("template_key")
                .withDescription("Stable key used to look the template up from code."))
            .addField(FieldDefinition.bool("isActive").withColumnName("is_active")
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            // PR 5 — payload mapper integration
            .addField(FieldDefinition.json("variablesSchema").withColumnName("variables_schema")
                .withDescription("JSON schema of the merge variables the template expects."))
            .addField(FieldDefinition.lookup("smtpCredentialId", "credentials", "SMTP Credential")
                .withColumnName("smtp_credential_id")
                .withDescription("Credential used to send mail for this template."))
            .build();
    }

    /**
     * Mass-email campaigns. Read-only over the generic API — all mutations and the
     * schedule/send/cancel actions go through {@code CampaignAdminController}, which enforces
     * {@code MANAGE_CAMPAIGNS} and the daily send governor limit. Aggregate stat columns
     * (sent/open/click/unsubscribe/failed) are maintained by the campaign runner.
     */
    public static CollectionDefinition campaigns() {
        return readOnlySystemBuilder("campaigns", "Email Campaigns", "email_campaign")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("subject", 500)
                .withDescription("Subject line."))
            .addField(FieldDefinition.text("bodyHtml").withColumnName("body_html")
                .withDescription("HTML body."))
            .addField(FieldDefinition.lookup("templateId", "email-templates", "Email Template")
                .withColumnName("template_id")
                .withDescription("Email template used."))
            .addField(FieldDefinition.requiredString("targetCollection", 100)
                .withColumnName("target_collection")
                .withDescription("Collection the campaign's recipients are drawn from."))
            .addField(FieldDefinition.requiredString("recipientEmailField", 100)
                .withColumnName("recipient_email_field")
                .withDescription("Field on the target collection holding the recipient address."))
            .addField(FieldDefinition.json("filterJson").withColumnName("filter_json")
                .withDescription("JSON filter narrowing the recipient set."))
            .addField(FieldDefinition.string("listViewId", 36).withColumnName("list_view_id")
                .withDescription("List view whose filters select the recipients."))
            .addField(FieldDefinition.string("fromName", 200).withColumnName("from_name")
                .withDescription("Sender display name."))
            .addField(FieldDefinition.string("fromAddress", 320).withColumnName("from_address")
                .withDescription("Sender email address."))
            .addField(FieldDefinition.requiredString("status", 20).withDefault("DRAFT")
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.datetime("scheduledAt").withColumnName("scheduled_at")
                .withDescription("When the campaign is scheduled to send."))
            .addField(FieldDefinition.integer("totalRecipients").withColumnName("total_recipients")
                .withDescription("Number of recipients the campaign resolved to."))
            .addField(FieldDefinition.integer("sentCount").withColumnName("sent_count")
                .withDescription("Number of messages sent."))
            .addField(FieldDefinition.integer("failedCount").withColumnName("failed_count")
                .withDescription("Number of messages that failed to send."))
            .addField(FieldDefinition.integer("openCount").withColumnName("open_count")
                .withDescription("Number of tracked email opens."))
            .addField(FieldDefinition.integer("clickCount").withColumnName("click_count")
                .withDescription("Number of tracked link clicks."))
            .addField(FieldDefinition.integer("unsubscribeCount").withColumnName("unsubscribe_count")
                .withDescription("Number of recipients who unsubscribed."))
            .addField(FieldDefinition.datetime("startedAt").withColumnName("started_at")
                .withDescription("When execution started."))
            .addField(FieldDefinition.datetime("completedAt").withColumnName("completed_at")
                .withDescription("When execution finished."))
            .addField(FieldDefinition.text("errorMessage").withColumnName("error_message")
                .withDescription("Failure detail recorded when the run did not succeed."))
            .build();
    }

    /** Per-recipient send + tracking rows for a campaign (read-only; written by the runner). */
    public static CollectionDefinition campaignRecipients() {
        return readOnlySystemBuilder("campaign-recipients", "Campaign Recipients",
                "email_campaign_recipient")
            .displayFieldName("email")
            .addField(FieldDefinition.lookup("campaignId", "campaigns", "Campaign")
                .withColumnName("campaign_id")
                .withDescription("Campaign this row belongs to."))
            .addField(FieldDefinition.string("recordId", 36).withColumnName("record_id")
                .withDescription("Id of the record this row refers to."))
            .addField(FieldDefinition.requiredString("email", 320)
                .withDescription("Address this recipient row was sent to."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.text("errorMessage").withColumnName("error_message")
                .withDescription("Failure detail recorded when the run did not succeed."))
            .addField(FieldDefinition.string("emailLogId", 36).withColumnName("email_log_id")
                .withDescription("Email log row recording the delivery attempt."))
            .addField(FieldDefinition.integer("openCount").withColumnName("open_count")
                .withDescription("Number of tracked email opens."))
            .addField(FieldDefinition.integer("clickCount").withColumnName("click_count")
                .withDescription("Number of tracked link clicks."))
            .addField(FieldDefinition.datetime("sentAt").withColumnName("sent_at")
                .withDescription("When the message was sent."))
            .addField(FieldDefinition.datetime("openedAt").withColumnName("opened_at")
                .withDescription("When the recipient first opened the message."))
            .addField(FieldDefinition.datetime("clickedAt").withColumnName("clicked_at")
                .withDescription("When the recipient first clicked a tracked link."))
            .addField(FieldDefinition.datetime("unsubscribedAt").withColumnName("unsubscribed_at")
                .withDescription("When the recipient unsubscribed."))
            .build();
    }

    /** Per-tenant unsubscribe / suppression list — a match here blocks all future campaign sends. */
    public static CollectionDefinition emailSuppressions() {
        return readOnlySystemBuilder("email-suppressions", "Email Suppressions", "email_suppression")
            .displayFieldName("email")
            .addField(FieldDefinition.requiredString("email", 320)
                .withDescription("Suppressed address; mail to it is never sent."))
            .addField(FieldDefinition.requiredString("reason", 30).withDefault("UNSUBSCRIBE")
                .withDescription("Why the address is suppressed (bounce, complaint, manual)."))
            .addField(FieldDefinition.lookup("campaignId", "campaigns", "Campaign")
                .withColumnName("campaign_id")
                .withDescription("Campaign this row belongs to."))
            .build();
    }

    // =========================================================================
    // Integration Collections
    // =========================================================================

    public static CollectionDefinition connectedApps() {
        return systemBuilder("connected-apps", "Connected Apps", "connected_app")
            .displayFieldName("name")
            .addImmutableField("clientId")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("clientId", 100)
                .withColumnName("client_id").withUnique(true)
                .withDescription("OAuth client identifier."))
            .addField(FieldDefinition.requiredString("clientSecretHash", 200)
                .withColumnName("client_secret_hash").withImmutable(true)
                .withDescription("Hash of the client secret; the secret itself is shown once at creation."))
            .addField(FieldDefinition.json("redirectUris").withColumnName("redirect_uris")
                .withDescription("JSON array of permitted OAuth redirect URIs."))
            .addField(FieldDefinition.json("scopes")
                .withDescription("Space-separated OAuth scopes."))
            .addField(FieldDefinition.json("ipRestrictions").withColumnName("ip_restrictions")
                .withDescription("JSON array of CIDR ranges the app may call from."))
            .addField(FieldDefinition.integer("rateLimitPerHour")
                .withColumnName("rate_limit_per_hour").withDefault(10000)
                .withDescription("Maximum API calls this app may make per hour."))
            .addField(FieldDefinition.bool("active")
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.datetime("lastUsedAt").withColumnName("last_used_at")
                .withDescription("When the app last called the API."))
            .addField(FieldDefinition.json("grantTypes").withColumnName("grant_types")
                .withDescription("JSON array of OAuth grant types the app may use."))
            .addField(FieldDefinition.bool("requirePkce").withColumnName("require_pkce")
                .withDescription("Whether the authorization-code flow must use PKCE."))
            .addField(FieldDefinition.bool("consentRequired").withColumnName("consent_required")
                .withDescription("Whether the user must consent before tokens are issued."))
            .build();
    }

    public static CollectionDefinition credentials() {
        return systemBuilder("credentials", "Credentials", "credential")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("displayName", 200).withColumnName("display_name")
                .withDescription("Label shown in the UI instead of the raw name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("type", 50)
                .withDescription("Credential type, which decides the shape of the encrypted material."))
            .addField(FieldDefinition.string("providerTemplate", 100)
                .withColumnName("provider_template")
                .withDescription("Provider preset that shapes the credential's fields."))
            // dataEnc holds AES-256-GCM ciphertext. The CredentialEncryptionHook
            // populates it from plaintext input fields; direct API edits to it
            // are blocked by withImmutable so the only way to change secret
            // material is through the hook path.
            .addField(FieldDefinition.text("dataEnc")
                .withColumnName("data_enc").withImmutable(true)
                .withDescription("Encrypted credential material; never returned in plaintext."))
            .addField(FieldDefinition.json("metadata")
                .withDescription("Non-secret JSON metadata about the credential."))
            .addField(FieldDefinition.datetime("lastTestAt").withColumnName("last_test_at")
                .withDescription("When the credential was last tested."))
            .addField(FieldDefinition.string("lastTestStatus", 20)
                .withColumnName("last_test_status")
                .withDescription("Outcome of the last credential test."))
            .addField(FieldDefinition.text("lastTestError").withColumnName("last_test_error")
                .withDescription("Error returned by the last failed credential test."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    public static CollectionDefinition credentialOauthTokens() {
        return readOnlySystemBuilder("credential-oauth-tokens", "Credential OAuth Tokens",
                "credential_oauth_token")
            .addField(FieldDefinition.lookup("credentialId", "credentials", "Credential")
                .withColumnName("credential_id")
                .withDescription("Credential these tokens belong to."))
            .addField(FieldDefinition.text("accessTokenEnc").withColumnName("access_token_enc")
                .withDescription("Encrypted OAuth access token."))
            .addField(FieldDefinition.text("refreshTokenEnc").withColumnName("refresh_token_enc")
                .withDescription("Encrypted OAuth refresh token."))
            .addField(FieldDefinition.string("tokenType", 40).withColumnName("token_type")
                .withDescription("OAuth token type, normally Bearer."))
            .addField(FieldDefinition.datetime("expiresAt").withColumnName("expires_at")
                .withDescription("When this record stops being valid."))
            .addField(FieldDefinition.datetime("refreshedAt").withColumnName("refreshed_at")
                .withDescription("When the access token was last refreshed."))
            .addField(FieldDefinition.integer("refreshFailureCount")
                .withColumnName("refresh_failure_count")
                .withDescription("Consecutive refresh failures; resets on success."))
            .addField(FieldDefinition.text("lastRefreshError").withColumnName("last_refresh_error")
                .withDescription("Error returned by the last failed refresh."))
            .addField(FieldDefinition.text("scope")
                .withDescription("OAuth scopes the stored token was granted."))
            .build();
    }

    public static CollectionDefinition apiSpecs() {
        return systemBuilder("api-specs", "API Specs", "api_spec")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 1000)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("specVersion", 20)
                .withColumnName("spec_version")
                .withDescription("OpenAPI version of the imported document."))
            .addField(FieldDefinition.string("apiTitle", 500).withColumnName("api_title")
                .withDescription("Title declared by the imported API document."))
            .addField(FieldDefinition.string("apiVersion", 50).withColumnName("api_version")
                .withDescription("Version declared by the imported API document."))
            .addField(FieldDefinition.string("baseUrl", 1000).withColumnName("base_url")
                .withDescription("Base URL callouts against this spec are sent to."))
            .addField(FieldDefinition.json("servers")
                .withDescription("JSON server list declared by the document."))
            .addField(FieldDefinition.json("securitySchemes").withColumnName("security_schemes")
                .withDescription("JSON security schemes declared by the document."))
            .addField(FieldDefinition.requiredString("sourceType", 20)
                .withColumnName("source_type")
                .withDescription("How the specification was supplied: uploaded or fetched by URL."))
            .addField(FieldDefinition.string("sourceUrl", 2000).withColumnName("source_url")
                .withDescription("URL the specification was fetched from."))
            // raw_spec / parsed_spec are exposed but immutable through the
            // dynamic router — imports go through ApiSpecController which runs
            // the parser. Keeping them here lets the system-collection viewer
            // render the spec's metadata without bouncing to a second endpoint.
            .addField(FieldDefinition.requiredText("rawSpec")
                .withColumnName("raw_spec").withImmutable(true)
                .withDescription("Specification document as imported."))
            .addField(FieldDefinition.requiredString("rawFormat", 10)
                .withColumnName("raw_format").withImmutable(true)
                .withDescription("Format of the raw document (JSON or YAML)."))
            .addField(FieldDefinition.requiredJson("parsedSpec")
                .withColumnName("parsed_spec").withImmutable(true)
                .withDescription("Normalized JSON form of the specification."))
            .addField(FieldDefinition.requiredString("specHash", 64)
                .withColumnName("spec_hash").withImmutable(true)
                .withDescription("Digest of the raw document, used to detect changes on re-import."))
            .addField(FieldDefinition.integer("revision").withDefault(1)
                .withDescription("Import revision number, incremented on every re-import."))
            .addField(FieldDefinition.bool("isActive").withColumnName("is_active")
                .withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.datetime("lastImportedAt")
                .withColumnName("last_imported_at")
                .withDescription("When the specification was last imported."))
            .build();
    }

    public static CollectionDefinition apiOperations() {
        return readOnlySystemBuilder("api-operations", "API Operations", "api_operation")
            .addField(FieldDefinition.lookup("specId", "api-specs", "Spec")
                .withColumnName("spec_id")
                .withDescription("API specification this operation belongs to."))
            .addField(FieldDefinition.string("operationId", 200).withColumnName("operation_id")
                .withDescription("operationId declared by the specification."))
            .addField(FieldDefinition.requiredString("syntheticOpId", 200)
                .withColumnName("synthetic_op_id")
                .withDescription("Generated operation id used when the document declares none."))
            .addField(FieldDefinition.requiredString("httpMethod", 10)
                .withColumnName("http_method")
                .withDescription("HTTP method of the operation."))
            .addField(FieldDefinition.requiredString("pathTemplate", 1000)
                .withColumnName("path_template")
                .withDescription("Path template of the operation, with brace parameters."))
            .addField(FieldDefinition.string("summary", 500)
                .withDescription("Summary declared for the operation by the specification."))
            .addField(FieldDefinition.text("description")
                .withDescription("Description declared for the operation by the specification."))
            .addField(FieldDefinition.json("tags")
                .withDescription("JSON tags the operation is grouped under."))
            .addField(FieldDefinition.json("parametersSchema").withColumnName("parameters_schema")
                .withDescription("JSON schema of the operation's parameters."))
            .addField(FieldDefinition.json("requestBodySchema")
                .withColumnName("request_body_schema")
                .withDescription("JSON schema of the operation's request body."))
            .addField(FieldDefinition.json("responseSchemas").withColumnName("response_schemas")
                .withDescription("JSON schemas of the operation's responses by status code."))
            .addField(FieldDefinition.json("securityRequired").withColumnName("security_required")
                .withDescription("Whether the operation requires authentication."))
            .addField(FieldDefinition.bool("deprecated")
                .withDescription("Whether the specification marks the operation deprecated."))
            .addField(FieldDefinition.text("searchText").withColumnName("search_text")
                .withDescription("Denormalized text used to search operations."))
            .build();
    }

    public static CollectionDefinition oidcProviders() {
        return systemBuilder("oidc-providers", "OIDC Providers", "oidc_provider")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.requiredString("issuer", 500)
                .withDescription("OIDC issuer URL."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.bool("isInternal").withColumnName("is_internal").withDefault(false)
                .withDescription("Whether this is the platform's own built-in provider."))
            .addField(FieldDefinition.string("clientId", 200).withColumnName("client_id")
                .withDescription("OAuth client identifier."))
            .addField(FieldDefinition.string("clientSecretEnc")
                .withColumnName("client_secret_enc")
                .withDescription("Encrypted OAuth client secret."))
            .addField(FieldDefinition.string("audience", 200)
                .withDescription("Expected audience claim on tokens from this provider."))
            // OIDC Discovery endpoint overrides (auto-discovered from issuer when NULL)
            .addField(FieldDefinition.string("jwksUri", 500)
                .withColumnName("jwks_uri")
                .withDescription("URL of the provider's signing key set."))
            .addField(FieldDefinition.string("authorizationUri", 500)
                .withColumnName("authorization_uri")
                .withDescription("Provider authorization endpoint."))
            .addField(FieldDefinition.string("tokenUri", 500)
                .withColumnName("token_uri")
                .withDescription("Provider token endpoint."))
            .addField(FieldDefinition.string("userinfoUri", 500)
                .withColumnName("userinfo_uri")
                .withDescription("Provider userinfo endpoint."))
            .addField(FieldDefinition.string("endSessionUri", 500)
                .withColumnName("end_session_uri")
                .withDescription("Provider end-session (logout) endpoint."))
            .addField(FieldDefinition.string("discoveryStatus", 20)
                .withColumnName("discovery_status").withDefault("unknown")
                .withDescription("Outcome of the last discovery-document fetch."))
            // Claim mappings
            .addField(FieldDefinition.string("rolesClaim", 200)
                .withColumnName("roles_claim")
                .withDescription("Token claim carrying the user's roles."))
            .addField(FieldDefinition.string("rolesMapping", 200)
                .withColumnName("roles_mapping")
                .withDescription("JSON map from provider role to platform profile."))
            .addField(FieldDefinition.string("emailClaim", 200)
                .withColumnName("email_claim").withDefault("email")
                .withDescription("Token claim carrying the user's email address."))
            .addField(FieldDefinition.string("usernameClaim", 200)
                .withColumnName("username_claim").withDefault("preferred_username")
                .withDescription("Token claim carrying the user's username."))
            .addField(FieldDefinition.string("nameClaim", 200)
                .withColumnName("name_claim").withDefault("name")
                .withDescription("Token claim carrying the user's display name."))
            .addField(FieldDefinition.string("groupsClaim", 200)
                .withColumnName("groups_claim")
                .withDescription("Token claim carrying the user's groups."))
            .addField(FieldDefinition.text("groupsProfileMapping")
                .withColumnName("groups_profile_mapping")
                .withDescription("JSON map from provider group to platform profile."))
            .build();
    }

    public static CollectionDefinition samlProviders() {
        return systemBuilder("saml-providers", "SAML Providers", "saml_provider")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.requiredString("registrationId", 100)
                .withColumnName("registration_id")
                .withDescription("Identifier used in this provider's SAML endpoint URLs."))
            .addField(FieldDefinition.requiredString("idpEntityId", 500)
                .withColumnName("idp_entity_id")
                .withDescription("SAML entity id of the identity provider."))
            .addField(FieldDefinition.requiredString("ssoUrl", 500)
                .withColumnName("sso_url")
                .withDescription("Identity provider single sign-on URL."))
            // IdP signing certificate (PEM) — TEXT, no length bound.
            .addField(FieldDefinition.text("idpCertificate")
                .withColumnName("idp_certificate")
                .withDescription("Identity provider signing certificate, PEM encoded."))
            .addField(FieldDefinition.string("nameIdFormat", 200)
                .withColumnName("name_id_format")
                .withDescription("Expected SAML NameID format."))
            // IdP SingleLogoutService URL (optional). When set, SLO is advertised in
            // SP metadata + IdP-initiated LogoutRequests are honored.
            .addField(FieldDefinition.string("sloUrl", 500)
                .withColumnName("slo_url")
                .withDescription("Identity provider single logout URL."))
            // Assertion attribute → user-field mappings.
            .addField(FieldDefinition.string("emailAttribute", 200)
                .withColumnName("email_attribute")
                .withDescription("SAML attribute carrying the user's email address."))
            .addField(FieldDefinition.string("profileAttribute", 200)
                .withColumnName("profile_attribute")
                .withDescription("SAML attribute carrying the profile to assign."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    // =========================================================================
    // Reports & Dashboards
    // =========================================================================

    public static CollectionDefinition reports() {
        return systemBuilder("reports", "Reports", "report")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 1000)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("reportType", 20)
                .withColumnName("report_type")
                .withEnumValues(List.of("TABULAR", "SUMMARY", "MATRIX"))
                .withDescription("Report shape: TABULAR, SUMMARY or MATRIX."))
            .addField(FieldDefinition.masterDetail("primaryCollectionId", "collections",
                "Primary Collection").withColumnName("primary_collection_id")
                .withDescription("Collection the report's rows come from."))
            .addField(FieldDefinition.json("relatedJoins").withColumnName("related_joins")
                .withDescription("JSON joins pulling fields from related collections."))
            .addField(FieldDefinition.requiredJson("columns")
                .withDescription("JSON array of column definitions rendered by the report."))
            .addField(FieldDefinition.json("filters")
                .withDescription("JSON filter conditions narrowing the report's rows."))
            .addField(FieldDefinition.string("filterLogic", 500)
                .withColumnName("filter_logic")
                .withDescription("Boolean expression combining the numbered filters, e.g. '1 AND (2 OR 3)'."))
            .addField(FieldDefinition.json("rowGroupings").withColumnName("row_groupings")
                .withDescription("JSON row grouping levels."))
            .addField(FieldDefinition.json("columnGroupings")
                .withColumnName("column_groupings")
                .withDescription("JSON column grouping levels, used by matrix reports."))
            .addField(FieldDefinition.json("sortOrder").withColumnName("sort_order")
                .withDescription("JSON sort specification applied to the report's rows."))
            .addField(FieldDefinition.string("chartType", 20).withColumnName("chart_type")
                .withDescription("Chart rendered alongside the report's rows."))
            .addField(FieldDefinition.json("chartConfig").withColumnName("chart_config")
                .withDescription("JSON chart rendering options."))
            .addField(FieldDefinition.string("groupBy", 200).withColumnName("group_by")
                .withDescription("Field results are grouped by."))
            .addField(FieldDefinition.string("sortBy", 200).withColumnName("sort_by")
                .withDescription("Field results are sorted by."))
            .addField(FieldDefinition.string("sortDirection", 4)
                .withColumnName("sort_direction").withDefault("ASC")
                .withEnumValues(List.of("ASC", "DESC"))
                .withDescription("Sort direction: ASC or DESC."))
            .addField(FieldDefinition.string("scope", 20).withDefault("MY_RECORDS")
                .withDescription("Which rows the report reads: MY_RECORDS, MY_TEAM_RECORDS or ALL_RECORDS."))
            .addField(FieldDefinition.lookup("folderId", "report-folders", "Folder")
                .withColumnName("folder_id")
                .withDescription("Folder this record is filed under."))
            .addField(FieldDefinition.string("accessLevel", 20)
                .withColumnName("access_level").withDefault("PRIVATE")
                .withEnumValues(List.of("PRIVATE", "PUBLIC", "HIDDEN"))
                .withDescription("Who may open the report: PRIVATE, PUBLIC or HIDDEN."))
            .build();
    }

    public static CollectionDefinition reportFolders() {
        return systemBuilder("report-folders", "Report Folders", "report_folder")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("accessLevel", 20)
                .withColumnName("access_level").withDefault("PRIVATE")
                .withEnumValues(List.of("PRIVATE", "PUBLIC", "HIDDEN"))
                .withDescription("Who may open the folder: PRIVATE, PUBLIC or HIDDEN."))
            .build();
    }

    public static CollectionDefinition dashboards() {
        return systemBuilder("dashboards", "Dashboards", "dashboard")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 1000)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.lookup("folderId", "report-folders", "Folder")
                .withColumnName("folder_id")
                .withDescription("Folder this record is filed under."))
            .addField(FieldDefinition.string("accessLevel", 20)
                .withColumnName("access_level")
                .withEnumValues(List.of("PRIVATE", "PUBLIC", "HIDDEN"))
                .withDescription("Who may open the dashboard: PRIVATE, PUBLIC or HIDDEN."))
            .addField(FieldDefinition.bool("dynamic").withColumnName("is_dynamic")
                .withDescription("Whether widgets execute as runningUserId rather than the viewer."))
            .addField(FieldDefinition.string("runningUserId", 36)
                .withColumnName("running_user_id")
                .withDescription("User a dynamic dashboard's widgets execute as."))
            .addField(FieldDefinition.integer("columnCount").withColumnName("column_count")
                .withDescription("Width of the dashboard grid in columns."))
            .build();
    }

    // =========================================================================
    // Collaboration Collections
    // =========================================================================

    public static CollectionDefinition notes() {
        return systemBuilder("notes", "Notes", "note")
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

    // =========================================================================
    // Platform Management Collections
    // =========================================================================

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

    // =========================================================================
    // Read-Only Audit/Log Collections
    // =========================================================================

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

    // =========================================================================
    // Groups & Membership Collections
    // =========================================================================

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

    // =========================================================================
    // Permission Collections
    // =========================================================================

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

    // =========================================================================
    // Layout Child Collections
    // =========================================================================

    public static CollectionDefinition layoutSections() {
        return systemBuilder("layout-sections", "Layout Sections", "layout_section")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("layoutId", "page-layouts", "Layout")
                .withColumnName("layout_id")
                .withDescription("Page layout this row belongs to."))
            .addField(FieldDefinition.string("heading", 200)
                .withDescription("Section heading rendered above the fields."))
            .addField(FieldDefinition.integer("columns").withDefault(2)
                .withDescription("Number of columns the section lays fields out in (default 2)."))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order")
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .addField(FieldDefinition.bool("collapsed").withDefault(false)
                .withDescription("Whether the section starts collapsed."))
            .addField(FieldDefinition.string("style", 20).withDefault("DEFAULT")
                .withDescription("Visual style applied to the section."))
            .addField(FieldDefinition.string("sectionType", 30)
                .withColumnName("section_type").withDefault("STANDARD")
                .withDescription("Section kind: STANDARD, FIELDS or HIGHLIGHTS_PANEL."))
            .addField(FieldDefinition.string("tabGroup", 100)
                .withColumnName("tab_group")
                .withDescription("Tab group this section belongs to, for tabbed layouts."))
            .addField(FieldDefinition.string("tabLabel", 200)
                .withColumnName("tab_label")
                .withDescription("Label of the tab this section is rendered under."))
            .addField(FieldDefinition.json("visibilityRule")
                .withColumnName("visibility_rule")
                .withDescription("JSON conditional-visibility expression; hides the element when it evaluates false."))
            .build();
    }

    public static CollectionDefinition layoutFields() {
        return systemBuilder("layout-fields", "Layout Fields", "layout_field")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("sectionId", "layout-sections", "Section")
                .withColumnName("section_id")
                .withDescription("Layout section this field placement belongs to."))
            .addField(FieldDefinition.masterDetail("fieldId", "fields", "Field")
                .withColumnName("field_id")
                .withDescription("Field this row refers to."))
            .addField(FieldDefinition.integer("columnNumber")
                .withColumnName("column_number").withDefault(0)
                .withDescription("0-based column within the section; values at or past the section's column count are clamped."))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order")
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .addField(FieldDefinition.bool("isRequiredOnLayout")
                .withColumnName("is_required_on_layout").withDefault(false)
                .withDescription("Whether the field is required on this layout even if optional in the schema."))
            .addField(FieldDefinition.bool("isReadOnlyOnLayout")
                .withColumnName("is_read_only_on_layout").withDefault(false)
                .withDescription("Whether the field is read-only on this layout."))
            .addField(FieldDefinition.string("labelOverride", 200)
                .withColumnName("label_override")
                .withDescription("Label shown instead of the field's own label on this layout."))
            .addField(FieldDefinition.string("helpTextOverride", 500)
                .withColumnName("help_text_override")
                .withDescription("Help text shown instead of the field's own help text."))
            .addField(FieldDefinition.json("visibilityRule")
                .withColumnName("visibility_rule")
                .withDescription("JSON conditional-visibility expression; hides the element when it evaluates false."))
            .addField(FieldDefinition.integer("columnSpan")
                .withColumnName("column_span").withDefault(1)
                .withDescription("How many of the section's columns the field spans."))
            .build();
    }

    public static CollectionDefinition layoutRules() {
        return systemBuilder("layout-rules", "Layout Rules", "layout_rule")
            .displayFieldName("name")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("layoutId", "page-layouts", "Layout")
                .withColumnName("layout_id")
                .withDescription("Page layout this row belongs to."))
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("kind", 20)
                .withEnumValues(List.of("COMPUTE", "VALIDATE", "DEFAULT", "TRANSFORM", "SCRIPT"))
                .withDescription("Rule kind, which decides how the body is interpreted."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.requiredJson("whenEvents")
                .withColumnName("when_events")
                .withDescription("JSON list of form events the rule reacts to."))
            .addField(FieldDefinition.string("targetField", 100)
                .withColumnName("target_field")
                .withDescription("Field the rule acts on."))
            .addField(FieldDefinition.json("dependsOn")
                .withColumnName("depends_on")
                .withDescription("JSON list of fields whose changes re-evaluate the rule."))
            .addField(FieldDefinition.requiredJson("body")
                .withDescription("JSON rule body describing what the rule does when it fires."))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order")
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .build();
    }

    public static CollectionDefinition layoutRelatedLists() {
        return systemBuilder("layout-related-lists", "Layout Related Lists",
                "layout_related_list")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("layoutId", "page-layouts", "Layout")
                .withColumnName("layout_id")
                .withDescription("Page layout this row belongs to."))
            .addField(FieldDefinition.masterDetail("relatedCollectionId", "collections",
                "Related Collection").withColumnName("related_collection_id")
                .withDescription("Related collection this row points at."))
            .addField(FieldDefinition.masterDetail("relationshipFieldId", "fields",
                "Relationship Field").withColumnName("relationship_field_id")
                .withDescription("Field on the related collection that points back at this record."))
            .addField(FieldDefinition.requiredJson("displayColumns")
                .withColumnName("display_columns")
                .withDescription("JSON array of field names shown as columns in the related list."))
            .addField(FieldDefinition.string("sortField", 100)
                .withColumnName("sort_field")
                .withDescription("Field name results are sorted by."))
            .addField(FieldDefinition.string("sortDirection", 4)
                .withColumnName("sort_direction").withDefault("DESC")
                .withEnumValues(List.of("ASC", "DESC"))
                .withDescription("Sort direction: ASC or DESC."))
            .addField(FieldDefinition.integer("rowLimit")
                .withColumnName("row_limit").withDefault(10)
                .withDescription("Maximum number of rows rendered."))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order")
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .build();
    }

    public static CollectionDefinition uiMenuItems() {
        return systemBuilder("ui-menu-items", "UI Menu Items", "ui_menu_item")
            .displayFieldName("label")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("menuId", "ui-menus", "Menu")
                .withColumnName("menu_id")
                .withDescription("Menu this item belongs to."))
            // Submenus (V166): a group header is an item with children and no path;
            // deleting a parent floats its children to the top level (FK SET NULL).
            .addField(FieldDefinition.lookup("parentId", "ui-menu-items", "Parent Item")
                .withColumnName("parent_id")
                .withDescription("Parent menu item, for submenus."))
            .addField(FieldDefinition.requiredString("label", 100)
                .withDescription("Display label shown in the UI."))
            // Optional since V166: group headers navigate nowhere themselves.
            .addField(FieldDefinition.string("path", 200)
                .withDescription("Route the menu item navigates to."))
            .addField(FieldDefinition.string("icon", 100)
                .withDescription("Icon name rendered beside the label."))
            .addField(FieldDefinition.integer("displayOrder")
                .withColumnName("display_order").withDefault(0)
                .withNullable(false)
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withNullable(false)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    // =========================================================================
    // Remaining Child Collections
    // =========================================================================

    public static CollectionDefinition picklistDependencies() {
        return systemBuilder("picklist-dependencies", "Picklist Dependencies",
                "picklist_dependency")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("controllingFieldId", "fields",
                "Controlling Field").withColumnName("controlling_field_id")
                .withDescription("Field whose value filters the dependent picklist."))
            .addField(FieldDefinition.masterDetail("dependentFieldId", "fields",
                "Dependent Field").withColumnName("dependent_field_id")
                .withDescription("Field whose available values are filtered."))
            .addField(FieldDefinition.requiredJson("mapping")
                .withDescription("JSON map from controlling value to the dependent values it allows."))
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

    public static CollectionDefinition connectedAppTokens() {
        return systemBuilder("connected-app-tokens", "Connected App Tokens",
                "connected_app_token")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("connectedAppId", "connected-apps",
                "Connected App").withColumnName("connected_app_id")
                .withDescription("Connected app this token was issued to."))
            .addField(FieldDefinition.requiredString("tokenHash", 200)
                .withColumnName("token_hash")
                .withDescription("Hash of the issued token; the token itself is shown once."))
            .addField(FieldDefinition.requiredJson("scopes")
                .withDescription("Space-separated OAuth scopes."))
            .addField(FieldDefinition.datetime("issuedAt")
                .withColumnName("issued_at").withNullable(false)
                .withDescription("When the token was issued."))
            .addField(FieldDefinition.datetime("expiresAt")
                .withColumnName("expires_at").withNullable(false)
                .withDescription("When this record stops being valid."))
            .addField(FieldDefinition.bool("revoked").withDefault(false)
                .withDescription("Whether the token has been revoked."))
            .addField(FieldDefinition.datetime("revokedAt")
                .withColumnName("revoked_at")
                .withDescription("When the token was revoked."))
            .build();
    }

    public static CollectionDefinition dashboardComponents() {
        return systemBuilder("dashboard-components", "Dashboard Components",
                "dashboard_component")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("dashboardId", "dashboards",
                "Dashboard").withColumnName("dashboard_id")
                .withDescription("Dashboard this component belongs to."))
            .addField(FieldDefinition.lookup("reportId", "reports", "Report")
                .withColumnName("report_id")
                .withDescription("Saved report backing the widget; optional when config names a collection."))
            .addField(FieldDefinition.requiredString("componentType", 20)
                .withColumnName("component_type")
                .withEnumValues(List.of("metric", "chart", "table", "recent"))
                .withDescription("Widget renderer: metric, chart, table or recent."))
            .addField(FieldDefinition.string("title", 200)
                .withDescription("Title rendered on the widget frame."))
            .addField(FieldDefinition.requiredInteger("columnPosition")
                .withColumnName("column_position")
                .withDescription("1-based grid column the widget starts at."))
            .addField(FieldDefinition.requiredInteger("rowPosition")
                .withColumnName("row_position")
                .withDescription("1-based grid row the widget starts at."))
            .addField(FieldDefinition.integer("columnSpan")
                .withColumnName("column_span").withDefault(1)
                .withDescription("How many grid columns the widget spans."))
            .addField(FieldDefinition.integer("rowSpan")
                .withColumnName("row_span").withDefault(1)
                .withDescription("How many grid rows the widget spans."))
            .addField(FieldDefinition.json("config").withDefault(Map.of())
                .withDescription("JSON widget settings: target collection, aggregate, filters, chart style."))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order")
                .withDescription("Ordinal position among sibling rows (ascending)."))
            .build();
    }

    // =========================================================================
    // Read-Only: Remaining Log Collections
    // =========================================================================

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

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Creates a builder pre-configured for a system collection.
     * Default: tenant-scoped, all CRUD enabled, events enabled, system=true.
     */
    private static CollectionDefinitionBuilder systemBuilder(String name, String displayName, String tableName) {
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
    private static CollectionDefinitionBuilder readOnlySystemBuilder(String name, String displayName, String tableName) {
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
