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
                .withValidation(ValidationRules.forString(null, 63, "^[a-z][a-z0-9-]{1,61}[a-z0-9]$")))
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.requiredString("edition", 20)
                .withDefault("PROFESSIONAL")
                .withEnumValues(List.of("FREE", "PROFESSIONAL", "ENTERPRISE", "UNLIMITED")))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("PROVISIONING")
                .withEnumValues(List.of("PROVISIONING", "ACTIVE", "SUSPENDED", "DECOMMISSIONED")))
            .addField(FieldDefinition.json("settings"))
            .addField(FieldDefinition.json("limits"))
            .addField(FieldDefinition.bool("ipAllowlistEnabled", false)
                .withColumnName("ip_allowlist_enabled"))
            .addField(FieldDefinition.json("ipAllowlistCidrs")
                .withColumnName("ip_allowlist_cidrs"))
            .addField(FieldDefinition.lookup("parentTenantId", "tenants", "Parent Tenant")
                .withColumnName("parent_tenant_id"))
            .build();
    }

    public static CollectionDefinition users() {
        return systemBuilder("users", "Users", "platform_user")
            .displayFieldName("email")
            .addImmutableField("tenantId")
            .addImmutableField("userType")
            .addField(FieldDefinition.lookup("tenantId", "tenants", "Tenant")
                .withColumnName("tenant_id"))
            .addField(FieldDefinition.requiredString("email", 320))
            .addField(FieldDefinition.requiredString("userType", 20)
                .withColumnName("user_type")
                .withDefault("INTERNAL")
                .withEnumValues(List.of("INTERNAL", "PORTAL")))
            .addField(FieldDefinition.string("username", 100))
            .addField(FieldDefinition.string("firstName", 100).withColumnName("first_name"))
            .addField(FieldDefinition.string("lastName", 100).withColumnName("last_name"))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("ACTIVE")
                .withEnumValues(List.of("ACTIVE", "INACTIVE", "LOCKED", "PENDING_ACTIVATION")))
            .addField(FieldDefinition.string("locale", 10).withDefault("en_US"))
            .addField(FieldDefinition.string("timezone", 50).withDefault("UTC"))
            .addField(FieldDefinition.lookup("profileId", "profiles", "Profile")
                .withColumnName("profile_id"))
            .addField(FieldDefinition.lookup("managerId", "users", "Manager")
                .withColumnName("manager_id"))
            .addField(FieldDefinition.datetime("lastLoginAt").withColumnName("last_login_at"))
            .addField(FieldDefinition.integer("loginCount").withColumnName("login_count").withDefault(0))
            .addField(FieldDefinition.bool("mfaEnabled").withColumnName("mfa_enabled").withDefault(false))
            .addField(FieldDefinition.json("settings"))
            .build();
    }

    public static CollectionDefinition profiles() {
        return systemBuilder("profiles", "Profiles", "profile")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 255))
            .addField(FieldDefinition.text("description"))
            .addField(FieldDefinition.bool("isSystem").withColumnName("is_system").withDefault(false))
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
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.text("description"))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .build();
    }

    public static CollectionDefinition chatConversations() {
        return systemBuilder("chat-conversations", "Chat Conversations", "chat_conversation")
            .displayFieldName("subject")
            .addImmutableField("origin")
            .addField(FieldDefinition.lookup("queueId", "chat-queues", "Queue")
                .withColumnName("queue_id"))
            .addField(FieldDefinition.string("subject", 200))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("OPEN")
                .withEnumValues(List.of("OPEN", "ASSIGNED", "CLOSED", "ARCHIVED")))
            .addField(FieldDefinition.requiredString("origin", 20)
                .withDefault("INTERNAL")
                .withEnumValues(List.of("PORTAL", "INTERNAL")))
            .addField(FieldDefinition.lookup("assignedTo", "users", "Assigned To")
                .withColumnName("assigned_to"))
            .addField(FieldDefinition.string("contextRecordId", 36)
                .withColumnName("context_record_id"))
            .addField(FieldDefinition.datetime("lastMessageAt").withColumnName("last_message_at"))
            .addField(FieldDefinition.datetime("closedAt").withColumnName("closed_at"))
            .build();
    }

    public static CollectionDefinition chatMessages() {
        return systemBuilder("chat-messages", "Chat Messages", "chat_message")
            .displayFieldName("id")
            .addImmutableField("conversationId")
            .addImmutableField("senderId")
            .addImmutableField("senderType")
            .addField(FieldDefinition.masterDetail("conversationId", "chat-conversations", "Conversation")
                .withColumnName("conversation_id"))
            .addField(FieldDefinition.lookup("senderId", "users", "Sender")
                .withColumnName("sender_id"))
            .addField(FieldDefinition.requiredString("senderType", 20)
                .withColumnName("sender_type")
                .withEnumValues(List.of("INTERNAL", "PORTAL", "SYSTEM")))
            .addField(FieldDefinition.requiredString("kind", 20)
                .withDefault("TEXT")
                .withEnumValues(List.of("TEXT", "SYSTEM", "ATTACHMENT")))
            .addField(FieldDefinition.requiredText("body"))
            .addField(FieldDefinition.datetime("sentAt").withColumnName("sent_at"))
            .build();
    }

    public static CollectionDefinition chatParticipants() {
        return systemBuilder("chat-participants", "Chat Participants", "chat_participant")
            .displayFieldName("id")
            .addImmutableField("conversationId")
            .addImmutableField("userId")
            .addField(FieldDefinition.masterDetail("conversationId", "chat-conversations", "Conversation")
                .withColumnName("conversation_id"))
            .addField(FieldDefinition.lookup("userId", "users", "User")
                .withColumnName("user_id"))
            .addField(FieldDefinition.requiredString("role", 20)
                .withEnumValues(List.of("AGENT", "PORTAL")))
            .addField(FieldDefinition.datetime("joinedAt").withColumnName("joined_at"))
            .addField(FieldDefinition.datetime("lastReadAt").withColumnName("last_read_at"))
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
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.text("description"))
            .addField(FieldDefinition.requiredString("address", 320))
            .addField(FieldDefinition.string("replyFromAddress", 320)
                .withColumnName("reply_from_address"))
            .addField(FieldDefinition.string("replyFromName", 200)
                .withColumnName("reply_from_name"))
            .addField(FieldDefinition.string("verpDomain", 255)
                .withColumnName("verp_domain"))
            .addField(FieldDefinition.requiredString("webhookKey", 64)
                .withColumnName("webhook_key"))
            .addField(FieldDefinition.requiredString("inboundProvider", 30)
                .withColumnName("inbound_provider")
                .withDefault("SES_SNS")
                .withEnumValues(List.of("SES_SNS", "SES_SNS_INLINE", "GENERIC_HMAC",
                                        "POSTMARK", "MAILGUN", "CLOUDMAILIN")))
            .addField(FieldDefinition.string("providerTopicArn", 500)
                .withColumnName("provider_topic_arn"))
            // The credential ids are deliberately NOT declared: an undeclared column
            // is not selectable through the generic route. A hint and a rotation
            // timestamp are everything the admin UI needs to show.
            .addField(FieldDefinition.string("inboundSecretHint", 12)
                .withColumnName("inbound_secret_hint"))
            .addField(FieldDefinition.datetime("inboundSecretRotatedAt")
                .withColumnName("inbound_secret_rotated_at"))
            .addField(FieldDefinition.integer("slaFirstResponseMinutes")
                .withColumnName("sla_first_response_minutes"))
            .addField(FieldDefinition.integer("slaResolutionMinutes")
                .withColumnName("sla_resolution_minutes"))
            .addField(FieldDefinition.integer("slaRiskThresholdPct")
                .withColumnName("sla_risk_threshold_pct"))
            .addField(FieldDefinition.json("businessHours").withColumnName("business_hours"))
            .addField(FieldDefinition.string("businessTimezone", 64)
                .withColumnName("business_timezone"))
            .addField(FieldDefinition.lookup("escalationUserId", "users", "Escalation Contact")
                .withColumnName("escalation_user_id"))
            .addField(FieldDefinition.bool("autoReplyEnabled", false)
                .withColumnName("auto_reply_enabled"))
            .addField(FieldDefinition.doubleField("autoReplyMinConfidence")
                .withColumnName("auto_reply_min_confidence"))
            .addField(FieldDefinition.integer("maxAutoRepliesPerThread")
                .withColumnName("max_auto_replies_per_thread"))
            .addField(FieldDefinition.bool("aiDraftEnabled", false)
                .withColumnName("ai_draft_enabled"))
            .addField(FieldDefinition.bool("requireVerifiedSenderForAccountData", true)
                .withColumnName("require_verified_sender_for_account_data"))
            .addField(FieldDefinition.lookup("defaultAssigneeId", "users", "Default Assignee")
                .withColumnName("default_assignee_id"))
            .addField(FieldDefinition.bool("active", true))
            .build();
    }

    public static CollectionDefinition mailboxAccess() {
        return readOnlySystemBuilder("mailbox-access", "Mailbox Access", "mailbox_access")
            .displayFieldName("id")
            .addField(FieldDefinition.masterDetail("mailboxId", "mailboxes", "Mailbox")
                .withColumnName("mailbox_id"))
            .addField(FieldDefinition.requiredString("principalType", 10)
                .withColumnName("principal_type")
                .withEnumValues(List.of("USER", "GROUP")))
            .addField(FieldDefinition.requiredString("principalId", 36)
                .withColumnName("principal_id"))
            // VIEWER reads, AGENT replies, MANAGER approves drafts and configures.
            // Approval authority lives here rather than in a system permission so
            // it cannot leak across mailboxes the holder is not a member of.
            .addField(FieldDefinition.requiredString("role", 10)
                .withEnumValues(List.of("VIEWER", "AGENT", "MANAGER")))
            .build();
    }

    public static CollectionDefinition mailboxThreads() {
        return readOnlySystemBuilder("mailbox-threads", "Mailbox Threads", "mailbox_thread")
            .displayFieldName("subject")
            .addField(FieldDefinition.masterDetail("mailboxId", "mailboxes", "Mailbox")
                .withColumnName("mailbox_id"))
            .addField(FieldDefinition.string("subject", 500))
            .addField(FieldDefinition.requiredString("status", 24)
                .withDefault("OPEN")
                .withEnumValues(List.of("OPEN", "ASSIGNED", "WAITING_ON_CUSTOMER",
                                        "WAITING_ON_APPROVAL", "RESOLVED", "CLOSED",
                                        "SPAM", "ARCHIVED")))
            .addField(FieldDefinition.requiredString("priority", 10)
                .withDefault("NORMAL")
                .withEnumValues(List.of("LOW", "NORMAL", "HIGH", "URGENT")))
            .addField(FieldDefinition.lookup("assignedTo", "users", "Assigned To")
                .withColumnName("assigned_to"))
            .addField(FieldDefinition.requiredString("requesterEmail", 320)
                .withColumnName("requester_email"))
            .addField(FieldDefinition.string("requesterName", 200)
                .withColumnName("requester_name"))
            // The disclosure gate. False unless an identity was actually proven.
            .addField(FieldDefinition.bool("requesterVerified", false)
                .withColumnName("requester_verified"))
            .addField(FieldDefinition.string("verificationMethod", 20)
                .withColumnName("verification_method")
                .withEnumValues(List.of("DMARC_MATCH", "CHALLENGE", "MANUAL")))
            .addField(FieldDefinition.string("category", 60))
            .addField(FieldDefinition.doubleField("categoryConfidence")
                .withColumnName("category_confidence"))
            .addField(FieldDefinition.integer("autoReplyCount")
                .withColumnName("auto_reply_count"))
            .addField(FieldDefinition.integer("messageCount").withColumnName("message_count"))
            .addField(FieldDefinition.datetime("lastMessageAt").withColumnName("last_message_at"))
            .addField(FieldDefinition.datetime("lastInboundAt").withColumnName("last_inbound_at"))
            .addField(FieldDefinition.datetime("lastOutboundAt").withColumnName("last_outbound_at"))
            .addField(FieldDefinition.datetime("firstResponseAt").withColumnName("first_response_at"))
            .addField(FieldDefinition.datetime("slaFirstResponseDueAt")
                .withColumnName("sla_first_response_due_at"))
            .addField(FieldDefinition.requiredString("slaFirstResponseState", 10)
                .withDefault("NONE")
                .withColumnName("sla_first_response_state")
                .withEnumValues(List.of("NONE", "PENDING", "AT_RISK", "BREACHED", "MET")))
            .addField(FieldDefinition.datetime("slaResolutionDueAt")
                .withColumnName("sla_resolution_due_at"))
            .addField(FieldDefinition.requiredString("slaResolutionState", 10)
                .withDefault("NONE")
                .withColumnName("sla_resolution_state")
                .withEnumValues(List.of("NONE", "PENDING", "AT_RISK", "BREACHED", "MET")))
            .addField(FieldDefinition.datetime("resolvedAt").withColumnName("resolved_at"))
            .addField(FieldDefinition.datetime("closedAt").withColumnName("closed_at"))
            .build();
    }

    public static CollectionDefinition mailboxMessages() {
        return readOnlySystemBuilder("mailbox-messages", "Mailbox Messages", "mailbox_message")
            .displayFieldName("subject")
            .addField(FieldDefinition.masterDetail("threadId", "mailbox-threads", "Thread")
                .withColumnName("thread_id"))
            .addField(FieldDefinition.requiredString("direction", 10)
                .withEnumValues(List.of("INBOUND", "OUTBOUND")))
            .addField(FieldDefinition.requiredString("kind", 12)
                .withDefault("EMAIL")
                .withEnumValues(List.of("EMAIL", "NOTE", "SYSTEM")))
            .addField(FieldDefinition.string("fromAddress", 320).withColumnName("from_address"))
            .addField(FieldDefinition.string("fromName", 200).withColumnName("from_name"))
            .addField(FieldDefinition.string("subject", 500))
            // Text only. bodyHtml is intentionally absent — see the block comment
            // above; it is third-party markup and must not reach a generic renderer.
            .addField(FieldDefinition.text("bodyText").withColumnName("body_text"))
            .addField(FieldDefinition.string("snippet", 500))
            .addField(FieldDefinition.string("spfResult", 20).withColumnName("spf_result"))
            .addField(FieldDefinition.string("dkimResult", 20).withColumnName("dkim_result"))
            .addField(FieldDefinition.string("dmarcResult", 20).withColumnName("dmarc_result"))
            .addField(FieldDefinition.string("spamVerdict", 20).withColumnName("spam_verdict"))
            .addField(FieldDefinition.string("virusVerdict", 20).withColumnName("virus_verdict"))
            .addField(FieldDefinition.bool("isBulk", false).withColumnName("is_bulk"))
            .addField(FieldDefinition.bool("isBounce", false).withColumnName("is_bounce"))
            .addField(FieldDefinition.string("deliveryStatus", 20)
                .withColumnName("delivery_status")
                .withEnumValues(List.of("QUEUED", "SENT", "FAILED", "SUPPRESSED")))
            .addField(FieldDefinition.datetime("sentAt").withColumnName("sent_at"))
            .addField(FieldDefinition.datetime("receivedAt").withColumnName("received_at"))
            .build();
    }

    public static CollectionDefinition mailboxAttachments() {
        return readOnlySystemBuilder("mailbox-attachments", "Mailbox Attachments", "mailbox_attachment")
            .displayFieldName("filename")
            .addField(FieldDefinition.masterDetail("messageId", "mailbox-messages", "Message")
                .withColumnName("message_id"))
            .addField(FieldDefinition.requiredString("filename", 500))
            .addField(FieldDefinition.requiredString("contentType", 200)
                .withColumnName("content_type"))
            .addField(FieldDefinition.longField("sizeBytes").withColumnName("size_bytes"))
            .addField(FieldDefinition.string("contentId", 255).withColumnName("content_id"))
            .addField(FieldDefinition.bool("inline", false))
            .addField(FieldDefinition.string("storageKey", 500).withColumnName("storage_key"))
            .addField(FieldDefinition.string("checksumSha256", 64).withColumnName("checksum_sha256"))
            .addField(FieldDefinition.requiredString("scanStatus", 20)
                .withDefault("UNKNOWN")
                .withColumnName("scan_status")
                .withEnumValues(List.of("UNKNOWN", "CLEAN", "INFECTED", "SKIPPED")))
            .build();
    }

    public static CollectionDefinition mailboxInboundEvents() {
        return readOnlySystemBuilder("mailbox-inbound-events", "Mailbox Inbound Events",
                                     "mailbox_inbound_event")
            .displayFieldName("id")
            .addField(FieldDefinition.masterDetail("mailboxId", "mailboxes", "Mailbox")
                .withColumnName("mailbox_id"))
            .addField(FieldDefinition.requiredString("provider", 30))
            .addField(FieldDefinition.string("providerEventId", 255)
                .withColumnName("provider_event_id"))
            .addField(FieldDefinition.requiredString("payloadDigest", 64)
                .withColumnName("payload_digest"))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("RECEIVED")
                .withEnumValues(List.of("RECEIVED", "PARSED", "ROUTED", "REJECTED",
                                        "DUPLICATE", "FAILED")))
            .addField(FieldDefinition.string("rejectReason", 200).withColumnName("reject_reason"))
            .addField(FieldDefinition.datetime("receivedAt").withColumnName("received_at"))
            .addField(FieldDefinition.datetime("processedAt").withColumnName("processed_at"))
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
                .withColumnName("mailbox_id"))
            .addField(FieldDefinition.requiredString("category", 60))
            .addField(FieldDefinition.requiredString("templateKey", 200)
                .withColumnName("template_key"))
            .addField(FieldDefinition.text("description"))
            .addField(FieldDefinition.json("matchKeywords").withColumnName("match_keywords"))
            .addField(FieldDefinition.json("excludeKeywords").withColumnName("exclude_keywords"))
            .addField(FieldDefinition.integer("priority"))
            // Every automation default is off. A template is not auto-sendable because it matches
            // well; someone has to say so, and the guard hook decides whether they may.
            .addField(FieldDefinition.bool("autoSendEligible", false)
                .withColumnName("auto_send_eligible"))
            .addField(FieldDefinition.doubleField("minConfidence").withColumnName("min_confidence"))
            .addField(FieldDefinition.bool("requiresVerifiedSender", false)
                .withColumnName("requires_verified_sender"))
            .addField(FieldDefinition.bool("disclosesAccountData", false)
                .withColumnName("discloses_account_data"))
            .addField(FieldDefinition.bool("active", true))
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
                .withColumnName("provider_id"))
            .addField(FieldDefinition.requiredString("kind", 20)
                .withDefault("RULE")
                .withEnumValues(List.of("RULE", "EXCEPTION")))
            .addField(FieldDefinition.integer("weekday"))
            .addField(FieldDefinition.date("exceptionDate").withColumnName("exception_date"))
            // Wall-clock "HH:mm" / "HH:mm:ss" strings (varchar(8) columns since
            // V172); SlotService parses them with LocalTime.parse, so keep the
            // pattern in lockstep with what java.time accepts.
            .addField(FieldDefinition.string("startTime", 8).withColumnName("start_time")
                .withValidation(ValidationRules.forString(null, 8, "^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$")))
            .addField(FieldDefinition.string("endTime", 8).withColumnName("end_time")
                .withValidation(ValidationRules.forString(null, 8, "^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$")))
            .addField(FieldDefinition.requiredString("timezone", 50).withDefault("UTC"))
            .addField(FieldDefinition.bool("closed").withDefault(false))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .build();
    }

    public static CollectionDefinition telehealthAppointments() {
        return systemBuilder("telehealth-appointments", "Telehealth Appointments", "telehealth_appointment")
            .displayFieldName("id")
            .addImmutableField("providerId")
            .addImmutableField("portalUserId")
            .addField(FieldDefinition.lookup("providerId", "users", "Provider")
                .withColumnName("provider_id"))
            .addField(FieldDefinition.lookup("portalUserId", "users", "Portal User")
                .withColumnName("portal_user_id"))
            .addField(FieldDefinition.datetime("scheduledStart").withColumnName("scheduled_start"))
            .addField(FieldDefinition.datetime("scheduledEnd").withColumnName("scheduled_end"))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("CONFIRMED")
                .withEnumValues(List.of("REQUESTED", "CONFIRMED", "CANCELLED", "COMPLETED", "NO_SHOW")))
            .addField(FieldDefinition.string("visitType", 100).withColumnName("visit_type"))
            .addField(FieldDefinition.string("reason", 500))
            .addField(FieldDefinition.lookup("conversationId", "chat-conversations", "Conversation")
                .withColumnName("conversation_id"))
            .addField(FieldDefinition.string("videoSessionId", 36).withColumnName("video_session_id"))
            .addField(FieldDefinition.datetime("reminderSentAt").withColumnName("reminder_sent_at"))
            .addField(FieldDefinition.datetime("cancelledAt").withColumnName("cancelled_at"))
            .build();
    }

    /** Video sessions (telehealth slice 5) — lifecycle owned by the LiveKit webhook. */
    public static CollectionDefinition videoSessions() {
        return systemBuilder("video-sessions", "Video Sessions", "video_session")
            .displayFieldName("id")
            .addImmutableField("roomName")
            .addField(FieldDefinition.lookup("appointmentId", "telehealth-appointments", "Appointment")
                .withColumnName("appointment_id"))
            .addField(FieldDefinition.lookup("conversationId", "chat-conversations", "Conversation")
                .withColumnName("conversation_id"))
            .addField(FieldDefinition.requiredString("roomName", 100)
                .withColumnName("room_name").withUnique(true))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("CREATED")
                .withEnumValues(List.of("CREATED", "ACTIVE", "ENDED")))
            .addField(FieldDefinition.datetime("startedAt").withColumnName("started_at"))
            .addField(FieldDefinition.datetime("endedAt").withColumnName("ended_at"))
            .addField(FieldDefinition.integer("durationSeconds").withColumnName("duration_seconds"))
            .addField(FieldDefinition.bool("recordingConsent").withColumnName("recording_consent")
                .withDefault(false))
            .addField(FieldDefinition.string("recordingKey", 500).withColumnName("recording_key"))
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
                .withEnumValues(List.of("CONVERSATION", "VIDEO_SESSION")))
            .addField(FieldDefinition.requiredString("sourceId", 36).withColumnName("source_id"))
            .addField(FieldDefinition.lookup("appointmentId", "telehealth-appointments", "Appointment")
                .withColumnName("appointment_id"))
            .addField(FieldDefinition.lookup("portalUserId", "users", "Portal User")
                .withColumnName("portal_user_id"))
            .addField(FieldDefinition.json("artifactAttachmentIds").withColumnName("artifact_attachment_ids"))
            .addField(FieldDefinition.string("sha256", 64))
            .addField(FieldDefinition.datetime("archivedAt").withColumnName("archived_at"))
            .addField(FieldDefinition.string("archivedBy", 64).withColumnName("archived_by"))
            .addField(FieldDefinition.datetime("retentionUntil").withColumnName("retention_until"))
            .addField(FieldDefinition.bool("legalHold", false).withColumnName("legal_hold"))
            .addField(FieldDefinition.datetime("purgedAt").withColumnName("purged_at"))
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
            .addField(FieldDefinition.requiredString("code", 100))
            .addField(FieldDefinition.requiredString("name", 255))
            .addField(FieldDefinition.text("description"))
            .addField(FieldDefinition.requiredString("kind", 20)
                .withDefault("SUBSCRIPTION")
                .withEnumValues(List.of("SUBSCRIPTION", "ONE_TIME", "DEFAULT")))
            .addField(FieldDefinition.string("stripeProductId", 100)
                .withColumnName("stripe_product_id"))
            .addField(FieldDefinition.string("stripePriceId", 100)
                .withColumnName("stripe_price_id"))
            .addField(FieldDefinition.json("entitlements").withDefault(Map.of()))
            .addField(FieldDefinition.integer("passDurationDays")
                .withColumnName("pass_duration_days"))
            .addField(FieldDefinition.bool("active", true))
            .addField(FieldDefinition.integer("sortOrder").withColumnName("sort_order"))
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
                .withColumnName("collection_name"))
            .addField(FieldDefinition.requiredString("limitKey", 100)
                .withColumnName("limit_key"))
            .addField(FieldDefinition.json("countFilter").withColumnName("count_filter"))
            .addField(FieldDefinition.requiredString("appliesTo", 20)
                .withColumnName("applies_to")
                .withDefault("PORTAL")
                .withEnumValues(List.of("PORTAL", "ALL")))
            .addField(FieldDefinition.string("message", 500))
            .addField(FieldDefinition.bool("active", true))
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
            .addField(FieldDefinition.requiredString("source", 50))
            .addField(FieldDefinition.requiredString("externalId", 200)
                .withColumnName("external_id"))
            .addField(FieldDefinition.requiredString("name", 255))
            .addField(FieldDefinition.string("category", 50))
            .addField(FieldDefinition.json("metadata").withDefault(Map.of()))
            .addField(FieldDefinition.bool("active", true))
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
                .withColumnName("member_id"))
            .addField(FieldDefinition.lookup("targetId", "watch-targets", "Target")
                .withColumnName("target_id"))
            .addField(FieldDefinition.json("criteria").withDefault(Map.of()))
            .addField(FieldDefinition.json("channels").withDefault(List.of()))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("ACTIVE")
                .withEnumValues(List.of("ACTIVE", "PAUSED", "EXPIRED", "FULFILLED")))
            .addField(FieldDefinition.datetime("expiresAt").withColumnName("expires_at"))
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
                .withColumnName("event_type"))
            .addField(FieldDefinition.text("query"))
            .addField(FieldDefinition.bool("zeroResult")
                .withColumnName("zero_result"))
            .addField(FieldDefinition.string("matchedTargetId", 36)
                .withColumnName("matched_target_id"))
            .addField(FieldDefinition.string("path", 500))
            .addField(FieldDefinition.string("referrer", 500))
            .addField(FieldDefinition.json("utm").withDefault(Map.of()))
            .addField(FieldDefinition.string("sessionId", 64)
                .withColumnName("session_id"))
            .addField(FieldDefinition.string("memberId", 36)
                .withColumnName("member_id"))
            .addField(FieldDefinition.string("geoCountry", 2)
                .withColumnName("geo_country"))
            .addField(FieldDefinition.string("geoRegion", 80)
                .withColumnName("geo_region"))
            .addField(FieldDefinition.json("metadata").withDefault(Map.of()))
            .addField(FieldDefinition.datetime("occurredAt")
                .withColumnName("occurred_at"))
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
                .withColumnName("member_id"))
            .addField(FieldDefinition.string("targetId", 36)
                .withColumnName("target_id"))
            .addField(FieldDefinition.string("watchId", 36)
                .withColumnName("watch_id"))
            .addField(FieldDefinition.string("alertId", 36)
                .withColumnName("alert_id"))
            .addField(FieldDefinition.string("category", 50))
            .addField(FieldDefinition.requiredString("summary", 280))
            .addField(FieldDefinition.integer("quantity"))
            .addField(FieldDefinition.bool("isPublic", false)
                .withColumnName("is_public"))
            .addField(FieldDefinition.string("claimantName", 80)
                .withColumnName("claimant_name"))
            .addField(FieldDefinition.datetime("claimedAt")
                .withColumnName("claimed_at"))
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
                .withColumnName("target_id"))
            .addField(FieldDefinition.requiredString("slug", 200))
            .addField(FieldDefinition.requiredString("title", 255))
            .addField(FieldDefinition.string("category", 50))
            .addField(FieldDefinition.integer("watcherCount")
                .withColumnName("watcher_count"))
            .addField(FieldDefinition.integer("winCount")
                .withColumnName("win_count"))
            .addField(FieldDefinition.datetime("lastWinAt")
                .withColumnName("last_win_at"))
            .addField(FieldDefinition.json("stats").withDefault(Map.of()))
            .addField(FieldDefinition.bool("published", false))
            .addField(FieldDefinition.datetime("generatedAt")
                .withColumnName("generated_at"))
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
                .withColumnName("user_id"))
            .addField(FieldDefinition.requiredString("stripeCustomerId", 100)
                .withColumnName("stripe_customer_id"))
            .addField(FieldDefinition.string("email", 255))
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
                .withColumnName("user_id"))
            .addField(FieldDefinition.lookup("planId", "billing-plans", "Plan")
                .withColumnName("plan_id"))
            .addField(FieldDefinition.requiredString("stripeSubscriptionId", 100)
                .withColumnName("stripe_subscription_id"))
            .addField(FieldDefinition.string("stripeCustomerId", 100)
                .withColumnName("stripe_customer_id"))
            .addField(FieldDefinition.requiredString("status", 40))
            .addField(FieldDefinition.datetime("currentPeriodEnd")
                .withColumnName("current_period_end"))
            .addField(FieldDefinition.bool("cancelAtPeriodEnd", false)
                .withColumnName("cancel_at_period_end"))
            .addField(FieldDefinition.datetime("canceledAt").withColumnName("canceled_at"))
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
                .withColumnName("user_id"))
            .addField(FieldDefinition.lookup("planId", "billing-plans", "Plan")
                .withColumnName("plan_id"))
            .addField(FieldDefinition.requiredString("stripeCheckoutSessionId", 100)
                .withColumnName("stripe_checkout_session_id"))
            .addField(FieldDefinition.string("stripePaymentIntentId", 100)
                .withColumnName("stripe_payment_intent_id"))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("ACTIVE")
                .withEnumValues(List.of("ACTIVE", "EXPIRED", "REFUNDED")))
            .addField(FieldDefinition.datetime("startsAt").withColumnName("starts_at"))
            .addField(FieldDefinition.datetime("expiresAt").withColumnName("expires_at"))
            .build();
    }

    public static CollectionDefinition collections() {
        return systemBuilder("collections", "Collections", "collection")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100).withUnique(true))
            .addField(FieldDefinition.string("displayName", 100).withColumnName("display_name"))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.string("path", 255))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .addField(FieldDefinition.bool("systemCollection").withColumnName("system_collection")
                .withDefault(false))
            .addField(FieldDefinition.requiredInteger("currentVersion").withColumnName("current_version")
                .withDefault(1))
            .addField(FieldDefinition.lookup("displayFieldId", "fields", "Display Field")
                .withColumnName("display_field_id"))
            .addField(FieldDefinition.json("adapterConfig").withColumnName("adapter_config"))
            .addField(FieldDefinition.bool("trackHistory").withColumnName("track_history")
                .withDefault(false))
            .addField(FieldDefinition.bool("captureGeo").withColumnName("capture_geo")
                .withDefault(false))
            .build();
    }

    public static CollectionDefinition fields() {
        return systemBuilder("fields", "Fields", "field")
            .displayFieldName("name")
            .tenantScoped(false)
            .addImmutableField("collectionId")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.string("displayName", 100).withColumnName("display_name"))
            .addField(FieldDefinition.requiredString("type", 50))
            .addField(FieldDefinition.bool("required").withDefault(false))
            .addField(FieldDefinition.bool("uniqueConstraint").withColumnName("unique_constraint")
                .withDefault(false))
            .addField(FieldDefinition.bool("indexed").withDefault(false))
            .addField(FieldDefinition.json("defaultValue").withColumnName("default_value"))
            .addField(FieldDefinition.string("referenceTarget", 100).withColumnName("reference_target"))
            .addField(FieldDefinition.integer("fieldOrder").withColumnName("field_order"))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .addField(FieldDefinition.json("constraints"))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.json("fieldTypeConfig").withColumnName("field_type_config"))
            .addField(FieldDefinition.string("autoNumberSequenceName", 100)
                .withColumnName("auto_number_sequence_name"))
            .addField(FieldDefinition.string("relationshipType", 20).withColumnName("relationship_type"))
            .addField(FieldDefinition.string("relationshipName", 100).withColumnName("relationship_name"))
            .addField(FieldDefinition.bool("cascadeDelete").withColumnName("cascade_delete")
                .withDefault(false))
            .addField(FieldDefinition.lookup("referenceCollectionId", "collections", "Reference Collection")
                .withColumnName("reference_collection_id"))
            .addField(FieldDefinition.bool("trackHistory").withColumnName("track_history")
                .withDefault(false))
            .addField(FieldDefinition.bool("searchable").withDefault(false))
            .build();
    }

    // =========================================================================
    // UI & Layout Collections
    // =========================================================================

    public static CollectionDefinition pageLayouts() {
        return systemBuilder("page-layouts", "Page Layouts", "page_layout")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.string("layoutType", 20).withColumnName("layout_type")
                .withDefault("DETAIL"))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default")
                .withDefault(false))
            .addField(FieldDefinition.json("defaultFilter").withColumnName("default_filter"))
            .addField(FieldDefinition.string("defaultSortField", 100).withColumnName("default_sort_field"))
            .addField(FieldDefinition.string("defaultSortDirection", 4).withColumnName("default_sort_direction")
                .withDefault("ASC"))
            .addField(FieldDefinition.integer("defaultRowLimit").withColumnName("default_row_limit")
                .withDefault(50))
            .addField(FieldDefinition.json("headerConfig").withColumnName("header_config"))
            .addField(FieldDefinition.json("railBlocks").withColumnName("rail_blocks"))
            .build();
    }

    public static CollectionDefinition layoutAssignments() {
        return systemBuilder("layout-assignments", "Layout Assignments", "layout_assignment")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("profileId", 36).withColumnName("profile_id"))
            .addField(FieldDefinition.string("recordTypeId", 36).withColumnName("record_type_id"))
            .addField(FieldDefinition.masterDetail("layoutId", "page-layouts", "Layout")
                .withColumnName("layout_id"))
            .addField(FieldDefinition.json("condition"))
            .addField(FieldDefinition.integer("evaluationOrder").withColumnName("evaluation_order")
                .withDefault(100).withNullable(false))
            .build();
    }

    public static CollectionDefinition listViews() {
        return systemBuilder("list-views", "List Views", "list_view")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.string("visibility", 20).withDefault("PRIVATE"))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default"))
            .addField(FieldDefinition.requiredJson("columns"))
            .addField(FieldDefinition.string("filterLogic").withColumnName("filter_logic"))
            // JSON-typed defaults must be JSON containers, not strings — a String
            // default is injected verbatim on create and stored as a JSON string
            // ("[]"), not an array, breaking every consumer of the field
            // (guarded by SystemCollectionJsonDefaultsTest).
            .addField(FieldDefinition.json("filters").withDefault(List.of()))
            .addField(FieldDefinition.string("sortField").withColumnName("sort_field"))
            .addField(FieldDefinition.string("sortDirection", 4).withColumnName("sort_direction")
                .withDefault("ASC"))
            .addField(FieldDefinition.json("sort"))
            .addField(FieldDefinition.integer("rowLimit").withColumnName("row_limit")
                .withDefault(50))
            .addField(FieldDefinition.json("chartConfig").withColumnName("chart_config"))
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
                .withColumnName("user_id"))
            .addField(FieldDefinition.requiredString("prefType", 30)
                .withColumnName("pref_type"))
            .addField(FieldDefinition.requiredString("prefKey", 200)
                .withColumnName("pref_key").withDefault("-"))
            .addField(FieldDefinition.requiredJson("value"))
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
            .addField(FieldDefinition.requiredString("locale", 10))
            .addField(FieldDefinition.requiredString("key", 200)
                .withColumnName("translation_key"))
            .addField(FieldDefinition.requiredString("value", 2000)
                .withColumnName("translation_value"))
            .build();
    }

    public static CollectionDefinition uiPages() {
        return systemBuilder("ui-pages", "UI Pages", "ui_page")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.requiredString("path", 200))
            .addField(FieldDefinition.string("slug", 200))
            .addField(FieldDefinition.string("title", 200))
            .addField(FieldDefinition.json("config"))
            .addField(FieldDefinition.bool("active").withDefault(true).withNullable(false))
            .addField(FieldDefinition.bool("published").withDefault(false).withNullable(false))
            .build();
    }

    public static CollectionDefinition uiMenus() {
        return systemBuilder("ui-menus", "UI Menus", "ui_menu")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.integer("displayOrder").withColumnName("display_order")
                .withDefault(0))
            // Apps (nav v2): a menu is the "app" unit — switcher icon, default app,
            // and an active flag that hides the app from the end-user shell (V164).
            .addField(FieldDefinition.string("icon", 100))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default")
                .withDefault(false).withNullable(false))
            .addField(FieldDefinition.bool("active").withDefault(true).withNullable(false))
            .build();
    }

    // =========================================================================
    // Picklist Collections
    // =========================================================================

    public static CollectionDefinition globalPicklists() {
        return systemBuilder("global-picklists", "Global Picklists", "global_picklist")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.bool("sorted").withDefault(false))
            .addField(FieldDefinition.bool("restricted").withDefault(true))
            .build();
    }

    public static CollectionDefinition picklistValues() {
        return systemBuilder("picklist-values", "Picklist Values", "picklist_value")
            .displayFieldName("label")
            .tenantScoped(true)
            .addField(FieldDefinition.requiredString("picklistSourceType", 20)
                .withColumnName("picklist_source_type")
                .withEnumValues(List.of("FIELD", "GLOBAL")))
            .addField(FieldDefinition.requiredString("picklistSourceId", 36)
                .withColumnName("picklist_source_id"))
            .addField(FieldDefinition.requiredString("value", 255))
            .addField(FieldDefinition.requiredString("label", 255))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default"))
            .addField(FieldDefinition.bool("isActive").withColumnName("is_active"))
            .addField(FieldDefinition.integer("sortOrder").withColumnName("sort_order")
                .withDefault(0))
            .addField(FieldDefinition.string("color", 20))
            .addField(FieldDefinition.string("description", 500))
            .build();
    }

    // =========================================================================
    // Record Types & Validation
    // =========================================================================

    public static CollectionDefinition recordTypes() {
        return systemBuilder("record-types", "Record Types", "record_type")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.bool("isActive").withColumnName("is_active")
                .withDefault(true))
            .addField(FieldDefinition.bool("isDefault").withColumnName("is_default")
                .withDefault(false))
            .build();
    }

    public static CollectionDefinition validationRules() {
        return systemBuilder("validation-rules", "Validation Rules", "validation_rule")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .addField(FieldDefinition.requiredString("errorConditionFormula")
                .withColumnName("error_condition_formula"))
            .addField(FieldDefinition.requiredString("errorMessage", 1000)
                .withColumnName("error_message"))
            .addField(FieldDefinition.string("errorField", 100).withColumnName("error_field"))
            .addField(FieldDefinition.requiredString("evaluateOn", 20)
                .withColumnName("evaluate_on")
                .withDefault("CREATE_AND_UPDATE")
                .withEnumValues(List.of("CREATE", "UPDATE", "CREATE_AND_UPDATE")))
            .addField(FieldDefinition.bool("enforceOnClient")
                .withColumnName("enforce_on_client")
                .withDefault(false))
            .addField(FieldDefinition.requiredString("severity", 10)
                .withDefault("ERROR")
                .withEnumValues(List.of("ERROR", "WARNING")))
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
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.requiredString("triggerType", 20)
                .withColumnName("trigger_type")
                .withEnumValues(List.of(
                    "BEFORE_CREATE", "BEFORE_UPDATE", "AFTER_CREATE", "AFTER_UPDATE", "AFTER_DELETE")))
            .addField(FieldDefinition.text("scriptSource").withColumnName("script_source"))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .addField(FieldDefinition.integer("orderSequence")
                .withColumnName("order_sequence").withDefault(0))
            .addField(FieldDefinition.integer("timeoutSeconds")
                .withColumnName("timeout_seconds").withDefault(5))
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
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("recordId", 36).withColumnName("record_id"))
            .addField(FieldDefinition.requiredString("sharedWithId", 36).withColumnName("shared_with_id"))
            .addField(FieldDefinition.requiredString("sharedWithType", 20)
                .withColumnName("shared_with_type")
                .withEnumValues(List.of("USER", "GROUP")))
            .addField(FieldDefinition.requiredString("accessLevel", 20)
                .withColumnName("access_level")
                .withDefault("READ")
                .withEnumValues(List.of("READ", "EDIT")))
            .addField(FieldDefinition.string("reason", 500))
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
            .addField(FieldDefinition.requiredString("collectionName", 200).withColumnName("collection_name"))
            .addField(FieldDefinition.requiredString("label", 200))
            .addField(FieldDefinition.string("icon", 50))
            .addField(FieldDefinition.requiredString("actionType", 30).withColumnName("action_type")
                .withEnumValues(List.of("create_related", "update_field", "run_script",
                    "log_activity", "send_email", "custom")))
            .addField(FieldDefinition.requiredString("context", 10)
                .withDefault("record")
                .withEnumValues(List.of("record", "list", "both")))
            .addField(FieldDefinition.integer("sortOrder").withColumnName("sort_order").withDefault(0))
            .addField(FieldDefinition.bool("requiresConfirmation")
                .withColumnName("requires_confirmation").withDefault(false))
            .addField(FieldDefinition.string("confirmationMessage", 500).withColumnName("confirmation_message"))
            .addField(FieldDefinition.json("config"))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .build();
    }

    // =========================================================================
    // Automation Collections
    // =========================================================================

    public static CollectionDefinition scripts() {
        return systemBuilder("scripts", "Scripts", "script")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.requiredString("scriptType", 30).withColumnName("script_type")
                .withEnumValues(List.of("BEFORE_TRIGGER", "AFTER_TRIGGER", "SCHEDULED",
                    "API_ENDPOINT", "VALIDATION", "EVENT_HANDLER", "EMAIL_HANDLER")))
            .addField(FieldDefinition.requiredString("language", 20).withDefault("javascript"))
            .addField(FieldDefinition.requiredText("sourceCode").withColumnName("source_code"))
            .addField(FieldDefinition.bool("active"))
            .addField(FieldDefinition.integer("version"))
            .addField(FieldDefinition.string("requiredPermission", 100)
                .withColumnName("required_permission"))
            .build();
    }

    public static CollectionDefinition flows() {
        return systemBuilder("flows", "Flows", "flow")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 1000))
            .addField(FieldDefinition.requiredString("flowType", 30).withColumnName("flow_type")
                .withEnumValues(List.of("RECORD_TRIGGERED", "NATS_TRIGGERED", "SCHEDULED",
                    "AUTOLAUNCHED", "SCREEN")))
            .addField(FieldDefinition.bool("active").withDefault(false))
            .addField(FieldDefinition.integer("version").withDefault(1))
            .addField(FieldDefinition.json("triggerConfig").withColumnName("trigger_config"))
            .addField(FieldDefinition.requiredJson("definition"))
            // Audit identity stamped on records this flow writes when the
            // execution has no initiating user (cron/NATS/webhook starts);
            // falls back to the flow owner (created_by) when unset.
            .addField(FieldDefinition.string("runAsUserId", 36).withColumnName("run_as_user_id"))
            .build();
    }

    public static CollectionDefinition approvalProcesses() {
        return systemBuilder("approval-processes", "Approval Processes", "approval_process")
            .displayFieldName("name")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 1000))
            .addField(FieldDefinition.bool("active"))
            .addField(FieldDefinition.string("entryCriteria").withColumnName("entry_criteria"))
            .addField(FieldDefinition.string("recordEditability", 20)
                .withColumnName("record_editability").withDefault("LOCKED"))
            .addField(FieldDefinition.string("initialSubmitterField", 100)
                .withColumnName("initial_submitter_field"))
            .addField(FieldDefinition.json("onSubmitFieldUpdates")
                .withColumnName("on_submit_field_updates"))
            .addField(FieldDefinition.json("onApprovalFieldUpdates")
                .withColumnName("on_approval_field_updates"))
            .addField(FieldDefinition.json("onRejectionFieldUpdates")
                .withColumnName("on_rejection_field_updates"))
            .addField(FieldDefinition.json("onRecallFieldUpdates")
                .withColumnName("on_recall_field_updates"))
            .addField(FieldDefinition.bool("allowRecall").withColumnName("allow_recall")
                .withDefault(true))
            .addField(FieldDefinition.integer("executionOrder").withColumnName("execution_order")
                .withDefault(0))
            .build();
    }

    public static CollectionDefinition scheduledJobs() {
        return systemBuilder("scheduled-jobs", "Scheduled Jobs", "scheduled_job")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.requiredString("jobType", 20).withColumnName("job_type")
                .withEnumValues(List.of("FLOW", "SCRIPT", "REPORT_EXPORT")))
            .addField(FieldDefinition.string("jobReferenceId", 36)
                .withColumnName("job_reference_id"))
            .addField(FieldDefinition.requiredString("cronExpression", 100)
                .withColumnName("cron_expression"))
            .addField(FieldDefinition.string("timezone", 50).withDefault("UTC"))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .addField(FieldDefinition.json("config"))
            .addField(FieldDefinition.datetime("lastRunAt").withColumnName("last_run_at"))
            .addField(FieldDefinition.string("lastStatus", 20).withColumnName("last_status"))
            .addField(FieldDefinition.datetime("nextRunAt").withColumnName("next_run_at"))
            .build();
    }

    // =========================================================================
    // Communication Collections
    // =========================================================================

    public static CollectionDefinition emailTemplates() {
        return systemBuilder("email-templates", "Email Templates", "email_template")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.requiredString("subject", 500))
            .addField(FieldDefinition.requiredText("bodyHtml").withColumnName("body_html"))
            .addField(FieldDefinition.text("bodyText").withColumnName("body_text"))
            .addField(FieldDefinition.string("relatedCollectionId")
                .withColumnName("related_collection_id"))
            .addField(FieldDefinition.string("folder"))
            // The stable key the platform resolves copy by (EmailRepository.findTemplateByKey,
            // with tenant -> 'system' fallback). Support-mailbox templates reference their copy
            // through it, so without this field their copy could only be authored by SQL.
            .addField(FieldDefinition.string("templateKey").withColumnName("template_key"))
            .addField(FieldDefinition.bool("isActive").withColumnName("is_active"))
            // PR 5 — payload mapper integration
            .addField(FieldDefinition.json("variablesSchema").withColumnName("variables_schema"))
            .addField(FieldDefinition.lookup("smtpCredentialId", "credentials", "SMTP Credential")
                .withColumnName("smtp_credential_id"))
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
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.requiredString("subject", 500))
            .addField(FieldDefinition.text("bodyHtml").withColumnName("body_html"))
            .addField(FieldDefinition.lookup("templateId", "email-templates", "Email Template")
                .withColumnName("template_id"))
            .addField(FieldDefinition.requiredString("targetCollection", 100)
                .withColumnName("target_collection"))
            .addField(FieldDefinition.requiredString("recipientEmailField", 100)
                .withColumnName("recipient_email_field"))
            .addField(FieldDefinition.json("filterJson").withColumnName("filter_json"))
            .addField(FieldDefinition.string("listViewId", 36).withColumnName("list_view_id"))
            .addField(FieldDefinition.string("fromName", 200).withColumnName("from_name"))
            .addField(FieldDefinition.string("fromAddress", 320).withColumnName("from_address"))
            .addField(FieldDefinition.requiredString("status", 20).withDefault("DRAFT"))
            .addField(FieldDefinition.datetime("scheduledAt").withColumnName("scheduled_at"))
            .addField(FieldDefinition.integer("totalRecipients").withColumnName("total_recipients"))
            .addField(FieldDefinition.integer("sentCount").withColumnName("sent_count"))
            .addField(FieldDefinition.integer("failedCount").withColumnName("failed_count"))
            .addField(FieldDefinition.integer("openCount").withColumnName("open_count"))
            .addField(FieldDefinition.integer("clickCount").withColumnName("click_count"))
            .addField(FieldDefinition.integer("unsubscribeCount").withColumnName("unsubscribe_count"))
            .addField(FieldDefinition.datetime("startedAt").withColumnName("started_at"))
            .addField(FieldDefinition.datetime("completedAt").withColumnName("completed_at"))
            .addField(FieldDefinition.text("errorMessage").withColumnName("error_message"))
            .build();
    }

    /** Per-recipient send + tracking rows for a campaign (read-only; written by the runner). */
    public static CollectionDefinition campaignRecipients() {
        return readOnlySystemBuilder("campaign-recipients", "Campaign Recipients",
                "email_campaign_recipient")
            .displayFieldName("email")
            .addField(FieldDefinition.lookup("campaignId", "campaigns", "Campaign")
                .withColumnName("campaign_id"))
            .addField(FieldDefinition.string("recordId", 36).withColumnName("record_id"))
            .addField(FieldDefinition.requiredString("email", 320))
            .addField(FieldDefinition.requiredString("status", 20))
            .addField(FieldDefinition.text("errorMessage").withColumnName("error_message"))
            .addField(FieldDefinition.string("emailLogId", 36).withColumnName("email_log_id"))
            .addField(FieldDefinition.integer("openCount").withColumnName("open_count"))
            .addField(FieldDefinition.integer("clickCount").withColumnName("click_count"))
            .addField(FieldDefinition.datetime("sentAt").withColumnName("sent_at"))
            .addField(FieldDefinition.datetime("openedAt").withColumnName("opened_at"))
            .addField(FieldDefinition.datetime("clickedAt").withColumnName("clicked_at"))
            .addField(FieldDefinition.datetime("unsubscribedAt").withColumnName("unsubscribed_at"))
            .build();
    }

    /** Per-tenant unsubscribe / suppression list — a match here blocks all future campaign sends. */
    public static CollectionDefinition emailSuppressions() {
        return readOnlySystemBuilder("email-suppressions", "Email Suppressions", "email_suppression")
            .displayFieldName("email")
            .addField(FieldDefinition.requiredString("email", 320))
            .addField(FieldDefinition.requiredString("reason", 30).withDefault("UNSUBSCRIBE"))
            .addField(FieldDefinition.lookup("campaignId", "campaigns", "Campaign")
                .withColumnName("campaign_id"))
            .build();
    }

    // =========================================================================
    // Integration Collections
    // =========================================================================

    public static CollectionDefinition connectedApps() {
        return systemBuilder("connected-apps", "Connected Apps", "connected_app")
            .displayFieldName("name")
            .addImmutableField("clientId")
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.requiredString("clientId", 100)
                .withColumnName("client_id").withUnique(true))
            .addField(FieldDefinition.requiredString("clientSecretHash", 200)
                .withColumnName("client_secret_hash").withImmutable(true))
            .addField(FieldDefinition.json("redirectUris").withColumnName("redirect_uris"))
            .addField(FieldDefinition.json("scopes"))
            .addField(FieldDefinition.json("ipRestrictions").withColumnName("ip_restrictions"))
            .addField(FieldDefinition.integer("rateLimitPerHour")
                .withColumnName("rate_limit_per_hour").withDefault(10000))
            .addField(FieldDefinition.bool("active"))
            .addField(FieldDefinition.datetime("lastUsedAt").withColumnName("last_used_at"))
            .addField(FieldDefinition.json("grantTypes").withColumnName("grant_types"))
            .addField(FieldDefinition.bool("requirePkce").withColumnName("require_pkce"))
            .addField(FieldDefinition.bool("consentRequired").withColumnName("consent_required"))
            .build();
    }

    public static CollectionDefinition credentials() {
        return systemBuilder("credentials", "Credentials", "credential")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("displayName", 200).withColumnName("display_name"))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.requiredString("type", 50))
            .addField(FieldDefinition.string("providerTemplate", 100)
                .withColumnName("provider_template"))
            // dataEnc holds AES-256-GCM ciphertext. The CredentialEncryptionHook
            // populates it from plaintext input fields; direct API edits to it
            // are blocked by withImmutable so the only way to change secret
            // material is through the hook path.
            .addField(FieldDefinition.text("dataEnc")
                .withColumnName("data_enc").withImmutable(true))
            .addField(FieldDefinition.json("metadata"))
            .addField(FieldDefinition.datetime("lastTestAt").withColumnName("last_test_at"))
            .addField(FieldDefinition.string("lastTestStatus", 20)
                .withColumnName("last_test_status"))
            .addField(FieldDefinition.text("lastTestError").withColumnName("last_test_error"))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .build();
    }

    public static CollectionDefinition credentialOauthTokens() {
        return readOnlySystemBuilder("credential-oauth-tokens", "Credential OAuth Tokens",
                "credential_oauth_token")
            .addField(FieldDefinition.lookup("credentialId", "credentials", "Credential")
                .withColumnName("credential_id"))
            .addField(FieldDefinition.text("accessTokenEnc").withColumnName("access_token_enc"))
            .addField(FieldDefinition.text("refreshTokenEnc").withColumnName("refresh_token_enc"))
            .addField(FieldDefinition.string("tokenType", 40).withColumnName("token_type"))
            .addField(FieldDefinition.datetime("expiresAt").withColumnName("expires_at"))
            .addField(FieldDefinition.datetime("refreshedAt").withColumnName("refreshed_at"))
            .addField(FieldDefinition.integer("refreshFailureCount")
                .withColumnName("refresh_failure_count"))
            .addField(FieldDefinition.text("lastRefreshError").withColumnName("last_refresh_error"))
            .addField(FieldDefinition.text("scope"))
            .build();
    }

    public static CollectionDefinition apiSpecs() {
        return systemBuilder("api-specs", "API Specs", "api_spec")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 1000))
            .addField(FieldDefinition.requiredString("specVersion", 20)
                .withColumnName("spec_version"))
            .addField(FieldDefinition.string("apiTitle", 500).withColumnName("api_title"))
            .addField(FieldDefinition.string("apiVersion", 50).withColumnName("api_version"))
            .addField(FieldDefinition.string("baseUrl", 1000).withColumnName("base_url"))
            .addField(FieldDefinition.json("servers"))
            .addField(FieldDefinition.json("securitySchemes").withColumnName("security_schemes"))
            .addField(FieldDefinition.requiredString("sourceType", 20)
                .withColumnName("source_type"))
            .addField(FieldDefinition.string("sourceUrl", 2000).withColumnName("source_url"))
            // raw_spec / parsed_spec are exposed but immutable through the
            // dynamic router — imports go through ApiSpecController which runs
            // the parser. Keeping them here lets the system-collection viewer
            // render the spec's metadata without bouncing to a second endpoint.
            .addField(FieldDefinition.requiredText("rawSpec")
                .withColumnName("raw_spec").withImmutable(true))
            .addField(FieldDefinition.requiredString("rawFormat", 10)
                .withColumnName("raw_format").withImmutable(true))
            .addField(FieldDefinition.requiredJson("parsedSpec")
                .withColumnName("parsed_spec").withImmutable(true))
            .addField(FieldDefinition.requiredString("specHash", 64)
                .withColumnName("spec_hash").withImmutable(true))
            .addField(FieldDefinition.integer("revision").withDefault(1))
            .addField(FieldDefinition.bool("isActive").withColumnName("is_active")
                .withDefault(true))
            .addField(FieldDefinition.datetime("lastImportedAt")
                .withColumnName("last_imported_at"))
            .build();
    }

    public static CollectionDefinition apiOperations() {
        return readOnlySystemBuilder("api-operations", "API Operations", "api_operation")
            .addField(FieldDefinition.lookup("specId", "api-specs", "Spec")
                .withColumnName("spec_id"))
            .addField(FieldDefinition.string("operationId", 200).withColumnName("operation_id"))
            .addField(FieldDefinition.requiredString("syntheticOpId", 200)
                .withColumnName("synthetic_op_id"))
            .addField(FieldDefinition.requiredString("httpMethod", 10)
                .withColumnName("http_method"))
            .addField(FieldDefinition.requiredString("pathTemplate", 1000)
                .withColumnName("path_template"))
            .addField(FieldDefinition.string("summary", 500))
            .addField(FieldDefinition.text("description"))
            .addField(FieldDefinition.json("tags"))
            .addField(FieldDefinition.json("parametersSchema").withColumnName("parameters_schema"))
            .addField(FieldDefinition.json("requestBodySchema")
                .withColumnName("request_body_schema"))
            .addField(FieldDefinition.json("responseSchemas").withColumnName("response_schemas"))
            .addField(FieldDefinition.json("securityRequired").withColumnName("security_required"))
            .addField(FieldDefinition.bool("deprecated"))
            .addField(FieldDefinition.text("searchText").withColumnName("search_text"))
            .build();
    }

    public static CollectionDefinition oidcProviders() {
        return systemBuilder("oidc-providers", "OIDC Providers", "oidc_provider")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.requiredString("issuer", 500))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .addField(FieldDefinition.bool("isInternal").withColumnName("is_internal").withDefault(false))
            .addField(FieldDefinition.string("clientId", 200).withColumnName("client_id"))
            .addField(FieldDefinition.string("clientSecretEnc")
                .withColumnName("client_secret_enc"))
            .addField(FieldDefinition.string("audience", 200))
            // OIDC Discovery endpoint overrides (auto-discovered from issuer when NULL)
            .addField(FieldDefinition.string("jwksUri", 500)
                .withColumnName("jwks_uri"))
            .addField(FieldDefinition.string("authorizationUri", 500)
                .withColumnName("authorization_uri"))
            .addField(FieldDefinition.string("tokenUri", 500)
                .withColumnName("token_uri"))
            .addField(FieldDefinition.string("userinfoUri", 500)
                .withColumnName("userinfo_uri"))
            .addField(FieldDefinition.string("endSessionUri", 500)
                .withColumnName("end_session_uri"))
            .addField(FieldDefinition.string("discoveryStatus", 20)
                .withColumnName("discovery_status").withDefault("unknown"))
            // Claim mappings
            .addField(FieldDefinition.string("rolesClaim", 200)
                .withColumnName("roles_claim"))
            .addField(FieldDefinition.string("rolesMapping", 200)
                .withColumnName("roles_mapping"))
            .addField(FieldDefinition.string("emailClaim", 200)
                .withColumnName("email_claim").withDefault("email"))
            .addField(FieldDefinition.string("usernameClaim", 200)
                .withColumnName("username_claim").withDefault("preferred_username"))
            .addField(FieldDefinition.string("nameClaim", 200)
                .withColumnName("name_claim").withDefault("name"))
            .addField(FieldDefinition.string("groupsClaim", 200)
                .withColumnName("groups_claim"))
            .addField(FieldDefinition.text("groupsProfileMapping")
                .withColumnName("groups_profile_mapping"))
            .build();
    }

    public static CollectionDefinition samlProviders() {
        return systemBuilder("saml-providers", "SAML Providers", "saml_provider")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.requiredString("registrationId", 100)
                .withColumnName("registration_id"))
            .addField(FieldDefinition.requiredString("idpEntityId", 500)
                .withColumnName("idp_entity_id"))
            .addField(FieldDefinition.requiredString("ssoUrl", 500)
                .withColumnName("sso_url"))
            // IdP signing certificate (PEM) — TEXT, no length bound.
            .addField(FieldDefinition.text("idpCertificate")
                .withColumnName("idp_certificate"))
            .addField(FieldDefinition.string("nameIdFormat", 200)
                .withColumnName("name_id_format"))
            // IdP SingleLogoutService URL (optional). When set, SLO is advertised in
            // SP metadata + IdP-initiated LogoutRequests are honored.
            .addField(FieldDefinition.string("sloUrl", 500)
                .withColumnName("slo_url"))
            // Assertion attribute → user-field mappings.
            .addField(FieldDefinition.string("emailAttribute", 200)
                .withColumnName("email_attribute"))
            .addField(FieldDefinition.string("profileAttribute", 200)
                .withColumnName("profile_attribute"))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .build();
    }

    // =========================================================================
    // Reports & Dashboards
    // =========================================================================

    public static CollectionDefinition reports() {
        return systemBuilder("reports", "Reports", "report")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 1000))
            .addField(FieldDefinition.requiredString("reportType", 20)
                .withColumnName("report_type"))
            .addField(FieldDefinition.masterDetail("primaryCollectionId", "collections",
                "Primary Collection").withColumnName("primary_collection_id"))
            .addField(FieldDefinition.json("relatedJoins").withColumnName("related_joins"))
            .addField(FieldDefinition.requiredJson("columns"))
            .addField(FieldDefinition.json("filters"))
            .addField(FieldDefinition.string("filterLogic", 500)
                .withColumnName("filter_logic"))
            .addField(FieldDefinition.json("rowGroupings").withColumnName("row_groupings"))
            .addField(FieldDefinition.json("columnGroupings")
                .withColumnName("column_groupings"))
            .addField(FieldDefinition.json("sortOrder").withColumnName("sort_order"))
            .addField(FieldDefinition.string("chartType", 20).withColumnName("chart_type"))
            .addField(FieldDefinition.json("chartConfig").withColumnName("chart_config"))
            .addField(FieldDefinition.string("groupBy", 200).withColumnName("group_by"))
            .addField(FieldDefinition.string("sortBy", 200).withColumnName("sort_by"))
            .addField(FieldDefinition.string("sortDirection", 4)
                .withColumnName("sort_direction").withDefault("ASC"))
            .addField(FieldDefinition.string("scope", 20).withDefault("MY_RECORDS"))
            .addField(FieldDefinition.lookup("folderId", "report-folders", "Folder")
                .withColumnName("folder_id"))
            .addField(FieldDefinition.string("accessLevel", 20)
                .withColumnName("access_level").withDefault("PRIVATE"))
            .build();
    }

    public static CollectionDefinition reportFolders() {
        return systemBuilder("report-folders", "Report Folders", "report_folder")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.string("accessLevel", 20)
                .withColumnName("access_level").withDefault("PRIVATE"))
            .build();
    }

    public static CollectionDefinition dashboards() {
        return systemBuilder("dashboards", "Dashboards", "dashboard")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 1000))
            .addField(FieldDefinition.lookup("folderId", "report-folders", "Folder")
                .withColumnName("folder_id"))
            .addField(FieldDefinition.string("accessLevel", 20)
                .withColumnName("access_level"))
            .addField(FieldDefinition.bool("dynamic").withColumnName("is_dynamic"))
            .addField(FieldDefinition.string("runningUserId", 36)
                .withColumnName("running_user_id"))
            .addField(FieldDefinition.integer("columnCount").withColumnName("column_count"))
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
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("recordId").withColumnName("record_id"))
            .addField(FieldDefinition.requiredText("content"))
            .build();
    }

    public static CollectionDefinition attachments() {
        return systemBuilder("attachments", "Attachments", "file_attachment")
            .displayFieldName("fileName")
            .addImmutableField("collectionId")
            .addImmutableField("recordId")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("recordId").withColumnName("record_id"))
            .addField(FieldDefinition.requiredString("fileName", 500)
                .withColumnName("file_name"))
            .addField(FieldDefinition.longField("fileSize").withColumnName("file_size"))
            .addField(FieldDefinition.requiredString("contentType", 200)
                .withColumnName("content_type"))
            .addField(FieldDefinition.string("storageKey", 500)
                .withColumnName("storage_key"))
            .addField(FieldDefinition.requiredString("uploadedBy", 320)
                .withColumnName("uploaded_by"))
            .addField(FieldDefinition.datetime("uploadedAt").withColumnName("uploaded_at"))
            .build();
    }

    // =========================================================================
    // Platform Management Collections
    // =========================================================================

    public static CollectionDefinition bulkJobs() {
        return systemBuilder("bulk-jobs", "Bulk Jobs", "bulk_job")
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("operation", 20))
            .addField(FieldDefinition.requiredString("status", 20).withDefault("QUEUED"))
            .addField(FieldDefinition.integer("totalRecords").withColumnName("total_records"))
            .addField(FieldDefinition.integer("processedRecords")
                .withColumnName("processed_records"))
            .addField(FieldDefinition.integer("successRecords")
                .withColumnName("success_records"))
            .addField(FieldDefinition.integer("errorRecords")
                .withColumnName("error_records"))
            .addField(FieldDefinition.string("externalIdField")
                .withColumnName("external_id_field"))
            .addField(FieldDefinition.string("contentType", 50)
                .withColumnName("content_type").withDefault("application/json"))
            .addField(FieldDefinition.integer("batchSize").withColumnName("batch_size")
                .withDefault(200))
            .addField(FieldDefinition.datetime("startedAt").withColumnName("started_at"))
            .addField(FieldDefinition.datetime("completedAt").withColumnName("completed_at"))
            .build();
    }

    public static CollectionDefinition packages() {
        return systemBuilder("packages", "Packages", "package")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.requiredString("version", 50))
            .addField(FieldDefinition.string("description"))
            .build();
    }

    public static CollectionDefinition packageItems() {
        return systemBuilder("package-items", "Package Items", "package_item")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("packageId", "packages", "Package")
                .withColumnName("package_id"))
            .addField(FieldDefinition.requiredString("itemType", 50)
                .withColumnName("item_type")
                .withEnumValues(List.of("COLLECTION", "FIELD", "ROLE", "POLICY",
                    "ROUTE_POLICY", "FIELD_POLICY", "OIDC_PROVIDER",
                    "UI_PAGE", "UI_MENU", "UI_MENU_ITEM")))
            .addField(FieldDefinition.requiredString("itemId", 36)
                .withColumnName("item_id"))
            .addField(FieldDefinition.json("content"))
            .build();
    }

    public static CollectionDefinition migrationRuns() {
        return systemBuilder("migration-runs", "Migration Runs", "migration_run")
            .addField(FieldDefinition.requiredString("collectionId", 36)
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredInteger("fromVersion")
                .withColumnName("from_version"))
            .addField(FieldDefinition.requiredInteger("toVersion")
                .withColumnName("to_version"))
            .addField(FieldDefinition.requiredString("status", 50))
            .addField(FieldDefinition.string("errorMessage", 2000)
                .withColumnName("error_message"))
            .build();
    }

    // =========================================================================
    // Read-Only Audit/Log Collections
    // =========================================================================

    public static CollectionDefinition securityAuditLogs() {
        return readOnlySystemBuilder("security-audit-logs", "Security Audit Logs", "security_audit_log")
            .addField(FieldDefinition.requiredString("eventType", 50)
                .withColumnName("event_type"))
            .addField(FieldDefinition.requiredString("eventCategory", 30)
                .withColumnName("event_category"))
            .addField(FieldDefinition.string("actorUserId", 36)
                .withColumnName("actor_user_id"))
            .addField(FieldDefinition.string("actorEmail", 320)
                .withColumnName("actor_email"))
            .addField(FieldDefinition.string("targetType", 50)
                .withColumnName("target_type"))
            .addField(FieldDefinition.string("targetId", 36)
                .withColumnName("target_id"))
            .addField(FieldDefinition.string("targetName", 255)
                .withColumnName("target_name"))
            .addField(FieldDefinition.json("details"))
            .addField(FieldDefinition.string("ipAddress", 45)
                .withColumnName("ip_address"))
            .addField(FieldDefinition.text("userAgent").withColumnName("user_agent"))
            .addField(FieldDefinition.string("correlationId", 36)
                .withColumnName("correlation_id"))
            .build();
    }

    public static CollectionDefinition setupAuditEntries() {
        return readOnlySystemBuilder("setup-audit-entries", "Setup Audit Entries", "setup_audit_trail")
            .addField(FieldDefinition.requiredString("userId", 36)
                .withColumnName("user_id"))
            .addField(FieldDefinition.requiredString("action", 50))
            .addField(FieldDefinition.requiredString("section", 100))
            .addField(FieldDefinition.requiredString("entityType", 50)
                .withColumnName("entity_type"))
            .addField(FieldDefinition.string("entityId", 36)
                .withColumnName("entity_id"))
            .addField(FieldDefinition.string("entityName", 200)
                .withColumnName("entity_name"))
            .addField(FieldDefinition.json("oldValue").withColumnName("old_value"))
            .addField(FieldDefinition.json("newValue").withColumnName("new_value"))
            .addField(FieldDefinition.datetime("timestamp"))
            .build();
    }

    public static CollectionDefinition fieldHistory() {
        return readOnlySystemBuilder("field-history", "Field History", "field_history")
            .tenantScoped(true)
            .addField(FieldDefinition.requiredString("collectionId", 36)
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("recordId", 36)
                .withColumnName("record_id"))
            .addField(FieldDefinition.requiredString("fieldName", 100)
                .withColumnName("field_name"))
            .addField(FieldDefinition.json("oldValue").withColumnName("old_value"))
            .addField(FieldDefinition.json("newValue").withColumnName("new_value"))
            .addField(FieldDefinition.requiredString("changedBy", 36)
                .withColumnName("changed_by"))
            .addField(FieldDefinition.datetime("changedAt").withColumnName("changed_at"))
            .addField(FieldDefinition.requiredString("changeSource", 20)
                .withColumnName("change_source"))
            .build();
    }

    public static CollectionDefinition recordVersions() {
        return readOnlySystemBuilder("record-versions", "Record Versions", "record_version")
            .tenantScoped(true)
            .addField(FieldDefinition.requiredString("collectionId", 36)
                .withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("recordId", 36)
                .withColumnName("record_id"))
            .addField(FieldDefinition.requiredInteger("versionNumber")
                .withColumnName("version_number"))
            .addField(FieldDefinition.requiredString("changeType", 10)
                .withColumnName("change_type"))
            .addField(FieldDefinition.json("snapshot"))
            .addField(FieldDefinition.json("changedFields").withColumnName("changed_fields"))
            .addField(FieldDefinition.requiredString("changedBy", 36)
                .withColumnName("changed_by"))
            .addField(FieldDefinition.datetime("changedAt").withColumnName("changed_at"))
            .addField(FieldDefinition.requiredString("changeSource", 20)
                .withColumnName("change_source"))
            .build();
    }

    public static CollectionDefinition emailLogs() {
        return readOnlySystemBuilder("email-logs", "Email Logs", "email_log")
            .addField(FieldDefinition.lookup("templateId", "email-templates", "Email Template")
                .withColumnName("template_id"))
            .addField(FieldDefinition.requiredString("recipientEmail", 320)
                .withColumnName("recipient_email"))
            .addField(FieldDefinition.requiredString("subject", 500))
            .addField(FieldDefinition.requiredString("status", 20))
            .addField(FieldDefinition.string("source", 30))
            .addField(FieldDefinition.string("sourceId", 36).withColumnName("source_id"))
            .addField(FieldDefinition.text("errorMessage").withColumnName("error_message"))
            .addField(FieldDefinition.datetime("sentAt").withColumnName("sent_at"))
            .build();
    }

    public static CollectionDefinition loginHistory() {
        return readOnlySystemBuilder("login-history", "Login History", "login_history")
            .addField(FieldDefinition.requiredString("userId", 36)
                .withColumnName("user_id"))
            .addField(FieldDefinition.datetime("loginTime").withColumnName("login_time"))
            .addField(FieldDefinition.string("sourceIp", 45).withColumnName("source_ip"))
            .addField(FieldDefinition.string("loginType", 20).withColumnName("login_type"))
            .addField(FieldDefinition.string("status", 20))
            .addField(FieldDefinition.text("userAgent").withColumnName("user_agent"))
            .addField(FieldDefinition.string("geoCountry", 2).withColumnName("geo_country"))
            .addField(FieldDefinition.string("geoRegion", 100).withColumnName("geo_region"))
            .addField(FieldDefinition.string("geoCity", 150).withColumnName("geo_city"))
            .addField(FieldDefinition.doubleField("geoLat").withColumnName("geo_lat"))
            .addField(FieldDefinition.doubleField("geoLon").withColumnName("geo_lon"))
            .build();
    }

    // =========================================================================
    // Groups & Membership Collections
    // =========================================================================

    public static CollectionDefinition userGroups() {
        return systemBuilder("user-groups", "User Groups", "user_group")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.string("groupType", 20).withColumnName("group_type")
                .withDefault("PUBLIC")
                .withEnumValues(List.of("PUBLIC", "QUEUE", "SYSTEM")))
            .addField(FieldDefinition.requiredString("source", 20)
                .withDefault("MANUAL"))
            .addField(FieldDefinition.string("oidcGroupName", 200)
                .withColumnName("oidc_group_name"))
            .build();
    }

    public static CollectionDefinition groupMemberships() {
        return systemBuilder("group-memberships", "Group Memberships", "group_membership")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("groupId", "user-groups", "Group")
                .withColumnName("group_id"))
            .addField(FieldDefinition.requiredString("memberType", 10)
                .withColumnName("member_type")
                .withEnumValues(List.of("USER", "GROUP")))
            .addField(FieldDefinition.requiredString("memberId", 36)
                .withColumnName("member_id"))
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
                .withColumnName("profile_id"))
            .addField(FieldDefinition.requiredString("permissionName", 100)
                .withColumnName("permission_name"))
            .addField(FieldDefinition.bool("granted").withDefault(false)
                .withNullable(false))
            .build();
    }

    public static CollectionDefinition profileObjectPermissions() {
        return systemBuilder("profile-object-permissions", "Profile Object Permissions",
                "profile_object_permission")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("profileId", "profiles", "Profile")
                .withColumnName("profile_id"))
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id"))
            .addField(FieldDefinition.bool("canCreate").withColumnName("can_create")
                .withDefault(false).withNullable(false))
            .addField(FieldDefinition.bool("canRead").withColumnName("can_read")
                .withDefault(false).withNullable(false))
            .addField(FieldDefinition.bool("canEdit").withColumnName("can_edit")
                .withDefault(false).withNullable(false))
            .addField(FieldDefinition.bool("canDelete").withColumnName("can_delete")
                .withDefault(false).withNullable(false))
            .build();
    }

    public static CollectionDefinition profileFieldPermissions() {
        return systemBuilder("profile-field-permissions", "Profile Field Permissions",
                "profile_field_permission")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("profileId", "profiles", "Profile")
                .withColumnName("profile_id"))
            .addField(FieldDefinition.masterDetail("collectionId", "collections", "Collection")
                .withColumnName("collection_id"))
            .addField(FieldDefinition.masterDetail("fieldId", "fields", "Field")
                .withColumnName("field_id"))
            .addField(FieldDefinition.requiredString("visibility", 20)
                .withDefault("VISIBLE"))
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
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .addField(FieldDefinition.json("delegatedUserIds").withColumnName("delegated_user_ids"))
            .addField(FieldDefinition.json("manageableProfileIds").withColumnName("manageable_profile_ids"))
            .addField(FieldDefinition.bool("canCreateUsers").withColumnName("can_create_users")
                .withDefault(false))
            .addField(FieldDefinition.bool("canDeactivateUsers").withColumnName("can_deactivate_users")
                .withDefault(false))
            .addField(FieldDefinition.bool("canResetPasswords").withColumnName("can_reset_passwords")
                .withDefault(false))
            .build();
    }

    // =========================================================================
    // Layout Child Collections
    // =========================================================================

    public static CollectionDefinition layoutSections() {
        return systemBuilder("layout-sections", "Layout Sections", "layout_section")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("layoutId", "page-layouts", "Layout")
                .withColumnName("layout_id"))
            .addField(FieldDefinition.string("heading", 200))
            .addField(FieldDefinition.integer("columns").withDefault(2))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order"))
            .addField(FieldDefinition.bool("collapsed").withDefault(false))
            .addField(FieldDefinition.string("style", 20).withDefault("DEFAULT"))
            .addField(FieldDefinition.string("sectionType", 30)
                .withColumnName("section_type").withDefault("STANDARD"))
            .addField(FieldDefinition.string("tabGroup", 100)
                .withColumnName("tab_group"))
            .addField(FieldDefinition.string("tabLabel", 200)
                .withColumnName("tab_label"))
            .addField(FieldDefinition.json("visibilityRule")
                .withColumnName("visibility_rule"))
            .build();
    }

    public static CollectionDefinition layoutFields() {
        return systemBuilder("layout-fields", "Layout Fields", "layout_field")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("sectionId", "layout-sections", "Section")
                .withColumnName("section_id"))
            .addField(FieldDefinition.masterDetail("fieldId", "fields", "Field")
                .withColumnName("field_id"))
            .addField(FieldDefinition.integer("columnNumber")
                .withColumnName("column_number").withDefault(1))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order"))
            .addField(FieldDefinition.bool("isRequiredOnLayout")
                .withColumnName("is_required_on_layout").withDefault(false))
            .addField(FieldDefinition.bool("isReadOnlyOnLayout")
                .withColumnName("is_read_only_on_layout").withDefault(false))
            .addField(FieldDefinition.string("labelOverride", 200)
                .withColumnName("label_override"))
            .addField(FieldDefinition.string("helpTextOverride", 500)
                .withColumnName("help_text_override"))
            .addField(FieldDefinition.json("visibilityRule")
                .withColumnName("visibility_rule"))
            .addField(FieldDefinition.integer("columnSpan")
                .withColumnName("column_span").withDefault(1))
            .build();
    }

    public static CollectionDefinition layoutRules() {
        return systemBuilder("layout-rules", "Layout Rules", "layout_rule")
            .displayFieldName("name")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("layoutId", "page-layouts", "Layout")
                .withColumnName("layout_id"))
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.requiredString("kind", 20)
                .withEnumValues(List.of("COMPUTE", "VALIDATE", "DEFAULT", "TRANSFORM", "SCRIPT")))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .addField(FieldDefinition.requiredJson("whenEvents")
                .withColumnName("when_events"))
            .addField(FieldDefinition.string("targetField", 100)
                .withColumnName("target_field"))
            .addField(FieldDefinition.json("dependsOn")
                .withColumnName("depends_on"))
            .addField(FieldDefinition.requiredJson("body"))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order"))
            .build();
    }

    public static CollectionDefinition layoutRelatedLists() {
        return systemBuilder("layout-related-lists", "Layout Related Lists",
                "layout_related_list")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("layoutId", "page-layouts", "Layout")
                .withColumnName("layout_id"))
            .addField(FieldDefinition.masterDetail("relatedCollectionId", "collections",
                "Related Collection").withColumnName("related_collection_id"))
            .addField(FieldDefinition.masterDetail("relationshipFieldId", "fields",
                "Relationship Field").withColumnName("relationship_field_id"))
            .addField(FieldDefinition.requiredJson("displayColumns")
                .withColumnName("display_columns"))
            .addField(FieldDefinition.string("sortField", 100)
                .withColumnName("sort_field"))
            .addField(FieldDefinition.string("sortDirection", 4)
                .withColumnName("sort_direction").withDefault("DESC"))
            .addField(FieldDefinition.integer("rowLimit")
                .withColumnName("row_limit").withDefault(10))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order"))
            .build();
    }

    public static CollectionDefinition uiMenuItems() {
        return systemBuilder("ui-menu-items", "UI Menu Items", "ui_menu_item")
            .displayFieldName("label")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("menuId", "ui-menus", "Menu")
                .withColumnName("menu_id"))
            // Submenus (V166): a group header is an item with children and no path;
            // deleting a parent floats its children to the top level (FK SET NULL).
            .addField(FieldDefinition.lookup("parentId", "ui-menu-items", "Parent Item")
                .withColumnName("parent_id"))
            .addField(FieldDefinition.requiredString("label", 100))
            // Optional since V166: group headers navigate nowhere themselves.
            .addField(FieldDefinition.string("path", 200))
            .addField(FieldDefinition.string("icon", 100))
            .addField(FieldDefinition.integer("displayOrder")
                .withColumnName("display_order").withDefault(0)
                .withNullable(false))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withNullable(false))
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
                "Controlling Field").withColumnName("controlling_field_id"))
            .addField(FieldDefinition.masterDetail("dependentFieldId", "fields",
                "Dependent Field").withColumnName("dependent_field_id"))
            .addField(FieldDefinition.requiredJson("mapping"))
            .build();
    }

    public static CollectionDefinition recordTypePicklists() {
        return systemBuilder("record-type-picklists", "Record Type Picklists",
                "record_type_picklist")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("recordTypeId", "record-types",
                "Record Type").withColumnName("record_type_id"))
            .addField(FieldDefinition.masterDetail("fieldId", "fields", "Field")
                .withColumnName("field_id"))
            .addField(FieldDefinition.requiredJson("availableValues")
                .withColumnName("available_values"))
            .addField(FieldDefinition.string("defaultValue", 255)
                .withColumnName("default_value"))
            .build();
    }

    public static CollectionDefinition scriptTriggers() {
        return systemBuilder("script-triggers", "Script Triggers", "script_trigger")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("scriptId", "scripts", "Script")
                .withColumnName("script_id"))
            .addField(FieldDefinition.masterDetail("collectionId", "collections",
                "Collection").withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("triggerEvent", 20)
                .withColumnName("trigger_event")
                .withEnumValues(List.of("INSERT", "UPDATE", "DELETE")))
            .addField(FieldDefinition.integer("executionOrder")
                .withColumnName("execution_order").withDefault(0))
            .addField(FieldDefinition.bool("active").withDefault(true))
            .build();
    }

    public static CollectionDefinition approvalSteps() {
        return systemBuilder("approval-steps", "Approval Steps", "approval_step")
            .displayFieldName("name")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("approvalProcessId",
                "approval-processes", "Approval Process")
                .withColumnName("approval_process_id"))
            .addField(FieldDefinition.requiredInteger("stepNumber")
                .withColumnName("step_number"))
            .addField(FieldDefinition.requiredString("name", 200))
            .addField(FieldDefinition.string("description", 500))
            .addField(FieldDefinition.text("entryCriteria")
                .withColumnName("entry_criteria"))
            .addField(FieldDefinition.requiredString("approverType", 30)
                .withColumnName("approver_type"))
            .addField(FieldDefinition.string("approverId", 36)
                .withColumnName("approver_id"))
            .addField(FieldDefinition.string("approverField", 100)
                .withColumnName("approver_field"))
            .addField(FieldDefinition.bool("unanimityRequired")
                .withColumnName("unanimity_required").withDefault(false))
            .addField(FieldDefinition.integer("escalationTimeoutHours")
                .withColumnName("escalation_timeout_hours"))
            .addField(FieldDefinition.string("escalationAction", 20)
                .withColumnName("escalation_action"))
            .addField(FieldDefinition.string("onApproveAction", 20)
                .withColumnName("on_approve_action").withDefault("NEXT_STEP"))
            .addField(FieldDefinition.string("onRejectAction", 20)
                .withColumnName("on_reject_action").withDefault("REJECT_FINAL"))
            .build();
    }

    public static CollectionDefinition approvalInstances() {
        return systemBuilder("approval-instances", "Approval Instances",
                "approval_instance")
            .addField(FieldDefinition.masterDetail("approvalProcessId",
                "approval-processes", "Approval Process")
                .withColumnName("approval_process_id"))
            .addField(FieldDefinition.masterDetail("collectionId", "collections",
                "Collection").withColumnName("collection_id"))
            .addField(FieldDefinition.requiredString("recordId", 36)
                .withColumnName("record_id"))
            .addField(FieldDefinition.requiredString("submittedBy", 36)
                .withColumnName("submitted_by"))
            .addField(FieldDefinition.requiredInteger("currentStepNumber")
                .withColumnName("current_step_number").withDefault(1))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("PENDING")
                .withEnumValues(List.of("PENDING", "APPROVED", "REJECTED", "RECALLED")))
            .addField(FieldDefinition.datetime("submittedAt")
                .withColumnName("submitted_at").withNullable(false))
            .addField(FieldDefinition.datetime("completedAt")
                .withColumnName("completed_at"))
            .build();
    }

    public static CollectionDefinition approvalStepInstances() {
        return systemBuilder("approval-step-instances", "Approval Step Instances",
                "approval_step_instance")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("approvalInstanceId",
                "approval-instances", "Approval Instance")
                .withColumnName("approval_instance_id"))
            .addField(FieldDefinition.masterDetail("stepId", "approval-steps",
                "Approval Step").withColumnName("step_id"))
            .addField(FieldDefinition.requiredString("assignedTo", 36)
                .withColumnName("assigned_to"))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("PENDING")
                .withEnumValues(List.of("PENDING", "APPROVED", "REJECTED", "REASSIGNED")))
            .addField(FieldDefinition.text("comments"))
            .addField(FieldDefinition.datetime("actedAt")
                .withColumnName("acted_at"))
            .build();
    }

    public static CollectionDefinition connectedAppTokens() {
        return systemBuilder("connected-app-tokens", "Connected App Tokens",
                "connected_app_token")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("connectedAppId", "connected-apps",
                "Connected App").withColumnName("connected_app_id"))
            .addField(FieldDefinition.requiredString("tokenHash", 200)
                .withColumnName("token_hash"))
            .addField(FieldDefinition.requiredJson("scopes"))
            .addField(FieldDefinition.datetime("issuedAt")
                .withColumnName("issued_at").withNullable(false))
            .addField(FieldDefinition.datetime("expiresAt")
                .withColumnName("expires_at").withNullable(false))
            .addField(FieldDefinition.bool("revoked").withDefault(false))
            .addField(FieldDefinition.datetime("revokedAt")
                .withColumnName("revoked_at"))
            .build();
    }

    public static CollectionDefinition dashboardComponents() {
        return systemBuilder("dashboard-components", "Dashboard Components",
                "dashboard_component")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("dashboardId", "dashboards",
                "Dashboard").withColumnName("dashboard_id"))
            .addField(FieldDefinition.masterDetail("reportId", "reports", "Report")
                .withColumnName("report_id"))
            .addField(FieldDefinition.requiredString("componentType", 20)
                .withColumnName("component_type"))
            .addField(FieldDefinition.string("title", 200))
            .addField(FieldDefinition.requiredInteger("columnPosition")
                .withColumnName("column_position"))
            .addField(FieldDefinition.requiredInteger("rowPosition")
                .withColumnName("row_position"))
            .addField(FieldDefinition.integer("columnSpan")
                .withColumnName("column_span").withDefault(1))
            .addField(FieldDefinition.integer("rowSpan")
                .withColumnName("row_span").withDefault(1))
            .addField(FieldDefinition.json("config").withDefault(Map.of()))
            .addField(FieldDefinition.requiredInteger("sortOrder")
                .withColumnName("sort_order"))
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
                .withColumnName("script_id"))
            .addField(FieldDefinition.requiredString("status", 20)
                .withEnumValues(List.of("SUCCESS", "FAILURE", "TIMEOUT",
                    "GOVERNOR_LIMIT")))
            .addField(FieldDefinition.string("triggerType", 30)
                .withColumnName("trigger_type"))
            .addField(FieldDefinition.string("recordId", 36)
                .withColumnName("record_id"))
            .addField(FieldDefinition.integer("durationMs")
                .withColumnName("duration_ms"))
            .addField(FieldDefinition.integer("cpuMs")
                .withColumnName("cpu_ms"))
            .addField(FieldDefinition.integer("queriesExecuted")
                .withColumnName("queries_executed").withDefault(0))
            .addField(FieldDefinition.integer("dmlRows")
                .withColumnName("dml_rows").withDefault(0))
            .addField(FieldDefinition.integer("callouts").withDefault(0))
            .addField(FieldDefinition.text("errorMessage")
                .withColumnName("error_message"))
            .addField(FieldDefinition.text("logOutput")
                .withColumnName("log_output"))
            .addField(FieldDefinition.datetime("executedAt")
                .withColumnName("executed_at"))
            .build();
    }

    public static CollectionDefinition flowExecutions() {
        return readOnlySystemBuilder("flow-executions", "Flow Executions",
                "flow_execution")
            .tenantScoped(true)
            .addField(FieldDefinition.masterDetail("flowId", "flows", "Flow")
                .withColumnName("flow_id"))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("RUNNING")
                .withEnumValues(List.of("RUNNING", "COMPLETED", "FAILED",
                    "WAITING", "CANCELLED")))
            .addField(FieldDefinition.string("startedBy", 36)
                .withColumnName("started_by"))
            .addField(FieldDefinition.string("triggerRecordId", 36)
                .withColumnName("trigger_record_id"))
            .addField(FieldDefinition.json("variables").withDefault(Map.of()))
            .addField(FieldDefinition.string("currentNodeId", 100)
                .withColumnName("current_node_id"))
            .addField(FieldDefinition.text("errorMessage")
                .withColumnName("error_message"))
            .addField(FieldDefinition.datetime("startedAt")
                .withColumnName("started_at").withNullable(false))
            .addField(FieldDefinition.datetime("completedAt")
                .withColumnName("completed_at"))
            .build();
    }

    public static CollectionDefinition jobExecutionLogs() {
        return readOnlySystemBuilder("job-execution-logs", "Job Execution Logs",
                "job_execution_log")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("jobId", "scheduled-jobs",
                "Scheduled Job").withColumnName("job_id"))
            .addField(FieldDefinition.requiredString("status", 20))
            .addField(FieldDefinition.integer("recordsProcessed")
                .withColumnName("records_processed").withDefault(0))
            .addField(FieldDefinition.text("errorMessage")
                .withColumnName("error_message"))
            .addField(FieldDefinition.datetime("startedAt")
                .withColumnName("started_at").withNullable(false))
            .addField(FieldDefinition.datetime("completedAt")
                .withColumnName("completed_at"))
            .addField(FieldDefinition.integer("durationMs")
                .withColumnName("duration_ms"))
            .build();
    }

    public static CollectionDefinition bulkJobResults() {
        return readOnlySystemBuilder("bulk-job-results", "Bulk Job Results",
                "bulk_job_result")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("bulkJobId", "bulk-jobs",
                "Bulk Job").withColumnName("bulk_job_id"))
            .addField(FieldDefinition.requiredInteger("recordIndex")
                .withColumnName("record_index"))
            .addField(FieldDefinition.string("recordId", 36)
                .withColumnName("record_id"))
            .addField(FieldDefinition.requiredString("status", 20)
                .withEnumValues(List.of("SUCCESS", "FAILURE")))
            .addField(FieldDefinition.text("errorMessage")
                .withColumnName("error_message"))
            .build();
    }

    public static CollectionDefinition collectionVersions() {
        return readOnlySystemBuilder("collection-versions", "Collection Versions",
                "collection_version")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("collectionId", "collections",
                "Collection").withColumnName("collection_id"))
            .addField(FieldDefinition.requiredInteger("version"))
            .addField(FieldDefinition.json("schema"))
            .build();
    }

    public static CollectionDefinition fieldVersions() {
        return readOnlySystemBuilder("field-versions", "Field Versions", "field_version")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("collectionVersionId",
                "collection-versions", "Collection Version")
                .withColumnName("collection_version_id"))
            .addField(FieldDefinition.masterDetail("fieldId", "fields", "Field")
                .withColumnName("field_id"))
            .addField(FieldDefinition.requiredString("name", 100))
            .addField(FieldDefinition.requiredString("type", 50))
            .addField(FieldDefinition.bool("required").withDefault(false)
                .withNullable(false))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withNullable(false))
            .addField(FieldDefinition.json("constraints"))
            .build();
    }

    public static CollectionDefinition migrationSteps() {
        return readOnlySystemBuilder("migration-steps", "Migration Steps",
                "migration_step")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("migrationRunId", "migration-runs",
                "Migration Run").withColumnName("migration_run_id"))
            .addField(FieldDefinition.requiredInteger("stepNumber")
                .withColumnName("step_number"))
            .addField(FieldDefinition.requiredString("operation", 100))
            .addField(FieldDefinition.requiredString("status", 50)
                .withDefault("PENDING")
                .withEnumValues(List.of("PENDING", "RUNNING", "COMPLETED",
                    "FAILED", "SKIPPED")))
            .addField(FieldDefinition.json("details"))
            .addField(FieldDefinition.string("errorMessage", 2000)
                .withColumnName("error_message"))
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
            .addField(FieldDefinition.datetime("createdAt").withColumnName("created_at"))
            .addField(FieldDefinition.lookup("createdBy", "users", "Created By").withColumnName("created_by"))
            .addField(FieldDefinition.datetime("updatedAt").withColumnName("updated_at"))
            .addField(FieldDefinition.lookup("updatedBy", "users", "Updated By").withColumnName("updated_by"));
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
            .addField(FieldDefinition.datetime("createdAt").withColumnName("created_at"))
            .addField(FieldDefinition.lookup("createdBy", "users", "Created By").withColumnName("created_by"))
            .addField(FieldDefinition.datetime("updatedAt").withColumnName("updated_at"))
            .addField(FieldDefinition.lookup("updatedBy", "users", "Updated By").withColumnName("updated_by"));
    }
}
